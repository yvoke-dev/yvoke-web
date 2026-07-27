package de.palsoftware.yvoke.chat.core.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Data access for the cost-monitoring dashboard (MNT-03 / Wave 3.2). Owns <b>all</b> raw cost SQL
 * moved out of {@code CostCalculationService}: the filter-dropdown lookups (available users /
 * conversations / models / sources / playbooks / MAS profiles), the per-model {@code calculate*}
 * token sums, the cost-explorer detail rows (RAW/MESSAGE per-call + CONVERSATION grouped), the
 * per-conversation message rows, the used-model usage counts, and the top-N token rows. Every
 * method returns untyped {@code List<Map<String,Object>>}/token rows; the service keeps the
 * {@link de.palsoftware.yvoke.chat.core.service.PricingCalculator} application and in-heap grouping
 * (tokens-in-SQL, cost-in-Java — SQL-side cost summation can't faithfully replicate the per-bucket
 * scale-8 rounding). Reads run within the caller's read-only transaction.
 */
@Repository
public class CostQueryRepository {

    private final JdbcClient jdbcClient;

    public CostQueryRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<Map<String, Object>> availableUsers() {
        return jdbcClient
            .sql("SELECT id, display_name, email FROM users ORDER BY display_name ASC NULLS LAST")
            .query().listOfRows();
    }

    public List<Map<String, Object>> availableConversations() {
        return jdbcClient
            .sql("SELECT id, title FROM conversations ORDER BY created_at DESC LIMIT 200").query()
            .listOfRows();
    }

    public List<String> availableMasProfiles() {
        return jdbcClient.sql("""
            SELECT DISTINCT ar.profile_name
            FROM agent_runs ar
            JOIN llm_call_logs l ON l.agent_run_id = ar.id
            WHERE ar.profile_name IS NOT NULL AND ar.profile_name != ''
            ORDER BY ar.profile_name ASC
            """).query(String.class).list();
    }

    public List<String> availableModels() {
        return jdbcClient.sql("""
            SELECT DISTINCT model FROM (
                SELECT model FROM llm_call_logs WHERE model IS NOT NULL AND model != ''
                UNION
                SELECT model_name AS model FROM chat_model_pricing
            ) all_m ORDER BY model ASC
            """).query(String.class).list();
    }

    public List<String> availableSources() {
        return jdbcClient
            .sql(
                """
                    SELECT DISTINCT item_type FROM (
                        SELECT COALESCE(role, source) AS item_type FROM llm_call_logs WHERE role IS NOT NULL OR source IS NOT NULL
                    ) t WHERE item_type IS NOT NULL AND item_type != '' ORDER BY item_type ASC
                    """)
            .query(String.class).list();
    }

    public List<String> availablePlaybooks() {
        return jdbcClient
            .sql(
                """
                    SELECT DISTINCT playbook FROM (
                        SELECT ar.profile_name AS playbook FROM agent_runs ar WHERE ar.profile_name IS NOT NULL AND ar.profile_name != ''
                        UNION
                        SELECT name AS playbook FROM playbooks
                        UNION
                        SELECT c.settings->>'playbook' AS playbook FROM conversations c WHERE c.settings->>'playbook' IS NOT NULL AND c.settings->>'playbook' != ''
                        UNION
                        SELECT c.settings->>'profile' AS playbook FROM conversations c WHERE c.settings->>'profile' IS NOT NULL AND c.settings->>'profile' != ''
                    ) all_pb ORDER BY playbook ASC
                    """)
            .query(String.class).list();
    }

    // -----------------------------------------------------------------------------------------------
    // Per-model token sums for the summary cards (calculate* family). Each returns one row per
    // model
    // (model, p_tokens, c_tokens, ca_tokens, t_tokens) over llm_call_logs; the service applies
    // PricingCalculator. SQL moved verbatim from CostCalculationService; the WHERE differs per
    // scope.
    // -----------------------------------------------------------------------------------------------

