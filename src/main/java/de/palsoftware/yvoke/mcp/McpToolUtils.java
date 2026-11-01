package de.palsoftware.yvoke.mcp;

import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.kg.core.model.KgEntity;
import jakarta.annotation.Nullable;
import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class McpToolUtils {

    private static final Logger log = LoggerFactory.getLogger(McpToolUtils.class);

    /**
     * Character budget for a single markdown table cell, so one long value cannot dominate a tool
     * result. Public so callers and tests can reason about what fits rather than rediscovering the
     * number; a cell clipped to it is always marked (see {@link #formatTableRows}).
     */
    public static final int MAX_CELL_CHARS = 1000;

    public static String toolError(String toolName, Throwable e) {
        log.error("MCP tool '{}' failed", toolName, e);
        return "ERROR: the '" + toolName + "' tool failed to complete the request.";
    }

    /**
     * Enforces that a tag-scoped collection is queried at exactly one tag. Returns {@code null}
     * when the call is acceptable, or the error string the tool should return verbatim.
     *
     * <p>
     * A tag means two different things in this corpus and omitting it is wrong in both: for product
     * versions the same object exists once per release, so an untagged read returns each hit twice
     * (or, for graph traversal, a picker with no edges); for dataset tags such as DB-History's
     * {@code schema}/{@code content} it merges two incompatible row shapes into one result. Neither
     * failure is visible in the output, which is why this is a hard error rather than a warning.
     *
     * <p>
     * The requirement is conditional on the collection rather than global, so a collection with no
     * tags exempts itself — it is therefore deliberately NOT expressed in the tools' JSON input
     * schemas, which cannot say "required depending on another argument's value".
     */
    @Nullable
    public static String requireTag(Collection collection, @Nullable String tag) {
        List<String> tags = collection.tags();
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        if (tag != null && !tag.isBlank()) {
            return null;
        }
        return "Error: Collection '" + collection.name()
            + "' is tag-scoped — the 'tag' parameter is required so results come from exactly one "
            + "of them. Valid tags: " + String.join(", ", tags);
    }

    /**
     * Enforces version/tag scoping on omit-collection {@code search_corpus} and
     * {@code list_documents} calls across an area's allowed collections.
     *
     * <p>
     * Deliberately requires a supplied {@code tag} to exist in <em>all</em> tagged collections in
     * the area: when a tag is valid for only one collection (e.g. {@code 9.3.1} in manuals while
     * schema only has {@code 10.0}), omitting {@code collection} would either silently drop the
     * other collection or mix versions, so the caller is prompted to name {@code collection}
     * explicitly.
     */
    @Nullable
    public static String requireAreaTag(List<Collection> allowed, @Nullable String rawTag) {
        List<Collection> taggedCols =
            allowed.stream().filter(c -> c.tags() != null && !c.tags().isEmpty()).toList();
        if (taggedCols.isEmpty()) {
            return null;
        }
        String parsedTag = (rawTag != null && !rawTag.isBlank()) ? rawTag.trim() : null;
        if (parsedTag == null) {
            if (allowed.size() == 1 && taggedCols.size() == 1) {
                Collection single = taggedCols.get(0);
                return "Error: Collection '" + single.name()
                    + "' is version/tag-scoped. Please specify 'tag' (valid values: "
                    + String.join(", ", single.tags())
                    + ") so results come from a single version/dataset.";
            }
            String taggedSummary = taggedCols.stream()
                .map(c -> c.name() + " (valid tags: " + String.join(", ", c.tags()) + ")")
                .collect(Collectors.joining("; "));
            return "Error: Area contains version/tag-scoped collections (" + taggedSummary
                + "). Please specify 'collection' or 'tag' so results come from a single version/dataset.";
        }
        for (Collection tc : taggedCols) {
            if (!tc.tags().contains(parsedTag)) {
                return "Error: Tag '" + parsedTag + "' does not exist in collection '" + tc.name()
                    + "' (valid tags: " + String.join(", ", tc.tags())
                    + "). Please specify 'collection' or a 'tag' present in all tagged collections.";
            }
        }
        return null;
    }

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
