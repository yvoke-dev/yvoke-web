package de.palsoftware.yvoke.rag.retrieval;

import de.palsoftware.yvoke.document.core.repository.ChunkRepository;
import de.palsoftware.yvoke.document.core.model.ChunkRow;
import de.palsoftware.yvoke.shared.db.VectorUtils;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class HybridSearch {

    private static final Logger log = LoggerFactory.getLogger(HybridSearch.class);

    private final ExecutorService searchExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private final ChunkRepository chunkRepository;
    private final EmbeddingService embeddingService;
    private final RerankClient rerankClient;
    private final RrfFuser rrfFuser;
    private final RetrievalTelemetryService telemetryService;
    private final JdbcClient jdbcClient;

    private final int defaultLimit;
    private final int maxLimit;
    private final double semanticLimitMultiplier;
    private final double fulltextLimitMultiplier;

    public HybridSearch(ChunkRepository chunkRepository, EmbeddingService embeddingService,
        RerankClient rerankClient, RrfFuser rrfFuser, RetrievalTelemetryService telemetryService,
        JdbcClient jdbcClient, @Value("${app.retrieval.default-limit}") int defaultLimit,
        @Value("${app.retrieval.max-limit}") int maxLimit,
        @Value("${app.retrieval.semantic-limit-multiplier}") double semanticLimitMultiplier,
        @Value("${app.retrieval.fulltext-limit-multiplier}") double fulltextLimitMultiplier) {
        this.chunkRepository = chunkRepository;
        this.embeddingService = embeddingService;
        this.rerankClient = rerankClient;
        this.rrfFuser = rrfFuser;
        this.telemetryService = telemetryService;
        this.jdbcClient = jdbcClient;
        this.defaultLimit = defaultLimit;
        this.maxLimit = maxLimit;
        this.semanticLimitMultiplier = semanticLimitMultiplier;
        this.fulltextLimitMultiplier = fulltextLimitMultiplier;
    }

    public List<HybridSearchResult> search(String query, SearchOptions opts) {
        return searchWithId(query, opts).results();
    }

    private static record ResolvedTags(List<String> collections, List<UUID> documentIds) {}

    private ResolvedTags resolveTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return new ResolvedTags(Collections.emptyList(), Collections.emptyList());
        }

        List<String> cols =
            jdbcClient.sql("SELECT name FROM collections WHERE tags && :tags::text[]")
                .param("tags", tags.toArray(new String[0])).query(String.class).list();

        return new ResolvedTags(cols, Collections.emptyList());
    }

    public SearchWithId searchWithId(String query, SearchOptions opts) {
        if (query == null || query.isBlank()) {
            return new SearchWithId(Collections.emptyList(), UUID.randomUUID());
        }

        boolean runSemantic = opts.semantic();
        boolean runFulltext = opts.fulltext();

        if (!runSemantic && !runFulltext) {
            throw new IllegalArgumentException(
                "At least one search lane (semantic or fulltext) must be enabled");
        }

        // The one place all three lane paths funnel through, so it is where the ceiling belongs.
        // Both pools are derived from this figure and the reranker is handed every fused
        // candidate in a single request, so an unclamped limit sizes a request large enough to
        // be rejected — which degrades silently to unreranked RRF order rather than failing.
        int requestedLimit =
            (opts.limit() != null && opts.limit() > 0) ? opts.limit() : this.defaultLimit;
        int limit = Math.min(requestedLimit, this.maxLimit);
        SearchOptions resolvedOpts = new SearchOptions(opts.collections(), limit, opts.semantic(),
            opts.fulltext(), opts.offset(), opts.rerank(), opts.tags());

        ResolvedTags resolvedTags = resolveTags(opts.tags());
        List<String> finalCollections;
        if (opts.collections() != null && !opts.collections().isEmpty()) {
            finalCollections = opts.collections();
        } else {
            finalCollections = resolvedTags.collections();
        }
        List<UUID> documentIds = resolvedTags.documentIds();

        UUID searchId = UUID.randomUUID();
        List<HybridSearchResult> results;

        if (runSemantic && runFulltext) {
            results =
                executeHybridSearch(searchId, query, resolvedOpts, finalCollections, documentIds);
        } else if (runSemantic) {
            results = executeSemanticSearchOnly(searchId, query, resolvedOpts, finalCollections,
                documentIds);
        } else {
            results = executeFulltextSearchOnly(searchId, query, resolvedOpts, finalCollections,
                documentIds);
        }
        return new SearchWithId(results, searchId);
    }

    private List<HybridSearchResult> executeSemanticSearchOnly(UUID searchId, String query,
        SearchOptions opts, List<String> collections, List<UUID> documentIds) {
        float[] queryEmbedding = embeddingService.embed(query);
        String vectorStr = VectorUtils.toVectorString(queryEmbedding);

        List<ChunkRow> rows = chunkRepository.findSemanticCandidates(vectorStr, opts.limit(),
            opts.offset(), opts.tags(), collections, documentIds);

        List<HybridSearchResult> results = new ArrayList<>();
        int semPool = rows.size() + opts.offset();
        for (int i = 0; i < rows.size(); i++) {
            ChunkRow row = rows.get(i);
            TelemetryInfo telemetry =
                new TelemetryInfo(true, false, semPool, 0, opts.offset() + i + 1);
            results.add(
                new HybridSearchResult(row.id(), row.documentId(), row.text(), row.headingPath(),
                    row.heading(), row.depth(), row.sortOrder(), row.tag(), row.documentTitle(),
                    row.kind(), row.collection(), row.metadata(), row.score(), telemetry));
        }

        List<UUID> semIds = rows.stream().map(ChunkRow::id).toList();
        telemetryService.logAndPersistTelemetry(searchId, query, opts, semPool, 0, results, false,
            semIds, null, null);

        return results;
    }

    private List<HybridSearchResult> executeFulltextSearchOnly(UUID searchId, String query,
        SearchOptions opts, List<String> collections, List<UUID> documentIds) {
        String sanitizedQuery = sanitizeQuery(query);
        if (sanitizedQuery.isEmpty()) {
            return Collections.emptyList();
        }

        List<ChunkRow> rows = chunkRepository.findFulltextCandidates(sanitizedQuery, opts.limit(),
            opts.offset(), opts.tags(), collections, documentIds);

        List<HybridSearchResult> results = new ArrayList<>();
        int ftPool = rows.size() + opts.offset();
        for (int i = 0; i < rows.size(); i++) {
            ChunkRow row = rows.get(i);
            TelemetryInfo telemetry =
                new TelemetryInfo(false, true, 0, ftPool, opts.offset() + i + 1);
            results.add(
                new HybridSearchResult(row.id(), row.documentId(), row.text(), row.headingPath(),
                    row.heading(), row.depth(), row.sortOrder(), row.tag(), row.documentTitle(),
                    row.kind(), row.collection(), row.metadata(), row.score(), telemetry));
        }

        List<UUID> ftIds = rows.stream().map(ChunkRow::id).toList();
        telemetryService.logAndPersistTelemetry(searchId, query, opts, 0, ftPool, results, false,
            ftIds, null, null);

        return results;
    }

    private List<HybridSearchResult> executeHybridSearch(UUID searchId, String query,
        SearchOptions opts, List<String> collections, List<UUID> documentIds) {
        // Semantic pool gets multiplier * (limit + offset)
        int semanticLimit =
            (int) Math.ceil(semanticLimitMultiplier * (opts.limit() + opts.offset()));

        // The BM25 full-text lane is independent of the embedding, so kick it off concurrently with
        // the (network embed → DB semantic) lane and join before fusion — saving the shorter lane's
        // latency on every hybrid query.
        int fullTextLimit =
            (int) Math.ceil(fulltextLimitMultiplier * (opts.limit() + opts.offset()));
        String sanitizedQuery = sanitizeQuery(query);
        CompletableFuture<List<ChunkRow>> ftFuture = CompletableFuture
            .supplyAsync(() -> sanitizedQuery.isEmpty() ? Collections.<ChunkRow>emptyList()
                : chunkRepository.findFulltextCandidates(sanitizedQuery, fullTextLimit, 0,
                    opts.tags(), collections, documentIds),
                searchExecutor);

        float[] queryEmbedding = embeddingService.embed(query);
        String vectorStr = VectorUtils.toVectorString(queryEmbedding);
        List<ChunkRow> semanticRows = chunkRepository.findSemanticCandidates(vectorStr,
            semanticLimit, 0, opts.tags(), collections, documentIds);

        List<ChunkRow> ftRows = ftFuture.join();

        int semanticPoolSize = semanticRows.size();
        int ftPoolSize = ftRows.size();

        // Perform RRF fusion
        List<RrfFuser.IntermediateRrfResult> sorted = rrfFuser.fuse(semanticRows, ftRows);

        // Capture the fused order (before reranking mutates the list)
        List<UUID> fusedChunkIds = sorted.stream().map(res -> res.getRow().id()).toList();

        // Apply Voyage AI Reranking
        if (opts.rerank() != null && opts.rerank()) {
            applyReranking(query, sorted);
        }

        // Capture the order after reranking
        List<UUID> rerankedChunkIds = sorted.stream().map(res -> res.getRow().id()).toList();

        // Slice to limit/offset and map to final results
        List<HybridSearchResult> finalResults = new ArrayList<>();
        int limitVal = opts.limit();
        int offsetVal = opts.offset();

        for (int rankIndex = 0; rankIndex < sorted.size(); rankIndex++) {
            RrfFuser.IntermediateRrfResult res = sorted.get(rankIndex);
            if (rankIndex >= offsetVal && rankIndex < offsetVal + limitVal) {
                ChunkRow row = res.getRow();
                TelemetryInfo telemetry = new TelemetryInfo(res.isInSem(), res.isInFt(),
                    semanticPoolSize, ftPoolSize, res.getRrfRank());
                finalResults.add(new HybridSearchResult(row.id(), row.documentId(), row.text(),
                    row.headingPath(), row.heading(), row.depth(), row.sortOrder(), row.tag(),
                    row.documentTitle(), row.kind(), row.collection(), row.metadata(),
                    res.getFinalScore(), telemetry));
            }
        }

        List<UUID> semIds = semanticRows.stream().map(ChunkRow::id).toList();
        List<UUID> ftIds = ftRows.stream().map(ChunkRow::id).toList();
        // Initial search results: semantic candidates (in order) followed by bm25 candidates (in
        // order), no
        // deduplication
        List<UUID> initialChunkIds = new ArrayList<>(semIds.size() + ftIds.size());
        initialChunkIds.addAll(semIds);
        initialChunkIds.addAll(ftIds);
        telemetryService.logAndPersistTelemetry(searchId, query, opts, semanticPoolSize, ftPoolSize,
            finalResults, true, initialChunkIds, fusedChunkIds, rerankedChunkIds);

        return finalResults;
    }

    private void applyReranking(String query, List<RrfFuser.IntermediateRrfResult> sorted) {
        List<String> documents = sorted.stream().map(res -> res.getRow().text()).toList();
        List<RerankClient.RerankResult> rerankResults;
        boolean rerankSuccess = false;
        try {
            rerankResults = rerankClient.rerank(query, documents);
            rerankSuccess = true;
            log.info("Voyage Reranker completed successfully for {} documents", documents.size());
        } catch (Exception e) {
            log.warn("Voyage Reranker failed; falling back to raw RRF order", e);
            rerankResults = Collections.emptyList();
        }

        if (rerankSuccess) {
            for (RerankClient.RerankResult rr : rerankResults) {
                if (rr.index() >= 0 && rr.index() < sorted.size()) {
                    sorted.get(rr.index()).setRerankScore(rr.relevanceScore());
                }
            }
            // Sort descending by final score (rerankScore when available, rrfScore otherwise)
            sorted.sort((a, b) -> Double.compare(b.getFinalScore(), a.getFinalScore()));
        }
    }

    private String sanitizeQuery(String query) {
        if (query == null) {
            return "";
        }
        // Replace Tantivy special characters with spaces
        return query.replaceAll("[+\\-=&|!(){}\\[\\]^\"~*?:\\\\/]", " ").trim().replaceAll("\\s+",
            " ");
    }
}
