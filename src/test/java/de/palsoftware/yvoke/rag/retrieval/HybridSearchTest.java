package de.palsoftware.yvoke.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import de.palsoftware.yvoke.document.core.repository.ChunkRepository;
import de.palsoftware.yvoke.document.core.model.ChunkRow;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

public class HybridSearchTest {

    private ChunkRepository chunkRepository;
    private EmbeddingService embeddingService;
    private RerankClient rerankClient;
    private RrfFuser rrfFuser;
    private RetrievalTelemetryService telemetryService;
    private JdbcClient jdbcClient;
    private HybridSearch hybridSearch;

    @SuppressWarnings("unchecked")
    @BeforeEach
    public void setUp() {
        chunkRepository = mock(ChunkRepository.class);
        embeddingService = mock(EmbeddingService.class);
        rerankClient = mock(RerankClient.class);
        rrfFuser = mock(RrfFuser.class);
        telemetryService = mock(RetrievalTelemetryService.class);
        jdbcClient = mock(JdbcClient.class);

        JdbcClient.StatementSpec mockSpec = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<Object> mockMqs = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbcClient.sql(anyString())).thenReturn(mockSpec);
        when(mockSpec.param(anyString(), any())).thenReturn(mockSpec);
        when(mockSpec.query(any(Class.class))).thenReturn(mockMqs);
        when(mockMqs.list()).thenReturn(Collections.emptyList());

