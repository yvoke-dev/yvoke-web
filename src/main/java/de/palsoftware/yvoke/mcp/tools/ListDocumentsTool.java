package de.palsoftware.yvoke.mcp.tools;

import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.service.CollectionService;
import de.palsoftware.yvoke.document.core.model.DocumentDetails;
import de.palsoftware.yvoke.document.core.repository.DocumentRepository;
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
public class ListDocumentsTool {

    private static final Logger log = LoggerFactory.getLogger(ListDocumentsTool.class);

    private final DocumentRepository documentRepository;
    private final CollectionService collectionService;

    public ListDocumentsTool(DocumentRepository documentRepository,
        CollectionService collectionService) {
        this.documentRepository = documentRepository;
        this.collectionService = collectionService;
    }

    @McpTool(name = "list_documents",
        description = "List (catalog) the documents in a collection. Use to discover which documents exist, optionally filtering by an approximate title via 'query' (e.g. a process-chain or config-namespace name). The output displays the current page and total count (e.g., 'showing 1-100 of 150'); use the 'offset' parameter to fetch subsequent pages if the total count exceeds the limit. Returns a markdown table with columns: document_id, kind, title, chunk count.")
    @Tool(name = "list_documents",
        description = "List (catalog) the documents in a collection. Use to discover which documents exist, optionally filtering by an approximate title via 'query' (e.g. a process-chain or config-namespace name). The output displays the current page and total count (e.g., 'showing 1-100 of 150'); use the 'offset' parameter to fetch subsequent pages if the total count exceeds the limit. Returns a markdown table with columns: document_id, kind, title, chunk count.")
    public String listDocuments(
        @McpToolParam(description = "The collection to list.", required = true)
        @ToolParam(description = "The collection to list.", required = true) String collection,
        @McpToolParam(
            description = "Filter by document kind (e.g. 'manual', 'table', 'procedure'). When nothing is specified all kinds are considered.",
            required = false)
        @ToolParam(
            description = "Filter by document kind (e.g. 'manual', 'table', 'procedure'). When nothing is specified all kinds are considered.",
            required = false)
        @Nullable String kind,
        @McpToolParam(
            description = "Scope to a single tag. REQUIRED when the collection has tags — results must come from exactly one tag; the error lists the valid values. Omit it only for a collection that has no tags.",
            required = false)
        @ToolParam(
            description = "Scope to a single tag. REQUIRED when the collection has tags — results must come from exactly one tag; the error lists the valid values. Omit it only for a collection that has no tags.",
            required = false)
        @Nullable String tag,
        @McpToolParam(
            description = "Max documents to return. When nothing is specified 100 is used.",
            required = false)
        @ToolParam(description = "Max documents to return. When nothing is specified 100 is used.",
            required = false)
        @Nullable Integer limit,
        @McpToolParam(
            description = "Pagination offset. When nothing is specified 0 is used. To fetch subsequent pages, set offset to (offset + limit) of the previous request.",
            required = false)
        @Nullable Integer offset,
        @McpToolParam(
            description = "Fuzzy document-title filter: finds documents by approximate title (e.g. a process-chain or config-namespace name). Matches case-insensitive substrings and close trigram matches, ranked best-first. When nothing is specified all titles are considered.",
            required = false)
        @ToolParam(
            description = "Fuzzy document-title filter: finds documents by approximate title (e.g. a process-chain or config-namespace name). Matches case-insensitive substrings and close trigram matches, ranked best-first. When nothing is specified all titles are considered.",
            required = false)
        @Nullable String query) {
        log.info("ListDocumentsTool: listing documents for collection '{}'", collection);

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

        String knd = (kind != null && !kind.isBlank()) ? kind.trim() : null;

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

        int lim = (limit != null) ? limit : 100;
        int off = (offset != null) ? offset : 0;

        try {
            if (knd != null) {
                List<String> validKinds = documentRepository.findDistinctKindsInCollection(col);
                if (!validKinds.isEmpty()) {
                    boolean isValid = validKinds.stream().anyMatch(knd::equalsIgnoreCase);
                    if (!isValid) {
                        return "Error: kind '" + knd
                            + "' is not valid or does not exist in collection '" + col
                            + "'. Valid kinds in this collection are: " + validKinds + ".";
                    }
                }
            }

            String fuzzyQuery = (query != null && !query.isBlank()) ? query.trim() : null;
            List<DocumentDetails> docs = documentRepository.listDocuments(col, lim, off, knd,
                parsedTag, null, null, fuzzyQuery);
            long total =
                documentRepository.countDocuments(col, knd, parsedTag, null, null, fuzzyQuery);

            if (docs.isEmpty()) {
                return "(no documents in **" + col + "**"
                    + (knd != null ? " of kind '" + knd + "'" : "") + ")";
            }

            List<Map<String, String>> rows = new ArrayList<>();
            for (DocumentDetails d : docs) {
                Map<String, String> row = new HashMap<>();
                row.put("document_id", (d.id() != null) ? d.id().toString() : "");
                row.put("kind", d.kind() != null ? d.kind() : "?");
                row.put("title", d.title() != null ? d.title() : "?");
                row.put("chunk count", String.valueOf(d.chunkCount()));
                rows.add(row);
            }

            String suffix = "";
            if (knd != null)
                suffix += " (kind=" + knd + ")";
            if (parsedTag != null)
                suffix += " (tag=" + parsedTag + ")";
            int shownTo = off + docs.size();
            String pageInfo = "showing " + (off + 1) + "–" + shownTo + " of " + total;
            return "**" + col + "**" + suffix + " — " + pageInfo + "\n\n" + McpToolUtils
                .formatTableRows(rows, List.of("document_id", "kind", "title", "chunk count"));
        } catch (Exception e) {
            return McpToolUtils.toolError("list_documents", e);
        }
    }
}
