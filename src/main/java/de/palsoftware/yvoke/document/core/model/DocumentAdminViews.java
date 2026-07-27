package de.palsoftware.yvoke.document.core.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-view DTOs for the document admin pages (ARC-01 / Wave 3.3). The admin templates render these
 * slim projections instead of raw {@link DocumentRow}/{@link ChunkRow}/{@link DocumentDetails}
 * records, so persistence-shaped types (and fields like {@code metadata}) never leak into
 * Thymeleaf. Accessor names deliberately mirror the source records so the templates bind unchanged;
 * mapping is done in {@code DocumentAdminViewService}.
 */
public final class DocumentAdminViews {

    private DocumentAdminViews() {}

    /** Row in the documents listing table (admin/documents). */
    public record DocumentSummary(UUID id, String title, String collection, String kind,
        long chunkCount, String ingestionStatus, List<String> tags, boolean kgProcessed,
        long kgFailedChunks) {}

    /** Header/metadata panel of a single document (admin/document-detail). */
    public record DocumentDetail(UUID id, String title, String collection, String kind,
        List<String> tags, String ingestionStatus, Instant createdAt, String metadataJson) {}

    /** Row in the chunk-sequence table on a document detail page (admin/document-detail). */
    public record ChunkSummary(UUID id, Integer sortOrder, String heading, List<String> headingPath,
        String text, double score) {}

    /** A single chunk (admin/chunk-detail). */
    public record ChunkDetail(UUID id, UUID documentId, String collection, String tag, String kind,
        String heading, Integer sortOrder, Integer depth, List<String> headingPath, String text,
        String metadataJson) {}

    /** Everything the document-detail page renders, assembled server-side. */
    public record DocumentDetailPage(DocumentDetail document, int chunkCount,
        List<Map<String, Object>> sectionSummaries, List<ChunkSummary> chunks) {}

    /** Everything the chunk-detail page renders, assembled server-side. */
    public record ChunkDetailPage(ChunkDetail chunk, boolean hasEmbedding,
        List<Map<String, Object>> surfacedMessages) {}
}
