package de.palsoftware.yvoke.rag.retrieval;

import de.palsoftware.yvoke.rag.retrieval.RagAdminViews.RetrievalLogView;
import de.palsoftware.yvoke.rag.retrieval.RagAdminViews.SearchResultView;
import de.palsoftware.yvoke.rag.retrieval.RagAdminViews.SearchTelemetryView;
import java.util.List;
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
