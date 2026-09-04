package de.palsoftware.yvoke.ingest.core.confluence;

import de.palsoftware.yvoke.ingest.core.model.MarkdownTree;
import de.palsoftware.yvoke.ingest.core.model.ParsedMarkdown;
import de.palsoftware.yvoke.ingest.core.model.Section;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the chunk sections for one Confluence page.
 *
 * <p>
 * {@link MarkdownTree} is shared with the manual/hierarchical/custom ingest paths and is
 * deliberately left alone; it assumes a document that starts with an H1 title and whose content
 * lives under headings. A Confluence page satisfies neither: the title is page <em>metadata</em>
 * (never part of the converted body storage), so three shapes lost all their content —
 *
 * <ol>
 * <li>a body with no ATX heading at all ({@code parse} returns no sections),
 * <li>a body whose only heading is a leading H1 (consumed as the document title),
 * <li>prose before the first heading (section bodies start after their heading).
 * </ol>
 *
 * <p>
 * This builder re-roots the page under a synthetic H1 carrying the page title, keeps the
 * pre-heading preamble as that title section's body, and then runs the ordinary
 * {@link MarkdownTree#buildOrderedSections(ParsedMarkdown)} pipeline (TOC filtering, empty-
 * placeholder dropping and oversized-section splitting) so the Confluence path gets the same
 * bounded chunks as every other path.
 */
public final class ConfluenceSectionBuilder {

    /** Matches {@link MarkdownTree}'s notion of a heading so the preamble cut lands identically. */
    private static final Pattern HEADING_RE = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");

    private static final Pattern H1_RE = Pattern.compile("^#\\s+(.+?)\\s*$");

    /** {@code Section.toChunkText()} renders {@code "#".repeat(depth)} with no depth > 0 guard. */
    private static final int TITLE_DEPTH = 1;

    private static final String FALLBACK_TITLE = "Untitled";

    /** The blank line joining two bodies — counted wherever a length is compared to the cap. */
    private static final int SEPARATOR_LENGTH = 2;

    private ConfluenceSectionBuilder() {}

    public static List<Section> build(String pageTitle, String markdown) {
        return build(pageTitle, markdown, null, null, null, null);
    }

    public static List<Section> build(String pageTitle, String markdown, @Nullable String sourceUrl,
        @Nullable String author, @Nullable String lastUpdated, @Nullable Integer version) {
        String title = normalizeTitle(pageTitle);
        String body = stripLeadingDuplicateTitle(markdown == null ? "" : markdown, title);

        // Nested sections: parsed from the body re-rooted under the page title, so a leading H1 in
        // the body is a real section instead of being swallowed as the document title.
        // Splitting of oversized sections is handled uniformly during section packing.
        ParsedMarkdown parsed = MarkdownTree.parse("# " + title + "\n\n" + body);
        List<Section> nested =
            MarkdownTree.dropEmptyPlaceholders(MarkdownTree.filterSections(parsed.sections()));

        List<Section> raw = new ArrayList<>();
        String preamble = preamble(body);
        if (!preamble.isBlank()) {
            raw.add(new Section(TITLE_DEPTH, title, List.of(), preamble));
        }
        for (Section section : nested) {
            // Prepending the page title to every heading path puts it into the "> Section path:"
            // breadcrumb of every chunk, which is both embedded and stored — on a wiki page the
            // title is the densest topical signal and it exists nowhere in the body.
            List<String> headingPath = new ArrayList<>(section.headingPath().size() + 1);
            headingPath.add(title);
            headingPath.addAll(section.headingPath());
            raw.add(new Section(section.depth(), section.title(), List.copyOf(headingPath),
                section.body()));
        }
        // The header is prepended to every finished body below, so it has to come OUT of the
        // allowance rather than sit on top of it — otherwise every chunk on a page that has
        // metadata overruns the cap, by an amount that varies with the page URL and the author's
        // name, so the real ceiling moves per page with nothing measuring it.
        String header = formatMetadataHeader(sourceUrl, author, lastUpdated, version);
        int budget = MarkdownTree.CHUNK_BODY_MAX_CHARS
            - (header.isEmpty() ? 0 : header.length() + SEPARATOR_LENGTH);
        List<Section> packed = packSections(title, raw, budget);
        if (header.isEmpty() || packed.isEmpty()) {
            return packed;
        }
        List<Section> withHeaders = new ArrayList<>(packed.size());
        for (Section s : packed) {
            withHeaders.add(
                new Section(s.depth(), s.title(), s.headingPath(), header + "\n\n" + s.body()));
        }
        return List.copyOf(withHeaders);
    }

