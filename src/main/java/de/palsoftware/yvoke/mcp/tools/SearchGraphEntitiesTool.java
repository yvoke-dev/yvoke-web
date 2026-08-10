package de.palsoftware.yvoke.mcp.tools;

import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.service.CollectionService;
import de.palsoftware.yvoke.kg.core.model.KgEntity;
import de.palsoftware.yvoke.kg.core.repository.KgGraphReadRepository;
import de.palsoftware.yvoke.mcp.McpToolUtils;
import jakarta.annotation.Nullable;
import java.util.*;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;


@Component
public class SearchGraphEntitiesTool {

    private static final Logger log = LoggerFactory.getLogger(SearchGraphEntitiesTool.class);

    private final KgGraphReadRepository kgRepository;
    private final CollectionService collectionService;

    public SearchGraphEntitiesTool(KgGraphReadRepository kgRepository,
        CollectionService collectionService) {
        this.kgRepository = kgRepository;
        this.collectionService = collectionService;
    }

    @McpTool(name = "search_graph_entities",
        description = "Search the knowledge graph for entities by name to discover exact entity names (e.g. database tables, columns, or code modules). Exact entity names are required to query neighbors using 'get_graph_neighbors'. If 'query' is omitted or '*', lists all entities (useful when filtering by 'kind'). Returns a markdown table with columns: entity_name, kind, tag, document_id, description. Identity is (collection, kind, name, tag), so the same name can appear once per kind AND once per tag (product version) \u2014 rows that differ only by tag are the same object in different versions and have different document_ids.")
    @Tool(name = "search_graph_entities",
        description = "Search the knowledge graph for entities by name to discover exact entity names (e.g. database tables, columns, or code modules). Exact entity names are required to query neighbors using 'get_graph_neighbors'. If 'query' is omitted or '*', lists all entities (useful when filtering by 'kind'). Returns a markdown table with columns: entity_name, kind, tag, document_id, description. Identity is (collection, kind, name, tag), so the same name can appear once per kind AND once per tag (product version) \u2014 rows that differ only by tag are the same object in different versions and have different document_ids.")
    public String searchGraphEntities(@McpToolParam(
        description = "Search term for fuzzy entity name matching (trigram-based). Partial names work. Leave blank or use '*' to list all entities (e.g. to list all modules).",
        required = false)
    @ToolParam(
        description = "Search term for fuzzy entity name matching (trigram-based). Partial names work. Leave blank or use '*' to list all entities (e.g. to list all modules).",
        required = false)
    @Nullable String query,
        @McpToolParam(description = "The collection to search.", required = true)
        @ToolParam(description = "The collection to search.", required = true) String collection,
        @McpToolParam(
            description = "Scope to a single tag. REQUIRED when the collection has tags — results must come from exactly one tag; the error lists the valid values. Omit it only for a collection that has no tags.",
            required = false)
        @ToolParam(
            description = "Scope to a single tag. REQUIRED when the collection has tags — results must come from exactly one tag; the error lists the valid values. Omit it only for a collection that has no tags.",
            required = false)
        @Nullable String tag,
        @McpToolParam(
            description = "Filter by entity kind (e.g. 'table', 'module', 'procedure'). When nothing is specified all kinds are considered.",
            required = false)
        @ToolParam(
            description = "Filter by entity kind (e.g. 'table', 'module', 'procedure'). When nothing is specified all kinds are considered.",
            required = false)
        @Nullable String kind,
        @McpToolParam(
            description = "Max matches to return. When nothing is specified 20 is used. Max is 200.",
            required = false)
        @ToolParam(
            description = "Max matches to return. When nothing is specified 20 is used. Max is 200.",
            required = false)
        @Nullable Integer limit) {
        log.info("SearchGraphEntitiesTool: searching entities for '{}' in collection '{}'", query,
            collection);

        if (collection == null || collection.isBlank()) {
            return "Error: 'collection' parameter is required.";
        }
        String col = collection.trim();

        List<Collection> allCols = collectionService.listCollections();
        Collection matchedCol =
            allCols.stream().filter(c -> c.name().equalsIgnoreCase(col)).findFirst().orElse(null);
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
                return "Error: Tag '" + parsedTag + "' does not exist in collection '" + col + "'.";
            }
        }

        String knd = (kind != null && !kind.isBlank()) ? kind.trim() : null;
        // Clamp to the repository's own ceiling here rather than passing the raw request through:
        // the repository clamps silently, so an unclamped `lim` would make the truncation test
        // below miss every over-cap request (200 rows returned for a requested 500 is truncated).
        int lim = Math.min((limit != null) ? limit : 20, KgGraphReadRepository.MAX_LIMIT);
        try {
            List<KgEntity> entities;
            boolean isListAll = query == null || query.isBlank() || query.trim().equals("*");
            if (isListAll) {
                entities = kgRepository.listEntities(col, parsedTag, knd, lim, 0);
            } else {
                entities = kgRepository.fuzzySearchEntities(query, lim, parsedTag, col, knd);
            }

            if (entities.isEmpty()) {
                String suffix = (parsedTag != null) ? " at tag '" + parsedTag + "'" : "";
                String kindSuffix = (knd != null) ? " with kind '" + knd + "'" : "";
                String querySuffix = isListAll ? " (all)" : " matching '" + query + "'";
                return "(no entities" + querySuffix + kindSuffix + " found in " + col + " graph"
                    + suffix + ")";
            }

            List<Map<String, String>> rows = new ArrayList<>();
            for (KgEntity e : entities) {
                Map<String, String> row = new HashMap<>();
                row.put("entity_name", e.name());
                row.put("kind", e.kind() != null ? e.kind() : "");
                String docId = "";
                if (e.metadata() != null && e.metadata().containsKey("document_id")) {
                    docId = e.metadata().get("document_id").toString();
                }
                row.put("document_id", docId);
                // Identity is tag-scoped, so two rows can be identical but for their version. The
                // tag is the only thing that tells them apart — and the only thing that explains
                // why their document_ids differ.
                row.put("tag",
                    (e.tags() == null || e.tags().isEmpty()) ? "" : String.join(", ", e.tags()));
                row.put("description", e.description() != null ? e.description() : "");
                rows.add(row);
            }

            String tagSuffix = (parsedTag != null) ? " (tag=" + parsedTag + ")" : "";
            String kindSuffix = (knd != null) ? " (kind=" + knd + ")" : "";
            // A full page is indistinguishable from a complete result set unless we say so, and
            // most kinds in a real corpus exceed the cap — an unlabelled page reads as "that is
            // all of them" and gets counted. Same wording as get_graph_neighbors, deliberately.
            String countLabel = (entities.size() >= lim)
                ? "showing first " + lim + ", more exist — narrow with query/kind, or raise limit"
                : "total: " + entities.size();
            String subject = isListAll ? "### All graph entities"
                : "### Graph Entities matching '" + query + "'";
            return subject + kindSuffix + " in " + col + tagSuffix + " (" + countLabel + ")\n\n"
                + duplicateNameNote(rows) + McpToolUtils.formatTableRows(rows,
                    List.of("entity_name", "kind", "tag", "document_id", "description"));
        } catch (Exception e) {
            return McpToolUtils.toolError("search_graph_entities", e);
        }
    }

    /**
     * Renders a warning block for names that occur under several kinds in this result set. Identity
     * in the graph is (collection, kind, name), so same-named rows are DIFFERENT objects — without
     * this hint a model happily picks the first row's document_id and reads the wrong document.
     * Returns "" (no note) when every returned name is unique.
     */
    private static String duplicateNameNote(List<Map<String, String>> rows) {
        // Keep table order for the names, alphabetical for the kinds of each name.
        Map<String, String> displayName = new LinkedHashMap<>();
        Map<String, SortedSet<String>> kindsByName = new LinkedHashMap<>();
        for (Map<String, String> row : rows) {
            String name = row.get("entity_name");
            if (name == null || name.isBlank()) {
                continue;
            }
            String key = name.toLowerCase(Locale.ROOT);
            displayName.putIfAbsent(key, name);
            String kind = row.get("kind");
            kindsByName.computeIfAbsent(key, k -> new TreeSet<>())
                .add((kind != null && !kind.isBlank()) ? kind : "?");
        }

        // The same (name, kind) under two tags is one object in two product versions — a different
        // problem with a different remedy, and invisible to the kind check above because the kind
        // set has size 1. Left unflagged, a model reads whichever version sorted first.
        Map<String, SortedSet<String>> tagsByNameKind = new LinkedHashMap<>();
        Map<String, String> displayNameKind = new LinkedHashMap<>();
        for (Map<String, String> row : rows) {
            String name = row.get("entity_name");
            if (name == null || name.isBlank()) {
                continue;
            }
            String kind = row.get("kind");
            String key = name.toLowerCase(Locale.ROOT) + "\0"
                + ((kind != null) ? kind.toLowerCase(Locale.ROOT) : "");
            displayNameKind.putIfAbsent(key,
                name + ((kind != null && !kind.isBlank()) ? " (" + kind + ")" : ""));
            String tag = row.get("tag");
            tagsByNameKind.computeIfAbsent(key, k -> new TreeSet<>())
                .add((tag != null && !tag.isBlank()) ? tag : "(untagged)");
        }

        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, SortedSet<String>> entry : kindsByName.entrySet()) {
            if (entry.getValue().size() > 1) {
                lines.add(">   '" + displayName.get(entry.getKey()) + "' → "
                    + String.join(", ", entry.getValue()));
            }
        }
        List<String> versionLines = new ArrayList<>();
        for (Map.Entry<String, SortedSet<String>> entry : tagsByNameKind.entrySet()) {
            if (entry.getValue().size() > 1) {
                versionLines.add(">   '" + displayNameKind.get(entry.getKey()) + "' → tags "
                    + String.join(", ", entry.getValue()));
            }
        }

        StringBuilder note = new StringBuilder();
        if (!lines.isEmpty()) {
            note.append(
                "> Note: these names exist under several kinds — they are DIFFERENT objects, not duplicates:\n")
                .append(String.join("\n", lines)).append("\n").append(
                    "> Pick the row whose 'kind' matches what you asked about: pass that kind to get_graph_neighbors, and use THAT row's document_id for get_section.\n\n");
        }
        if (!versionLines.isEmpty()) {
            note.append(
                "> Note: these rows are the SAME object in several product versions, each with its own document:\n")
                .append(String.join("\n", versionLines)).append("\n").append(
                    "> Pass the 'tag' of the version you mean, and use THAT row's document_id for get_section.\n\n");
        }
        return note.toString();
    }
}
