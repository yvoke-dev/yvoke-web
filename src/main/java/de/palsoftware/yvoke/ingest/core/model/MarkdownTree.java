package de.palsoftware.yvoke.ingest.core.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.Yaml;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class MarkdownTree {

    public static final int CHUNK_BODY_MAX_CHARS = 3_500;

    private static final Pattern HEADING_RE = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");
    private static final Pattern FRONTMATTER_RE =
        Pattern.compile("^---\\s*\\n(.*?)\\n---\\s*\\n", Pattern.DOTALL);

    private MarkdownTree() {}

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    public static ParsedMarkdown parse(String mdText) {
        Map<String, Object> front = Map.of();
        String body = mdText;

        Matcher fm = FRONTMATTER_RE.matcher(mdText);
        if (fm.lookingAt()) {
            front = parseFrontmatter(fm.group(1));
            body = mdText.substring(fm.end());
        }

        List<String> lines = splitLines(body);

        // First pass: collect (lineIndex, depth, title) for every heading.
        List<int[]> headingPositions = new ArrayList<>();
        List<String> headingTitles = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = HEADING_RE.matcher(lines.get(i));
            if (m.matches()) {
                int depth = m.group(1).length();
                String title = m.group(2).strip();
                headingPositions.add(new int[] {i, depth});
                headingTitles.add(title);
            }
        }

        if (headingPositions.isEmpty()) {
            return new ParsedMarkdown(front, null, List.of());
        }

        // H1 is the manual title â€” recorded but not emitted as a section.
        String titleH1 = null;
        int startIdx = 0;
        if (headingPositions.get(0)[1] == 1) {
            titleH1 = headingTitles.get(0);
            startIdx = 1;
        }

        // Build heading_path for each heading via a depth stack.
        List<Section> sections = new ArrayList<>();
        Deque<int[]> stackDepths = new ArrayDeque<>(); // depth per stack entry
        Deque<String> stackTitles = new ArrayDeque<>(); // title per stack entry (parallel)

        for (int idx = startIdx; idx < headingPositions.size(); idx++) {
            int lineNo = headingPositions.get(idx)[0];
            int depth = headingPositions.get(idx)[1];
            String title = headingTitles.get(idx);

            while (!stackDepths.isEmpty() && stackDepths.peek()[0] >= depth) {
                stackDepths.pop();
                stackTitles.pop();
            }
            // heading_path = ancestor titles, top-most first.
            List<String> headingPath = new ArrayList<>(stackTitles);
            Collections.reverse(headingPath);

            int bodyStart = lineNo + 1;
            int bodyEnd = (idx + 1 < headingPositions.size()) ? headingPositions.get(idx + 1)[0]
                : lines.size();
            String sectionBody = String.join("\n", lines.subList(bodyStart, bodyEnd)).strip();

            sections.add(new Section(depth, title, headingPath, sectionBody));

            stackDepths.push(new int[] {depth});
            stackTitles.push(title);
        }

        return new ParsedMarkdown(front, titleH1, sections);
    }

    // ------------------------------------------------------------------
    // Pipeline: filter â†’ drop-empty-placeholders â†’ split-oversized
    // ------------------------------------------------------------------

    public static List<Section> buildOrderedSections(String mdText) {
        return buildOrderedSections(parse(mdText));
    }

    public static List<Section> buildOrderedSections(ParsedMarkdown parsed) {
        List<Section> sections = filterSections(parsed.sections());
        sections = dropEmptyPlaceholders(sections);
        List<Section> expanded = new ArrayList<>();
        for (Section s : sections) {
            expanded.addAll(splitOversizedSection(s));
        }
        return expanded;
    }

    public static List<Section> filterSections(List<Section> sections) {
        List<Section> out = new ArrayList<>();
        for (Section s : sections) {
            boolean topLevelToc = s.headingPath().isEmpty()
                && s.title().toLowerCase(Locale.ROOT).equals("table of contents");
            boolean underToc = !s.headingPath().isEmpty()
                && s.headingPath().get(0).toLowerCase(Locale.ROOT).equals("table of contents");
            if (topLevelToc || underToc) {
                continue;
            }
            out.add(s);
        }
        return out;
    }

    public static List<Section> dropEmptyPlaceholders(List<Section> sections) {
        // A section is a group node iff the immediately-following section is deeper (matches the
        // single-step lookahead in _drop_empty_placeholders, which breaks on the first `later`).
        Set<String> titlesWithChildren = new HashSet<>();
        for (int i = 0; i + 1 < sections.size(); i++) {
            Section s = sections.get(i);
            if (sections.get(i + 1).depth() > s.depth()) {
                titlesWithChildren.add(childKey(s));
            }
        }
        List<Section> keep = new ArrayList<>();
        for (Section s : sections) {
            if (!s.body().strip().isEmpty() || titlesWithChildren.contains(childKey(s))) {
                keep.add(s);
            }
        }
        return keep;
    }

    private static String childKey(Section s) {
        return s.depth() + "\u0000" + s.title();
    }

    /**
     * Splits a section whose body exceeds {@link #CHUNK_BODY_MAX_CHARS}. Public so a caller that
     * must NOT run the TOC filter (the Confluence page-title root, whose empty heading path makes
     * {@link #filterSections} treat a page named "Table of Contents" as a TOC to drop) can still
     * get bounded chunks.
     */
    public static List<Section> splitOversizedSection(Section section) {
        if (section.body().length() <= CHUNK_BODY_MAX_CHARS) {
            return List.of(section);
        }
        List<String> parts = splitRecursive(section.body(), CHUNK_BODY_MAX_CHARS);
        if (parts.size() == 1) {
            return List.of(section);
        }
        int n = parts.size();
        List<Section> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(
                new Section(section.depth(), section.title() + " (part " + (i + 1) + "/" + n + ")",
                    section.headingPath(), parts.get(i)));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Splitting helpers
    // ------------------------------------------------------------------

    static List<String> splitRecursive(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return List.of(text);
        }
        List<String> pieces = new ArrayList<>();
        pieces.add(text);
        for (String sep : new String[] {"\n\n", "\n", ". ", " "}) {
            if (allWithin(pieces, maxChars)) {
                return pieces;
            }
            List<String> next = new ArrayList<>();
            for (String p : pieces) {
                if (p.length() <= maxChars) {
                    next.add(p);
                } else {
                    next.addAll(greedySplit(p, maxChars, sep));
                }
            }
            pieces = next;
        }
        // Hard slice anything still over the cap (no separators worked).
        List<String> out = new ArrayList<>();
        for (String p : pieces) {
            if (p.length() <= maxChars) {
                out.add(p);
            } else {
                for (int i = 0; i < p.length(); i += maxChars) {
                    out.add(p.substring(i, Math.min(i + maxChars, p.length())));
                }
            }
        }
        return out;
    }

    static List<String> greedySplit(String text, int maxChars, String sep) {
        String[] tokens = text.split(Pattern.quote(sep), -1);
        List<String> parts = new ArrayList<>();
        List<String> buf = new ArrayList<>();
        int bufSize = 0;
        for (String t : tokens) {
            int tSize = t.length() + sep.length();
            if (!buf.isEmpty() && bufSize + tSize > maxChars) {
                parts.add(String.join(sep, buf));
                buf = new ArrayList<>();
                buf.add(t);
                bufSize = tSize;
            } else {
                buf.add(t);
                bufSize += tSize;
            }
        }
        if (!buf.isEmpty()) {
            parts.add(String.join(sep, buf));
        }
        return parts;
    }

    private static boolean allWithin(List<String> pieces, int maxChars) {
        for (String p : pieces) {
            if (p.length() > maxChars) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Low-level helpers
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseFrontmatter(String yamlText) {
        try {
            Object loaded = new Yaml().load(yamlText);
            if (loaded instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
        } catch (RuntimeException ignored) {
            // Mirror the Python behaviour: swallow malformed frontmatter and treat it as empty.
        }
        return Map.of();
    }

    static List<String> splitLines(String text) {
        List<String> out = new ArrayList<>();
        int n = text.length();
        int start = 0;
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                out.add(text.substring(start, i));
                if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') {
                    i++;
                }
                i++;
                start = i;
            } else {
                i++;
            }
        }
        if (start < n) {
            out.add(text.substring(start));
        }
        return out;
    }
}
