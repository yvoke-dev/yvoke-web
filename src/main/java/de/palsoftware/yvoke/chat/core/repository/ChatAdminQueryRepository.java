package de.palsoftware.yvoke.chat.core.repository;

import de.palsoftware.yvoke.document.core.repository.ChunkSurfacingMessageLookup;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Admin-facing read/update queries over chat-domain tables (conversations, messages,
 * message_feedback). Extracted from the former shared.web.admin.AdminQueryRepository.
 */
@Repository
public class ChatAdminQueryRepository implements ChunkSurfacingMessageLookup {

    private final JdbcClient jdbcClient;

    public ChatAdminQueryRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public record FeedbackComment(String feedbackId, String messageId, int rating, String comment,
        OffsetDateTime createdAt, String queryText, String conversationId, boolean reviewed,
        String notes) {}

    public record AdminConversation(UUID id, UUID userId, String userDisplayName, String userEmail,
        String title, String source, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

    public long countConversations() {
        return jdbcClient.sql("SELECT COUNT(*) FROM conversations").query(Long.class).single();
    }

    public List<AdminConversation> listConversations(int limit, int offset) {
        String sql =
            """
                SELECT c.id, c.user_id, u.display_name, u.email, c.title, c.source, c.created_at, c.updated_at
                FROM conversations c
                LEFT JOIN users u ON c.user_id = u.id
                ORDER BY c.created_at DESC, c.id DESC
                LIMIT :limit OFFSET :offset
                """;
        return jdbcClient.sql(sql).param("limit", limit).param("offset", offset)
            .query((rs, rowNum) -> new AdminConversation(rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class), rs.getString("display_name"),
                rs.getString("email"), rs.getString("title"), rs.getString("source"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class)))
            .list();
    }

    @Override
    public List<Map<String, Object>> findMessagesSurfacingChunk(UUID chunkId) {
        String sql =
            """
                SELECT m.id::text AS message_id, m.conversation_id::text AS conversation_id, m.role, m.content, m.created_at,
                       c.title AS conversation_title
                FROM messages m
                JOIN conversations c ON m.conversation_id = c.id
                WHERE :chunkId = ANY(m.retrieved_chunk_ids)
                ORDER BY m.created_at DESC
                LIMIT 10
                """;
        return jdbcClient.sql(sql).param("chunkId", chunkId).query((rs, rowNum) -> {
            Map<String, Object> map = new HashMap<>();
            map.put("message_id", rs.getString("message_id"));
            map.put("conversation_id", rs.getString("conversation_id"));
            map.put("role", rs.getString("role"));
            map.put("content", rs.getString("content"));
            map.put("created_at", rs.getObject("created_at", OffsetDateTime.class));
            map.put("conversation_title", rs.getString("conversation_title"));
            return map;
        }).list();
    }

    public long countFeedbackByRating(int rating) {
        return jdbcClient.sql("SELECT COUNT(*) FROM message_feedback WHERE rating = :rating")
            .param("rating", rating).query(Long.class).single();
    }

    public long countFilteredFeedback(String rating, Boolean reviewed, String timeRange) {
        StringBuilder sql = new StringBuilder("""
            SELECT COUNT(*)
            FROM message_feedback f
            JOIN messages m ON f.message_id = m.id
            WHERE 1=1
            """);
        Map<String, Object> params = new HashMap<>();
        appendFilters(sql, params, rating, reviewed, timeRange);
        return jdbcClient.sql(sql.toString()).params(params).query(Long.class).single();
    }

    public List<FeedbackComment> listFeedback(String rating, Boolean reviewed, String timeRange,
        String sort, int size, int offset) {
        StringBuilder sql = new StringBuilder(
            """
                SELECT f.id::text AS feedback_id, f.message_id::text AS message_id, f.rating, f.comment, f.created_at,
                       f.reviewed, f.notes, m.content AS query_text, m.conversation_id::text AS conversation_id
                FROM message_feedback f
                JOIN messages m ON f.message_id = m.id
                WHERE 1=1
                """);
        Map<String, Object> params = new HashMap<>();
        params.put("limit", size);
        params.put("offset", offset);
        appendFilters(sql, params, rating, reviewed, timeRange);

        if ("oldest".equalsIgnoreCase(sort)) {
            sql.append(" ORDER BY f.created_at ASC, f.id ASC");
        } else {
            sql.append(" ORDER BY f.created_at DESC, f.id DESC");
        }
        sql.append(" LIMIT :limit OFFSET :offset");

        return jdbcClient.sql(sql.toString()).params(params)
            .query((rs, rowNum) -> new FeedbackComment(rs.getString("feedback_id"),
                rs.getString("message_id"), rs.getInt("rating"), rs.getString("comment"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getString("query_text"),
                rs.getString("conversation_id"), rs.getBoolean("reviewed"), rs.getString("notes")))
            .list();
    }

    public void setFeedbackReviewed(UUID id, boolean reviewed) {
        jdbcClient.sql(
            "UPDATE message_feedback SET reviewed = :reviewed, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
            .param("reviewed", reviewed).param("id", id).update();
    }

    public void setFeedbackNotes(UUID id, String notes) {
        jdbcClient.sql(
            "UPDATE message_feedback SET notes = :notes, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
            .param("notes", notes != null && !notes.isBlank() ? notes.trim() : null).param("id", id)
            .update();
    }

    private void appendFilters(StringBuilder sql, Map<String, Object> params, String rating,
        Boolean reviewed, String timeRange) {
        if ("good".equalsIgnoreCase(rating)) {
            sql.append(" AND f.rating = 1");
        } else if ("bad".equalsIgnoreCase(rating)) {
            sql.append(" AND f.rating = -1");
        }
        if (reviewed != null) {
            sql.append(" AND f.reviewed = :reviewed");
            params.put("reviewed", reviewed);
        }
        OffsetDateTime cutoff = null;
        if ("day".equalsIgnoreCase(timeRange)) {
            cutoff = OffsetDateTime.now().minusDays(1);
        } else if ("week".equalsIgnoreCase(timeRange)) {
            cutoff = OffsetDateTime.now().minusWeeks(1);
        } else if ("month".equalsIgnoreCase(timeRange)) {
            cutoff = OffsetDateTime.now().minusMonths(1);
        }
        if (cutoff != null) {
            sql.append(" AND f.created_at >= :timeCutoff");
            params.put("timeCutoff", cutoff);
        }
    }
}