    /** Global per-model token sums over the optional half-open UTC date range. */
    public List<Map<String, Object>> globalModelTokenRows(LocalDate startDate, LocalDate endDate) {
        Map<String, Object> params = new HashMap<>();
        String dateFilter =
            new CostFilters(params).dateRange("created_at", startDate, endDate).sql();
        String sql = """
            SELECT
                model,
                -- Grouped on as well, so each row is uniformly replayed or not and the service
                -- can zero the cost of gateway-replayed calls while keeping their token counts.
                gateway_cache_status,
                SUM(prompt_tokens) AS p_tokens,
                SUM(completion_tokens) AS c_tokens,
                SUM(cached_tokens) AS ca_tokens,
                SUM(thought_tokens) AS t_tokens
            FROM llm_call_logs
            WHERE model IS NOT NULL AND model != ''""" + dateFilter
            + " GROUP BY model, gateway_cache_status";
        return runRows(sql, params);
    }

    /** Per-model token sums for one user over the optional half-open UTC date range. */
    public List<Map<String, Object>> userModelTokenRows(UUID userId, LocalDate startDate,
        LocalDate endDate) {
        Map<String, Object> params = new HashMap<>();
        params.put("userId", userId);
        String dateFilter =
            new CostFilters(params).dateRange("created_at", startDate, endDate).sql();
        String sql = """
            SELECT
                model,
                -- Grouped on as well, so each row is uniformly replayed or not and the service
                -- can zero the cost of gateway-replayed calls while keeping their token counts.
                gateway_cache_status,
                SUM(prompt_tokens) AS p_tokens,
                SUM(completion_tokens) AS c_tokens,
                SUM(cached_tokens) AS ca_tokens,
                SUM(thought_tokens) AS t_tokens
            FROM llm_call_logs
            WHERE model IS NOT NULL AND model != '' AND user_id = :userId""" + dateFilter
            + " GROUP BY model, gateway_cache_status";
        return runRows(sql, params);
    }

    /** Per-model token sums for one conversation over the optional half-open UTC date range. */
    public List<Map<String, Object>> conversationModelTokenRows(UUID conversationId,
        LocalDate startDate, LocalDate endDate) {
        Map<String, Object> params = new HashMap<>();
        params.put("convId", conversationId);
        String dateFilter =
            new CostFilters(params).dateRange("created_at", startDate, endDate).sql();
        String sql = """
            SELECT
                model,
                -- Grouped on as well, so each row is uniformly replayed or not and the service
                -- can zero the cost of gateway-replayed calls while keeping their token counts.
                gateway_cache_status,
                SUM(prompt_tokens) AS p_tokens,
                SUM(completion_tokens) AS c_tokens,
                SUM(cached_tokens) AS ca_tokens,
                SUM(thought_tokens) AS t_tokens
            FROM llm_call_logs
            WHERE model IS NOT NULL AND model != '' AND conversation_id = :convId""" + dateFilter
            + " GROUP BY model, gateway_cache_status";
        return runRows(sql, params);
    }

    /** Per-model token sums for one agent run (no date filter). */
    public List<Map<String, Object>> agentRunModelTokenRows(UUID agentRunId) {
        Map<String, Object> params = new HashMap<>();
        params.put("runId", agentRunId);
        String sql = """
            SELECT
                model,
                -- Grouped on as well, so each row is uniformly replayed or not and the service
                -- can zero the cost of gateway-replayed calls while keeping their token counts.
                gateway_cache_status,
                SUM(prompt_tokens) AS p_tokens,
                SUM(completion_tokens) AS c_tokens,
                SUM(cached_tokens) AS ca_tokens,
                SUM(thought_tokens) AS t_tokens
            FROM llm_call_logs
            WHERE model IS NOT NULL AND model != '' AND agent_run_id = :runId
            GROUP BY model, gateway_cache_status
            """;
        return runRows(sql, params);
    }

