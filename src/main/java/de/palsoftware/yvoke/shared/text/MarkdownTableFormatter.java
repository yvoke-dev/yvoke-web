package de.palsoftware.yvoke.shared.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Shared markdown table formatter for MCP tool outputs and citation verification reports.
 */
public final class MarkdownTableFormatter {

    /**
     * Character budget for a single markdown table cell, so one long value cannot dominate a tool
     * result. Public so callers and tests can reason about what fits rather than rediscovering the
     * number; a cell clipped to it is always marked (see {@link #formatTableRows}).
     */
    public static final int MAX_CELL_CHARS = 1000;

    private MarkdownTableFormatter() {}

    public static String formatTableRows(List<Map<String, String>> rows, List<String> cols) {
        if (rows == null || rows.isEmpty()) {
            return "(no rows)";
        }
        StringBuilder sb = new StringBuilder();
        // Header
        sb.append("| ").append(String.join(" | ", cols)).append(" |\n");
        // Separator
        sb.append("| ").append(cols.stream().map(c -> "---").collect(Collectors.joining(" | ")))
            .append(" |\n");
        // Rows
        for (Map<String, String> row : rows) {
            List<String> vals = new ArrayList<>();
            for (String col : cols) {
                String val = row.get(col);
                if (val == null) {
                    val = "";
                }
                // Inline table cells must not contain | or newlines.
                val = val.replace("|", "\\|").replace("\n", " ").replace("\r", "");
                // A bare "..." was ambiguous — real content ends that way too, so a clipped cell
                // was indistinguishable from a complete one and the caller could not tell how much
                // was missing (a 4,275-char value came back as ~23% of itself, silently). State
                // the loss explicitly, and fit the marker INSIDE the budget so the cap still holds.
                if (val.length() > MAX_CELL_CHARS) {
                    String marker = " … [truncated, " + val.length() + " chars total]";
                    int keep = Math.max(0, MAX_CELL_CHARS - marker.length());
                    val = val.substring(0, keep) + marker;
                }
                vals.add(val);
            }
            sb.append("| ").append(String.join(" | ", vals)).append(" |\n");
        }
        return sb.toString().trim();
    }
}
