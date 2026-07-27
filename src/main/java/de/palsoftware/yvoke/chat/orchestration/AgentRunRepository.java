package de.palsoftware.yvoke.chat.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Optional;
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

    private static final String SELECT_COLUMNS = """
        SELECT id, conversation_id, message_id, profile_name, status, config::text AS config,
               review_rounds, final_verdict::text AS final_verdict, prompt_tokens,
               completion_tokens, total_tokens, cached_tokens, thought_tokens, error,
               started_at, finished_at
        FROM agent_runs
        """;

    private static AgentRun mapRow(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new AgentRun(rs.getObject("id", UUID.class),
            rs.getObject("conversation_id", UUID.class), rs.getObject("message_id", UUID.class),
            rs.getString("profile_name"), rs.getString("status"), rs.getString("config"),
            rs.getInt("review_rounds"), rs.getString("final_verdict"),
            (Integer) rs.getObject("prompt_tokens"), (Integer) rs.getObject("completion_tokens"),
            (Integer) rs.getObject("total_tokens"), (Integer) rs.getObject("cached_tokens"),
            (Integer) rs.getObject("thought_tokens"), rs.getString("error"),
            toInstant(rs.getObject("started_at")), toInstant(rs.getObject("finished_at")));
    }

    public Optional<AgentRun> findById(UUID id) {
        return jdbcClient.sql(SELECT_COLUMNS + " WHERE id = :id").param("id", id)
            .query(AgentRunRepository::mapRow).optional();
    }

    /** Most recent runs first, for the admin trace viewer. */
    public java.util.List<AgentRun> findRecent(int limit) {
        return jdbcClient.sql(SELECT_COLUMNS + " ORDER BY started_at DESC LIMIT :limit")
            .param("limit", limit).query(AgentRunRepository::mapRow).list();
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

    private static Instant toInstant(Object ts) {
        if (ts == null) {
            return null;
        }
        if (ts instanceof java.sql.Timestamp t) {
            return t.toInstant();
        }
        if (ts instanceof java.time.OffsetDateTime odt) {
            return odt.toInstant();
        }
        return null;
    }
}