    /** Per-model token sums for one MAS profile (joins agent_runs) over the optional date range. */
    public List<Map<String, Object>> masProfileModelTokenRows(String profileName,
        LocalDate startDate, LocalDate endDate) {
        Map<String, Object> params = new HashMap<>();
        params.put("profileName", profileName);
        String dateFilter =
            new CostFilters(params).dateRange("l.created_at", startDate, endDate).sql();
        String sql = """
            SELECT
                l.model,
                -- Grouped on as well, so each row is uniformly replayed or not and the service
                -- can zero the cost of gateway-replayed calls while keeping their token counts.
                l.gateway_cache_status AS gateway_cache_status,
                SUM(l.prompt_tokens) AS p_tokens,
                SUM(l.completion_tokens) AS c_tokens,
                SUM(l.cached_tokens) AS ca_tokens,
                SUM(l.thought_tokens) AS t_tokens
            FROM llm_call_logs l
            JOIN agent_runs ar ON l.agent_run_id = ar.id
            WHERE l.model IS NOT NULL AND l.model != '' AND ar.profile_name = :profileName"""
            + dateFilter + " GROUP BY l.model, l.gateway_cache_status";
        return runRows(sql, params);
    }

    /**
     * Per-call rows for one conversation (id, role, effective_model, token buckets, created_at),
     * ordered oldest-first. The service prices each row via PricingCalculator.
     */
    public List<Map<String, Object>> messageCostRows(UUID conversationId) {
        String sql = """
            SELECT
                l.id,
                COALESCE(l.role, 'assistant') AS role,
                l.model AS effective_model,
                COALESCE(l.prompt_tokens, 0) AS prompt_tokens,
                COALESCE(l.completion_tokens, 0) AS completion_tokens,
                COALESCE(l.cached_tokens, 0) AS cached_tokens,
                COALESCE(l.thought_tokens, 0) AS thought_tokens,
                -- The explorer re-prices from token counts at CURRENT rates rather than reading
                -- total_cost, so it has to know which calls the gateway replayed: those reported
                -- tokens were never purchased. Without this the dashboard would keep charging for
                -- cache hits even though the persisted ledger no longer does.
                l.gateway_cache_status AS gateway_cache_status,
                l.created_at
            FROM llm_call_logs l
            WHERE l.conversation_id = :convId
            ORDER BY l.created_at ASC
            """;
        return jdbcClient.sql(sql).param("convId", conversationId).query().listOfRows();
    }

    /** Per-model call counts over all non-empty models (drives the used-models pricing status). */
    public List<Map<String, Object>> modelUsageCounts() {
        return jdbcClient.sql("""
            SELECT model, COUNT(*) AS cnt FROM llm_call_logs
            WHERE model IS NOT NULL AND model != ''
            GROUP BY model
            """).query().listOfRows();
    }

    // -----------------------------------------------------------------------------------------------
    // Cost-explorer detail rows (RAW/MESSAGE share one per-call query; CONVERSATION groups in SQL).
    // Both are hard-capped at rowCap (PRF-01). Token-in-SQL / cost-in-Java: the service groups and
    // prices the returned rows. SQL moved verbatim from CostCalculationService.
    // -----------------------------------------------------------------------------------------------

