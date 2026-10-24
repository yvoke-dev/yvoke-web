package de.palsoftware.yvoke.chat.core.repository;

import de.palsoftware.yvoke.chat.core.model.Conversation;
import de.palsoftware.yvoke.chat.core.model.ConversationSource;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
        create(id, userId, title, settings, "web", null);
    }

    public void create(UUID id, UUID userId, String title, Map<String, Object> settings,
        String source) {
        create(id, userId, title, settings, source, null);
    }

    public void create(UUID id, UUID userId, String title, Map<String, Object> settings,
        String source, String area) {
        ConversationSource.fromValue(source);
        String settingsJson = serializeSettings(settings);
        String sql =
            """
                INSERT INTO conversations (id, user_id, title, settings, source, area, created_at, updated_at, tags)
                VALUES (:id, :userId, :title, :settings::jsonb, :source, :area, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '{}'::text[])
                """;
        jdbcClient.sql(sql).param("id", id).param("userId", userId).param("title", title)
            .param("settings", settingsJson).param("source", source).param("area", area).update();
    }

    public Optional<Conversation> findById(UUID id) {
        String sql =
            """
                SELECT id, user_id, title, settings::text AS settings_text, created_at, updated_at, tags, source, area
                FROM conversations
                WHERE id = :id
                """;
        return jdbcClient.sql(sql).param("id", id).query(new ConversationRowMapper(objectMapper))
            .optional();
    }

    public void updateAreaIfAbsent(UUID id, String area) {
        if (area == null || area.isBlank()) {
            return;
        }
        String sql = """
            UPDATE conversations
            SET area = :area
            WHERE id = :id AND area IS NULL
            """;
        jdbcClient.sql(sql).param("id", id).param("area", area.trim()).update();
    }

    public void lockForUpdate(UUID id) {
        String sql = """
            SELECT id
            FROM conversations
            WHERE id = :id
            FOR UPDATE
            """;
        jdbcClient.sql(sql).param("id", id).query(UUID.class).optional();
    }

    public List<Conversation> listAll(UUID userId, int limit, int offset) {
        return listAll(userId, limit, offset, null);
    }

    public List<Conversation> listAll(UUID userId, int limit, int offset,
        Set<String> allowedAreasLower) {
        StringBuilder sql = new StringBuilder(
            """
                SELECT id, user_id, title, settings::text AS settings_text, created_at, updated_at, tags, source, area
                FROM conversations
                WHERE (user_id = :userId OR 'public' = ANY(tags))
                """);

        if (allowedAreasLower != null) {
            if (allowedAreasLower.isEmpty()) {
                sql.append(" AND area IS NULL");
            } else {
                sql.append(" AND (area IS NULL OR LOWER(area) IN (:allowedAreasLower))");
            }
        }

        sql.append(" ORDER BY created_at DESC, id ASC LIMIT :limit OFFSET :offset");

        var spec = jdbcClient.sql(sql.toString()).param("userId", userId).param("limit", limit)
            .param("offset", offset);

        if (allowedAreasLower != null && !allowedAreasLower.isEmpty()) {
            spec = spec.param("allowedAreasLower", allowedAreasLower);
        }

        return spec.query(new ConversationRowMapper(objectMapper)).list();
    }

    /**
     * Owner-scoped, deliberately unlike {@link #listAll} above.
     *
     * <p>
     * This feeds the desktop API only ({@code DesktopSyncService.listConversations}), whose
     * ownership check is a bare {@code conversation.userId() == caller} with no {@code public} and
     * no admin carve-out — so the sibling query's {@code OR 'public' = ANY(tags)} listed rows that
     * every follow-up call then rejected with 403, and the listed title is auto-generated from the
     * owner's first message, i.e. their question text. The web sidebar keeps the wider rule because
     * {@code ChatConversationService} actually serves those reads.
     */
    public List<Conversation> listByUserAndSource(UUID userId, String source, int limit,
        int offset) {
        return listByUserAndSource(userId, source, limit, offset, null);
    }

    public List<Conversation> listByUserAndSource(UUID userId, String source, int limit, int offset,
        Set<String> allowedAreasLower) {
        ConversationSource.fromValue(source);
        StringBuilder sql = new StringBuilder(
            """
                SELECT id, user_id, title, settings::text AS settings_text, created_at, updated_at, tags, source, area
                FROM conversations
                WHERE user_id = :userId AND source = :source
                """);

        if (allowedAreasLower != null) {
            if (allowedAreasLower.isEmpty()) {
                sql.append(" AND area IS NULL");
            } else {
                sql.append(" AND (area IS NULL OR LOWER(area) IN (:allowedAreasLower))");
            }
        }

        sql.append(" ORDER BY updated_at DESC, id ASC LIMIT :limit OFFSET :offset");

        var spec = jdbcClient.sql(sql.toString()).param("userId", userId).param("source", source)
            .param("limit", limit).param("offset", offset);

        if (allowedAreasLower != null && !allowedAreasLower.isEmpty()) {
            spec = spec.param("allowedAreasLower", allowedAreasLower);
        }

        return spec.query(new ConversationRowMapper(objectMapper)).list();
    }

    public void touch(UUID id) {
        String sql = """
            UPDATE conversations
            SET updated_at = CURRENT_TIMESTAMP
            WHERE id = :id
            """;
        jdbcClient.sql(sql).param("id", id).update();
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
