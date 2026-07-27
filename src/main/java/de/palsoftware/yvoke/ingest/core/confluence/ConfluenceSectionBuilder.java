package de.palsoftware.yvoke.ingest.core.confluence;

import de.palsoftware.yvoke.ingest.core.model.MarkdownTree;
import de.palsoftware.yvoke.ingest.core.model.ParsedMarkdown;
import de.palsoftware.yvoke.ingest.core.model.Section;
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

    private ConfluenceSectionBuilder() {}

    public static List<Section> build(String pageTitle, String markdown) {
        String title = normalizeTitle(pageTitle);
        String body = stripLeadingDuplicateTitle(markdown == null ? "" : markdown, title);

        // Nested sections: parsed from the body re-rooted under the page title, so a leading H1 in
        // the body is a real section instead of being swallowed as the document title.
        List<Section> nested = MarkdownTree.buildOrderedSections("# " + title + "\n\n" + body);

        List<Section> out = new ArrayList<>();
        String preamble = preamble(body);
        if (!preamble.isBlank()) {
            // The title section carries the pre-heading prose (and, for a heading-less page, the
            // whole body). Only the oversized-section split is applied: routing it through the full
            // pipeline sent it through MarkdownTree.filterSections, which drops any depth-1 section
            // with an empty heading path titled "table of contents" — the exact shape of this
            // synthetic root, so a page actually named "Table of Contents" lost its whole body.
            // Filtering stays on for the NESTED sections, where a real TOC section lives.
            out.addAll(MarkdownTree
                .splitOversizedSection(new Section(TITLE_DEPTH, title, List.of(), preamble)));
        }
        for (Section section : nested) {
            // Prepending the page title to every heading path puts it into the "> Section path:"
            // breadcrumb of every chunk, which is both embedded and stored — on a wiki page the
            // title is the densest topical signal and it exists nowhere in the body.
            List<String> headingPath = new ArrayList<>(section.headingPath().size() + 1);
            headingPath.add(title);
            headingPath.addAll(section.headingPath());
            out.add(new Section(section.depth(), section.title(), List.copyOf(headingPath),
                section.body()));
        }
        return List.copyOf(out);
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
