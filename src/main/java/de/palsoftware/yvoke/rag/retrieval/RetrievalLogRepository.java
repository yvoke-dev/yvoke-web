package de.palsoftware.yvoke.rag.retrieval;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

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

    public List<Map<String, Object>> findLatestTelemetry(String collection) {
        String sql =
            """
                SELECT l.pools::text AS pools_text, l.final::text AS final_text, l.rerank::text AS rerank_text
                FROM retrieval_logs l
                JOIN collections c ON l.collection_id = c.id
                WHERE LOWER(c.name) = LOWER(:collection)
                ORDER BY l.created_at DESC
                LIMIT 1
                """;
        return jdbcClient.sql(sql).param("collection", collection).query().listOfRows();
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