    private static String formatMetadataHeader(@Nullable String sourceUrl, @Nullable String author,
        @Nullable String lastUpdated, @Nullable Integer version) {
        List<String> lines = new ArrayList<>();
        if (sourceUrl != null && !sourceUrl.isBlank()) {
            lines.add("🔗 Confluence Page: [View in Confluence](" + sourceUrl.strip() + ")");
        }
        boolean hasAuthor = author != null && !author.isBlank();
        boolean hasDate = lastUpdated != null && !lastUpdated.isBlank();
        if (hasAuthor && hasDate) {
            String datePart = "📅 Last Updated: " + lastUpdated.strip()
                + (version != null ? " (v" + version + ")" : "");
            lines.add("👤 Author: " + author.strip() + " | " + datePart);
        } else if (hasAuthor) {
            lines.add("👤 Author: " + author.strip());
        } else if (hasDate) {
            lines.add("📅 Last Updated: " + lastUpdated.strip()
                + (version != null ? " (v" + version + ")" : ""));
        }
        return String.join("\n", lines);
    }

    /**
     * Packs consecutive sections up to {@code maxChars} to avoid micro-chunk fragmentation while
     * preserving markdown headings in the body. Oversized sections (> maxChars) flush the
     * accumulated buffer and are split into parts via
     * {@link MarkdownTree#splitOversizedSection(Section)}.
     */
    static List<Section> packSections(String pageTitle, List<Section> rawSections, int maxChars) {
        if (rawSections == null || rawSections.isEmpty()) {
            return List.of();
        }

        List<Section> out = new ArrayList<>();
        List<Section> buffer = new ArrayList<>();
        int currentBufferLength = 0;

        for (Section s : rawSections) {
            if (s.body().length() > maxChars) {
                if (!buffer.isEmpty()) {
                    out.add(createCompositeSection(pageTitle, buffer, rawSections));
                    buffer.clear();
                    currentBufferLength = 0;
                }
                out.addAll(MarkdownTree.splitOversizedSection(s, maxChars));
                continue;
            }

            String formatted = formatSectionForComposite(s, pageTitle);
            int formattedLength = formatted.length();

            if (buffer.isEmpty()) {
                buffer.add(s);
                currentBufferLength = formattedLength;
            } else {
                int candidateLength = currentBufferLength + SEPARATOR_LENGTH + formattedLength;
                if (candidateLength > maxChars) {
                    out.add(createCompositeSection(pageTitle, buffer, rawSections));
                    buffer.clear();
                    buffer.add(s);
                    currentBufferLength = formattedLength;
                } else {
                    buffer.add(s);
                    currentBufferLength = candidateLength;
                }
            }
        }

        if (!buffer.isEmpty()) {
            out.add(createCompositeSection(pageTitle, buffer, rawSections));
        }

        return List.copyOf(out);
    }

    private static Section createCompositeSection(String pageTitle, List<Section> group,
        List<Section> rawSections) {
        if (group.size() == 1) {
            return group.get(0);
        }

        List<String> commonPrefix = longestCommonPrefix(group);
        int depth;
        String title;
        List<String> headingPath;

        if (commonPrefix.size() <= 1) {
            depth = TITLE_DEPTH;
            title = pageTitle;
            headingPath = List.of();
        } else {
            title = commonPrefix.get(commonPrefix.size() - 1);
            headingPath = List.copyOf(commonPrefix.subList(0, commonPrefix.size() - 1));
            depth = lookupDepth(commonPrefix, rawSections, commonPrefix.size());
        }

        List<String> formattedBodies = new ArrayList<>(group.size());
        for (Section s : group) {
            formattedBodies.add(formatSectionForComposite(s, pageTitle));
        }
        String compositeBody = String.join("\n\n", formattedBodies);
        return new Section(depth, title, headingPath, compositeBody);
    }

