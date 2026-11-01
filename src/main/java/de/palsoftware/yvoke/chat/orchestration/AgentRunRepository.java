package de.palsoftware.yvoke.chat.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.shared.config.JdbcMappers;
import jakarta.annotation.Nullable;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AgentRunRepository {
    private static final Logger log = LoggerFactory.getLogger(AgentRunRepository.class);

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public AgentRunRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    /** Inserts a new run with status {@code running}. */
    public void create(UUID id, UUID conversationId, String profileName, Object config) {
        String sql =
            """
                INSERT INTO agent_runs (id, conversation_id, profile_name, status, config, started_at)
                VALUES (:id, :conversationId, :profileName, 'running', :config::jsonb, CURRENT_TIMESTAMP)
                """;
        jdbcClient.sql(sql).param("id", id).param("conversationId", conversationId)
            .param("profileName", profileName).param("config", toJson(config)).update();
    }

    /** Finalises a run: links the delivered message, records verdict, tokens and finished_at. */
    public void finish(UUID id, UUID messageId, String status, int reviewRounds,
        Object finalVerdict, int promptTokens, int completionTokens, int totalTokens,
        int cachedTokens, int thoughtTokens, String error) {
        String sql = """
            UPDATE agent_runs
            SET message_id = :messageId,
                status = :status,
                review_rounds = :reviewRounds,
                final_verdict = :finalVerdict::jsonb,
                prompt_tokens = :promptTokens,
                completion_tokens = :completionTokens,
                total_tokens = :totalTokens,
                cached_tokens = :cachedTokens,
                thought_tokens = :thoughtTokens,
                error = :error,
                finished_at = CURRENT_TIMESTAMP
            WHERE id = :id
            """;
        int rows = jdbcClient.sql(sql).param("id", id).param("messageId", messageId)
            .param("status", status).param("reviewRounds", reviewRounds)
            .param("finalVerdict", toJson(finalVerdict)).param("promptTokens", promptTokens)
            .param("completionTokens", completionTokens).param("totalTokens", totalTokens)
            .param("cachedTokens", cachedTokens).param("thoughtTokens", thoughtTokens)
            .param("error", error).update();
        if (rows == 0) {
            log.warn("finish() modified 0 rows for agent_run id: {}", id);
        }
    }

    /** Links the delivered assistant message id to this run. */
    public void updateAssistantMessageId(UUID id, UUID assistantMessageId) {
        String sql = """
            UPDATE agent_runs
            SET assistant_message_id = :assistantMessageId
            WHERE id = :id
            """;
        jdbcClient.sql(sql).param("id", id).param("assistantMessageId", assistantMessageId)
            .update();
    }

    private static final String SELECT_COLUMNS =
        """
            SELECT id, conversation_id, message_id, assistant_message_id, profile_name, status, config::text AS config,
                   review_rounds, final_verdict::text AS final_verdict, prompt_tokens,
                   completion_tokens, total_tokens, cached_tokens, thought_tokens, error,
                   started_at, finished_at
            FROM agent_runs
            """;

    private static AgentRun mapRow(ResultSet rs, int n) throws SQLException {
        return new AgentRun(rs.getObject("id", UUID.class),
            rs.getObject("conversation_id", UUID.class), rs.getObject("message_id", UUID.class),
            rs.getObject("assistant_message_id", UUID.class), rs.getString("profile_name"),
            rs.getString("status"), rs.getString("config"), rs.getInt("review_rounds"),
            rs.getString("final_verdict"), (Integer) rs.getObject("prompt_tokens"),
            (Integer) rs.getObject("completion_tokens"), (Integer) rs.getObject("total_tokens"),
            (Integer) rs.getObject("cached_tokens"), (Integer) rs.getObject("thought_tokens"),
            rs.getString("error"), JdbcMappers.toInstant(rs.getObject("started_at")),
            JdbcMappers.toInstant(rs.getObject("finished_at")));
    }

    public Optional<AgentRun> findById(UUID id) {
        return jdbcClient.sql(SELECT_COLUMNS + " WHERE id = :id").param("id", id)
            .query(AgentRunRepository::mapRow).optional();
    }

    public Optional<AgentRun> findByMessageId(UUID messageId) {
        return jdbcClient.sql(SELECT_COLUMNS
            + " WHERE assistant_message_id = :messageId OR message_id = :messageId ORDER BY started_at DESC, id DESC LIMIT 1")
            .param("messageId", messageId).query(AgentRunRepository::mapRow).optional();
    }

    /** Most recent runs first, for the admin trace viewer. */
    public List<AgentRun> findRecent(int limit) {
        return findRecent(limit, null);
    }

    public List<AgentRun> findRecent(int limit, @Nullable Set<String> allowedAreasLower) {
        if (allowedAreasLower != null && allowedAreasLower.isEmpty()) {
            return List.of();
        }
        if (allowedAreasLower == null) {
            return jdbcClient.sql(SELECT_COLUMNS + " ORDER BY started_at DESC LIMIT :limit")
                .param("limit", limit).query(AgentRunRepository::mapRow).list();
        }
        String sql =
            """
                SELECT r.id, r.conversation_id, r.message_id, r.assistant_message_id, r.profile_name, r.status,
                       r.config::text AS config, r.review_rounds, r.final_verdict::text AS final_verdict,
                       r.prompt_tokens, r.completion_tokens, r.total_tokens, r.cached_tokens, r.thought_tokens,
                       r.error, r.started_at, r.finished_at
                FROM agent_runs r
                LEFT JOIN conversations c ON r.conversation_id = c.id
                LEFT JOIN orchestrator_profiles op ON r.profile_name = op.name
                WHERE COALESCE(c.area, op.area) IS NOT NULL
                  AND LOWER(COALESCE(c.area, op.area)) IN (:allowedAreas)
                ORDER BY r.started_at DESC LIMIT :limit
                """;
        return jdbcClient.sql(sql).param("limit", limit).param("allowedAreas", allowedAreasLower)
            .query(AgentRunRepository::mapRow).list();
    }

    public Optional<String> findRunArea(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        String sql = """
            SELECT COALESCE(c.area, op.area)
            FROM agent_runs r
            LEFT JOIN conversations c ON r.conversation_id = c.id
            LEFT JOIN orchestrator_profiles op ON r.profile_name = op.name
            WHERE r.id = :id
            """;
        return jdbcClient.sql(sql).param("id", id).query(String.class).optional();
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Failed to serialize agent_run JSONB payload", e);
            return null;
        }
    }
}
