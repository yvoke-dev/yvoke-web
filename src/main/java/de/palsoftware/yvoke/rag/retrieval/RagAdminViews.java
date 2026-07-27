package de.palsoftware.yvoke.rag.retrieval;

import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Per-view DTOs for the RAG admin pages (ARC-01 / Wave 3.3). The search and logs templates render
 * these slim projections instead of raw {@link HybridSearchResult}/{@link RetrievalLogDetails}
 * records (persistence/retrieval-shaped types, the former nesting {@link TelemetryInfo}). Accessor
 * names mirror the source records (including the derived {@code collection()} and
 * {@code getTruncatedMessageContent}); mapping is in {@code RagAdminViewService}.
 */
public final class RagAdminViews {

    private RagAdminViews() {}

    /** The lane-membership flags the search page shows per result. */
    public record SearchTelemetryView(boolean inSem, boolean inFt) {}

    /** A retrieved chunk on the search console (admin/search). */
    public record SearchResultView(UUID id, String text, List<String> headingPath,
        @Nullable String heading, @Nullable String documentTitle, @Nullable String kind,
        double score, SearchTelemetryView telemetry) {}

    /** A retrieval-log row (admin/logs). */
    public record RetrievalLogView(UUID id, UUID messageId, String collection, String tag,
        String pools, String finalVal, String rerank, Instant createdAt,
        @Nullable String messageContent, @Nullable Integer feedbackRating,
        @Nullable String feedbackComment, List<UUID> retrievedChunkIds) {

        @Nullable
        public String getTruncatedMessageContent(int maxChars) {
            if (messageContent == null) {
                return null;
            }
            if (messageContent.length() <= maxChars) {
                return messageContent;
            }
            return messageContent.substring(0, maxChars) + "...";
        }
    }
}