    /** Shared SELECT/FROM/WHERE prefix for the per-call explorer queries (RAW + MESSAGE). */
    private static final String EXPLORER_CALL_SELECT =
        """
            SELECT
                l.id AS item_id,
                l.message_id AS message_id,
                CASE WHEN l.agent_run_id IS NOT NULL THEN 'MAS_STEP' ELSE 'MESSAGE' END AS item_type,
                COALESCE(l.role, 'assistant') AS role,
                COALESCE(
                    ar.profile_name,
                    m.playbook,
                    c.settings->>'chat-prompt',
                    c.settings->>'orchestrator-profile',
                    c.settings->>'playbook',
                    c.settings->>'profile',
                    (SELECT m2.playbook FROM messages m2 WHERE m2.conversation_id = c.id AND m2.playbook IS NOT NULL AND m2.playbook != '' LIMIT 1)
                ) AS playbook,
                ar.profile_name AS mas_profile,
                l.model AS model,
                c.id AS conversation_id,
                c.title AS conversation_title,
                u.display_name AS user_name,
                COALESCE(l.prompt_tokens, 0) AS prompt_tokens,
                COALESCE(l.completion_tokens, 0) AS completion_tokens,
                COALESCE(l.cached_tokens, 0) AS cached_tokens,
                COALESCE(l.thought_tokens, 0) AS thought_tokens,
                -- The explorer re-prices from token counts at CURRENT rates rather than reading
                -- total_cost, so it has to know which calls the gateway replayed: those reported
                -- tokens were never purchased. Without this the dashboard would keep charging for
                -- cache hits even though the persisted ledger no longer does.
                l.gateway_cache_status AS gateway_cache_status,
                l.created_at
            FROM llm_call_logs l
            LEFT JOIN conversations c ON l.conversation_id = c.id
            LEFT JOIN messages m ON l.message_id = m.id
            LEFT JOIN agent_runs ar ON l.agent_run_id = ar.id
            LEFT JOIN users u ON COALESCE(l.user_id, c.user_id) = u.id
            WHERE l.model IS NOT NULL AND l.model != ''""";

    private String explorerCallFilters(Map<String, Object> params, LocalDate startDate,
        LocalDate endDate, List<String> models, List<UUID> userIds, List<String> sources,
        List<String> playbooks) {
        return new CostFilters(params).dateRange("l.created_at", startDate, endDate)
            .in("l.model", "models", models)
            .in("COALESCE(l.user_id, c.user_id)", "userIds", userIds)
            .in("COALESCE(l.role, l.source)", "sources", sources)
            .in("COALESCE(ar.profile_name, m.playbook, c.settings->>'chat-prompt', c.settings->>'orchestrator-profile', c.settings->>'playbook', c.settings->>'profile')",
                "playbooks", playbooks)
            .sql();
    }

    /** Per-call explorer rows (MESSAGE view), newest-first, capped at {@code rowCap}. */
    public List<Map<String, Object>> explorerCallRows(LocalDate startDate, LocalDate endDate,
        List<String> models, List<UUID> userIds, List<String> sources, List<String> playbooks,
        int rowCap) {
        Map<String, Object> params = new HashMap<>();
        String filters =
            explorerCallFilters(params, startDate, endDate, models, userIds, sources, playbooks);
        String sql =
            EXPLORER_CALL_SELECT + filters + " ORDER BY l.created_at DESC LIMIT :explorerRowCap";
        params.put("explorerRowCap", rowCap);
        return runRows(sql, params);
    }

    /**
     * Per-call explorer rows (RAW view) with forward keyset pagination (PRF-01 / Slice 3). Orders
     * by {@code (created_at, id) DESC} — {@code id} is the unique tiebreaker that makes the keyset
     * stable when timestamps collide. A non-null cursor fetches the page strictly older than it
     * ({@code (l.created_at, l.id) < (:cursorCreatedAt, :cursorId)}). Fetches up to {@code limit}
     * rows; the service passes {@code pageSize + 1} so it can detect whether a further page exists.
     */
    public List<Map<String, Object>> explorerCallRowsPaged(LocalDate startDate, LocalDate endDate,
        List<String> models, List<UUID> userIds, List<String> sources, List<String> playbooks,
        Instant cursorCreatedAt, UUID cursorId, int limit) {
        Map<String, Object> params = new HashMap<>();
        String filters =
            explorerCallFilters(params, startDate, endDate, models, userIds, sources, playbooks);
        if (cursorCreatedAt != null && cursorId != null) {
            filters += " AND (l.created_at, l.id) < (:cursorCreatedAt, :cursorId)";
            params.put("cursorCreatedAt", cursorCreatedAt.atOffset(ZoneOffset.UTC));
            params.put("cursorId", cursorId);
        }
        String sql =
            EXPLORER_CALL_SELECT + filters + " ORDER BY l.created_at DESC, l.id DESC LIMIT :limit";
        params.put("limit", limit);
        return runRows(sql, params);
    }

