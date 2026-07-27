package de.palsoftware.yvoke.document.core.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import de.palsoftware.yvoke.document.core.HierarchyUtils;
import de.palsoftware.yvoke.document.core.model.ChunkPathRow;
import de.palsoftware.yvoke.document.core.model.ChunkRow;
import de.palsoftware.yvoke.document.core.model.DocumentKind;
import de.palsoftware.yvoke.document.core.model.DocumentRow;
import de.palsoftware.yvoke.document.core.model.SectionResponse;
import de.palsoftware.yvoke.document.core.repository.ChunkRepository;
import de.palsoftware.yvoke.document.core.repository.DocumentRepository;
import jakarta.annotation.Nullable;

@Service
public class SectionService {

    private final ChunkRepository chunkRepository;
    private final DocumentRepository documentRepository;

    public SectionService(ChunkRepository chunkRepository, DocumentRepository documentRepository) {
        this.chunkRepository = chunkRepository;
        this.documentRepository = documentRepository;
    }

    public SectionResponse getSection(String collection, String documentName,
        @Nullable String headingPathStr) {
        if (collection == null || collection.isBlank()) {
            throw new IllegalArgumentException("Collection name cannot be null or empty.");
        }
        if (documentName == null || documentName.isBlank()) {
            throw new IllegalArgumentException("Document name cannot be null or empty.");
        }

        DocumentRow doc = documentRepository.findByManual(documentName, collection)
            .orElseThrow(() -> new NoSuchElementException("(no manual matching '" + documentName
                + "'" + " in collection " + collection + ")"));

        List<String> targetPath =
            headingPathStr == null || headingPathStr.isBlank() ? Collections.emptyList()
                : HierarchyUtils.splitHeadingPath(headingPathStr);

        return getSectionForDocument(doc, targetPath,
            headingPathStr == null || headingPathStr.isBlank());
    }

    public SectionResponse getSectionByChunkId(String chunkIdPrefix) {
        if (chunkIdPrefix == null || chunkIdPrefix.isBlank()) {
            throw new IllegalArgumentException("Chunk ID prefix cannot be null or empty.");
        }

        ChunkRow chunk = chunkRepository.findByIdPrefix(chunkIdPrefix)
            .orElseThrow(() -> new NoSuchElementException(
                "(no chunk with id starting '" + chunkIdPrefix + "')"));

        DocumentRow doc = documentRepository.findById(chunk.documentId())
            .orElseThrow(() -> new NoSuchElementException(
                "(no document found for chunk '" + chunkIdPrefix + "')"));

        List<String> targetPath = HierarchyUtils.getChunkFullPath(chunk);

        return getSectionForDocument(doc, targetPath, false);
    }

    public SectionResponse getSectionByDocumentId(String documentIdStr,
        @Nullable String headingPathStr) {
        if (documentIdStr == null || documentIdStr.isBlank()) {
            throw new IllegalArgumentException("Document ID cannot be null or empty.");
        }

        UUID docId;
        try {
            docId = UUID.fromString(documentIdStr.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid UUID format: " + documentIdStr);
        }

        DocumentRow doc = documentRepository.findById(docId).orElseThrow(
            () -> new NoSuchElementException("(no document found for id '" + docId + "')"));

        List<String> targetPath =
            headingPathStr == null || headingPathStr.isBlank() ? Collections.emptyList()
                : HierarchyUtils.splitHeadingPath(headingPathStr);

        return getSectionForDocument(doc, targetPath,
            headingPathStr == null || headingPathStr.isBlank());
    }

    private SectionResponse getSectionForDocument(DocumentRow doc, List<String> targetPath,
        boolean fetchFullDocument) {
        // 2+3. Resolve the matching chunks. For a section request, match the target path
        // against a hierarchy-only projection first (path normalization must stay in Java),
        // then fetch full rows for the matches — chunk text of unmatched sections never
        // leaves the database.
        List<ChunkPathRow> pathRows = List.of();
        List<ChunkRow> matched;
        if (fetchFullDocument) {
            matched = new ArrayList<>(chunkRepository.findChunksByDocumentId(doc.id(), null));
        } else {
            pathRows = chunkRepository.findChunkPathsByDocumentId(doc.id());
            List<UUID> matchedIds = new ArrayList<>();
            for (ChunkPathRow chunk : pathRows) {
                List<String> chunkPath = HierarchyUtils.getChunkFullPath(chunk);
                if (HierarchyUtils.isSubpathOf(targetPath, chunkPath)) {
                    matchedIds.add(chunk.id());
                }
            }
            matched = new ArrayList<>(chunkRepository.findChunksByIds(matchedIds));
        }

        if (matched.isEmpty()) {
            // Reconstruct all paths to help user debug mismatch
            List<String> allPaths = new ArrayList<>();
            for (ChunkPathRow chunk : pathRows) {
                List<String> chunkPath = HierarchyUtils.getChunkFullPath(chunk);
                allPaths.add(String.join(" > ", chunkPath));
            }
            // Sort distinct paths
            Set<String> distinctPaths = new TreeSet<>(allPaths);
            List<String> pathSample = new ArrayList<>(distinctPaths);
            int totalPaths = pathSample.size();
            if (pathSample.size() > 40) {
                pathSample = pathSample.subList(0, 40);
            }

            StringBuilder errorMsg = new StringBuilder("(no section matched '")
                .append(String.join(" > ", targetPath)).append(
                    "' in this document. heading_path must be rooted at the top, exactly as shown in a search hit's breadcrumb. Sections in this document:\n");
            for (String p : pathSample) {
                errorMsg.append("  ").append(p).append("\n");
            }
            if (totalPaths > 40) {
                errorMsg.append("  …and ").append(totalPaths - 40).append(" more\n");
            }
            errorMsg.deleteCharAt(errorMsg.length() - 1); // remove trailing newline
            errorMsg.append(")");
            throw new NoSuchElementException(errorMsg.toString());
        }

        // 4. Sort matched chunks by sortOrder ascending
        matched.sort(
            Comparator.comparing(c -> c.sortOrder() != null ? c.sortOrder() : Integer.MAX_VALUE));

        // 5. Build response and concatenate text
        String documentTitle = matched.get(0).documentTitle();
        if (documentTitle == null)
            documentTitle = "?";
        String tag = matched.get(0).tag();
        if (tag == null)
            tag = "?";

        String scope = fetchFullDocument ? "full document" : "with sub-sections";
        String sectionName = fetchFullDocument ? "Full Document" : String.join(" > ", targetPath);
        String docKind = matched.get(0).kind();
        String unit = (DocumentKind.HIERARCHICAL.getValue().equals(docKind)
            || DocumentKind.CONFLUENCE.getValue().equals(docKind)) ? "section(s)" : "chunk(s)";
        String header = "# Section: " + sectionName + "\n" + "_(document: " + documentTitle
            + "  ·  tag: " + tag + "  ·  " + matched.size() + " " + unit + "  ·  " + scope + ")_\n";

        StringBuilder sb = new StringBuilder(header).append("\n");
        for (ChunkRow chunk : matched) {
            sb.append(HierarchyUtils.stripBreadcrumb(chunk.text()).stripTrailing()).append("\n");
        }

        return new SectionResponse(targetPath, documentTitle, tag, matched.size(), scope,
            sb.toString());
    }
}
