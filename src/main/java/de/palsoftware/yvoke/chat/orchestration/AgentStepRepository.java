package de.palsoftware.yvoke.chat.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AgentStepRepository {
    private static final Logger log = LoggerFactory.getLogger(AgentStepRepository.class);

    static final String STATUS_OK = "ok";
    static final String STATUS_FAILED = "failed";

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public AgentStepRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public void insert(UUID id, UUID agentRunId, int seq, String role, int round,
        String playbookName, String model, String thinkingLevel, String input, String output,
        Object messages, Object verdict, int promptTokens, int completionTokens, int totalTokens,
        int cachedTokens, int thoughtTokens) {
        insertStep(id, agentRunId, seq, role, round, playbookName, model, thinkingLevel, input,
            output, messages, verdict, promptTokens, completionTokens, totalTokens, cachedTokens,
            thoughtTokens, STATUS_OK, null);
    }

    /**
     * Records a step that threw, so the admin timeline shows where a run died.
     *
     * <p>
     * Kept as its own method rather than two more parameters on {@link #insert}: the successful
     * path has several callers and none of them should have to name a status.
     */
    public void insertFailed(UUID id, UUID agentRunId, int seq, String role, int round,
        String playbookName, String model, String thinkingLevel, String input, String partialOutput,
        String error) {
        insertStep(id, agentRunId, seq, role, round, playbookName, model, thinkingLevel, input,
            partialOutput, null, null, 0, 0, 0, 0, 0, STATUS_FAILED, error);
    }

    private void insertStep(UUID id, UUID agentRunId, int seq, String role, int round,
        String playbookName, String model, String thinkingLevel, String input, String output,
        Object messages, Object verdict, int promptTokens, int completionTokens, int totalTokens,
        int cachedTokens, int thoughtTokens, String status, String error) {
        String sql =
            """
                INSERT INTO agent_steps (id, agent_run_id, seq, role, round, playbook_name, model,
                    thinking_level, input, output, messages, verdict, prompt_tokens, completion_tokens,
                    total_tokens, cached_tokens, thought_tokens, status, error, created_at)
                VALUES (:id, :agentRunId, :seq, :role, :round, :playbookName, :model, :thinkingLevel,
                    :input, :output, :messages::jsonb, :verdict::jsonb, :promptTokens, :completionTokens,
                    :totalTokens, :cachedTokens, :thoughtTokens, :status, :error, CURRENT_TIMESTAMP)
                """;
        jdbcClient.sql(sql).param("id", id).param("agentRunId", agentRunId).param("seq", seq)
            .param("role", role).param("round", round).param("playbookName", playbookName)
            .param("model", model).param("thinkingLevel", thinkingLevel).param("input", input)
            .param("output", output).param("messages", toJson(messages))
            .param("verdict", toJson(verdict)).param("promptTokens", promptTokens)
            .param("completionTokens", completionTokens).param("totalTokens", totalTokens)
            .param("cachedTokens", cachedTokens).param("thoughtTokens", thoughtTokens)
            .param("status", status).param("error", error).update();
    }

    public List<AgentStep> findByRunId(UUID agentRunId) {
        String sql = """
            SELECT id, agent_run_id, seq, role, round, playbook_name, model, thinking_level, input,
                   output, messages::text AS messages, verdict::text AS verdict, prompt_tokens,
                   completion_tokens, total_tokens, cached_tokens, thought_tokens, created_at,
                   status, error
            FROM agent_steps WHERE agent_run_id = :agentRunId ORDER BY seq ASC
            """;
        return jdbcClient.sql(sql).param("agentRunId", agentRunId)
            .query((rs, n) -> new AgentStep(rs.getObject("id", UUID.class),
                rs.getObject("agent_run_id", UUID.class), rs.getInt("seq"), rs.getString("role"),
                rs.getInt("round"), rs.getString("playbook_name"), rs.getString("model"),
                rs.getString("thinking_level"), rs.getString("input"), rs.getString("output"),
                rs.getString("messages"), rs.getString("verdict"),
                (Integer) rs.getObject("prompt_tokens"),
                (Integer) rs.getObject("completion_tokens"), (Integer) rs.getObject("total_tokens"),
                (Integer) rs.getObject("cached_tokens"), (Integer) rs.getObject("thought_tokens"),
                toInstant(rs.getObject("created_at")), rs.getString("status"),
                rs.getString("error")))
            .list();
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Failed to serialize agent_step JSONB payload", e);
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
