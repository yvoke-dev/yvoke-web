package de.palsoftware.yvoke.chat.core.repository;

import de.palsoftware.yvoke.chat.core.model.Message;
import de.palsoftware.yvoke.chat.core.model.MessageRole;
import de.palsoftware.yvoke.rag.core.service.CitationVerifier.CitationCheckResult;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MessageRepository {
    private static final Logger log = LoggerFactory.getLogger(MessageRepository.class);

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public MessageRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public void save(Message message) {
        MessageRole.fromValue(message.role());
        UUID[] chunkIdsArray = null;
        if (message.retrievedChunkIds() != null && !message.retrievedChunkIds().isEmpty()) {
            chunkIdsArray = message.retrievedChunkIds().toArray(new UUID[0]);
        }

        String citationsJson = null;
        if (message.citations() != null && !message.citations().isEmpty()) {
            try {
                citationsJson = objectMapper.writeValueAsString(message.citations());
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize citations for message {}", message.id(), e);
            }
        }

        // clock_timestamp(), not CURRENT_TIMESTAMP: the latter is the *transaction* start time, so
        // a caller saving several messages in one transaction (DesktopSyncService.appendMessages
        // posts a whole turn as one batch) stamped every row identically. findByConversationId
        // then fell through its ORDER BY tie-break to the random v4 id and returned the turn
        // scrambled.
        String sql =
            """
                INSERT INTO messages (id, conversation_id, role, content, playbook, retrieved_chunk_ids, citations, prompt_tokens, completion_tokens, total_tokens, cached_tokens, thought_tokens, status, model, created_at, updated_at)
                VALUES (:id, :conversationId, :role, :content, :playbook, :retrievedChunkIds::uuid[], :citations::jsonb, :promptTokens, :completionTokens, :totalTokens, :cachedTokens, :thoughtTokens, :status, :model, clock_timestamp(), clock_timestamp())
                """;

        jdbcClient.sql(sql).param("id", message.id())
            .param("conversationId", message.conversationId()).param("role", message.role())
            .param("content", message.content()).param("playbook", message.playbook())
            .param("retrievedChunkIds", chunkIdsArray).param("citations", citationsJson)
            .param("promptTokens", message.promptTokens())
            .param("completionTokens", message.completionTokens())
            .param("totalTokens", message.totalTokens())
            .param("cachedTokens", message.cachedTokens())
            .param("thoughtTokens", message.thoughtTokens()).param("status", message.status())
            .param("model", message.model()).update();
    }

    public List<Message> findByConversationId(UUID conversationId, int limit, int offset) {
        String sql =
            """
                SELECT id, conversation_id, role, content, playbook, retrieved_chunk_ids, citations::text AS citations_text, prompt_tokens, completion_tokens, total_tokens, cached_tokens, thought_tokens, status, model, created_at
                FROM messages
                WHERE conversation_id = :conversationId
                ORDER BY created_at ASC, id ASC
                LIMIT :limit OFFSET :offset
                """;

        return jdbcClient.sql(sql).param("conversationId", conversationId).param("limit", limit)
            .param("offset", offset).query(new MessageRowMapper(objectMapper)).list();
    }

    public Optional<Message> findById(UUID id) {
        String sql =
            """
                SELECT id, conversation_id, role, content, playbook, retrieved_chunk_ids, citations::text AS citations_text, prompt_tokens, completion_tokens, total_tokens, cached_tokens, thought_tokens, status, model, created_at
                FROM messages
                WHERE id = :id
                """;

        return jdbcClient.sql(sql).param("id", id).query(new MessageRowMapper(objectMapper))
            .optional();
    }

    public void updateStatus(UUID messageId, String status) {
        String sql = """
            UPDATE messages
            SET status = :status, updated_at = CURRENT_TIMESTAMP
            WHERE id = :id
            """;
        int rows = jdbcClient.sql(sql).param("status", status).param("id", messageId).update();
        if (rows == 0) {
            log.warn("updateStatus modified 0 rows for message id: {}", messageId);
        }
    }

    public void updateContentAndStatus(UUID id, String content, List<UUID> retrievedChunkIds,
        List<CitationCheckResult> citations, Integer promptTokens, Integer completionTokens,
        Integer totalTokens, Integer cachedTokens, Integer thoughtTokens, String status,
        String model) {
        UUID[] chunkIdsArray = null;
        if (retrievedChunkIds != null && !retrievedChunkIds.isEmpty()) {
            chunkIdsArray = retrievedChunkIds.toArray(new UUID[0]);
        }

        String citationsJson = null;
        if (citations != null && !citations.isEmpty()) {
            try {
                citationsJson = objectMapper.writeValueAsString(citations);
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize citations for message {}", id, e);
            }
        }

        String sql = """
            UPDATE messages
            SET content = :content,
                retrieved_chunk_ids = :retrievedChunkIds::uuid[],
                citations = :citations::jsonb,
                prompt_tokens = :promptTokens,
                completion_tokens = :completionTokens,
                total_tokens = :totalTokens,
                cached_tokens = :cachedTokens,
                thought_tokens = :thoughtTokens,
                status = :status,
                model = COALESCE(:model, model),
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :id
            """;

        int rows = jdbcClient.sql(sql).param("id", id).param("content", content)
            .param("retrievedChunkIds", chunkIdsArray).param("citations", citationsJson)
            .param("promptTokens", promptTokens).param("completionTokens", completionTokens)
            .param("totalTokens", totalTokens).param("cachedTokens", cachedTokens)
            .param("thoughtTokens", thoughtTokens).param("status", status).param("model", model)
            .update();
        if (rows == 0) {
            log.warn("updateContentAndStatus modified 0 rows for message id: {}", id);
        }
    }

    public void resetGeneratingMessages() {
        String sql =
            """
                UPDATE messages
                SET status = 'error', content = '⚠️ *[Generation interrupted (system restart)]*', updated_at = CURRENT_TIMESTAMP
                WHERE status = 'generating'
                """;
        int updated = jdbcClient.sql(sql).update();
        if (updated > 0) {
            log.info("Reset {} messages stuck in 'generating' status to 'error'", updated);
        }
    }

    public long countByConversationId(UUID conversationId) {
        String sql = """
            SELECT count(*) FROM messages
            WHERE conversation_id = :conversationId
            """;
        return jdbcClient.sql(sql).param("conversationId", conversationId).query(Long.class)
            .single();
    }
}