    /** SQL-grouped per-conversation explorer rows, capped at {@code rowCap}. */
    public List<Map<String, Object>> explorerConversationRows(LocalDate startDate,
        LocalDate endDate, List<String> models, List<UUID> userIds, List<String> sources,
        List<String> playbooks, int rowCap) {
        Map<String, Object> params = new HashMap<>();
        String filters = new CostFilters(params).dateRange("l.created_at", startDate, endDate)
            .in("l.model", "models", models)
            .in("COALESCE(l.user_id, c.user_id)", "userIds", userIds)
            .in("COALESCE(l.role, l.source)", "sources", sources)
            .in("COALESCE(ar.profile_name, c.settings->>'chat-prompt', c.settings->>'orchestrator-profile', c.settings->>'playbook', c.settings->>'profile')",
                "playbooks", playbooks)
            .sql();
        String sql =
            """
                SELECT
                    c.id AS conv_id,
                    c.title,
                    u.display_name AS user_name,
                    c.updated_at AS last_activity,
                    l.model,
                    ar.profile_name AS mas_profile,
                    COALESCE(
                        ar.profile_name,
                        c.settings->>'chat-prompt',
                        c.settings->>'orchestrator-profile',
                        c.settings->>'playbook',
                        c.settings->>'profile',
                        (SELECT m2.playbook FROM messages m2 WHERE m2.conversation_id = c.id AND m2.playbook IS NOT NULL AND m2.playbook != '' LIMIT 1)
                    ) AS playbook,
                    CASE WHEN l.agent_run_id IS NOT NULL THEN 'MAS_STEP' ELSE 'MESSAGE' END AS source_type,
                    -- Grouped on as well, so replayed and forwarded calls land in separate rows
                    -- and the service can price each group correctly. The service re-aggregates
                    -- per conversation anyway, so the extra split costs nothing downstream.
                    l.gateway_cache_status AS gateway_cache_status,
                    SUM(COALESCE(l.prompt_tokens, 0)) AS p_tokens,
                    SUM(COALESCE(l.completion_tokens, 0)) AS c_tokens,
                    SUM(COALESCE(l.cached_tokens, 0)) AS ca_tokens,
                    SUM(COALESCE(l.thought_tokens, 0)) AS t_tokens,
                    COUNT(CASE WHEN l.agent_run_id IS NULL THEN 1 END) AS msg_count,
                    COUNT(CASE WHEN l.agent_run_id IS NOT NULL THEN 1 END) AS step_count
                FROM llm_call_logs l
                JOIN conversations c ON l.conversation_id = c.id
                LEFT JOIN agent_runs ar ON l.agent_run_id = ar.id
                LEFT JOIN users u ON COALESCE(l.user_id, c.user_id) = u.id
                WHERE l.model IS NOT NULL AND l.model != ''"""
                + filters
                + " GROUP BY c.id, c.title, u.display_name, c.updated_at, l.model, ar.profile_name, l.source, l.gateway_cache_status, c.settings->>'chat-prompt', c.settings->>'orchestrator-profile', c.settings->>'playbook', c.settings->>'profile', CASE WHEN l.agent_run_id IS NOT NULL THEN 'MAS_STEP' ELSE 'MESSAGE' END"
                // A LIMIT with no ORDER BY lets Postgres return ANY rowCap of the grouped rows, so
                // a wide date range showed an arbitrary subset that changed between refreshes with
                // identical filters. Ordering on a TOTAL key — updated_at alone is not unique —
                // keeps the most recent conversations and makes truncation reproducible.
                + " ORDER BY c.updated_at DESC, c.id, l.model LIMIT :explorerRowCap";
        params.put("explorerRowCap", rowCap);
        return runRows(sql, params);
    }

