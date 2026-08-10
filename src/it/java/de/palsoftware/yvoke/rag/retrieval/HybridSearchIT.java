package de.palsoftware.yvoke.rag.retrieval;

import de.palsoftware.yvoke.shared.db.VectorUtils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import java.sql.Array;
import org.assertj.core.data.Offset;

@SpringBootTest
public class HybridSearchIT {

    private static final String COLLECTION = "OIM-TEST";
    private static final String COLLECTION_2 = "OIM-TEST-2";

    @Autowired
    private HybridSearch hybridSearch;

    @Autowired
    private RetrievalTelemetryService telemetryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RetrievalLogRepository retrievalLogRepository;

    @MockitoBean
    private EmbeddingService embeddingService;

    @MockitoBean
    private RerankClient rerankClient;

    private final UUID docId1 = UUID.randomUUID();
    private final UUID docId2 = UUID.randomUUID();

    private final UUID chunkId1 = UUID.randomUUID();
    private final UUID chunkId2 = UUID.randomUUID();
    private final UUID chunkId3 = UUID.randomUUID();

    private UUID collectionId1;
    private UUID collectionId2;

    @BeforeEach
    public void setUp() {
        // Clear target tables first
        cleanup();

        collectionId1 = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO collections (id, name) VALUES (?, ?)", collectionId1, COLLECTION);
        collectionId2 = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO collections (id, name) VALUES (?, ?)", collectionId2, COLLECTION_2);

        // 1. Insert documents
        jdbcTemplate.update(
            "INSERT INTO documents (id, collection_id, kind, title, ingestion_status, tags, metadata) " +
            "VALUES (?, ?, ?, ?, ?, ARRAY['9.3']::TEXT[], ?::jsonb)",
            docId1, collectionId1, "manual", "manual93.md", "completed", "{\"source_file\": \"manual93.md\", \"tag\": \"9.3\"}"
        );
        jdbcTemplate.update(
            "INSERT INTO documents (id, collection_id, kind, title, ingestion_status, tags, metadata) " +
            "VALUES (?, ?, ?, ?, ?, ARRAY['10.0']::TEXT[], ?::jsonb)",
            docId2, collectionId1, "manual", "manual100.md", "completed", "{\"source_file\": \"manual100.md\", \"tag\": \"10.0\"}"
        );

        // 2. Insert chunks with 1024-dimensional mock embeddings
        insertChunk(chunkId1, docId1, "identity manager database connection pool", createMockVector(0), new String[]{"Database", "Pool"}, "Database", 1, 0, "9.3", "manual93.md");
        insertChunk(chunkId2, docId1, "active directory connector sync group", createMockVector(1), new String[]{"Directory", "Sync"}, "Directory", 1, 1, "9.3", "manual93.md");
        insertChunk(chunkId3, docId2, "approval workflow policy rule for identity", createMockVector(2), new String[]{"Approval", "Workflow"}, "Approval", 1, 0, "10.0", "manual100.md");
        
        // Rebuild ParadeDB index to make newly inserted data searchable
        jdbcTemplate.execute("REINDEX INDEX chunks_bm25_idx");

        // Stub RerankClient to throw exception by default to trigger fallback behavior
        Mockito.lenient().when(rerankClient.rerank(Mockito.anyString(), Mockito.anyList()))
               .thenThrow(new RuntimeException("Default mock fallback behavior"));
     }

    @AfterEach
    public void tearDown() {
        cleanup();
    }

    private void cleanup() {
        jdbcTemplate.update("DELETE FROM retrieval_logs WHERE collection_id IN (SELECT id FROM collections WHERE name IN (?, ?))", COLLECTION, COLLECTION_2);
        jdbcTemplate.update("DELETE FROM chunks WHERE collection_id IN (SELECT id FROM collections WHERE name IN (?, ?))", COLLECTION, COLLECTION_2);
        jdbcTemplate.update("DELETE FROM documents WHERE collection_id IN (SELECT id FROM collections WHERE name IN (?, ?))", COLLECTION, COLLECTION_2);
        jdbcTemplate.update("DELETE FROM collections WHERE name IN (?, ?)", COLLECTION, COLLECTION_2);
    }

