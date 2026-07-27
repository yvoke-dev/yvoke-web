package de.palsoftware.yvoke.chat.core.repository;

import de.palsoftware.yvoke.chat.core.model.Feedback;


import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class FeedbackRepository {
    private final JdbcClient jdbcClient;

    public FeedbackRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void upsert(UUID messageId, int rating, String comment) {
        if (rating < -1 || rating > 5) {
            throw new IllegalArgumentException("Rating must be between -1 and 5: " + rating);
        }
        UUID id = UUID.randomUUID();
        String sql = """
            INSERT INTO message_feedback (id, message_id, rating, comment, created_at, updated_at)
            VALUES (:id, :messageId, :rating, :comment, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (message_id) DO UPDATE
            SET rating = EXCLUDED.rating,
                comment = EXCLUDED.comment,
                updated_at = CURRENT_TIMESTAMP
            """;

        jdbcClient.sql(sql).param("id", id).param("messageId", messageId).param("rating", rating)
            .param("comment", comment).update();
    }

    public List<Feedback> findByConversationId(UUID conversationId) {
        String sql = """
            SELECT f.id, f.message_id, f.rating, f.comment, f.created_at, f.updated_at
            FROM message_feedback f
            JOIN messages m ON m.id = f.message_id
            WHERE m.conversation_id = :conversationId
            """;
        return jdbcClient.sql(sql).param("conversationId", conversationId)
            .query(new FeedbackRowMapper()).list();
    }

    public Optional<Feedback> findByMessageId(UUID messageId) {
        String sql = """
            SELECT id, message_id, rating, comment, created_at, updated_at
            FROM message_feedback
            WHERE message_id = :messageId
            """;
        return jdbcClient.sql(sql).param("messageId", messageId).query(new FeedbackRowMapper())
            .optional();
    }
}