    /**
     * Typed, parameterized builder for the cost-query WHERE filters (moved verbatim from
     * {@code CostCalculationService}; replaces the former magic-comment SQL-templating DSL and its
     * unsafe regex {@code appendReplacement}). Each call appends a parameterized {@code AND …}
     * fragment and binds its value into the shared param map; the caller splices {@link #sql()}
     * into its query at the point the filters belong. Distinct from {@link #dateFilter} (used by
     * the placeholder-token UNION queries below) — this appends at the WHERE tail.
     */
    private static final class CostFilters {
        private final Map<String, Object> params;
        private final StringBuilder clause = new StringBuilder();

        CostFilters(Map<String, Object> params) {
            this.params = params;
        }

        // Half-open UTC range: start inclusive, end exclusive (end date + 1 day at start-of-day).
        CostFilters dateRange(String column, LocalDate startDate, LocalDate endDate) {
            if (startDate != null) {
                clause.append(" AND ").append(column).append(" >= :startDate");
                params.put("startDate", startDate.atStartOfDay().atOffset(ZoneOffset.UTC));
            }
            if (endDate != null) {
                clause.append(" AND ").append(column).append(" < :endDate");
                params.put("endDate", endDate.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC));
            }
            return this;
        }

        CostFilters in(String column, String param, List<?> values) {
            if (values != null && !values.isEmpty()) {
                clause.append(" AND ").append(column).append(" IN (:").append(param).append(")");
                params.put(param, values);
            }
            return this;
        }

