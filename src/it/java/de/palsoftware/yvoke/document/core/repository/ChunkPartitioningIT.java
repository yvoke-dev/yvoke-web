package de.palsoftware.yvoke.document.core.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.palsoftware.yvoke.document.core.model.ChunkRow;
import de.palsoftware.yvoke.shared.jobengine.model.EnqueueRequest;
import de.palsoftware.yvoke.shared.jobengine.repository.JobRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifies the V11 chunks partitioning: partition pruning for collection-scoped searches,
 * collection-local BM25 statistics (the point of partitioning), literal-tokenizer tag
 * matching, trigger-managed partition lifecycle, and strict collection resolution.
 */
@SpringBootTest
public class ChunkPartitioningIT {

    private static final String SMALL_COLLECTION = "PART-SMALL";
    private static final String LARGE_COLLECTION = "PART-LARGE";
    // A term that appears in exactly one chunk per collection; collection sizes differ,
    // so partition-local IDF must differ.
    private static final String SENTINEL = "zebraquark";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ChunkRepository chunkRepository;

    @Autowired
    private JobRepository jobRepository;

    private UUID smallCollectionId;
    private UUID largeCollectionId;

    @BeforeEach
    public void setUp() {
        cleanup();

        smallCollectionId = UUID.randomUUID();
        largeCollectionId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO collections (id, name, tags) VALUES (?, ?, ARRAY['long term support']::TEXT[])",
            smallCollectionId, SMALL_COLLECTION);
        jdbcTemplate.update("INSERT INTO collections (id, name) VALUES (?, ?)",
            largeCollectionId, LARGE_COLLECTION);

        seedCollection(smallCollectionId, "small.md", 3);
        seedCollection(largeCollectionId, "large.md", 150);

        // Same pattern as HybridSearchIT: rebuild the (now partitioned) BM25 index so newly
        // inserted data is searchable. Also proves REINDEX recurses over partitioned indexes.
        jdbcTemplate.execute("REINDEX INDEX chunks_bm25_idx");
    }

    @AfterEach
    public void tearDown() {
        cleanup();
    }

    private void cleanup() {
        jdbcTemplate.update(
            "DELETE FROM collections WHERE name IN (?, ?)", SMALL_COLLECTION, LARGE_COLLECTION);
    }

    /** One sentinel chunk (tagged 'long term support') plus fillerCount filler chunks. */
    private void seedCollection(UUID collectionId, String title, int fillerCount) {
        UUID docId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO documents (id, collection_id, kind, title, ingestion_status, tags) "
                + "VALUES (?, ?, 'manual', ?, 'completed', ARRAY['long term support']::TEXT[])",
            docId, collectionId, title);

        jdbcTemplate.update(
            "INSERT INTO chunks (id, document_id, text, collection_id, sort_order, tags) "
                + "VALUES (?, ?, ?, ?, 0, ARRAY['long term support']::TEXT[])",
            UUID.randomUUID(), docId,
            "the " + SENTINEL + " configuration option controls replication", collectionId);

        for (int i = 1; i <= fillerCount; i++) {
            jdbcTemplate.update(
                "INSERT INTO chunks (id, document_id, text, collection_id, sort_order, tags) "
                    + "VALUES (?, ?, ?, ?, ?, '{}'::TEXT[])",
                UUID.randomUUID(), docId,
                "filler section number " + i + " describing routine identity workflows",
                collectionId, i);
        }
    }

    private String partitionName(UUID collectionId) {
        return "chunks_p_" + collectionId.toString().replace("-", "");
    }

    @Test
    public void testCollectionScopedSearchPrunesToSinglePartition() {
        List<String> planLines = jdbcTemplate.queryForList(
            "EXPLAIN SELECT ch.id, pdb.score(ch.id) FROM chunks ch "
                + "WHERE ch.text ||| 'identity' AND ch.collection_id = '" + smallCollectionId + "' "
                + "ORDER BY pdb.score(ch.id) DESC LIMIT 5",
            String.class);
        String plan = String.join("\n", planLines);

        assertThat(plan).contains(partitionName(smallCollectionId));
        assertThat(plan).doesNotContain(partitionName(largeCollectionId));
    }

    @Test
    public void testBm25StatisticsAreCollectionLocal() {
        // The sentinel term matches exactly one chunk in each collection with identical text
        // and term frequency. With a single shared index both would score identically; with
        // partition-local statistics the IDF (driven by partition size: 4 vs 151 docs) differs.
        double smallScore = scoreSentinel(SMALL_COLLECTION);
        double largeScore = scoreSentinel(LARGE_COLLECTION);

        assertThat(smallScore).isGreaterThan(0.0);
        assertThat(largeScore).isGreaterThan(0.0);
        assertThat(largeScore)
            .withFailMessage(
                "Sentinel in the large collection should out-IDF the small one (local stats): small=%s large=%s",
                smallScore, largeScore)
            .isGreaterThan(smallScore * 1.5);
    }

    private double scoreSentinel(String collection) {
        List<ChunkRow> rows =
            chunkRepository.findFulltextCandidates(SENTINEL, 5, 0, null, List.of(collection));
        assertThat(rows).hasSize(1);
        return rows.get(0).score();
    }

    @Test
    public void testTagFilterMatchesExactLiteralTag() {
        // 'long term support' is a multi-word tag: the literal tokenizer indexes it as one
        // exact token, so the full tag value matches and a sub-word does not.
        List<ChunkRow> exact = chunkRepository.findFulltextCandidates(
            SENTINEL, 5, 0, List.of("long term support"), List.of(SMALL_COLLECTION));
        assertThat(exact).hasSize(1);

        List<ChunkRow> subWord = chunkRepository.findFulltextCandidates(
            SENTINEL, 5, 0, List.of("term"), List.of(SMALL_COLLECTION));
        assertThat(subWord).isEmpty();
    }

    @Test
    public void testDeletingCollectionDropsPartitionAndChunks() {
        String partition = partitionName(smallCollectionId);
        assertThat(tableExists(partition)).isTrue();

        jdbcTemplate.update("DELETE FROM collections WHERE id = ?", smallCollectionId);

        assertThat(tableExists(partition)).isFalse();
        Integer remaining = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM chunks WHERE collection_id = ?", Integer.class, smallCollectionId);
        assertThat(remaining).isZero();
    }

    @Test
    public void testUnknownCollectionIsRejectedInsteadOfAutoCreated() {
        assertThatThrownBy(() -> jobRepository.enqueue(new EnqueueRequest(
                "manual-zip", "some/file.zip", List.of(), "NO-SUCH-COLLECTION", Map.of())))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not exist");

        Integer created = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM collections WHERE name = 'NO-SUCH-COLLECTION'", Integer.class);
        assertThat(created).isZero();
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?",
            Integer.class, tableName);
        return count != null && count > 0;
    }
}
