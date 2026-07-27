package de.palsoftware.yvoke.mcp.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.service.CollectionService;
import de.palsoftware.yvoke.jsonobject.core.model.JsonObject;
import de.palsoftware.yvoke.jsonobject.core.repository.JsonObjectRepository;
import de.palsoftware.yvoke.jsonobject.core.service.JsonObjectService;
import de.palsoftware.yvoke.mcp.McpToolUtils;
import jakarta.annotation.Nullable;


@Component
public class QueryJsonObjectsTool {

    private static final Logger log = LoggerFactory.getLogger(QueryJsonObjectsTool.class);

    private final JsonObjectService jsonObjectService;
    private final CollectionService collectionService;
    private final ObjectMapper objectMapper;

    public QueryJsonObjectsTool(JsonObjectService jsonObjectService,
        CollectionService collectionService, ObjectMapper objectMapper) {
        this.jsonObjectService = jsonObjectService;
        this.collectionService = collectionService;
        this.objectMapper = objectMapper;
    }

    @McpTool(name = "query_json_objects",
        description = "Query structured JSON objects stored in a collection. Use this tool when the collection contains structured/tabular data (e.g. sensor readings, configuration records, metadata entries) rather than prose documents — for prose, use 'search_corpus' instead. Supports PostgreSQL jsonpath filter expressions and field projection. Returns a markdown table with columns matching the requested fields. Set countOnly=true to get just the count without fetching rows.")
    @Tool(name = "query_json_objects",
        description = "Query structured JSON objects stored in a collection. Use this tool when the collection contains structured/tabular data (e.g. sensor readings, configuration records, metadata entries) rather than prose documents — for prose, use 'search_corpus' instead. Supports PostgreSQL jsonpath filter expressions and field projection. Returns a markdown table with columns matching the requested fields. Set countOnly=true to get just the count without fetching rows.")
    public String queryJsonObjects(
        @McpToolParam(description = "The collection to query.", required = true)
        @ToolParam(description = "The collection to query.", required = true) String collection,

        @McpToolParam(
            description = "PostgreSQL jsonpath filter expression, e.g. '$.sensors[*] ? (@.temperature > 30)'. Uses SQL/JSON path syntax (not JQ or XPath). Use 'get_json_schema' first to discover available fields. When nothing is specified all objects are returned.",
            required = false)
        @ToolParam(
            description = "PostgreSQL jsonpath filter expression, e.g. '$.sensors[*] ? (@.temperature > 30)'. Uses SQL/JSON path syntax (not JQ or XPath). Use 'get_json_schema' first to discover available fields. When nothing is specified all objects are returned.",
            required = false)
        @Nullable String jsonPath,

        @McpToolParam(
            description = "Scope to a single tag. REQUIRED when the collection has tags — results must come from exactly one tag; the error lists the valid values. Omit it only for a collection that has no tags.",
            required = false)
        @ToolParam(
            description = "Scope to a single tag. REQUIRED when the collection has tags — results must come from exactly one tag; the error lists the valid values. Omit it only for a collection that has no tags.",
            required = false)
        @Nullable String tag,

        @McpToolParam(
            description = "Comma-separated list of JSON keys/paths to retrieve (e.g. 'name,address.city'). At least one field path must be specified unless countOnly is true. Use 'get_json_schema' to find valid field paths. The output table columns will match these paths.",
            required = false)
        @ToolParam(
            description = "Comma-separated list of JSON keys/paths to retrieve (e.g. 'name,address.city'). At least one field path must be specified unless countOnly is true. Use 'get_json_schema' to find valid field paths. The output table columns will match these paths.",
            required = false)
        @Nullable String fields,

        @McpToolParam(description = "Max results. When nothing is specified 500 is used.",
            required = false)
        @ToolParam(description = "Max results. When nothing is specified 500 is used.",
            required = false)
        @Nullable Integer limit,

        @McpToolParam(
            description = "Pagination offset. When nothing is specified 0 is used. To fetch subsequent pages, set offset to (offset + limit) of the previous request.",
            required = false)
        @ToolParam(description = "Optional offset for pagination (default 0).", required = false)
        @Nullable Integer offset,

        @McpToolParam(
            description = "When true, return only the count of matching objects instead of the actual rows. Use this to get summary counts before deciding whether to fetch all rows. The 'fields' parameter is ignored when this is true.",
            required = false)
        @ToolParam(
            description = "When true, return only the count of matching objects instead of the actual rows. Use this to get summary counts before deciding whether to fetch all rows. The 'fields' parameter is ignored when this is true.",
            required = false)
        @Nullable Boolean countOnly,

        @McpToolParam(
            description = "When countOnly is true, this optionally groups the counts by the specified top-level JSON field (e.g. 'module' or 'op'). Returns a markdown table of counts per distinct value.",
            required = false)
        @ToolParam(
            description = "When countOnly is true, this optionally groups the counts by the specified top-level JSON field (e.g. 'module' or 'op'). Returns a markdown table of counts per distinct value.",
            required = false)
        @Nullable String groupBy) {
        log.info("QueryJsonObjectsTool: querying JSON objects in collection '{}'", collection);

        if (collection == null || collection.isBlank()) {
            return "Error: 'collection' parameter is required.";
        }
        String col = collection.trim();
        boolean isCountOnly = Boolean.TRUE.equals(countOnly);

        if (!isCountOnly && (fields == null || fields.isBlank())) {
            return "Error: 'fields' parameter is required. At least one field path must be specified.";
        }
        List<String> fieldPaths = isCountOnly ? List.of() : parseCsv(fields);
        if (!isCountOnly && fieldPaths.isEmpty()) {
            return "Error: 'fields' parameter cannot be empty. At least one field path must be specified.";
        }

        try {
            List<Collection> allCols = collectionService.listCollections();
            Collection matchedCol = allCols.stream().filter(c -> c.name().equalsIgnoreCase(col))
                .findFirst().orElse(null);
            if (matchedCol == null) {
                return "Error: Collection '" + col + "' does not exist.";
            }

            // Validate tag (optional)
            String parsedTag = (tag != null && !tag.isBlank()) ? tag.trim() : null;
            // A tag-scoped collection must be read at exactly one tag: untagged reads either
            // duplicate every hit across product versions or merge unrelated datasets, and
            // neither failure is visible in the result. Conditional on the collection, so an
            // untagged collection exempts itself.
            String tagRequired = McpToolUtils.requireTag(matchedCol, parsedTag);
            if (tagRequired != null) {
                return tagRequired;
            }
            if (parsedTag != null) {
                if (matchedCol.tags() == null || !matchedCol.tags().contains(parsedTag)) {
                    return "Error: Tag '" + parsedTag + "' does not exist in collection '" + col
                        + "'.";
                }
            }

            List<String> tagList = (parsedTag != null) ? List.of(parsedTag) : List.of();
            // Count-only mode: return just the count without fetching rows
            if (isCountOnly) {
                if (groupBy != null && !groupBy.isBlank()) {
                    // groupBy is concatenated into data->>'…' downstream, so anything outside the
                    // identifier charset gets stripped. That silently rewrites a nested path into a
                    // key no row has, and GROUP BY then returns a single (null) bucket whose count
                    // equals the whole set — a well-formed table that reads like a real breakdown.
                    // Reject it here, where an actionable message can still be returned.
                    String requested = groupBy.trim();
                    if (!requested.equals(requested.replaceAll("[^a-zA-Z0-9_\\-]", ""))) {
                        return "Error: groupBy '" + requested
                            + "' is not a top-level field. groupBy accepts a single top-level key "
                            + "only (letters, digits, '_' and '-' — no dots, spaces or quotes). To "
                            + "break down a nested or spaced value, filter with jsonPath and run "
                            + "one countOnly query per value instead.";
                    }
                    Map<String, Long> counts = jsonObjectService
                        .countGroupedObjects(matchedCol.id(), jsonPath, groupBy.trim(), tagList);
                    // The repository fetches one group beyond the cap purely as a probe: seeing it
                    // means there are more groups than we are about to show. Trim it back off so
                    // the extra never reaches the caller as data.
                    boolean moreGroups = counts.size() > JsonObjectRepository.MAX_GROUPS;
                    List<Map<String, String>> countRows = new ArrayList<>();
                    for (Map.Entry<String, Long> entry : counts.entrySet()) {
                        if (countRows.size() == JsonObjectRepository.MAX_GROUPS) {
                            break;
                        }
                        countRows.add(Map.of(groupBy.trim(), entry.getKey(), "Count",
                            entry.getValue().toString()));
                    }
                    // Rows arrive ordered by count DESC, so a truncated result is the TOP groups —
                    // useful, but only if it does not read as the complete picture.
                    String groupScope = moreGroups ? " (showing the top "
                        + JsonObjectRepository.MAX_GROUPS
                        + " groups by count — more groups exist; narrow with jsonPath, or group "
                        + "by a lower-cardinality field)" : " (" + countRows.size() + " groups)";
                    return "Grouped counts for collection " + col
                        + (parsedTag != null ? " (tag: " + parsedTag + ")" : "")
                        + (jsonPath != null && !jsonPath.isBlank() ? " with filter: " + jsonPath
                            : "")
                        + " grouped by " + groupBy.trim() + groupScope + ":\n\n"
                        + McpToolUtils.formatTableRows(countRows, List.of(groupBy.trim(), "Count"));
                } else {
                    long count;
                    if (jsonPath != null && !jsonPath.isBlank()) {
                        count = jsonObjectService.countSearchObjects(matchedCol.id(), jsonPath,
                            tagList);
                    } else {
                        count = jsonObjectService.countObjects(matchedCol.id(), tagList);
                    }
                    return "Count: " + count + " objects match in collection " + col
                        + (parsedTag != null ? " (tag: " + parsedTag + ")" : "")
                        + (jsonPath != null && !jsonPath.isBlank() ? " with filter: " + jsonPath
                            : "")
                        + ".";
                }
            }

            int lim = limit != null ? limit : 500;
            int off = offset != null ? offset : 0;

            List<JsonObject> results;

            if (jsonPath != null && !jsonPath.isBlank()) {
                results =
                    jsonObjectService.queryObjects(matchedCol.id(), jsonPath, tagList, lim, off);
            } else {
                // Offset-based, matching the filtered branch above. Passing `off / lim` to the
                // page-based overload rounded any non-multiple offset down to its page start.
                results = jsonObjectService.listObjectsByOffset(matchedCol.id(), tagList, lim, off);
            }

            if (results.isEmpty()) {
                return "No JSON objects found matching the criteria in collection " + col;
            }

            // Format table preview
            List<String> headers = new ArrayList<>(fieldPaths);

            List<Map<String, String>> rows = new ArrayList<>();
            for (JsonObject obj : results) {
                Map<String, String> row = new HashMap<>();

                for (String path : fieldPaths) {
                    row.put(path, getNestedValue(obj.data(), path));
                }

                rows.add(row);
            }

            String paginationNote =
                results.size() == lim ? ". To fetch the next page, use offset=" + (off + lim)
                    : " (reached the end of results)";
            return "Found " + results.size() + " JSON objects in collection " + col + " (offset="
                + off + ", limit=" + lim + ")" + paginationNote + ".\n\n"
                + McpToolUtils.formatTableRows(rows, headers);
        } catch (Exception e) {
            return McpToolUtils.toolError("query_json_objects", e);
        }
    }

