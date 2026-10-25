package de.palsoftware.yvoke.mcp.tools;

import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.service.CollectionService;
import de.palsoftware.yvoke.document.core.model.DocumentDetails;
import de.palsoftware.yvoke.document.core.repository.DocumentRepository;
import de.palsoftware.yvoke.mcp.McpToolUtils;
import de.palsoftware.yvoke.rag.core.model.AgenticChatContext;
import de.palsoftware.yvoke.shared.security.AreaAccessContext;
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
        @ToolParam(
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
        return listDocuments(collection, kind, tag, limit, offset, query, null);
    }

    public String listDocuments(@Nullable String collection, @Nullable String kind,
        @Nullable String tag, @Nullable Integer limit, @Nullable Integer offset,
        @Nullable String query, @Nullable AgenticChatContext context) {
        log.info("ListDocumentsTool: listing documents for collection '{}'", collection);

        String knd = (kind != null && !kind.isBlank()) ? kind.trim() : null;
        String parsedTag = (tag != null && !tag.isBlank()) ? tag.trim() : null;
        int lim = Math.max(1, Math.min((limit != null) ? limit : 100, 500));
        int off = Math.max(0, (offset != null) ? offset : 0);
        String fuzzyQuery = (query != null && !query.isBlank()) ? query.trim() : null;

        if (collection == null || collection.isBlank()) {
            if (context == null) {
                return "Error: 'collection' parameter is required.";
            }
            List<Collection> allowed = collectionService.listCollections().stream()
                .filter(c -> context.isAreaAllowed(c.area())).toList();
            if (allowed.isEmpty()) {
                return "(no documents in **all allowed collections**"
                    + (knd != null ? " of kind '" + knd + "'" : "") + ")";
            }
            String tagError = McpToolUtils.requireAreaTag(allowed, parsedTag);
            if (tagError != null) {
                return tagError;
            }
            try {
                List<DocumentDetails> allDocs = new ArrayList<>();
                for (Collection c : allowed) {
                    String effectiveKind = knd;
                    if (knd != null) {
                        List<String> validKinds =
                            documentRepository.findDistinctKindsInCollection(c.name());
                        if (validKinds != null && !validKinds.isEmpty()) {
                            effectiveKind = validKinds.stream().filter(knd::equalsIgnoreCase)
                                .findFirst().orElse(null);
                            if (effectiveKind == null) {
                                continue;
                            }
                        }
                    }
                    List<DocumentDetails> docs = documentRepository.listDocuments(c.name(),
                        Integer.MAX_VALUE, 0, effectiveKind, parsedTag, null, null, fuzzyQuery);
                    if (docs != null) {
                        allDocs.addAll(docs);
                    }
                }
                String colLabel =
                    String.join(", ", allowed.stream().map(Collection::name).toList());
                if (allDocs.isEmpty()) {
                    return "(no documents in **" + colLabel + "**"
                        + (knd != null ? " of kind '" + knd + "'" : "") + ")";
                }
                int fromIndex = Math.min(off, allDocs.size());
                int toIndex = Math.min(off + lim, allDocs.size());
                List<DocumentDetails> page = allDocs.subList(fromIndex, toIndex);
                return formatDocumentPage(colLabel, knd, parsedTag, off, page, allDocs.size());
            } catch (Exception e) {
                return McpToolUtils.toolError("list_documents", e);
            }
        }

        String col = collection.trim();

        List<Collection> allCols = collectionService.listCollections();
        Collection matchedCol =
            allCols.stream().filter(c -> c.name().equalsIgnoreCase(col)).findFirst().orElse(null);
        if (matchedCol == null) {
            return "Error: Collection '" + col + "' does not exist.";
        }
        if (!isAreaAllowed(matchedCol.area(), context)) {
            return "Error: Collection '" + col + "' does not exist.";
        }

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

        try {
            if (knd != null) {
                List<String> validKinds = documentRepository.findDistinctKindsInCollection(col);
                if (!validKinds.isEmpty()) {
                    // Adopt the stored casing: validation is case-insensitive but the filter is
                    // `AND d.kind = :kind` (case-sensitive), so forwarding the caller's spelling
                    // would pass validation and then match nothing.
                    String storedKind =
                        validKinds.stream().filter(knd::equalsIgnoreCase).findFirst().orElse(null);
                    if (storedKind == null) {
                        return "Error: kind '" + knd
                            + "' is not valid or does not exist in collection '" + col
                            + "'. Valid kinds in this collection are: " + validKinds + ".";
                    }
                    knd = storedKind;
                }
            }

            List<DocumentDetails> docs = documentRepository.listDocuments(col, lim, off, knd,
                parsedTag, null, null, fuzzyQuery);
            long total =
                documentRepository.countDocuments(col, knd, parsedTag, null, null, fuzzyQuery);

            if (docs.isEmpty()) {
                return "(no documents in **" + col + "**"
                    + (knd != null ? " of kind '" + knd + "'" : "") + ")";
            }

            return formatDocumentPage(col, knd, parsedTag, off, docs, total);
        } catch (Exception e) {
            return McpToolUtils.toolError("list_documents", e);
        }
    }

    private static String formatDocumentPage(String colLabel, @Nullable String knd,
        @Nullable String parsedTag, int off, List<DocumentDetails> docs, long total) {
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
        return "**" + colLabel + "**" + suffix + " — " + pageInfo + "\n\n" + McpToolUtils
            .formatTableRows(rows, List.of("document_id", "kind", "title", "chunk count"));
    }

    private static boolean isAreaAllowed(@Nullable String area,
        @Nullable AgenticChatContext context) {
        if (context != null) {
            return context.isAreaAllowed(area);
        }
        if (area == null || area.isBlank()) {
            return true;
        }
        return AreaAccessContext.canAccessUserArea(area);
    }
}
