package de.palsoftware.yvoke.document.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.document.core.HierarchyUtils;
import de.palsoftware.yvoke.document.core.model.ChunkRow;
import de.palsoftware.yvoke.document.core.model.DocumentAdminViews.ChunkDetail;
import de.palsoftware.yvoke.document.core.model.DocumentAdminViews.ChunkDetailPage;
import de.palsoftware.yvoke.document.core.model.DocumentAdminViews.ChunkSummary;
import de.palsoftware.yvoke.document.core.model.DocumentAdminViews.DocumentDetail;
import de.palsoftware.yvoke.document.core.model.DocumentAdminViews.DocumentDetailPage;
import de.palsoftware.yvoke.document.core.model.DocumentAdminViews.DocumentSummary;
import de.palsoftware.yvoke.document.core.model.DocumentDetails;
import de.palsoftware.yvoke.document.core.model.DocumentKind;
import de.palsoftware.yvoke.document.core.model.DocumentRow;
import de.palsoftware.yvoke.document.core.repository.ChunkRepository;
import de.palsoftware.yvoke.document.core.repository.ChunkSurfacingMessageLookup;
import de.palsoftware.yvoke.document.core.repository.DocumentRepository;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-side view service for the document admin pages (ARC-01 / Wave 3.3). It fetches persistence
 * rows and maps them to the per-view DTOs in {@code DocumentAdminViews}, so the controller and
 * templates never touch raw {@link DocumentRow}/{@link ChunkRow}/{@link DocumentDetails} records.
 * The document-detail section-summary ordering (which needs the raw chunk hierarchy) lives here
 * too.
 */
@Service
public class DocumentAdminViewService {

    private static final Logger log = LoggerFactory.getLogger(DocumentAdminViewService.class);

    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final ChunkSurfacingMessageLookup chunkSurfacingMessageLookup;
    private final ObjectMapper objectMapper;

    public DocumentAdminViewService(DocumentRepository documentRepository,
        ChunkRepository chunkRepository, ChunkSurfacingMessageLookup chunkSurfacingMessageLookup,
        ObjectMapper objectMapper) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.chunkSurfacingMessageLookup = chunkSurfacingMessageLookup;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<DocumentSummary> listDocuments(@Nullable String collection, int limit, int offset,
        @Nullable String kind, @Nullable String tag, @Nullable String searchId,
        @Nullable String searchTitle) {
        return documentRepository
            .listDocuments(collection, limit, offset, kind, tag, searchId, searchTitle).stream()
            .map(DocumentAdminViewService::toSummary).toList();
    }

    @Transactional(readOnly = true)
    public long countDocuments(@Nullable String collection, @Nullable String kind,
        @Nullable String tag, @Nullable String searchId, @Nullable String searchTitle) {
        return documentRepository.countDocuments(collection, kind, tag, searchId, searchTitle);
    }

    public List<String> distinctKinds() {
        return documentRepository.findDistinctKinds();
    }

    @Transactional(readOnly = true)
    public Optional<DocumentDetailPage> documentDetailPage(UUID id) {
        Optional<DocumentRow> found = documentRepository.findById(id);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        DocumentRow document = found.get();
        List<ChunkRow> chunks = chunkRepository.findChunksByDocumentId(document.id(), null);
        List<Map<String, Object>> sectionSummaries = sectionSummaries(document, chunks);
        List<ChunkSummary> chunkViews =
            chunks.stream().map(DocumentAdminViewService::toChunkSummary).toList();
        return Optional.of(new DocumentDetailPage(toDetail(document), chunks.size(),
            sectionSummaries, chunkViews));
    }

    @Transactional(readOnly = true)
    public Optional<ChunkDetailPage> chunkDetailPage(String idPrefix) {
        return chunkRepository.findByIdPrefix(idPrefix).map(chunk -> {
            boolean hasEmbedding = chunkRepository.chunkHasEmbedding(chunk.id());
            List<Map<String, Object>> surfaced =
                chunkSurfacingMessageLookup.findMessagesSurfacingChunk(chunk.id());
            return new ChunkDetailPage(toChunkDetail(chunk), hasEmbedding, surfaced);
        });
    }

    /**
     * Orders the section summaries by the physical position of their heading path in the document's
     * chunk sequence (ported verbatim from the controller). Returns an empty list for non-sectioned
     * documents or on any failure.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> sectionSummaries(DocumentRow document,
        List<ChunkRow> chunks) {
        boolean sectioned = DocumentKind.HIERARCHICAL.getValue().equals(document.kind())
            || DocumentKind.CONFLUENCE.getValue().equals(document.kind());
        if (!sectioned) {
            return List.of();
        }
        try {
            List<Map<String, Object>> fetched =
                documentRepository.findSectionSummaries(document.id());

            Map<List<String>, Integer> pathSortOrders = new HashMap<>();
            for (ChunkRow chunk : chunks) {
                List<String> full = HierarchyUtils.getChunkFullPath(chunk);
                if (full.isEmpty()) {
                    continue;
                }
                int so = chunk.sortOrder() != null ? chunk.sortOrder() : Integer.MAX_VALUE;
                List<String> fullNormalized =
                    full.stream().map(HierarchyUtils::normalizeSegment).toList();
                for (int i = 1; i <= fullNormalized.size(); i++) {
                    List<String> subPath = fullNormalized.subList(0, i);
                    pathSortOrders.putIfAbsent(subPath, so);
                    pathSortOrders.compute(subPath,
                        (k, current) -> current == null ? so : Math.min(current, so));
                }
            }

            List<Map<String, Object>> sorted = new ArrayList<>(fetched);
            sorted.sort((a, b) -> {
                List<String> pathA = (List<String>) a.get("path");
                List<String> pathB = (List<String>) b.get("path");
                List<String> normA = pathA.stream().map(HierarchyUtils::normalizeSegment).toList();
                List<String> normB = pathB.stream().map(HierarchyUtils::normalizeSegment).toList();
                int soA = pathSortOrders.getOrDefault(normA, Integer.MAX_VALUE);
                int soB = pathSortOrders.getOrDefault(normB, Integer.MAX_VALUE);
                return Integer.compare(soA, soB);
            });
            return sorted;
        } catch (Exception e) {
            log.error("Failed to load section summaries for document: {}", document.id(), e);
            return List.of();
        }
    }

    private static DocumentSummary toSummary(DocumentDetails d) {
        return new DocumentSummary(d.id(), d.title(), d.collection(), d.kind(), d.chunkCount(),
            d.ingestionStatus(), d.tags(), d.kgProcessed(), d.kgFailedChunks());
    }

    private DocumentDetail toDetail(DocumentRow d) {
        return new DocumentDetail(d.id(), d.title(), d.collection(), d.kind(), d.tags(),
            d.ingestionStatus(), d.createdAt(), prettyPrintJson(d.metadata()));
    }

    private static ChunkSummary toChunkSummary(ChunkRow c) {
        return new ChunkSummary(c.id(), c.sortOrder(), c.heading(), c.headingPath(), c.text(),
            c.score());
    }

    private ChunkDetail toChunkDetail(ChunkRow c) {
        return new ChunkDetail(c.id(), c.documentId(), c.collection(), c.tag(), c.kind(),
            c.heading(), c.sortOrder(), c.depth(), c.headingPath(), c.text(),
            prettyPrintJson(c.metadata()));
    }

    private String prettyPrintJson(@Nullable Object obj) {
        if (obj == null) {
            return "{}";
        }
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }
}