    private List<String> parseCsv(String input) {
        if (input == null || input.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(input.split(",")).map(String::trim).filter(s -> !s.isEmpty())
            .toList();
    }

    private String getNestedValue(Map<String, Object> map, String path) {
        if (map == null)
            return "";
        String[] parts = path.split("\\.");
        Object current = extractPath(map, parts, 0);
        if (current == null) {
            return "";
        }
        if (current instanceof Map || current instanceof List) {
            try {
                return objectMapper.writeValueAsString(current);
            } catch (Exception e) {
                return current.toString();
            }
        }
        return current.toString();
    }

    private Object extractPath(Object current, String[] parts, int index) {
        if (current == null) {
            return null;
        }
        if (index >= parts.length) {
            return current;
        }
        String part = parts[index];
        if (current instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> nested = (Map<String, Object>) current;
            if (part.contains("*")) {
                String regex = "(?i)" + part.replace("*", ".*");
                Map<String, Object> matches = new HashMap<>();
                for (Map.Entry<String, Object> entry : nested.entrySet()) {
                    if (entry.getKey().matches(regex)) {
                        Object val = extractPath(entry.getValue(), parts, index + 1);
                        if (val != null) {
                            matches.put(entry.getKey(), val);
                        }
                    }
                }
                return matches.isEmpty() ? null : matches;
            } else {
                return extractPath(nested.get(part), parts, index + 1);
            }
        } else if (current instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) current;
            List<Object> matches = new ArrayList<>();
            for (Object item : list) {
                Object val = extractPath(item, parts, index);
                if (val != null) {
                    matches.add(val);
                }
            }
            return matches.isEmpty() ? null : matches;
        }
        return null;
    }
}