    private void insertChunk(UUID id, UUID docId, String text, float[] vector, String[] headingPath, String heading, int depth, int sortOrder, String version, String srcFile) {
        String vectorStr = VectorUtils.toVectorString(vector);
        UUID collectionId = jdbcTemplate.queryForObject("SELECT id FROM collections WHERE name = ?", UUID.class, COLLECTION);
        jdbcTemplate.execute((ConnectionCallback<Void>) conn -> {
            String sql = "INSERT INTO chunks (id, document_id, text, embedding, heading_path, heading, depth, sort_order, collection_id, tags) " +
                         "VALUES (?, ?, ?, ?::vector, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setObject(1, id);
                ps.setObject(2, docId);
                ps.setString(3, text);
                ps.setString(4, vectorStr);
                ps.setArray(5, conn.createArrayOf("text", headingPath));
                ps.setString(6, heading);
                ps.setInt(7, depth);
                ps.setInt(8, sortOrder);
                ps.setObject(9, collectionId);
                ps.setArray(10, conn.createArrayOf("text", version != null ? new String[]{version} : new String[0]));
                ps.executeUpdate();
            }
            return null;
        });
    }

    private float[] createMockVector(int activeDimensionIndex) {
        float[] vector = new float[1024];
        vector[activeDimensionIndex] = 1.0f;
        return vector;
    }

    @Test
    public void testSemanticSearchOnly() {
        // Mock EmbeddingService to return vector focusing on chunk 1
        when(embeddingService.embed(anyString())).thenReturn(createMockVector(0));

        SearchOptions opts = new SearchOptions(COLLECTION, 10, true, false, null, 0);
        List<HybridSearchResult> results = hybridSearch.search("database", opts);

        assertThat(results).isNotEmpty();
        // Since we filtered for COLLECTION, Chunk 1 should be the top match (distance = 0.0, score = 1.0)
        assertThat(results.get(0).id()).isEqualTo(chunkId1);
        assertThat(results.get(0).score()).isCloseTo(1.0, Offset.offset(0.01));
        assertThat(results.get(0).telemetry().inSem()).isTrue();
        assertThat(results.get(0).telemetry().inFt()).isFalse();
        assertThat(results.get(0).telemetry().semPool()).isEqualTo(results.size());
    }

    @Test
    public void testFulltextSearchOnly() {
        SearchOptions opts = new SearchOptions(COLLECTION, 10, false, true, null, 0);
        List<HybridSearchResult> results = hybridSearch.search("directory", opts);

        // Should return Chunk 2
        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo(chunkId2);
        assertThat(results.get(0).telemetry().inSem()).isFalse();
        assertThat(results.get(0).telemetry().inFt()).isTrue();
    }

    @Test
    public void testSemanticSearchVersionFilter() {
        // Mock embedding to search for vector 2 (Chunk 3 is at version 10.0, Chunk 1 is 9.3)
        when(embeddingService.embed(anyString())).thenReturn(createMockVector(2));

        // Search with version filter = "9.3"
        SearchOptions optsWithFilter = new SearchOptions(COLLECTION, 10, true, false, "9.3", 0);
        List<HybridSearchResult> results = hybridSearch.search("query", optsWithFilter);

        // Chunk 3 is version 10.0, so it shouldn't match. Chunk 1 & 2 should return.
        assertThat(results).hasSize(2);
        assertThat(results.stream().anyMatch(r -> r.id().equals(chunkId3))).isFalse();

        // Search with version filter = "10.0"
        SearchOptions optsWithFilter100 = new SearchOptions(COLLECTION, 10, true, false, "10.0", 0);
        List<HybridSearchResult> results100 = hybridSearch.search("query", optsWithFilter100);

        assertThat(results100).hasSize(1);
        assertThat(results100.get(0).id()).isEqualTo(chunkId3);
    }

    @Test
    public void testFulltextSearchVersionFilter() {
        // Search "identity" which exists in Chunk 1 (9.3) and Chunk 3 (10.0)
        SearchOptions opts93 = new SearchOptions(COLLECTION, 10, false, true, "9.3", 0);
        List<HybridSearchResult> results93 = hybridSearch.search("identity", opts93);
        assertThat(results93).hasSize(1);
        assertThat(results93.get(0).id()).isEqualTo(chunkId1);

        SearchOptions opts100 = new SearchOptions(COLLECTION, 10, false, true, "10.0", 0);
        List<HybridSearchResult> results100 = hybridSearch.search("identity", opts100);
        assertThat(results100).hasSize(1);
        assertThat(results100.get(0).id()).isEqualTo(chunkId3);
    }

    @Test
    public void testHybridSearchRrfFusion() {
        // We mock embedding to favor Chunk 2 (index 1)
        when(embeddingService.embed(anyString())).thenReturn(createMockVector(1));

        // We run a hybrid search query "database" which favors Chunk 1 for fulltext
        SearchOptions opts = new SearchOptions(COLLECTION, 5, true, true, null, 0);
        List<HybridSearchResult> results = hybridSearch.search("database", opts);

        // Vector lane pool size = 2 * (5 + 0) = 10, but only 3 chunks exist.
        // Fulltext lane pool cap = 50.
        // Chunk 2 will be rank 1 in semantic. Chunk 1 will be rank 1 in full-text.
        // Let's assert that both returned, and the RRF rank telemetry was annotated.
        assertThat(results).isNotEmpty();
        
        // Let's verify that the output results carry telemetry
        for (HybridSearchResult res : results) {
            assertThat(res.telemetry().semPool()).isGreaterThan(0);
            assertThat(res.telemetry().ftPool()).isGreaterThan(0);
            assertThat(res.telemetry().rrfRank()).isGreaterThan(0);
            
            if (res.id().equals(chunkId1)) {
                // Chunk 1 is found by BM25 (text contains "database")
                assertThat(res.telemetry().inFt()).isTrue();
            }
            if (res.id().equals(chunkId2)) {
                // Chunk 2 is found by semantic
                assertThat(res.telemetry().inSem()).isTrue();
            }
        }
    }

    @Test
    public void testEmptyQuerySearch() {
        SearchOptions opts = new SearchOptions(
                List.of("COL-2"), 10, true, true, 0, true, List.of("2.0"));
        List<HybridSearchResult> resultsEmpty = hybridSearch.search("", opts);
        assertThat(resultsEmpty).isEmpty();

        List<HybridSearchResult> resultsNull = hybridSearch.search(null, opts);
        assertThat(resultsNull).isEmpty();
        
        List<HybridSearchResult> resultsBlank = hybridSearch.search("   ", opts);
        assertThat(resultsBlank).isEmpty();
    }

    @Test
    public void testQuerySanitization() {
        // Query has Tantivy operators: quotes, colons, brackets, punctuation
        // Searching for "+directory:" which sanitizes to "directory"
        SearchOptions opts = new SearchOptions(COLLECTION, 10, false, true, null, 0);
        List<HybridSearchResult> results = hybridSearch.search("+directory:", opts);

        // Should successfully sanitize and match Chunk 2 ("active directory connector sync group")
        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo(chunkId2);
        
        // Searching for only special characters (e.g. "+++") should sanitize to empty and return empty list cleanly
        List<HybridSearchResult> resultsEmpty = hybridSearch.search("+++", opts);
        assertThat(resultsEmpty).isEmpty();
    }

    @Test
    public void testDefaultLimitFallback() {
        when(embeddingService.embed(anyString())).thenReturn(createMockVector(0));

        // Create SearchOptions with null limit to trigger default limit fallback
        SearchOptions opts = new SearchOptions(COLLECTION, null, true, true, null, 0);
        List<HybridSearchResult> results = hybridSearch.search("database", opts);

        assertThat(results).isNotEmpty();
        assertThat(results.size()).isLessThanOrEqualTo(8); // fallback to default-limit = 8
        for (HybridSearchResult res : results) {
            assertThat(res.telemetry().semPool()).isEqualTo(3); // total chunks matched in DB is 3
        }
    }

    @Test
    public void testOversamplingMultiplier() {
        when(embeddingService.embed(anyString())).thenReturn(createMockVector(0));

        // limit = 1, multiplier = 2.0 -> semanticLimit = 2.0 * (1 + 0) = 2
        SearchOptions opts = new SearchOptions(COLLECTION, 1, true, true, null, 0);
        List<HybridSearchResult> results = hybridSearch.search("database", opts);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).telemetry().semPool()).isEqualTo(2); // semantic pool size should be 2
    }

    @Test
    public void testHybridSearchRerankSuccess() throws Exception {
        // Mock embedding to return vector focusing on chunk 2
        when(embeddingService.embed(anyString())).thenReturn(createMockVector(1));

        // Stub RerankClient to score all 3 candidates.
        // Candidates in RRF sorted list will be:
        // Rank 1 (index 0): Chunk 2 (RRF rank 1)
        // Rank 2 (index 1): Chunk 1 (RRF rank 2)
        // Rank 3 (index 2): Chunk 3 (RRF rank 3)
        // We will order them: Chunk 1 (0.99), Chunk 2 (0.01), Chunk 3 (0.005)
        List<RerankClient.RerankResult> mockRerank = List.of(
            new RerankClient.RerankResult(0, 0.01),  // Chunk 2 (original index 0)
            new RerankClient.RerankResult(1, 0.99),  // Chunk 1 (original index 1)
            new RerankClient.RerankResult(2, 0.005)  // Chunk 3 (original index 2)
        );
        when(rerankClient.rerank(Mockito.eq("database"), Mockito.anyList())).thenReturn(mockRerank);

        SearchOptions opts = new SearchOptions(COLLECTION, 5, true, true, null, 0);
        List<HybridSearchResult> results = hybridSearch.search("database", opts);

        assertThat(results).hasSize(3);
        
        // Chunk 2 (original index 1, pre-rerank rank 2) should now be top (rank 1) because of score 0.99
        assertThat(results.get(0).id()).isEqualTo(chunkId2);
        assertThat(results.get(0).score()).isEqualTo(0.99);
        assertThat(results.get(0).telemetry().rrfRank()).isEqualTo(2); // original RRF rank was 2

        // Chunk 1 (original index 0, pre-rerank rank 1) should now be second because of score 0.01
        assertThat(results.get(1).id()).isEqualTo(chunkId1);
        assertThat(results.get(1).score()).isEqualTo(0.01);
        assertThat(results.get(1).telemetry().rrfRank()).isEqualTo(1); // original RRF rank was 1

        // Chunk 3 (original index 2, pre-rerank rank 3) should now be third because of score 0.005
        assertThat(results.get(2).id()).isEqualTo(chunkId3);
        assertThat(results.get(2).score()).isEqualTo(0.005);
        assertThat(results.get(2).telemetry().rrfRank()).isEqualTo(3); // original RRF rank was 3

        // Verify that a retrieval log row was written to the database (telemetry persists
        // asynchronously — flush before asserting)
        telemetryService.flush();
        List<Map<String, Object>> logs = jdbcTemplate.queryForList(
            "SELECT rl.*, c.name as collection_name FROM retrieval_logs rl JOIN collections c ON rl.collection_id = c.id WHERE c.name = ? ORDER BY rl.created_at DESC LIMIT 1",
            COLLECTION
        );
        assertThat(logs).isNotEmpty();
        Map<String, Object> logRow = logs.get(0);
        assertThat(logRow.get("collection_name")).isEqualTo(COLLECTION);

        // Verify retrieved_chunk_ids
        Array chunkIdsArray = (Array) logRow.get("retrieved_chunk_ids");
        assertThat(chunkIdsArray).isNotNull();
        UUID[] chunkIds = (UUID[]) chunkIdsArray.getArray();
        assertThat(chunkIds).containsExactly(chunkId2, chunkId1, chunkId3);
        
        // Verify JSON fields
        String rerankJson = logRow.get("rerank").toString();
        assertThat(rerankJson).contains("\"top1_changed\": true");
        assertThat(rerankJson).contains("\"promotions\": 0"); 
        assertThat(rerankJson).contains("\"avg_disp\": 0.67"); 
    }

    /**
     * The admin search console's lane trace is sliced out of ONE {@code retrieval_logs} row:
     * {@code RagAdminViewService.toLaneTrace} guards on
     * {@code initialChunkIds.size() == semPool + ftPool}, then reads {@code initial[0..semPool)} as
     * the semantic lane and the rest as the BM25 lane. A wrong boundary from
     * {@code findTelemetryById} fails in one of two silent ways: the guard trips and the whole lane
     * trace vanishes from /admin/search with no error, or -- when the pools happen to sum correctly
     * -- every chunk is attributed to the wrong lane while each rendered row still looks plausible.
     * That trace is the only instrument for diagnosing fusion/recall problems, so mis-attribution
     * actively misleads whoever is debugging retrieval.
     *
     * <p>
     * The last assertion pins why the method exists: it replaced a
     * {@code findLatestTelemetry(collection)} that fell back to the newest row for the collection,
     * so (telemetry being async) the console showed the PREVIOUS search's numbers beside the
     * current results. An unknown searchId must yield an empty Optional, never a neighbour.
     *
     * <p>
     * The fixture is deliberately asymmetric -- with semantic-limit-multiplier 1.5 the vector lane
     * asks for ceil(1.5*5)=8 and gets all 3 seeded chunks, while only chunk 1 contains "database"
     * so the BM25 lane pool is 1. A symmetric fixture would let a sem/ft swap pass unnoticed.
     */
    @Test
    public void theTelemetryReadBackByIdSplitsTheTwoLanesAtTheBoundaryTheSearchActuallyUsed() {
        when(embeddingService.embed(anyString())).thenReturn(createMockVector(1));

        SearchOptions opts = new SearchOptions(COLLECTION, 5, true, true, null, 0);
        SearchWithId search = hybridSearch.searchWithId("database", opts);
        List<HybridSearchResult> results = search.results();
        assertThat(results).isNotEmpty();

        // Telemetry is written on a single-threaded executor; the console flushes for the same
        // reason before reading its row back.
        telemetryService.flush();

        RetrievalTelemetryRow row =
            retrievalLogRepository.findTelemetryById(search.searchId()).orElseThrow();

        assertThat(row.semPool()).isEqualTo(3);
        assertThat(row.ftPool()).isEqualTo(1);
        assertThat(row.semPool()).isEqualTo(results.get(0).telemetry().semPool());
        assertThat(row.ftPool()).isEqualTo(results.get(0).telemetry().ftPool());

        // initial_chunk_ids is the semantic ids in rank order followed by the BM25 ids in rank
        // order, undeduped -- the concatenation boundary IS semPool.
        assertThat(row.initialChunkIds()).hasSize(row.semPool() + row.ftPool());
        List<UUID> semLane = row.initialChunkIds().subList(0, row.semPool());
        List<UUID> ftLane =
            row.initialChunkIds().subList(row.semPool(), row.semPool() + row.ftPool());
        assertThat(semLane).containsExactlyInAnyOrder(chunkId1, chunkId2, chunkId3);
        assertThat(ftLane).containsExactly(chunkId1);

        List<UUID> ftMembers = results.stream().filter(r -> r.telemetry().inFt())
            .map(HybridSearchResult::id).toList();
        assertThat(ftLane).containsExactlyInAnyOrderElementsOf(ftMembers);

        assertThat(row.retrievedChunkIds())
            .containsExactlyElementsOf(results.stream().map(HybridSearchResult::id).toList());

        // The guard in toLaneTrace only passes when the recorded boundary is the real one.
        assertThat(RagAdminViewService.toLaneTrace(row, 10).fusionOrder()).isNotEmpty();

        Optional<RetrievalTelemetryRow> someOtherSearch =
            retrievalLogRepository.findTelemetryById(UUID.randomUUID());
        assertThat(someOtherSearch).isEmpty();
    }

    private void insertChunkForColl(UUID id, UUID docId, String text, float[] vector, String[] headingPath, String heading, int depth, int sortOrder, String version, String srcFile, String collection) {
        String vectorStr = VectorUtils.toVectorString(vector);
        UUID collectionId = jdbcTemplate.queryForObject("SELECT id FROM collections WHERE name = ?", UUID.class, collection);
        jdbcTemplate.execute((ConnectionCallback<Void>) conn -> {
            String sql = "INSERT INTO chunks (id, document_id, text, embedding, heading_path, heading, depth, sort_order, collection_id, tags) " +
                         "VALUES (?, ?, ?, ?::vector, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setObject(1, id);
                ps.setObject(2, docId);
                ps.setString(3, text);
                ps.setString(4, vectorStr);
                ps.setArray(5, conn.createArrayOf("text", headingPath));
                ps.setString(6, heading);
                ps.setInt(7, depth);
                ps.setInt(8, sortOrder);
                ps.setObject(9, collectionId);
                ps.setArray(10, conn.createArrayOf("text", version != null ? new String[]{version} : new String[0]));
                ps.executeUpdate();
            }
            return null;
        });
    }

    /**
     * Pins {@code updateMessageId}, {@code RetrievalLogDetailsMapper.mapRow} and the two-hop LEFT
     * JOIN in {@code listLogs} together, because they only mean anything together. /admin/logs
     * exists to answer "which retrievals produced answers the user marked bad", and the only path
     * from a retrieval_logs row to a message_feedback row is
     * retrieval_logs.message_id -> messages.id -> message_feedback.message_id. Break the write and
     * the join still executes, the page still renders and every row still looks normal -- the
     * ratings column is simply empty forever, which an operator reads as "nobody gave feedback"
     * rather than "the link is missing", and the one signal for finding bad retrievals is gone.
     *
     * <p>
     * Asserting the BEFORE state (rating null while message_id is null) and the AFTER state is what
     * makes this a contract rather than a smoke test, and it is the first execution of mapRow's
     * column names, its {@code getObject("feedback_rating", Integer.class)} read and
     * {@code JdbcMappers.arrayToUuidList} against real Postgres.
     *
     * <p>
     * Note {@code listLogs} selects {@code l.query AS message_content} -- the message content column
     * carries the RETRIEVAL's query, not the message body -- so that is what is asserted here.
     */
    @Test
    public void linkingARetrievalLogToItsMessageIsWhatMakesTheUsersFeedbackVisibleInTheAdminLog() {
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID searchId = UUID.randomUUID();
        String query = "how do I size the identity manager database connection pool?";
        try {
            jdbcTemplate.update(
                "INSERT INTO users (id, entra_oid, email, display_name) VALUES (?, ?, ?, ?)",
                userId, "oid-" + userId, "logs-it@example.com", "Logs IT");
            jdbcTemplate.update(
                "INSERT INTO conversations (id, user_id, title) VALUES (?, ?, ?)",
                conversationId, userId, "retrieval log linking");
            jdbcTemplate.update(
                "INSERT INTO messages (id, conversation_id, role, content) VALUES (?, ?, ?, ?)",
                messageId, conversationId, "assistant", "Configure it in the connection string.");
            jdbcTemplate.update(
                "INSERT INTO message_feedback (id, message_id, rating, comment) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), messageId, -1, "answer cited the wrong kit version");

            retrievalLogRepository.saveTelemetry(searchId, query, collectionId1, "9.3",
                "{\"sem\": 2, \"ft\": 1}", "{\"n\": 2}", "{\"promotions\": 0}",
                List.of(chunkId1, chunkId2), List.of(chunkId1, chunkId2, chunkId1),
                List.of(chunkId1, chunkId2), List.of(chunkId1, chunkId2));

            RetrievalLogDetails before = findLoggedSearch(searchId);
            assertThat(before.messageId()).isNull();
            assertThat(before.feedbackRating()).isNull();
            assertThat(before.feedbackComment()).isNull();

            retrievalLogRepository.updateMessageId(searchId, messageId);

            RetrievalLogDetails after = findLoggedSearch(searchId);
            assertThat(after.messageId()).isEqualTo(messageId);
            assertThat(after.feedbackRating()).isEqualTo(-1);
            assertThat(after.feedbackComment()).isEqualTo("answer cited the wrong kit version");
            assertThat(after.messageContent()).isEqualTo(query);
            assertThat(after.collection()).isEqualTo(COLLECTION);
            assertThat(after.tag()).isEqualTo("9.3");
            assertThat(after.retrievedChunkIds()).containsExactly(chunkId1, chunkId2);
        } finally {
            // cleanup() only removes rows for this class's two collections; the chat-side rows
            // seeded here are ours to remove (message_feedback cascades from messages, and
            // retrieval_logs.message_id is ON DELETE SET NULL).
            jdbcTemplate.update("DELETE FROM messages WHERE id = ?", messageId);
            jdbcTemplate.update("DELETE FROM conversations WHERE id = ?", conversationId);
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
    }

    private RetrievalLogDetails findLoggedSearch(UUID searchId) {
        return retrievalLogRepository.listLogs(50, 0).stream()
            .filter(l -> searchId.equals(l.id())).findFirst()
            .orElseThrow(() -> new AssertionError(
                "retrieval log " + searchId + " not on the first page of listLogs"));
    }

    @Test
    public void testHybridSearchMultipleCollectionsAndVersions() {
        UUID multiDoc1 = UUID.randomUUID();
        UUID multiDoc2 = UUID.randomUUID();
        UUID multiChunk1 = UUID.randomUUID();
        UUID multiChunk2 = UUID.randomUUID();

        // Insert documents
        jdbcTemplate.update(
            "INSERT INTO documents (id, collection_id, kind, title, ingestion_status, tags, metadata) " +
            "VALUES (?, ?, ?, ?, ?, ARRAY['11.0']::TEXT[], ?::jsonb)",
            multiDoc1, collectionId2, "manual", "multi110.md", "completed", "{\"source_file\": \"multi110.md\", \"tag\": \"11.0\"}"
        );
        jdbcTemplate.update(
            "INSERT INTO documents (id, collection_id, kind, title, ingestion_status, tags, metadata) " +
            "VALUES (?, ?, ?, ?, ?, '{}'::TEXT[], ?::jsonb)",
            multiDoc2, collectionId2, "manual", "multinull.md", "completed", "{\"source_file\": \"multinull.md\", \"tag\": null}"
        );

        // Insert chunks
        insertChunkForColl(multiChunk1, multiDoc1, "multi collection vector database storage", createMockVector(0), new String[]{"Multi"}, "Multi", 1, 0, "11.0", "multi110.md", COLLECTION_2);
        insertChunkForColl(multiChunk2, multiDoc2, "schema-less data repository", createMockVector(1), new String[]{"Schema"}, "Schema", 1, 0, null, "multinull.md", COLLECTION_2);

        // Rebuild ParadeDB index to make newly inserted data searchable
        jdbcTemplate.execute("REINDEX INDEX chunks_bm25_idx");

        // 1. Search for "database" across BOTH collections, but with version filter "11.0"
        SearchOptions opts = new SearchOptions(
            List.of(COLLECTION, COLLECTION_2),
            10,
            false, // no semantic
            true,  // fulltext
            0,
            false, // no rerank
            List.of("11.0")
        );

        List<HybridSearchResult> results = hybridSearch.search("database", opts);

        // Should return:
        // - multiChunk1 (version 11.0, COLLECTION_2)
        // - Should NOT return chunkId1 (version 9.3, COLLECTION) because 9.3 is not in List.of("11.0")
        // - Should NOT return chunkId3 (version 10.0, COLLECTION) because 10.0 is not in List.of("11.0")
        assertThat(results).isNotEmpty();
        assertThat(results.stream().anyMatch(r -> r.id().equals(multiChunk1))).isTrue();
        assertThat(results.stream().anyMatch(r -> r.id().equals(chunkId1))).isFalse();
        assertThat(results.stream().anyMatch(r -> r.id().equals(chunkId3))).isFalse();

        // 2. Search for "repository" (matches multiChunk2 which has null version) across both collections, but with version filter "11.0"
        List<HybridSearchResult> resultsRepo = hybridSearch.search("repository", opts);

        // Since version filter is "11.0", multiChunk2 (null version/tags) should not match (untagged is not global)
        assertThat(resultsRepo).isEmpty();
    }

    @Test
    public void testSearchIntersectionFilters() {
        UUID multiDocCol2 = UUID.randomUUID();
        UUID multiChunkCol2 = UUID.randomUUID();
        UUID docCol1 = UUID.randomUUID();
        UUID chunkCol1 = UUID.randomUUID();

        jdbcTemplate.update(
            "INSERT INTO collections (id, name, tags) VALUES (?, ?, ARRAY['9.3.1']::TEXT[]) ON CONFLICT DO NOTHING",
            collectionId2, COLLECTION_2
        );
        jdbcTemplate.update(
            "UPDATE collections SET tags = ARRAY['9.3.1']::TEXT[] WHERE id = ?",
            collectionId1
        );

        jdbcTemplate.update(
            "INSERT INTO documents (id, collection_id, kind, title, ingestion_status, tags, metadata) " +
            "VALUES (?, ?, ?, ?, ?, ARRAY['9.3.1']::TEXT[], ?::jsonb)",
            multiDocCol2, collectionId2, "manual", "col2_doc.md", "completed", "{\"source_file\": \"col2_doc.md\", \"tag\": \"9.3.1\"}"
        );

        jdbcTemplate.update(
            "INSERT INTO documents (id, collection_id, kind, title, ingestion_status, tags, metadata) " +
            "VALUES (?, ?, ?, ?, ?, ARRAY['9.3.1']::TEXT[], ?::jsonb)",
            docCol1, collectionId1, "manual", "col1_doc.md", "completed", "{\"source_file\": \"col1_doc.md\", \"tag\": \"9.3.1\"}"
        );

        insertChunkForColl(multiChunkCol2, multiDocCol2, "service now integration group in col2", createMockVector(0), new String[]{"Test"}, "Test", 1, 0, "9.3.1", "col2_doc.md", COLLECTION_2);
        insertChunk(chunkCol1, docCol1, "service now integration info in col1", createMockVector(0), new String[]{"Test"}, "Test", 1, 0, "9.3.1", "col1_doc.md");

        jdbcTemplate.execute("REINDEX INDEX chunks_bm25_idx");

        when(embeddingService.embed(anyString())).thenReturn(createMockVector(0));

        SearchOptions opts = new SearchOptions(
            List.of(COLLECTION),
            10,
            true, // semantic
            true, // fulltext
            0,
            false, // no rerank
            List.of("9.3.1")
        );

        List<HybridSearchResult> results = hybridSearch.search("service now integration", opts);

        assertThat(results).isNotEmpty();
        boolean containsCol1Result = results.stream().anyMatch(r -> r.id().equals(chunkCol1));
        boolean containsCol2Result = results.stream().anyMatch(r -> r.id().equals(multiChunkCol2));

        assertThat(containsCol1Result).isTrue();
        assertThat(containsCol2Result).isFalse();
    }
}
