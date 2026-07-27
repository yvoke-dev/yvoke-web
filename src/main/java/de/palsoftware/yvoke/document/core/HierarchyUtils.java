package de.palsoftware.yvoke.document.core;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import de.palsoftware.yvoke.document.core.model.ChunkPathRow;
import de.palsoftware.yvoke.document.core.model.ChunkRow;

public final class HierarchyUtils {

    private static final Pattern PART_SUFFIX_PATTERN =
        Pattern.compile("\\s*\\(part \\d+/\\d+\\)\\s*$");
    private static final Pattern BREADCRUMB_PATTERN =
        Pattern.compile("^>\\s+Section path:[^\\n]*\\n+");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    private HierarchyUtils() {
        // Private constructor to prevent instantiation
    }

    public static String stripPart(String heading) {
        if (heading == null) {
            return "";
        }
        return PART_SUFFIX_PATTERN.matcher(heading).replaceAll("").trim();
    }

    public static List<String> splitHeadingPath(String pathStr) {
        if (pathStr == null || pathStr.isBlank()) {
            return Collections.emptyList();
        }
        String[] segments = pathStr.split(">");
        List<String> result = new ArrayList<>();
        for (String segment : segments) {
            String clean = stripPart(segment.trim());
            if (!clean.isEmpty()) {
                result.add(clean);
            }
        }
        return result;
    }

    public static String normalizeSegment(String segment) {
        if (segment == null) {
            return "";
        }
        String normalized = Normalizer.normalize(segment, Normalizer.Form.NFKC);
        normalized = WHITESPACE_PATTERN.matcher(normalized).replaceAll(" ");
        return normalized.trim().toLowerCase();
    }

    public static String stripBreadcrumb(String text) {
        if (text == null) {
            return "";
        }
        return BREADCRUMB_PATTERN.matcher(text).replaceFirst("");
    }

    public static boolean isSubpathOf(List<String> target, List<String> candidate) {
        if (candidate.size() < target.size()) {
            return false;
        }
        for (int i = 0; i < target.size(); i++) {
            String tNorm = normalizeSegment(target.get(i));
            String cNorm = normalizeSegment(candidate.get(i));
            if (!tNorm.equals(cNorm)) {
                return false;
            }
        }
        return true;
    }

    public static boolean isExactPathMatch(List<String> target, List<String> candidate) {
        if (candidate.size() != target.size()) {
            return false;
        }
        for (int i = 0; i < target.size(); i++) {
            String tNorm = normalizeSegment(target.get(i));
            String cNorm = normalizeSegment(candidate.get(i));
            if (!tNorm.equals(cNorm)) {
                return false;
            }
        }
        return true;
    }

    public static List<String> getChunkFullPath(ChunkRow chunk) {
        return getChunkFullPath(chunk.headingPath(), chunk.heading());
    }

    public static List<String> getChunkFullPath(ChunkPathRow chunk) {
        return getChunkFullPath(chunk.headingPath(), chunk.heading());
    }

    public static List<String> getChunkFullPath(List<String> headingPath, String heading) {
        List<String> full = new ArrayList<>();
        if (headingPath != null) {
            for (String p : headingPath) {
                if (p != null && !p.isBlank()) {
                    full.add(stripPart(p));
                }
            }
        }
        if (heading != null && !heading.isBlank()) {
            String sh = stripPart(heading);
            if (!sh.isEmpty()) {
                full.add(sh);
            }
        }
        return full;
    }
}
