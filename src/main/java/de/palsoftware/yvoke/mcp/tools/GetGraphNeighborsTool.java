package de.palsoftware.yvoke.mcp.tools;

import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.service.CollectionService;
import de.palsoftware.yvoke.kg.core.model.KgEntityKindEdgeCount;
import de.palsoftware.yvoke.kg.core.model.KgNeighborEdges;
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
public class GetGraphNeighborsTool {

    private static final Logger log = LoggerFactory.getLogger(GetGraphNeighborsTool.class);

    /** Cap on returned edges — keeps hub nodes from flooding the DB query and the MCP response. */
    private static final int MAX_EDGES = 200;

    private final KgGraphReadRepository kgRepository;
    private final CollectionService collectionService;

    public GetGraphNeighborsTool(KgGraphReadRepository kgRepository,
        CollectionService collectionService) {
        this.kgRepository = kgRepository;
        this.collectionService = collectionService;
    }

    @McpTool(name = "get_graph_neighbors",
        description = "Query connections (edges) for a starting node/entity in the knowledge graph. A knowledge graph is scoped to a collection and an optional tag. Node/entity names can be retrieved via 'search_graph_entities'. When several entities share a name (e.g. a table and a process both named 'ADS') they are DIFFERENT objects: calling without 'kind' returns the candidate kinds with their edge counts instead of edges — re-run with 'kind' (or the name prefixed as '<kind>:<name>') to get the edges of the one you mean. 'tag' is REQUIRED when the collection has tags: results must come from exactly one product version, and the error lists the valid values. Returns a markdown table with columns: counterpart (the connected node/entity), kind (the counterpart's kind), relation (the predicate), direction, and description.")
    @Tool(name = "get_graph_neighbors",
        description = "Query connections (edges) for a starting node/entity in the knowledge graph. A knowledge graph is scoped to a collection and an optional tag. Node/entity names can be retrieved via 'search_graph_entities'. When several entities share a name (e.g. a table and a process both named 'ADS') they are DIFFERENT objects: calling without 'kind' returns the candidate kinds with their edge counts instead of edges — re-run with 'kind' (or the name prefixed as '<kind>:<name>') to get the edges of the one you mean. 'tag' is REQUIRED when the collection has tags: results must come from exactly one product version, and the error lists the valid values. Returns a markdown table with columns: counterpart (the connected node/entity), kind (the counterpart's kind), relation (the predicate), direction, and description.")
    public String getGraphNeighbors(
        @McpToolParam(description = "Exact name of the node/entity (case-insensitive).",
            required = true)
        @ToolParam(description = "Exact name of the node/entity (case-insensitive).",
            required = true) String entity_name,
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
            description = "Filter by edge relationship predicate (e.g. 'fk_to', 'calls', 'references_table'). When nothing is specified all predicates are considered.",
            required = false)
        @ToolParam(
            description = "Filter by edge relationship predicate (e.g. 'fk_to', 'calls', 'references_table'). When nothing is specified all predicates are considered.",
            required = false)
        @Nullable String relation_type,
        @McpToolParam(
            description = "Edge direction to traverse relative to the starting node ('incoming' points to the node, 'outgoing' points from the node, 'both' traverses both). When nothing is specified 'both' is used.",
            required = false)
        @ToolParam(
            description = "Edge direction to traverse relative to the starting node ('incoming' points to the node, 'outgoing' points from the node, 'both' traverses both). When nothing is specified 'both' is used.",
            required = false)
        @Nullable String direction,
        @McpToolParam(
            description = "Disambiguating node/entity kind (e.g. 'table', 'process', 'module'). Only needed when several kinds share the same name; when nothing is specified and the name is ambiguous, the candidate kinds are returned instead of edges. The name may instead be prefixed as '<kind>:<name>'.",
            required = false)
        @ToolParam(
            description = "Disambiguating node/entity kind (e.g. 'table', 'process', 'module'). Only needed when several kinds share the same name; when nothing is specified and the name is ambiguous, the candidate kinds are returned instead of edges. The name may instead be prefixed as '<kind>:<name>'.",
            required = false)
        @Nullable String kind) {
        log.info("GetGraphNeighborsTool: searching neighbors for '{}' in collection '{}'",
            entity_name, collection);
        // Validate entity_name (mandatory)
        if (entity_name == null || entity_name.isBlank()) {
            return "Error: 'entity_name' parameter is required for get_graph_neighbors.";
        }

        // Validate collection (mandatory)
        if (collection == null || collection.isBlank()) {
            return "Error: 'collection' parameter is required for get_graph_neighbors.";
        }
        String col = collection.trim();

        List<Collection> allCols = collectionService.listCollections();
        Collection matchedCol =
            allCols.stream().filter(c -> c.name().equalsIgnoreCase(col)).findFirst().orElse(null);
        if (matchedCol == null) {
            return "Error: Collection '" + col + "' does not exist.";
        }
        // The stored spelling, not the caller's: the match above is case-insensitive but every
        // KgGraphReadRepository query is `c.name = :collection` / `c.name IN (:collections)`,
        // i.e. case-SENSITIVE. Forwarding `col` rejects valid relation types and returns an empty
        // neighbour set for a graph that exists.
        String colName = matchedCol.name();

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

        // Validate relation_type (optional)
        if (relation_type != null && !relation_type.isBlank()) {
            if (!kgRepository.relationshipPredicateExists(relation_type, List.of(colName))) {
                return "Error: Relation type '" + relation_type + "' does not exist in collection '"
                    + col + "'.";
            }
        }

        // Validate direction (optional)
        if (direction != null && !direction.isBlank()) {
            String dirClean = direction.trim().toLowerCase();
            if (!Set.of("incoming", "outgoing", "both").contains(dirClean)) {
                return "Error: Invalid direction '" + direction
                    + "'. Allowed values are 'incoming', 'outgoing', or 'both'.";
            }
        }

        String dir =
            (direction != null && !direction.isBlank()) ? direction.toLowerCase().trim() : "both";
        String relType =
            (relation_type != null && !relation_type.isBlank()) ? relation_type.trim() : null;

        // A "<kind>:<name>" prefix is a kind hint (playbooks/models pass e.g. "table:ADSAccount");
        // an explicit kind param wins over the prefix. Both narrow the node to a single identity.
        EntityRef ref = parseEntityRef(entity_name, kind);
        String bareName = ref.name();
        String kindHint = ref.kind();

        try {
            // Resolve the candidate kinds FIRST: when the caller pinned no kind and the name lives
            // under several kinds, those are different objects and merging their edges into one
            // capped table buries the ones actually asked for. Hard-stop with the candidates
            // instead — and skip the expensive merged edge fetch entirely.
            // Only the kind can be ambiguous here. Identity is also tag-scoped, but a tag-scoped
            // collection now rejects an untagged call outright (McpToolUtils.requireTag), so the
            // candidates are already narrowed to one product version by the time we get here.
            if (kindHint == null) {
                List<KgEntityKindEdgeCount> candidates = kgRepository
                    .findEntityKindsWithEdgeCounts(bareName, parsedTag, colName, relType, dir);
                if (distinctKinds(candidates) > 1) {
                    return renderDisambiguation(bareName, col, parsedTag, candidates);
                }
            }

            // Predicate and direction are filtered in SQL, and the result is capped so a hub
            // node cannot return an unbounded edge set.
            KgNeighborEdges result = kgRepository.getEntityRelationships(bareName, kindHint,
                parsedTag, colName, relType, dir, MAX_EDGES);

            // Direction and counterpart come resolved from the repository (by endpoint id).
            // Re-deriving them from the name text here reported every edge between two same-named
            // nodes (module 'ADS' -> connector 'ADS') as 'self' pointing at the node itself.
            List<Map<String, String>> rows = new ArrayList<>();
            for (KgNeighborEdges.Edge e : result.edges()) {
                Map<String, String> row = new HashMap<>();
                row.put("counterpart", e.counterpart());
                row.put("kind", kindLabel(e.counterpartKind()));
                row.put("relation", e.predicate());
                row.put("direction", e.direction().label());
                row.put("description", e.description() != null ? e.description() : "");
                rows.add(row);
            }

            if (rows.isEmpty()) {
                String suffix = (parsedTag != null) ? " at tag '" + parsedTag + "'" : "";
                String relSuffix = (relType != null) ? " of type '" + relType + "'" : "";
                return String.format("(no relationships%s found for '%s' in collection '%s'%s)",
                    relSuffix, bareName, col, suffix);
            }

            int total = rows.size();
            String tagSuffix = (parsedTag != null) ? " (tag=" + parsedTag + ")" : "";
            String countLabel =
                (total >= MAX_EDGES)
                    ? "showing first " + MAX_EDGES
                        + ", more exist — narrow with relation_type/direction"
                    : "total: " + total;
            List<String> out = new ArrayList<>();
            out.add(String.format("### Connections for '%s' in %s%s (%s)", bareName, col, tagSuffix,
                countLabel));
            out.add(McpToolUtils.formatTableRows(rows,
                List.of("counterpart", "kind", "relation", "direction", "description")));
            return String.join("\n", out);

        } catch (Exception e) {
            return McpToolUtils.toolError("get_graph_neighbors", e);
        }
    }

    /** How many distinct kinds the candidates span — tag twins share one kind. */
    private static long distinctKinds(List<KgEntityKindEdgeCount> candidates) {
        return candidates.stream().map(c -> kindLabel(c.kind())).distinct().count();
    }

    /**
     * Renders the candidate kinds of an ambiguous starting name instead of edges. Candidates arrive
     * ordered by edge count (descending) then kind, so the first row is the most connected node and
     * makes the best re-query example.
     */
    private static String renderDisambiguation(String bareName, String col, @Nullable String tag,
        List<KgEntityKindEdgeCount> candidates) {
        String tagSuffix = (tag != null) ? " (tag=" + tag + ")" : "";
        // Most connected candidate first, but never suggest re-querying with the placeholder kind
        // of a legacy row that has none — that would not narrow anything.
        String topKind = candidates.stream().map(KgEntityKindEdgeCount::kind)
            .filter(k -> k != null && !k.isBlank()).findFirst()
            .orElse(kindLabel(candidates.get(0).kind()));
        // Identity is tag-scoped, so a kind can appear once per product version. Pinning only the
        // kind would then bounce the caller straight into the tag picker, so when the candidates
        // carry tags we suggest both at once and spend one round-trip instead of two.
        String topTag =
            candidates.stream().filter(c -> topKind.equalsIgnoreCase(kindLabel(c.kind())))
                .map(KgEntityKindEdgeCount::tags).filter(t -> t != null && !t.isBlank()).findFirst()
                .orElse("");
        String pin = topTag.isBlank() ? String.format("kind=\"%s\"", topKind)
            : String.format("kind=\"%s\", tag=\"%s\"", topKind, topTag);
        List<Map<String, String>> rows = new ArrayList<>();
        for (KgEntityKindEdgeCount c : candidates) {
            Map<String, String> row = new HashMap<>();
            row.put("kind", kindLabel(c.kind()));
            row.put("tag", (c.tags() != null && !c.tags().isBlank()) ? c.tags() : "(untagged)");
            row.put("edges", Long.toString(c.edgeCount()));
            row.put("document_id", c.documentId() != null ? c.documentId() : "");
            rows.add(row);
        }
        return String.join("\n", List.of(
            String.format("### '%s' is ambiguous in %s%s — %d kinds across %d entities", bareName,
                col, tagSuffix, distinctKinds(candidates), candidates.size()),
            String.format(
                "> These are different objects. Re-run get_graph_neighbors with the one you mean, e.g. %s (or entity_name=\"%s:%s\"). Rows sharing a kind are the same object in different product versions — tell them apart by the tag column, and take the document_id from the row you pick.",
                pin, topKind, bareName),
            "",
            McpToolUtils.formatTableRows(rows, List.of("kind", "tag", "edges", "document_id"))));
    }

    private static String kindLabel(@Nullable String kind) {
        return (kind != null && !kind.isBlank()) ? kind : "?";
    }

    /** A starting node reference split into an optional kind hint and its bare name. */
    record EntityRef(@Nullable String kind, String name) {}

    /**
     * Splits a "<kind>:<name>" prefix into a kind hint plus the bare name. An explicit
     * {@code explicitKind} takes precedence over the prefix. The prefix is only honored when it is
     * a single leading token (no spaces) followed by a non-empty name, so entity names that merely
     * contain a colon are left intact.
     */
    static EntityRef parseEntityRef(String raw, @Nullable String explicitKind) {
        String name = raw;
        String kind =
            (explicitKind != null && !explicitKind.isBlank()) ? explicitKind.trim() : null;
        int idx = raw.indexOf(':');
        if (idx > 0 && idx < raw.length() - 1) {
            String prefix = raw.substring(0, idx).trim();
            String rest = raw.substring(idx + 1).trim();
            if (!prefix.isEmpty() && !rest.isEmpty() && !prefix.contains(" ")) {
                name = rest;
                if (kind == null) {
                    kind = prefix;
                }
            }
        }
        return new EntityRef(kind, name);
    }
}
