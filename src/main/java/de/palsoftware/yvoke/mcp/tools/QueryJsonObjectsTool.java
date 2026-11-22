package de.palsoftware.yvoke.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.service.CollectionService;
import de.palsoftware.yvoke.jsonobject.core.model.JsonObject;
import de.palsoftware.yvoke.jsonobject.core.repository.JsonObjectRepository;
import de.palsoftware.yvoke.jsonobject.core.service.JsonObjectService;
import de.palsoftware.yvoke.mcp.McpToolUtils;
import de.palsoftware.yvoke.mcp.McpToolUtils.CollectionResolution;
import de.palsoftware.yvoke.rag.core.McpToolHints;
import jakarta.annotation.Nullable;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

@Component
@McpToolHints(readOnly = true, destructive = false, idempotent = true)
public class QueryJsonObjectsTool {

    private static final Logger log = LoggerFactory.getLogger(QueryJsonObjectsTool.class);

    private final JsonObjectService jsonObjectService;
    private final CollectionService collectionService;
    private final ObjectMapper objectMapper;

    public QueryJsonObjectsTool(
            JsonObjectService jsonObjectService,
            CollectionService collectionService,
            ObjectMapper objectMapper) {
        this.jsonObjectService = jsonObjectService;
        this.collectionService = collectionService;
        this.objectMapper = objectMapper;
    }

