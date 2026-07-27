package de.palsoftware.yvoke.rag.retrieval;


import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import de.palsoftware.yvoke.collection.core.repository.CollectionRepository;

public class RetrievalTelemetryServiceTest {

    private RetrievalTelemetryService telemetryService;
    private ObjectMapper objectMapper;
    private RetrievalLogRepository retrievalLogRepository;
    private CollectionRepository collectionRepository;
    private UUID collectionId;

    @BeforeEach
    public void setUp() {
        retrievalLogRepository = mock(RetrievalLogRepository.class);
        collectionRepository = mock(CollectionRepository.class);
        objectMapper = new ObjectMapper();
        telemetryService = new RetrievalTelemetryService(retrievalLogRepository,
            collectionRepository, objectMapper);

        collectionId = UUID.randomUUID();
        de.palsoftware.yvoke.collection.core.model.Collection mockCol =
            new de.palsoftware.yvoke.collection.core.model.Collection(collectionId, "OIM-TEST",
                "desc", List.of("1.0"), java.time.OffsetDateTime.now());
        when(collectionRepository.findByName("OIM-TEST")).thenReturn(Optional.of(mockCol));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testLogAndPersistTelemetryHybrid() throws Exception {
        UUID searchId = UUID.randomUUID();
        SearchOptions opts = new SearchOptions("OIM-TEST", 5, true, true, "1.0", 0);

        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        // finalResults:
        // Result 1: inSem=true, inFt=true, rrfRank=2 (meaning it was demoted/displaced)
        // Result 2: inSem=true, inFt=false, rrfRank=1
        List<HybridSearchResult> results = List.of(
            new HybridSearchResult(id1, UUID.randomUUID(), "chunk1", Collections.emptyList(),
                "head1", 1, 0, "1.0", "f1.md", "manual", "OIM-TEST", Collections.emptyMap(), 0.99,
                new TelemetryInfo(true, true, 3, 2, 2)),
            new HybridSearchResult(id2, UUID.randomUUID(), "chunk2", Collections.emptyList(),
                "head2", 1, 1, "1.0", "f1.md", "manual", "OIM-TEST", Collections.emptyMap(), 0.95,
                new TelemetryInfo(true, false, 3, 2, 1)));

        UUID semId = UUID.randomUUID();
        UUID ftId = UUID.randomUUID();
        List<UUID> initialIds = List.of(semId, ftId);
        List<UUID> fusedIds = List.of(id2, id1);
        List<UUID> rerankedIds = List.of(id1, id2);
        telemetryService.logAndPersistTelemetry(searchId, "query content text", opts, 3, 2, results,
            true, initialIds, fusedIds, rerankedIds);

        // Capture parameters passed to retrievalLogRepository.saveTelemetry
        ArgumentCaptor<UUID> idCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<UUID> collIdCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<String> verCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> poolsCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> finalCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> rerankCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<UUID>> chunkIdsCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<UUID>> initialIdsCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<UUID>> fusedIdsCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<UUID>> rerankedIdsCaptor = ArgumentCaptor.forClass(List.class);

        // The persist runs on the telemetry executor; await it instead of verifying inline.
        verify(retrievalLogRepository, Mockito.timeout(5000)).saveTelemetry(idCaptor.capture(),
            queryCaptor.capture(), collIdCaptor.capture(), verCaptor.capture(),
            poolsCaptor.capture(), finalCaptor.capture(), rerankCaptor.capture(),
            chunkIdsCaptor.capture(), initialIdsCaptor.capture(), fusedIdsCaptor.capture(),
            rerankedIdsCaptor.capture());

        assertThat(idCaptor.getValue()).isEqualTo(searchId);
        assertThat(queryCaptor.getValue()).isEqualTo("query content text");
        assertThat(collIdCaptor.getValue()).isEqualTo(collectionId);
        assertThat(verCaptor.getValue()).isEqualTo("1.0");
        assertThat(chunkIdsCaptor.getValue()).containsExactly(id1, id2);
        assertThat(initialIdsCaptor.getValue()).containsExactly(semId, ftId);
        assertThat(fusedIdsCaptor.getValue()).containsExactly(id2, id1);
        assertThat(rerankedIdsCaptor.getValue()).containsExactly(id1, id2);

        // Pools validation
        Map<String, Object> pools = objectMapper.readValue(poolsCaptor.getValue(), Map.class);
        assertThat(pools.get("sem")).isEqualTo(3);
        assertThat(pools.get("ft")).isEqualTo(2);

        // Final validation
        Map<String, Object> finalMap = objectMapper.readValue(finalCaptor.getValue(), Map.class);
        assertThat(finalMap.get("n")).isEqualTo(2);
        assertThat(finalMap.get("both")).isEqualTo(1);
        assertThat(finalMap.get("sem_only")).isEqualTo(1);
        assertThat(finalMap.get("ft_only")).isEqualTo(0);

        // Rerank validation
        Map<String, Object> rerankMap = objectMapper.readValue(rerankCaptor.getValue(), Map.class);
        // rrfOrder was [2, 1].
        // displacement:
        // Element 0: rrfRank = 2, actualIndex = 1 (displacement = |2-1| = 1)
        // Element 1: rrfRank = 1, actualIndex = 2 (displacement = |1-2| = 1)
        // Average displacement: (1 + 1)/2 = 1.0
        assertThat(rerankMap.get("avg_disp")).isEqualTo(1.0);
        assertThat(rerankMap.get("top1_changed")).isEqualTo(true); // first element had rrfRank = 2
                                                                   // != 1
        assertThat((List<Integer>) rerankMap.get("rrf_order")).containsExactly(2, 1);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testLogAndPersistTelemetrySingleLane() throws Exception {
        UUID searchId = UUID.randomUUID();
        // Semantic search only
        SearchOptions opts = new SearchOptions("OIM-TEST", 5, true, false, null, 0);

        UUID id1 = UUID.randomUUID();
        List<HybridSearchResult> results = List.of(new HybridSearchResult(id1, UUID.randomUUID(),
            "chunk1", Collections.emptyList(), "head1", 1, 0, "1.0", "f1.md", "manual", "OIM-TEST",
            Collections.emptyMap(), 0.99, new TelemetryInfo(true, false, 1, 0, 0)));

        telemetryService.logAndPersistTelemetry(searchId, "another query", opts, 1, 0, results,
            false, List.of(id1), null, null);

        ArgumentCaptor<String> poolsCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> finalCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> rerankCaptor = ArgumentCaptor.forClass(String.class);

        verify(retrievalLogRepository, Mockito.timeout(5000)).saveTelemetry(Mockito.eq(searchId),
            Mockito.eq("another query"), Mockito.eq(collectionId), Mockito.isNull(),
            poolsCaptor.capture(), finalCaptor.capture(), rerankCaptor.capture(),
            Mockito.eq(List.of(id1)), Mockito.eq(List.of(id1)), Mockito.isNull(), Mockito.isNull());

        Map<String, Object> pools = objectMapper.readValue(poolsCaptor.getValue(), Map.class);
        assertThat(pools.get("sem")).isEqualTo(1);
        assertThat(pools.get("ft")).isEqualTo(0);

        Map<String, Object> finalMap = objectMapper.readValue(finalCaptor.getValue(), Map.class);
        assertThat(finalMap.get("both")).isEqualTo(0);
        assertThat(finalMap.get("sem_only")).isEqualTo(1);

        Map<String, Object> rerankMap = objectMapper.readValue(rerankCaptor.getValue(), Map.class);
        // Under single lane, reranking metrics should be null/empty
        assertThat(rerankMap.get("avg_disp")).isNull();
        assertThat(rerankMap.get("top1_changed")).isEqualTo(false);
        assertThat((List<Integer>) rerankMap.get("rrf_order")).isEmpty();
    }
}
