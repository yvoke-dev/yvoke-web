package de.palsoftware.yvoke.chat.core.repository;

import de.palsoftware.yvoke.chat.core.model.Conversation;
import de.palsoftware.yvoke.chat.core.model.ConversationSource;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ConversationRepository {
    private static final Logger log = LoggerFactory.getLogger(ConversationRepository.class);

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public ConversationRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public void create(UUID id, UUID userId, String title, Map<String, Object> settings) {
        create(id, userId, title, settings, "web");
    }

    public void create(UUID id, UUID userId, String title, Map<String, Object> settings,
        String source) {
        ConversationSource.fromValue(source);
        String settingsJson = serializeSettings(settings);
        String sql =
            """
                INSERT INTO conversations (id, user_id, title, settings, source, created_at, updated_at, tags)
                VALUES (:id, :userId, :title, :settings::jsonb, :source, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '{}'::text[])
                """;
        jdbcClient.sql(sql).param("id", id).param("userId", userId).param("title", title)
            .param("settings", settingsJson).param("source", source).update();
    }

    public Optional<Conversation> findById(UUID id) {
        String sql =
            """
                SELECT id, user_id, title, settings::text AS settings_text, created_at, updated_at, tags, source
                FROM conversations
                WHERE id = :id
                """;
        return jdbcClient.sql(sql).param("id", id).query(new ConversationRowMapper(objectMapper))
            .optional();
    }

    public List<Conversation> listAll(UUID userId, int limit, int offset) {
        String sql =
            """
                SELECT id, user_id, title, settings::text AS settings_text, created_at, updated_at, tags, source
                FROM conversations
                WHERE (user_id = :userId OR 'public' = ANY(tags))
                ORDER BY created_at DESC, id ASC
                LIMIT :limit OFFSET :offset
                """;
        return jdbcClient.sql(sql).param("userId", userId).param("limit", limit)
            .param("offset", offset).query(new ConversationRowMapper(objectMapper)).list();
    }

    public List<Conversation> listByUserAndSource(UUID userId, String source, int limit,
        int offset) {
        ConversationSource.fromValue(source);
        String sql =
            """
                SELECT id, user_id, title, settings::text AS settings_text, created_at, updated_at, tags, source
                FROM conversations
                WHERE (user_id = :userId OR 'public' = ANY(tags)) AND source = :source
                ORDER BY updated_at DESC, id ASC
                LIMIT :limit OFFSET :offset
                """;
        return jdbcClient.sql(sql).param("userId", userId).param("source", source)
            .param("limit", limit).param("offset", offset)
            .query(new ConversationRowMapper(objectMapper)).list();
    }

    public void touch(UUID id) {
        String sql = """
            UPDATE conversations
            SET updated_at = CURRENT_TIMESTAMP
            WHERE id = :id
            """;
        jdbcClient.sql(sql).param("id", id).update();
    }

    public List<Conversation> listAllGlobal(int limit, int offset) {
        String sql =
            """
                SELECT id, user_id, title, settings::text AS settings_text, created_at, updated_at, tags, source
                FROM conversations
                ORDER BY created_at DESC, id ASC
                LIMIT :limit OFFSET :offset
                """;
        return jdbcClient.sql(sql).param("limit", limit).param("offset", offset)
            .query(new ConversationRowMapper(objectMapper)).list();
    }


    public void updateTitle(UUID id, String title) {
        String sql = """
            UPDATE conversations
            SET title = :title, updated_at = CURRENT_TIMESTAMP
            WHERE id = :id
            """;
        jdbcClient.sql(sql).param("id", id).param("title", title).update();
    }

    public void updateSettings(UUID id, Map<String, Object> settings) {
        String settingsJson = serializeSettings(settings);
        String sql = """
            UPDATE conversations
            SET settings = :settings::jsonb, updated_at = CURRENT_TIMESTAMP
            WHERE id = :id
            """;
        jdbcClient.sql(sql).param("id", id).param("settings", settingsJson).update();
    }

    public void delete(UUID id) {
        String sql = """
            DELETE FROM conversations
            WHERE id = :id
            """;
        jdbcClient.sql(sql).param("id", id).update();
    }

    private String serializeSettings(Map<String, Object> settings) {
        if (settings == null || settings.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(settings);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize conversation settings", e);
            return "{}";
        }
    }
}