    private static List<String> longestCommonPrefix(List<Section> group) {
        if (group.isEmpty()) {
            return List.of();
        }
        List<String> prefix = new ArrayList<>(group.get(0).headingPath());
        for (int i = 1; i < group.size(); i++) {
            List<String> current = group.get(i).headingPath();
            int maxLen = Math.min(prefix.size(), current.size());
            int matchLen = 0;
            while (matchLen < maxLen && prefix.get(matchLen).equals(current.get(matchLen))) {
                matchLen++;
            }
            if (matchLen < prefix.size()) {
                prefix = new ArrayList<>(prefix.subList(0, matchLen));
            }
            if (prefix.isEmpty()) {
                break;
            }
        }
        return List.copyOf(prefix);
    }

    /**
     * The depth of the ancestor sitting at {@code ancestorPath}.
     *
     * <p>
     * Matched on the whole PATH, never on the name alone. A heading's identity on this corpus is
     * its path — "Overview", "Notes" and "Configuration" repeat all over a space — so scanning for
     * the first section with a matching title returns whichever same-named heading comes first in
     * document order, and the composite is then rendered at that heading's level and stores its
     * depth in {@code chunks.depth}, which is what the document hierarchy reads.
     *
     * <p>
     * The fallback is still the path length, because the ancestor genuinely can be absent: a
     * body-less heading is removed by {@code dropEmptyPlaceholders} before packing. It is only a
     * guess — markdown permits skipped levels ({@code ## A} then {@code #### B}), so path length
     * and depth are different numbers and only the real section knows which.
     */
    private static int lookupDepth(List<String> ancestorPath, List<Section> rawSections,
        int fallbackDepth) {
        for (Section s : rawSections) {
            List<String> fullPath = new ArrayList<>(s.headingPath().size() + 1);
            fullPath.addAll(s.headingPath());
            fullPath.add(s.title());
            if (fullPath.equals(ancestorPath)) {
                return s.depth();
            }
        }
        return fallbackDepth;
    }

    private static String formatSectionForComposite(Section s, String pageTitle) {
        if (s.depth() == TITLE_DEPTH
            && (s.title().equals(pageTitle) || s.title().equals(normalizeTitle(pageTitle)))) {
            return s.body().stripTrailing();
        }
        return "#".repeat(s.depth()) + " " + s.title() + "\n\n" + s.body().stripTrailing();
    }

    private static String normalizeTitle(String pageTitle) {
        if (pageTitle == null || pageTitle.isBlank()) {
            return FALLBACK_TITLE;
        }
        // Collapse whitespace so the synthesized "# <title>" stays a single heading line.
        return pageTitle.strip().replaceAll("\\s+", " ");
    }

    /**
     * Drops a leading H1 that merely repeats the page title, so the title is not rendered twice in
     * the first chunk. The line is removed rather than the synthetic root being skipped — skipping
     * it would put the body back into loss path (2).
     */
    private static String stripLeadingDuplicateTitle(String markdown, String title) {
        List<String> lines = markdown.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            Matcher m = H1_RE.matcher(line);
            if (m.matches() && m.group(1).strip().equalsIgnoreCase(title)) {
                return String.join("\n", lines.subList(i + 1, lines.size()));
            }
            return markdown;
        }
        return markdown;
    }

    /** Everything before the first heading; the whole text when there is no heading at all. */
    private static String preamble(String markdown) {
        List<String> lines = markdown.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            if (HEADING_RE.matcher(lines.get(i)).matches()) {
                return String.join("\n", lines.subList(0, i)).strip();
            }
        }
        return markdown.strip();
    }
}
