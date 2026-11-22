package de.palsoftware.yvoke.mcp.tools;

import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.service.CollectionService;
import de.palsoftware.yvoke.document.core.model.DocumentDetails;
import de.palsoftware.yvoke.document.core.repository.DocumentRepository;
import de.palsoftware.yvoke.mcp.McpToolUtils;
import de.palsoftware.yvoke.mcp.McpToolUtils.CollectionResolution;
import de.palsoftware.yvoke.rag.core.model.AgenticChatContext;
import jakarta.annotation.Nullable;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ListDocumentsTool {

    private static final Logger log = LoggerFactory.getLogger(ListDocumentsTool.class);

    private final DocumentRepository documentRepository;
    private final CollectionService collectionService;

    public ListDocumentsTool(
            DocumentRepository documentRepository, CollectionService collectionService) {
        this.documentRepository = documentRepository;
        this.collectionService = collectionService;
    }

    /**
     * Lists documents in a collection or across the context's allowed areas. Published to MCP and
     * the in-app assistant via {@link ListDocumentsToolCallback} in {@code McpToolsConfig}.
     */
    public String listDocuments(
            @Nullable String collection,
            @Nullable String kind,
            @Nullable String tag,
            @Nullable Integer limit,
            @Nullable Integer offset,
            @Nullable String query,
            @Nullable AgenticChatContext context) {
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
            try {
                List<Collection> allowed =
                        collectionService.listCollections().stream()
                                .filter(c -> context.isAreaAllowed(c.area()))
                                .toList();
                if (allowed.isEmpty()) {
                    return "(no documents in **all allowed collections**"
                            + (knd != null ? " of kind '" + knd + "'" : "")
                            + ")";
                }
                String tagError = McpToolUtils.requireAreaTag(allowed, parsedTag);
                if (tagError != null) {
                    return tagError;
                }
                Set<String> allowedAreasLower =
                        context.isAllAreas() ? null : context.getAllowedAreasLower();
                String colLabel =
                        String.join(", ", allowed.stream().map(Collection::name).toList());
                String effectiveKind = knd;
                if (knd != null) {
                    List<String> validKinds =
                            documentRepository.findDistinctKinds(allowedAreasLower);
                    if (validKinds != null && !validKinds.isEmpty()) {
                        effectiveKind =
                                validKinds.stream()
                                        .filter(knd::equalsIgnoreCase)
                                        .findFirst()
                                        .orElse(null);
                        if (effectiveKind == null) {
                            return "(no documents in **" + colLabel + "** of kind '" + knd + "')";
                        }
                    }
                }
                List<DocumentDetails> page =
                        documentRepository.listDocuments(
                                null,
                                lim,
                                off,
                                effectiveKind,
                                parsedTag,
                                null,
                                null,
                                fuzzyQuery,
                                allowedAreasLower);
                long total =
                        documentRepository.countDocuments(
                                null,
                                effectiveKind,
                                parsedTag,
                                null,
                                null,
                                fuzzyQuery,
                                allowedAreasLower);
                if (page == null || page.isEmpty()) {
                    return "(no documents in **"
                            + colLabel
                            + "**"
                            + (knd != null ? " of kind '" + knd + "'" : "")
                            + ")";
                }
                return formatDocumentPage(colLabel, knd, parsedTag, off, page, total);
            } catch (Exception e) {
                return McpToolUtils.toolError("list_documents", e);
            }
        }

        String col = collection.trim();
        try {
            CollectionResolution resolution =
                    McpToolUtils.resolveReadableCollection(
                            collectionService, collection, tag, context);
            if (resolution instanceof CollectionResolution.Refused refused) {
                return refused.errorMessage();
            }
            CollectionResolution.Allowed allowed = (CollectionResolution.Allowed) resolution;
            String storedCol = allowed.collection().name();
            parsedTag = allowed.tag();

            if (knd != null) {
                List<String> validKinds =
                        documentRepository.findDistinctKindsInCollection(storedCol);
                if (!validKinds.isEmpty()) {
                    // Adopt the stored casing: validation is case-insensitive but the filter is
                    // `AND d.kind = :kind` (case-sensitive), so forwarding the caller's spelling
                    // would pass validation and then match nothing.
                    String storedKind =
                            validKinds.stream()
                                    .filter(knd::equalsIgnoreCase)
                                    .findFirst()
                                    .orElse(null);
                    if (storedKind == null) {
                        return "Error: kind '"
                                + knd
                                + "' is not valid or does not exist in collection '"
                                + col
                                + "'. Valid kinds in this collection are: "
                                + validKinds
                                + ".";
                    }
                    knd = storedKind;
                }
            }

            List<DocumentDetails> docs =
                    documentRepository.listDocuments(
                            storedCol, lim, off, knd, parsedTag, null, null, fuzzyQuery);
            long total =
                    documentRepository.countDocuments(
                            storedCol, knd, parsedTag, null, null, fuzzyQuery);

            if (docs.isEmpty()) {
                return "(no documents in **"
                        + col
                        + "**"
                        + (knd != null ? " of kind '" + knd + "'" : "")
                        + ")";
            }

            return formatDocumentPage(col, knd, parsedTag, off, docs, total);
        } catch (Exception e) {
            return McpToolUtils.toolError("list_documents", e);
        }
    }

    private static String formatDocumentPage(
            String colLabel,
            @Nullable String knd,
            @Nullable String parsedTag,
            int off,
            List<DocumentDetails> docs,
            long total) {
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
        if (knd != null) suffix += " (kind=" + knd + ")";
        if (parsedTag != null) suffix += " (tag=" + parsedTag + ")";
        int shownTo = off + docs.size();
        String pageInfo = "showing " + (off + 1) + "–" + shownTo + " of " + total;
        return "**"
                + colLabel
                + "**"
                + suffix
                + " — "
                + pageInfo
                + "\n\n"
                + McpToolUtils.formatTableRows(
                        rows, List.of("document_id", "kind", "title", "chunk count"));
    }
}
