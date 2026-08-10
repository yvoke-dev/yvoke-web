package de.palsoftware.yvoke.rag.retrieval;

import de.palsoftware.yvoke.shared.config.JdbcMappers;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class RetrievalLogRepository {

    private final JdbcClient jdbcClient;

    public RetrievalLogRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<RetrievalLogDetails> listLogs(int limit, int offset) {
        String sql =
            """
                SELECT l.id, l.message_id, l.collection_id, c.name AS collection, l.tag, l.pools::text AS pools_text, l.final::text AS final_text, l.rerank::text AS rerank_text, l.created_at,
                       l.query AS message_content,
                       f.rating AS feedback_rating, f.comment AS feedback_comment,
                       l.retrieved_chunk_ids
                FROM retrieval_logs l
                JOIN collections c ON l.collection_id = c.id
                LEFT JOIN messages m ON l.message_id = m.id
                LEFT JOIN message_feedback f ON f.message_id = m.id
                ORDER BY l.created_at DESC, l.id ASC
                LIMIT :limit OFFSET :offset
                """;

        return jdbcClient.sql(sql).param("limit", limit).param("offset", offset)
            .query(new RetrievalLogDetailsMapper()).list();
    }

    public long countLogs() {
        return jdbcClient.sql("SELECT count(*) FROM retrieval_logs").query(Long.class).single();
    }

    public void updateMessageId(UUID id, UUID messageId) {
        String sql = """
            UPDATE retrieval_logs
            SET message_id = :messageId, updated_at = CURRENT_TIMESTAMP
            WHERE id = :id
            """;
        jdbcClient.sql(sql).param("messageId", messageId).param("id", id).update();
    }

    /**
     * The telemetry for ONE search, by its {@code searchId}.
     *
     * <p>
     * Replaces an earlier {@code findLatestTelemetry(collection)} that took the most recent row for
     * the collection. Telemetry is persisted asynchronously, so on the search that just ran that
     * row frequently does not exist yet and the console rendered the <em>previous</em> search's
     * numbers beside the current results — invisibly, since every field still looked plausible.
     * Callers must flush telemetry before reading (see {@code RetrievalTelemetryService#flush()}).
     */
    @Transactional(readOnly = true)
    public Optional<RetrievalTelemetryRow> findTelemetryById(UUID searchId) {
        String sql =
            """
                SELECT pools::text AS pools_text, final::text AS final_text, rerank::text AS rerank_text,
                       COALESCE((pools->>'sem')::int, 0) AS sem_pool,
                       COALESCE((pools->>'ft')::int, 0)  AS ft_pool,
                       initial_chunk_ids, fused_chunk_ids, retrieved_chunk_ids
                FROM retrieval_logs
                WHERE id = :id
                """;

        return jdbcClient.sql(sql).param("id", searchId)
            .query((rs, rowNum) -> new RetrievalTelemetryRow(rs.getString("pools_text"),
                rs.getString("final_text"), rs.getString("rerank_text"), rs.getInt("sem_pool"),
                rs.getInt("ft_pool"), JdbcMappers.arrayToUuidList(rs, "initial_chunk_ids"),
                JdbcMappers.arrayToUuidList(rs, "fused_chunk_ids"),
                JdbcMappers.arrayToUuidList(rs, "retrieved_chunk_ids")))
            .optional();
    }

    public void saveTelemetry(UUID searchId, String query, UUID collectionId, String tag,
        String poolsJson, String finalJson, String rerankJson, List<UUID> retrievedChunkIds,
        List<UUID> initialChunkIds, List<UUID> fusedChunkIds, List<UUID> rerankedChunkIds) {
        String sql =
            """
                INSERT INTO retrieval_logs (id, query, collection_id, tag, pools, final, rerank, retrieved_chunk_ids, initial_chunk_ids, fused_chunk_ids, reranked_chunk_ids, created_at)
                VALUES (:id, :query, :collectionId, :tag, :pools::jsonb, :final::jsonb, :rerank::jsonb, :retrieved_chunk_ids, :initial_chunk_ids, :fused_chunk_ids, :reranked_chunk_ids, CURRENT_TIMESTAMP)
                """;

        jdbcClient.sql(sql).param("id", searchId).param("query", query)
            .param("collectionId", collectionId).param("tag", tag != null ? tag : "ALL")
            .param("pools", poolsJson).param("final", finalJson).param("rerank", rerankJson)
            .param("retrieved_chunk_ids",
                retrievedChunkIds != null ? retrievedChunkIds.toArray(new UUID[0]) : null)
            .param("initial_chunk_ids",
                initialChunkIds != null ? initialChunkIds.toArray(new UUID[0]) : null)
            .param("fused_chunk_ids",
                fusedChunkIds != null ? fusedChunkIds.toArray(new UUID[0]) : null)
            .param("reranked_chunk_ids",
                rerankedChunkIds != null ? rerankedChunkIds.toArray(new UUID[0]) : null)
            .update();
    }
}
