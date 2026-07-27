package de.palsoftware.yvoke.rag.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.repository.CollectionRepository;

@Service
public class RetrievalTelemetryService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalTelemetryService.class);

    // Telemetry is pure logging, so its DB writes stay off the search hot path. Single-threaded
    // to keep inserts ordered and bound the write concurrency; the linking of a log row to its
    // chat message (updateMessageId) only happens after the LLM stream completes, long after
    // this insert has landed.
    private final ExecutorService telemetryExecutor =
        Executors.newSingleThreadExecutor(Thread.ofVirtual().name("retrieval-telemetry").factory());

    private final RetrievalLogRepository retrievalLogRepository;
    private final CollectionRepository collectionRepository;
    private final ObjectMapper objectMapper;

    public RetrievalTelemetryService(RetrievalLogRepository retrievalLogRepository,
        CollectionRepository collectionRepository, ObjectMapper objectMapper) {
        this.retrievalLogRepository = retrievalLogRepository;
        this.collectionRepository = collectionRepository;
        this.objectMapper = objectMapper;
    }

    @PreDestroy
    void shutdown() {
        telemetryExecutor.close();
    }

    /**
     * Blocks until all telemetry submitted so far has been persisted. The executor is
     * single-threaded FIFO, so awaiting a no-op task implies every earlier write completed.
     * Intended for tests that assert on retrieval_logs right after a search.
     */
    void flush() {
        try {
            telemetryExecutor.submit(() -> {
            }).get();
        } catch (Exception e) {
            throw new IllegalStateException("Interrupted while flushing retrieval telemetry", e);
        }
    }

    public void logAndPersistTelemetry(UUID searchId, String query, SearchOptions opts,
        Integer semPool, Integer ftPool, List<HybridSearchResult> finalResults, boolean hybrid,
        List<UUID> initialChunkIds, List<UUID> fusedChunkIds, List<UUID> rerankedChunkIds) {

        int n = finalResults.size();
        int both = 0;
        int semOnly = 0;
        int ftOnly = 0;
        int promotions = 0;
        List<Integer> rrfOrder = new ArrayList<>();

        for (int i = 0; i < finalResults.size(); i++) {
            HybridSearchResult res = finalResults.get(i);
            TelemetryInfo tel = res.telemetry();
            if (tel.inSem() && tel.inFt()) {
                both++;
            } else if (tel.inSem()) {
                semOnly++;
            } else if (tel.inFt()) {
                ftOnly++;
            }
            if (hybrid) {
                rrfOrder.add(tel.rrfRank());
                if (tel.rrfRank() > n) {
                    promotions++;
                }
            }
        }

        boolean top1Changed = false;
        Double avgDisp = null;
        if (hybrid && !rrfOrder.isEmpty()) {
            top1Changed = rrfOrder.get(0) != 1;
            double sumDisp = 0.0;
            for (int i = 0; i < rrfOrder.size(); i++) {
                sumDisp += Math.abs(rrfOrder.get(i) - (i + 1));
            }
            avgDisp = Math.round((sumDisp / rrfOrder.size()) * 100.0) / 100.0;
        }

        Map<String, Object> pool = new LinkedHashMap<>();
        pool.put("sem", semPool);
        pool.put("ft", ftPool);

        Map<String, Object> finalMap = new LinkedHashMap<>();
        finalMap.put("n", n);
        finalMap.put("both", both);
        finalMap.put("sem_only", semOnly);
        finalMap.put("ft_only", ftOnly);

        Map<String, Object> rerankMap = new LinkedHashMap<>();
        rerankMap.put("promotions", promotions);
        rerankMap.put("top1_changed", top1Changed);
        rerankMap.put("avg_disp", avgDisp);
        rerankMap.put("rrf_order", rrfOrder);

        List<UUID> retrievedChunkIds = finalResults.stream().map(HybridSearchResult::id).toList();

        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("id", searchId.toString());
        rec.put("query", query);
        rec.put("coll", opts.collection());
        rec.put("tag", opts.tag() != null ? opts.tag() : "ALL");
        rec.put("limit", opts.limit());
        rec.put("sem_on", opts.semantic());
        rec.put("ft_on", opts.fulltext());
        rec.put("pool", pool);
        rec.put("final", finalMap);
        rec.put("rerank", rerankMap);
        rec.put("retrieved_chunk_ids", retrievedChunkIds.stream().map(UUID::toString).toList());
        if (initialChunkIds != null) {
            rec.put("initial_chunk_ids", initialChunkIds.stream().map(UUID::toString).toList());
        }
        if (fusedChunkIds != null) {
            rec.put("fused_chunk_ids", fusedChunkIds.stream().map(UUID::toString).toList());
        }
        if (rerankedChunkIds != null) {
            rec.put("reranked_chunk_ids", rerankedChunkIds.stream().map(UUID::toString).toList());
        }

        telemetryExecutor.execute(() -> {
            try {
                String jsonStr = objectMapper.writeValueAsString(rec);
                log.info("RETRIEVAL {}", jsonStr);

                String poolsJson = objectMapper.writeValueAsString(pool);
                String finalJson = objectMapper.writeValueAsString(finalMap);
                String rerankJson = objectMapper.writeValueAsString(rerankMap);

                String collectionName = opts.collection();
                if (collectionName.contains(",")) {
                    collectionName = collectionName.split(",")[0];
                }
                String finalColName = collectionName.trim();
                UUID collectionId = collectionRepository.findByName(finalColName)
                    .map(Collection::id).orElseGet(() -> collectionRepository
                        .create(finalColName, "Auto-created for telemetry").id());

                retrievalLogRepository.saveTelemetry(searchId, query, collectionId, opts.tag(),
                    poolsJson, finalJson, rerankJson, retrievedChunkIds, initialChunkIds,
                    fusedChunkIds, rerankedChunkIds);
            } catch (Exception e) {
                log.warn("Failed to write retrieval telemetry logs", e);
            }
        });
    }
}