        String sql() {
            return clause.toString();
        }
    }

    // -----------------------------------------------------------------------------------------------
    // Top-N token rows (Wave 3.2 Slice 3 repo move). These return the per-entity×model token sums
    // from messages + agent_steps; the service applies PricingCalculator and sorts/limits by cost.
    // SQL moved verbatim from CostCalculationService; behavior pinned by CostCalculationTopNIT.
    // -----------------------------------------------------------------------------------------------

    /** Per (user × model) token sums over the optional half-open UTC date range. */
    public List<Map<String, Object>> topUserTokenRows(LocalDate startDate, LocalDate endDate) {
        Map<String, Object> params = new HashMap<>();
        String dateFilter =
            new CostFilters(params).dateRange("l.created_at", startDate, endDate).sql();
        String sql = """
            SELECT
                -- Sourced from llm_call_logs, not the messages/agent_steps token mirrors. Those
                -- mirrors carry no gateway_cache_status, so this tab kept charging list price for
                -- calls the gateway replayed while every llm_call_logs-based view had stopped.
                -- Reading the ledger also removes the dashboard's second, divergent token source.
                u.id AS user_id,
                u.display_name,
                u.email,
                l.model,
                l.gateway_cache_status,
                SUM(COALESCE(l.prompt_tokens, 0)) AS p_tokens,
                SUM(COALESCE(l.completion_tokens, 0)) AS c_tokens,
                SUM(COALESCE(l.cached_tokens, 0)) AS ca_tokens,
                SUM(COALESCE(l.thought_tokens, 0)) AS t_tokens
            FROM llm_call_logs l
            LEFT JOIN conversations c ON l.conversation_id = c.id
            JOIN users u ON COALESCE(l.user_id, c.user_id) = u.id
            WHERE l.model IS NOT NULL AND l.model != ''""" + dateFilter
            + " GROUP BY u.id, u.display_name, u.email, l.model, l.gateway_cache_status";
        return runRows(sql, params);
    }

    /** Per (conversation × model) token sums over the optional half-open UTC date range. */
    public List<Map<String, Object>> topConversationTokenRows(LocalDate startDate,
        LocalDate endDate) {
        Map<String, Object> params = new HashMap<>();
        String dateFilter =
            new CostFilters(params).dateRange("l.created_at", startDate, endDate).sql();
        String sql = """
            SELECT
                -- Sourced from llm_call_logs, not the messages/agent_steps token mirrors. Those
                -- mirrors carry no gateway_cache_status, so this tab kept charging list price for
                -- calls the gateway replayed while every llm_call_logs-based view had stopped.
                -- Reading the ledger also removes the dashboard's second, divergent token source.
                c.id AS conv_id,
                c.title,
                u.display_name AS user_name,
                l.model,
                l.gateway_cache_status,
                SUM(COALESCE(l.prompt_tokens, 0)) AS p_tokens,
                SUM(COALESCE(l.completion_tokens, 0)) AS c_tokens,
                SUM(COALESCE(l.cached_tokens, 0)) AS ca_tokens,
                SUM(COALESCE(l.thought_tokens, 0)) AS t_tokens
            FROM llm_call_logs l
            JOIN conversations c ON l.conversation_id = c.id
            JOIN users u ON c.user_id = u.id
            WHERE l.model IS NOT NULL AND l.model != ''""" + dateFilter
            + " GROUP BY c.id, c.title, u.display_name, l.model, l.gateway_cache_status";
        return runRows(sql, params);
    }

    /** Per (MAS profile × model) token sums (agent_steps only) over the optional date range. */
    public List<Map<String, Object>> masProfileTokenRows(LocalDate startDate, LocalDate endDate) {
        Map<String, Object> params = new HashMap<>();
        String dateFilter =
            new CostFilters(params).dateRange("l.created_at", startDate, endDate).sql();
        String sql = """
            SELECT
                -- Sourced from llm_call_logs, not the messages/agent_steps token mirrors. Those
                -- mirrors carry no gateway_cache_status, so this tab kept charging list price for
                -- calls the gateway replayed while every llm_call_logs-based view had stopped.
                -- Reading the ledger also removes the dashboard's second, divergent token source.
                ar.profile_name,
                l.model,
                l.gateway_cache_status,
                SUM(COALESCE(l.prompt_tokens, 0)) AS p_tokens,
                SUM(COALESCE(l.completion_tokens, 0)) AS c_tokens,
                SUM(COALESCE(l.cached_tokens, 0)) AS ca_tokens,
                SUM(COALESCE(l.thought_tokens, 0)) AS t_tokens
            FROM llm_call_logs l
            JOIN agent_runs ar ON l.agent_run_id = ar.id
            WHERE l.model IS NOT NULL AND l.model != '' AND ar.profile_name IS NOT NULL"""
            + dateFilter + " GROUP BY ar.profile_name, l.model, l.gateway_cache_status";
        return runRows(sql, params);
    }

    private List<Map<String, Object>> runRows(String sql, Map<String, Object> params) {
        var query = jdbcClient.sql(sql);
        for (var entry : params.entrySet()) {
            query = query.param(entry.getKey(), entry.getValue());
        }
        return query.query().listOfRows();
    }

    /**
     * Appends a half-open UTC {@code [start, end+1day)} predicate on {@code column} and binds
     * {@code :startDate}/{@code :endDate} (shared across a query's date-filtered columns). A null
     * bound contributes nothing — matching the retired {@code CostFilters.dateRange} exactly.
     */
    private String dateFilter(String column, LocalDate startDate, LocalDate endDate,
        Map<String, Object> params) {
        StringBuilder clause = new StringBuilder();
        if (startDate != null) {
            clause.append(" AND ").append(column).append(" >= :startDate");
            params.put("startDate", startDate.atStartOfDay().atOffset(ZoneOffset.UTC));
        }
        if (endDate != null) {
            clause.append(" AND ").append(column).append(" < :endDate");
            params.put("endDate", endDate.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC));
        }
        return clause.toString();
    }
}
