package de.palsoftware.yvoke.rag.retrieval;

import de.palsoftware.yvoke.rag.retrieval.RagAdminViews.LaneTrace;
import de.palsoftware.yvoke.rag.retrieval.RagAdminViews.LaneTraceRow;
import de.palsoftware.yvoke.rag.retrieval.RagAdminViews.RetrievalLogView;
import de.palsoftware.yvoke.rag.retrieval.RagAdminViews.SearchResultView;
import de.palsoftware.yvoke.rag.retrieval.RagAdminViews.SearchTelemetryView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-side view service for the RAG admin pages (ARC-01 / Wave 3.3). Maps raw
 * {@link HybridSearchResult}/{@link RetrievalLogDetails} rows to the per-view DTOs in
 * {@code RagAdminViews}, so the controller and templates never touch retrieval-shaped records.
 */
@Service
public class RagAdminViewService {

    private final RetrievalLogRepository retrievalLogRepository;

    public RagAdminViewService(RetrievalLogRepository retrievalLogRepository) {
        this.retrievalLogRepository = retrievalLogRepository;
    }

    public List<SearchResultView> toSearchResults(List<HybridSearchResult> results) {
        return results.stream().map(RagAdminViewService::toSearchResultView).toList();
    }

    @Transactional(readOnly = true)
    public List<RetrievalLogView> listLogs(int limit, int offset) {
        return retrievalLogRepository.listLogs(limit, offset).stream()
            .map(RagAdminViewService::toLogView).toList();
    }

    public long countLogs() {
        return retrievalLogRepository.countLogs();
    }

    /**
     * Replays a search's stage snapshots into per-lane coordinates. Everything here comes from
     * columns {@code retrieval_logs} already stores — {@code initial_chunk_ids} is the semantic ids
     * in rank order followed by the BM25 ids in rank order, undeduped, so slicing it at the
     * recorded pool sizes recovers each lane's rank for every candidate.
     *
     * <p>
     * Returns {@link LaneTrace#empty()} rather than a partial trace when the row cannot support
     * one: single-lane searches persist a NULL {@code fused_chunk_ids}, and a length mismatch
     * against {@code sem + ft} means the slice assumption does not hold for this row. A trace that
     * silently mis-attributes ranks would be worse than none.
     */
    public static LaneTrace toLaneTrace(RetrievalTelemetryRow row, int maxFusionRows) {
        if (row == null || row.fusedChunkIds().isEmpty()) {
            return LaneTrace.empty();
        }
        List<UUID> initial = row.initialChunkIds();
        int sem = row.semPool();
        int ft = row.ftPool();
        if (initial.size() != sem + ft) {
            return LaneTrace.empty();
        }

        Map<UUID, Integer> semRank = new HashMap<>();
        for (int i = 0; i < sem; i++) {
            semRank.putIfAbsent(initial.get(i), i + 1);
        }
        Map<UUID, Integer> ftRank = new HashMap<>();
        for (int j = 0; j < ft; j++) {
            ftRank.putIfAbsent(initial.get(sem + j), j + 1);
        }

        List<UUID> fused = row.fusedChunkIds();
        Map<UUID, Integer> fusedRank = new HashMap<>();
        for (int i = 0; i < fused.size(); i++) {
            fusedRank.putIfAbsent(fused.get(i), i + 1);
        }

        int shown = Math.min(Math.max(maxFusionRows, 0), fused.size());
        List<LaneTraceRow> fusionOrder = new ArrayList<>(shown);
        for (int i = 0; i < shown; i++) {
            UUID id = fused.get(i);
            fusionOrder.add(new LaneTraceRow(i + 1, i + 1, semRank.get(id), ftRank.get(id)));
        }

        List<UUID> returned = row.retrievedChunkIds();
        List<LaneTraceRow> returnedOrder = new ArrayList<>(returned.size());
        for (int i = 0; i < returned.size(); i++) {
            UUID id = returned.get(i);
            returnedOrder.add(new LaneTraceRow(i + 1, fusedRank.getOrDefault(id, 0),
                semRank.get(id), ftRank.get(id)));
        }

        return new LaneTrace(fusionOrder, returnedOrder, fused.size(), shown);
    }

    private static SearchResultView toSearchResultView(HybridSearchResult h) {
        TelemetryInfo t = h.telemetry();
        SearchTelemetryView telemetry = t != null ? new SearchTelemetryView(t.inSem(), t.inFt())
            : new SearchTelemetryView(false, false);
        return new SearchResultView(h.id(), h.text(), h.headingPath(), h.heading(),
            h.documentTitle(), h.kind(), h.score(), telemetry);
    }

    private static RetrievalLogView toLogView(RetrievalLogDetails r) {
        return new RetrievalLogView(r.id(), r.messageId(), r.collection(), r.tag(), r.pools(),
            r.finalVal(), r.rerank(), r.createdAt(), r.messageContent(), r.feedbackRating(),
            r.feedbackComment(), r.retrievedChunkIds());
    }
}
