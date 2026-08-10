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

    /**
     * One chunk's coordinates across the retrieval stages. {@code semRank}/{@code ftRank} are the
     * chunk's 1-indexed position in that lane's candidate list, or {@code null} when the lane did
     * not retrieve it at all — which is the distinction the fused rank alone cannot show.
     */
    public record LaneTraceRow(int position, int rrfRank, @Nullable Integer semRank,
        @Nullable Integer ftRank) {

        /** {@code both} / {@code sem} / {@code ft} — which lane(s) supplied this chunk. */
        public String lane() {
            if (semRank != null && ftRank != null) {
                return "both";
            }
            return semRank != null ? "sem" : "ft";
        }
    }

    /**
     * The per-stage trace behind one search, reconstructed from the stage snapshots in
     * {@code retrieval_logs} — no extra instrumentation. {@code fusionOrder} is capped for display
     * ({@code fusionShown} of {@code fusedTotal}); {@code returnedOrder} is every row the caller
     * actually got, in the order shown.
     */
    public record LaneTrace(List<LaneTraceRow> fusionOrder, List<LaneTraceRow> returnedOrder,
        int fusedTotal, int fusionShown) {

        public static LaneTrace empty() {
            return new LaneTrace(List.of(), List.of(), 0, 0);
        }

        public boolean isEmpty() {
            return returnedOrder.isEmpty() && fusionOrder.isEmpty();
        }

        /** True when the displayed fusion list is a prefix, so the template can say so. */
        public boolean isFusionTruncated() {
            return fusionShown < fusedTotal;
        }
    }

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