    @Tool(
            name = "query_json_objects",
            description =
                    "Query structured JSON objects stored in a collection. Use this tool when the collection contains structured/tabular data (e.g. configuration records, change histories, metadata entries) rather than prose documents — for prose, use 'search_corpus' instead. Supports PostgreSQL jsonpath filter expressions and field projection. Returns a markdown table with columns matching the requested fields. Set countOnly=true to get just the count without fetching rows.")
    public String queryJsonObjects(
            @ToolParam(
                            description =
                                    "The collection to query (if not given by a playbook, call 'list_areas' to discover authorized areas and their collections).",
                            required = true)
                    String collection,
            @ToolParam(
                            description =
                                    "PostgreSQL jsonpath filter expression starting with '$', e.g. '$ ? (@.status == \"active\")' or '$.items[*] ? (@.count > 10)'. Uses SQL/JSON path syntax (not JQ or XPath). Use 'get_json_schema' first to discover available fields. When nothing is specified all objects are returned.",
                            required = false)
                    @Nullable
                    String jsonPath,
            @ToolParam(
                            description =
                                    "Scope to a single tag. REQUIRED when the collection has tags — results must come from exactly one tag; the error lists the valid values. Omit it only for a collection that has no tags.",
                            required = false)
                    @Nullable
                    String tag,
            @ToolParam(
                            description =
                                    "Comma-separated list of JSON keys/paths to retrieve (e.g. 'name,address.city'). Supports '*' wildcards in key segments (case-insensitive, e.g. 'addr*.city'). At least one field path must be specified unless countOnly is true. Use 'get_json_schema' to find valid field paths. The output table columns will match these paths.",
                            required = false)
                    @Nullable
                    String fields,
            @ToolParam(
                            description =
                                    "Max results (1–"
                                            + JsonObjectRepository.MAX_LIMIT
                                            + "). When nothing is specified "
                                            + JsonObjectRepository.MAX_LIMIT
                                            + " is used.",
                            required = false)
                    @Nullable
                    Integer limit,
            @ToolParam(
                            description =
                                    "Pagination offset. When nothing is specified 0 is used. To fetch subsequent pages, set offset to (offset + limit) of the previous request.",
                            required = false)
                    @Nullable
                    Integer offset,
            @ToolParam(
                            description =
                                    "When true, return only the count of matching objects instead of the actual rows. Use this to get summary counts before deciding whether to fetch all rows. The 'fields' parameter is ignored when this is true.",
                            required = false)
                    @Nullable
                    Boolean countOnly,
            @ToolParam(
                            description =
                                    "When countOnly is true, this optionally groups the counts by the specified top-level JSON field (e.g. 'module' or 'op'). Returns up to "
                                            + JsonObjectRepository.MAX_GROUPS
                                            + " groups ordered by count descending as a markdown table.",
                            required = false)
                    @Nullable
                    String groupBy) {
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

        String normalizedJsonPath =
                (jsonPath != null && !jsonPath.isBlank()) ? jsonPath.trim() : null;
        if (normalizedJsonPath != null && !normalizedJsonPath.startsWith("$")) {
            return "Error: filter '"
                    + normalizedJsonPath
                    + "' is not a valid PostgreSQL jsonPath expression — it must start with '$' "
                    + "(e.g. '$ ? (@.status == \"active\")').";
        }

        try {
            CollectionResolution resolution =
                    McpToolUtils.resolveReadableCollection(
                            collectionService, collection, tag, null);
            if (resolution instanceof CollectionResolution.Refused refused) {
                return refused.errorMessage();
            }
            CollectionResolution.Allowed allowed = (CollectionResolution.Allowed) resolution;
            Collection matchedCol = allowed.collection();
            String parsedTag = allowed.tag();

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
                        return "Error: groupBy '"
                                + requested
                                + "' is not a top-level field. groupBy accepts a single top-level key "
                                + "only (letters, digits, '_' and '-' — no dots, spaces or quotes). To "
                                + "break down a nested or spaced value, filter with jsonPath and run "
                                + "one countOnly query per value instead.";
                    }
                    Map<String, Long> counts =
                            jsonObjectService.countGroupedObjects(
                                    matchedCol.id(), normalizedJsonPath, groupBy.trim(), tagList);
                    // The repository fetches one group beyond the cap purely as a probe: seeing it
                    // means there are more groups than we are about to show. Trim it back off so
                    // the extra never reaches the caller as data.
                    boolean moreGroups = counts.size() > JsonObjectRepository.MAX_GROUPS;
                    List<Map<String, String>> countRows = new ArrayList<>();
                    for (Map.Entry<String, Long> entry : counts.entrySet()) {
                        if (countRows.size() == JsonObjectRepository.MAX_GROUPS) {
                            break;
                        }
                        countRows.add(
                                Map.of(
                                        groupBy.trim(),
                                        entry.getKey(),
                                        "Count",
                                        entry.getValue().toString()));
                    }
                    // Rows arrive ordered by count DESC, so a truncated result is the TOP groups —
                    // useful, but only if it does not read as the complete picture.
                    String groupScope =
                            moreGroups
                                    ? " (showing the top "
                                            + JsonObjectRepository.MAX_GROUPS
                                            + " groups by count — more groups exist; narrow with jsonPath, or group "
                                            + "by a lower-cardinality field)"
                                    : " (" + countRows.size() + " groups)";
                    return "Grouped counts for collection "
                            + col
                            + (parsedTag != null ? " (tag: " + parsedTag + ")" : "")
                            + (normalizedJsonPath != null
                                    ? " with filter: " + normalizedJsonPath
                                    : "")
                            + " grouped by "
                            + groupBy.trim()
                            + groupScope
                            + ":\n\n"
                            + McpToolUtils.formatTableRows(
                                    countRows, List.of(groupBy.trim(), "Count"));
                } else {
                    long count;
                    if (normalizedJsonPath != null) {
                        count =
                                jsonObjectService.countSearchObjects(
                                        matchedCol.id(), normalizedJsonPath, tagList);
                    } else {
                        count = jsonObjectService.countObjects(matchedCol.id(), tagList);
                    }
                    return "Count: "
                            + count
                            + " objects match in collection "
                            + col
                            + (parsedTag != null ? " (tag: " + parsedTag + ")" : "")
                            + (normalizedJsonPath != null
                                    ? " with filter: " + normalizedJsonPath
                                    : "")
                            + ".";
                }
            }

            if (offset != null && offset < 0) {
                return "Error: 'offset' must be 0 or greater.";
            }
            int lim =
                    Math.max(
                            1,
                            Math.min(
                                    limit != null ? limit : JsonObjectRepository.MAX_LIMIT,
                                    JsonObjectRepository.MAX_LIMIT));
            int off = offset != null ? offset : 0;

            List<JsonObject> results;

            if (normalizedJsonPath != null) {
                results =
                        jsonObjectService.queryObjects(
                                matchedCol.id(), normalizedJsonPath, tagList, lim, off);
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
                    results.size() == lim
                            ? ". To fetch the next page, use offset=" + (off + lim)
                            : " (reached the end of results)";
            return "Found "
                    + results.size()
                    + " JSON objects in collection "
                    + col
                    + " (offset="
                    + off
                    + ", limit="
                    + lim
                    + ")"
                    + paginationNote
                    + ".\n\n"
                    + McpToolUtils.formatTableRows(rows, headers);
        } catch (DataAccessException e) {
            if (normalizedJsonPath != null && isPostgresJsonPathError(e)) {
                log.warn(
                        "Invalid PostgreSQL jsonPath '{}' for collection '{}': {}",
                        normalizedJsonPath,
                        col,
                        e.getMessage());
                return "Error: Invalid PostgreSQL jsonPath expression '"
                        + normalizedJsonPath
                        + "' — check SQL/JSON path syntax (e.g. '$ ? (@.field == \"value\")'; "
                        + "use '==' for equality and double quotes for strings).";
            }
            return McpToolUtils.toolError("query_json_objects", e);
        } catch (Exception e) {
            return McpToolUtils.toolError("query_json_objects", e);
        }
    }

    private static boolean isPostgresJsonPathError(Throwable t) {
        Throwable cursor = t;
        int depth = 0;
        while (cursor != null && depth < 16) {
            if (cursor instanceof SQLException sqlEx) {
                String state = sqlEx.getSQLState();
                if ("42601".equals(state)
                        || "22023".equals(state)
                        || (state != null && state.startsWith("2203"))) {
                    return true;
                }
            }
            cursor = cursor.getCause();
            depth++;
        }
        return false;
    }

    private List<String> parseCsv(String input) {
        if (input == null || input.isBlank()) {
            return List.of();
        }
        return Arrays.stream(input.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private String getNestedValue(Map<String, Object> map, String path) {
        if (map == null) return "";
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
                Map<String, Object> matches = new HashMap<>();
                for (Map.Entry<String, Object> entry : nested.entrySet()) {
                    if (matchesWildcardIgnoreCase(entry.getKey(), part)) {
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

    /**
     * Case-insensitive glob matcher supporting {@code *} (zero or more of any character). Treats
     * all other characters — including regex metacharacters like {@code [}, {@code (}, {@code +} —
     * as literals, avoiding {@code PatternSyntaxException} and catastrophic backtracking (ReDoS).
     */
    static boolean matchesWildcardIgnoreCase(String text, String pattern) {
        String s = text.toLowerCase(Locale.ROOT);
        String p = pattern.toLowerCase(Locale.ROOT);
        int si = 0;
        int pi = 0;
        int starIdx = -1;
        int matchIdx = 0;
        while (si < s.length()) {
            if (pi < p.length() && p.charAt(pi) == s.charAt(si)) {
                si++;
                pi++;
            } else if (pi < p.length() && p.charAt(pi) == '*') {
                starIdx = pi;
                matchIdx = si;
                pi++;
            } else if (starIdx != -1) {
                pi = starIdx + 1;
                matchIdx++;
                si = matchIdx;
            } else {
                return false;
            }
        }
        while (pi < p.length() && p.charAt(pi) == '*') {
            pi++;
        }
        return pi == p.length();
    }
}