        hybridSearch = new HybridSearch(chunkRepository, embeddingService, rerankClient, rrfFuser,
            telemetryService, jdbcClient, 8, // defaultLimit
            2.0, // semanticLimitMultiplier
            50 // fulltextLimitCap

        );
    }

    @Test
    public void testSearchInvalidQueryOrOptions() {
        SearchOptions opts = new SearchOptions("OIM", 5, true, true, "1.0", 0);

        // Blank query returns empty list immediately
        assertThat(hybridSearch.search("", opts)).isEmpty();
        assertThat(hybridSearch.search(null, opts)).isEmpty();

        // Both semantic and full-text disabled throws IllegalArgumentException
        SearchOptions disabledOpts = new SearchOptions("OIM", 5, false, false, "1.0", 0);
        assertThatThrownBy(() -> hybridSearch.search("query", disabledOpts))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void testExecuteSemanticSearchOnly() {
        SearchOptions opts = new SearchOptions("OIM", 5, true, false, "1.0", 0);
        when(embeddingService.embed("database")).thenReturn(new float[] {0.1f});

        UUID id = UUID.randomUUID();
        ChunkRow row = new ChunkRow(id, UUID.randomUUID(), "database connection", null, null, 1, 0,
            "1.0", "f.md", "manual", "OIM", Collections.emptyMap(), 0.99);
        when(chunkRepository.findSemanticCandidates(anyString(), anyInt(), anyInt(),
            eq(List.of("1.0")), eq(List.of("OIM")), any())).thenReturn(List.of(row));

        List<HybridSearchResult> results = hybridSearch.search("database", opts);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo(id);
        assertThat(results.get(0).telemetry().inSem()).isTrue();
        assertThat(results.get(0).telemetry().inFt()).isFalse();

        verify(telemetryService).logAndPersistTelemetry(any(UUID.class), eq("database"), eq(opts),
            eq(1), anyInt(), anyList(), eq(false), anyList(), isNull(), isNull());
    }

    @Test
    public void testExecuteFulltextSearchOnly() {
        SearchOptions opts = new SearchOptions("OIM", 5, false, true, "1.0", 0);

        UUID id = UUID.randomUUID();
        ChunkRow row = new ChunkRow(id, UUID.randomUUID(), "active directory", null, null, 1, 0,
            "1.0", "f.md", "manual", "OIM", Collections.emptyMap(), 5.2);
        when(chunkRepository.findFulltextCandidates(eq("directory"), anyInt(), anyInt(),
            eq(List.of("1.0")), eq(List.of("OIM")), any())).thenReturn(List.of(row));

        List<HybridSearchResult> results = hybridSearch.search("directory", opts);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo(id);
        assertThat(results.get(0).telemetry().inSem()).isFalse();
        assertThat(results.get(0).telemetry().inFt()).isTrue();

        verify(telemetryService).logAndPersistTelemetry(any(UUID.class), eq("directory"), eq(opts),
            anyInt(), eq(1), anyList(), eq(false), anyList(), isNull(), isNull());
    }

    @Test
    public void testExecuteHybridSearchWithRerankSuccess() {
        SearchOptions opts = new SearchOptions("OIM", 5, true, true, "1.0", 0);
        when(embeddingService.embed("query")).thenReturn(new float[] {0.1f});

        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        ChunkRow row1 = new ChunkRow(id1, UUID.randomUUID(), "chunk one", null, null, 1, 0, "1.0",
            "f.md", "manual", "OIM", Collections.emptyMap(), 0.9);
        ChunkRow row2 = new ChunkRow(id2, UUID.randomUUID(), "chunk two", null, null, 1, 1, "1.0",
            "f.md", "manual", "OIM", Collections.emptyMap(), 0.8);

        when(chunkRepository.findSemanticCandidates(anyString(), anyInt(), anyInt(), anyList(),
            anyList(), any())).thenReturn(List.of(row1, row2));
        when(chunkRepository.findFulltextCandidates(anyString(), anyInt(), anyInt(), anyList(),
            anyList(), any())).thenReturn(List.of(row2));

        // RRF Fusion Mock
        RrfFuser.IntermediateRrfResult rrfRes1 = new RrfFuser.IntermediateRrfResult();
        rrfRes1.setRow(row1);
        rrfRes1.setRrfScore(0.5);
        rrfRes1.setRrfRank(1);
        rrfRes1.setInSem(true);
        rrfRes1.setInFt(false);
        RrfFuser.IntermediateRrfResult rrfRes2 = new RrfFuser.IntermediateRrfResult();
        rrfRes2.setRow(row2);
        rrfRes2.setRrfScore(0.4);
        rrfRes2.setRrfRank(2);
        rrfRes2.setInSem(true);
        rrfRes2.setInFt(true);

        List<RrfFuser.IntermediateRrfResult> rrfList = new ArrayList<>(List.of(rrfRes1, rrfRes2));
        when(rrfFuser.fuse(anyList(), anyList(), anyInt(), anyInt())).thenReturn(rrfList);

        // Reranker returns reranked scores (index 1 is row2, index 0 is row1)
        // Let's rerank row2 (score 0.99) over row1 (score 0.01)
        List<RerankClient.RerankResult> mockRerank =
            List.of(new RerankClient.RerankResult(0, 0.01), new RerankClient.RerankResult(1, 0.99));
        when(rerankClient.rerank(anyString(), anyList())).thenReturn(mockRerank);

        List<HybridSearchResult> results = hybridSearch.search("query", opts);

        assertThat(results).hasSize(2);
        // Row 2 should now be first (index 0) because of rerank score 0.99
        assertThat(results.get(0).id()).isEqualTo(id2);
        assertThat(results.get(0).score()).isEqualTo(0.99);

        // Row 1 should be second (index 1)
        assertThat(results.get(1).id()).isEqualTo(id1);
        assertThat(results.get(1).score()).isEqualTo(0.01);
    }

    @Test
    public void testExecuteHybridSearchRerankFailureFallback() {
        SearchOptions opts = new SearchOptions("OIM", 5, true, true, "1.0", 0);
        when(embeddingService.embed("query")).thenReturn(new float[] {0.1f});

        UUID id = UUID.randomUUID();
        ChunkRow row = new ChunkRow(id, UUID.randomUUID(), "chunk", null, null, 1, 0, "1.0", "f.md",
            "manual", "OIM", Collections.emptyMap(), 0.9);
        when(chunkRepository.findSemanticCandidates(anyString(), anyInt(), anyInt(), anyList(),
            anyList(), any())).thenReturn(List.of(row));
        when(chunkRepository.findFulltextCandidates(anyString(), anyInt(), anyInt(), anyList(),
            anyList(), any())).thenReturn(Collections.emptyList());

        RrfFuser.IntermediateRrfResult rrfRes = new RrfFuser.IntermediateRrfResult();
        rrfRes.setRow(row);
        rrfRes.setRrfScore(0.5);
        rrfRes.setRrfRank(1);
        rrfRes.setInSem(true);
        rrfRes.setInFt(false);
        when(rrfFuser.fuse(anyList(), anyList(), anyInt(), anyInt()))
            .thenReturn(new ArrayList<>(List.of(rrfRes)));

        // Voyage Rerank throws exception
        when(rerankClient.rerank(anyString(), anyList()))
            .thenThrow(new RuntimeException("API Outage"));

        List<HybridSearchResult> results = hybridSearch.search("query", opts);

        // Should fallback to RRF score and complete query successfully
        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo(id);
        assertThat(results.get(0).score()).isEqualTo(0.5); // Fallback RRF score (via getFinalScore)
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testExecuteSearchWithTags() {
        SearchOptions opts =
            new SearchOptions(Collections.emptyList(), 5, true, false, 0, false, List.of("my-tag"));

        JdbcClient.StatementSpec spec1 = mock(JdbcClient.StatementSpec.class);
        JdbcClient.StatementSpec spec2 = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<String> querySpec1 = mock(JdbcClient.MappedQuerySpec.class);
        JdbcClient.MappedQuerySpec<UUID> querySpec2 = mock(JdbcClient.MappedQuerySpec.class);

        when(jdbcClient.sql(contains("FROM collections"))).thenReturn(spec1);
        when(spec1.param(eq("tags"), any())).thenReturn(spec1);
        when(spec1.query(String.class)).thenReturn(querySpec1);
        when(querySpec1.list()).thenReturn(List.of("tag-collection"));

        when(jdbcClient.sql(contains("FROM documents"))).thenReturn(spec2);
        when(spec2.param(eq("tags"), any())).thenReturn(spec2);
        when(spec2.query(UUID.class)).thenReturn(querySpec2);
        UUID targetDocId = UUID.randomUUID();
        when(querySpec2.list()).thenReturn(List.of(targetDocId));

        when(embeddingService.embed("database")).thenReturn(new float[] {0.1f});

        UUID id = UUID.randomUUID();
        ChunkRow row = new ChunkRow(id, targetDocId, "database connection", null, null, 1, 0, "1.0",
            "f.md", "manual", "tag-collection", Collections.emptyMap(), 0.99);
        when(chunkRepository.findSemanticCandidates(anyString(), anyInt(), anyInt(), any(), any(),
            any())).thenReturn(List.of(row));

        List<HybridSearchResult> results = hybridSearch.search("database", opts);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo(id);
        verify(chunkRepository).findSemanticCandidates(anyString(), anyInt(), anyInt(), any(),
            any(), any());
    }
}
