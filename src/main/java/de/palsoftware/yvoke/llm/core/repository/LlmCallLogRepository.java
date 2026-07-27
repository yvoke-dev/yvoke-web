package de.palsoftware.yvoke.llm.core.repository;

import de.palsoftware.yvoke.llm.core.model.LlmCallLog;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class LlmCallLogRepository {

    private final JdbcClient jdbcClient;

    public LlmCallLogRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void insert(LlmCallLog log) {
        String sql =
            """
                INSERT INTO llm_call_logs (
                    id, conversation_id, message_id, agent_run_id, user_id, source, role, model,
                    prompt_tokens, completion_tokens, cached_tokens, thought_tokens, total_tokens,
                    prompt_price_per_million, completion_price_per_million, cached_price_per_million, thought_price_per_million,
                    total_cost, call_duration_ms, created_at,
                    gateway_cache_status, gateway_log_id, cost_avoided
                ) VALUES (
                    :id, :conversationId, :messageId, :agentRunId, :userId, :source, :role, :model,
                    :promptTokens, :completionTokens, :cachedTokens, :thoughtTokens, :totalTokens,
                    :promptPrice, :completionPrice, :cachedPrice, :thoughtPrice,
                    :totalCost, :durationMs, CURRENT_TIMESTAMP,
                    :gatewayCacheStatus, :gatewayLogId, :costAvoided
                )
                """;
        jdbcClient.sql(sql).param("id", log.id()).param("conversationId", log.conversationId())
            .param("messageId", log.messageId()).param("agentRunId", log.agentRunId())
            .param("userId", log.userId()).param("source", log.source()).param("role", log.role())
            .param("model", log.model()).param("promptTokens", log.promptTokens())
            .param("completionTokens", log.completionTokens())
            .param("cachedTokens", log.cachedTokens()).param("thoughtTokens", log.thoughtTokens())
            .param("totalTokens", log.totalTokens())
            .param("promptPrice", log.promptPricePerMillion())
            .param("completionPrice", log.completionPricePerMillion())
            .param("cachedPrice", log.cachedPricePerMillion())
            .param("thoughtPrice", log.thoughtPricePerMillion()).param("totalCost", log.totalCost())
            .param("durationMs", log.callDurationMs())
            .param("gatewayCacheStatus", log.gatewayCacheStatus())
            .param("gatewayLogId", log.gatewayLogId())
            .param("costAvoided", log.costAvoided() != null ? log.costAvoided() : BigDecimal.ZERO)
            .update();
    }

    public List<Map<String, Object>> getModelUsageSummaries() {
        String sql = """
            SELECT
                m.model,
                COUNT(*) AS call_count,
                COALESCE(SUM(m.prompt_tokens), 0) AS total_prompt_tokens,
                COALESCE(SUM(m.completion_tokens), 0) AS total_completion_tokens,
                COALESCE(SUM(m.cached_tokens), 0) AS total_cached_tokens,
                COALESCE(SUM(m.thought_tokens), 0) AS total_thought_tokens,
                COALESCE(SUM(m.total_tokens), 0) AS total_tokens,
                COALESCE(SUM(m.total_cost), 0) AS total_cost
            FROM llm_call_logs m
            GROUP BY m.model
            """;
        return jdbcClient.sql(sql).query().listOfRows();
    }

    public BigDecimal getTotalCostGlobal() {
        String sql = "SELECT COALESCE(SUM(total_cost), 0) FROM llm_call_logs";
        return jdbcClient.sql(sql).query(BigDecimal.class).single();
    }
}
