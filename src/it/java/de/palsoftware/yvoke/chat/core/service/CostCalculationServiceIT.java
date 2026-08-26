package de.palsoftware.yvoke.chat.core.service;

import de.palsoftware.yvoke.chat.core.model.Conversation;
import de.palsoftware.yvoke.chat.core.model.ModelPricing;
import de.palsoftware.yvoke.chat.core.repository.ConversationRepository;
import de.palsoftware.yvoke.chat.core.repository.ModelPricingRepository;
import de.palsoftware.yvoke.llm.core.model.LlmCallLog;
import de.palsoftware.yvoke.llm.core.repository.LlmCallLogRepository;
import de.palsoftware.yvoke.shared.user.model.User;
import de.palsoftware.yvoke.shared.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=filesystem:docker/db/migration"
})
public class CostCalculationServiceIT {

    /** The priced model seeded in {@link #setUp()} (per-million: 0.50 / 1.50 / 0.10 / 0.00). */
    private static final String PRICED_MODEL = "gemini-3.5-flash-test";

    @Autowired
    private CostCalculationService costCalculationService;

    @Autowired
    private LlmCallLogRepository llmCallLogRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ModelPricingRepository modelPricingRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User testUser;
    private UUID testConvId;

    @BeforeEach
    void setUp() {
        String oid = "oid-" + UUID.randomUUID();
        userRepository.upsert(oid, "test-cost-user@example.com", "Test Cost User");
        testUser = userRepository.findByEntraOid(oid).orElseThrow();
        testConvId = UUID.randomUUID();
        conversationRepository.create(testConvId, testUser.id(), "Test Cost Conversation", Map.of("profile", "OIM"), "web");

        modelPricingRepository.upsert(new ModelPricing(
            UUID.randomUUID(),
            "gemini-3.5-flash-test",
            new BigDecimal("0.50"),
            new BigDecimal("1.50"),
            new BigDecimal("0.10"),
            new BigDecimal("0.00"),
            Instant.now()
        ));
    }

    @Test
    void testCalculateCostByConversationFromLlmCallLogs() {
        LlmCallLog log1 = new LlmCallLog(
            UUID.randomUUID(),
            testConvId,
            null,
            null,
            testUser.id(),
            "orchestration",
            "orchestrator",
            "gemini-3.5-flash-test",
            1000,
            500,
            200,
            0,
            1700,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("0.001170"), 0,
            Instant.now()
        );
        llmCallLogRepository.insert(log1);

        CostCalculationService.CostReport report = costCalculationService.calculateCostByConversation(testConvId, null, null);
        assertThat(report.breakdowns()).hasSize(1);
        assertThat(report.totalPromptTokens()).isEqualTo(1000);
        assertThat(report.totalCompletionTokens()).isEqualTo(500);
        assertThat(report.totalCachedTokens()).isEqualTo(200);

        // Cost VALUE lock: pricing per-million: prompt 0.50 / completion 1.50 / cached 0.10.
        // uncached prompt = 1000-200 = 800 -> 800*0.50/1e6 = 0.000400;
        // completion 500*1.50/1e6 = 0.000750; cached 200*0.10/1e6 = 0.000020; total 0.001170.
        assertThat(report.estimatedCostUsd()).isEqualByComparingTo("0.001170");
        assertThat(report.breakdowns().get(0).estimatedCostUsd()).isEqualByComparingTo("0.001170");
    }

    @Test
    void testGetFilteredExplorerReportConversationLevel() {
        LlmCallLog log1 = new LlmCallLog(
            UUID.randomUUID(),
            testConvId,
            null,
            null,
            testUser.id(),
            "orchestration",
            "specialist",
            "gemini-3.5-flash-test",
            2000,
            1000,
            500,
            0,
            3500,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0,
            Instant.now()
        );
        llmCallLogRepository.insert(log1);

        CostCalculationService.FilteredCostExplorerReport report = costCalculationService.getFilteredExplorerReport(
            "CONVERSATION",
            LocalDate.now().minusDays(1),
            LocalDate.now().plusDays(1),
            List.of("gemini-3.5-flash-test"),
            List.of(testUser.id()),
            null
        );

        assertThat(report.conversations()).isNotEmpty();
        CostCalculationService.FilteredConversationRow row = report.conversations().stream()
            .filter(c -> c.conversationId().equals(testConvId))
            .findFirst()
            .orElse(null);

        assertThat(row).isNotNull();
        assertThat(row.totalTokens()).isGreaterThanOrEqualTo(3500);
    }

    @Test
    void testGetFilteredExplorerReportMessageLevel() {
        LlmCallLog log1 = new LlmCallLog(
            UUID.randomUUID(),
            testConvId,
            null,
            null,
            testUser.id(),
            "chat",
            "assistant",
            "gemini-3.5-flash-test",
            300,
            150,
            50,
            0,
            500,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0,
            Instant.now()
        );
        llmCallLogRepository.insert(log1);

        CostCalculationService.FilteredCostExplorerReport report = costCalculationService.getFilteredExplorerReport(
            "MESSAGE",
            LocalDate.now().minusDays(1),
            LocalDate.now().plusDays(1),
            List.of("gemini-3.5-flash-test"),
            List.of(testUser.id()),
            null
        );

        assertThat(report.messages()).isNotEmpty();
        boolean found = report.messages().stream().anyMatch(m -> m.id().equals(log1.id()));
        assertThat(found).isTrue();
    }

    /**
     * Pins the WHERE-building behavior the placeholder DSL produces: filtering by model, user, and
     * source each narrows the result to exactly the matching calls. The explorer's "source" filter
     * is actually {@code COALESCE(l.role, l.source)}, so distinct roles are used to exercise it.
     * Unique model names keep the assertions isolated from rows other tests seed in this class.
     */
    @Test
    void testFilterParityByModelUserAndSource() {
        UUID u2 = createUser();
        UUID conv2 = UUID.randomUUID();
        conversationRepository.create(conv2, u2, "Conv2", Map.of(), "web");

        LlmCallLog a = insertLog(testConvId, testUser.id(), "assistant", "COST-FILTER-M1");
        LlmCallLog b = insertLog(testConvId, testUser.id(), "assistant", "COST-FILTER-M2");
        LlmCallLog c = insertLog(conv2, u2, "specialist", "COST-FILTER-M1");

        // model filter narrows to matching-model rows
        assertThat(rawIds(List.of("COST-FILTER-M1"), List.of(), List.of()))
            .containsExactlyInAnyOrder(a.id(), c.id());
        assertThat(rawIds(List.of("COST-FILTER-M2"), List.of(), List.of()))
            .containsExactly(b.id());
        // within my two models, user filter narrows to the first user's rows
        assertThat(rawIds(List.of("COST-FILTER-M1", "COST-FILTER-M2"), List.of(testUser.id()), List.of()))
            .containsExactlyInAnyOrder(a.id(), b.id());
        // within my two models, the "source" filter (COALESCE(role, source)) narrows by role
        assertThat(rawIds(List.of("COST-FILTER-M1", "COST-FILTER-M2"), List.of(), List.of("specialist")))
            .containsExactly(c.id());
    }

    /**
     * Characterization for {@code getMessagesByConversation} (its own SQL, previously unpinned):
     * returns every call in the conversation, priced via {@link PricingCalculator}, ordered by
     * {@code created_at ASC}. Pins the conversation filter, the order, and the per-row cost so the
     * planned SQL move into {@code CostQueryRepository} is provably behavior-preserving.
     */
    @Test
    void testGetMessagesByConversationReturnsPricedRowsOrderedByCreatedAt() {
        // earlier row: uncached prompt 800@0.50=0.000400 + completion 500@1.50=0.000750 + cached
        // 200@0.10=0.000020 -> 0.001170; later row: 100@0.50=0.000050 + 50@1.50=0.000075 -> 0.000125.
        jdbcTemplate.update("INSERT INTO llm_call_logs (id, conversation_id, user_id, source, role, "
            + "model, prompt_tokens, completion_tokens, cached_tokens, thought_tokens, total_tokens, "
            + "total_cost, created_at) VALUES (?, ?, ?, 'chat', 'assistant', ?, 1000, 500, 200, 0, 1700, 0.001170, "
            + "now() - interval '1 hour')", UUID.randomUUID(), testConvId, testUser.id(), PRICED_MODEL);
        jdbcTemplate.update("INSERT INTO llm_call_logs (id, conversation_id, user_id, source, role, "
            + "model, prompt_tokens, completion_tokens, cached_tokens, thought_tokens, total_tokens, "
            + "total_cost, created_at) VALUES (?, ?, ?, 'chat', 'assistant', ?, 100, 50, 0, 0, 150, 0.000125, now())",
            UUID.randomUUID(), testConvId, testUser.id(), PRICED_MODEL);

        List<CostCalculationService.MessageCostRow> rows =
            costCalculationService.getMessagesByConversation(testConvId);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).promptTokens()).isEqualTo(1000);
        assertThat(rows.get(0).estimatedCostUsd()).isEqualByComparingTo("0.001170");
        assertThat(rows.get(1).promptTokens()).isEqualTo(100);
        assertThat(rows.get(1).estimatedCostUsd()).isEqualByComparingTo("0.000125");
    }

    /**
     * Characterization for {@code getAllUsedModelsWithPricingStatus} (its own usage-count SQL merged
     * with the pricing table): a used-but-unpriced model reports its call count with
     * {@code hasPricing=false}; a priced-but-unused model appears with {@code usageCount=0} and
     * {@code hasPricing=true}. Unique model names isolate the assertions from other seeded rows.
     */
    @Test
    void testGetAllUsedModelsWithPricingStatusCountsUsageAndFlagsPricing() {
        String unpriced = "USAGE-STATUS-UNPRICED-" + UUID.randomUUID();
        String priced = "USAGE-STATUS-PRICED-" + UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            jdbcTemplate.update("INSERT INTO llm_call_logs (id, model, source, prompt_tokens, "
                + "completion_tokens, created_at) VALUES (?, ?, 'chat', 10, 10, now())",
                UUID.randomUUID(), unpriced);
        }
        modelPricingRepository.upsert(new ModelPricing(UUID.randomUUID(), priced,
            new BigDecimal("0.50"), new BigDecimal("1.50"), new BigDecimal("0.10"),
            new BigDecimal("0.00"), Instant.now()));

        List<CostCalculationService.UsedModelPricingStatus> statuses =
            costCalculationService.getAllUsedModelsWithPricingStatus();

        CostCalculationService.UsedModelPricingStatus u = statuses.stream()
            .filter(s -> s.modelName().equals(unpriced)).findFirst().orElseThrow();
        assertThat(u.hasPricing()).isFalse();
        assertThat(u.usageCount()).isEqualTo(3);

        CostCalculationService.UsedModelPricingStatus p = statuses.stream()
            .filter(s -> s.modelName().equals(priced)).findFirst().orElseThrow();
        assertThat(p.hasPricing()).isTrue();
        assertThat(p.usageCount()).isEqualTo(0);
    }

    /**
     * Characterization for the {@code user_id} WHERE variant of the shared {@code calculate*}
     * aggregation (the conversation variant already pins the formula). {@code testUser} is unique per
     * run, so the sum is exactly the one seeded call.
     */
    @Test
    void testCalculateCostByUserPinsUserFilterAndCost() {
        insertPricedCall(testConvId, testUser.id(), null, 1000, 500, 200);

        CostCalculationService.CostReport report =
            costCalculationService.calculateCostByUser(testUser.id(), null, null);

        assertThat(report.breakdowns()).hasSize(1);
        assertThat(report.totalPromptTokens()).isEqualTo(1000);
        assertThat(report.estimatedCostUsd()).isEqualByComparingTo("0.001170");
    }

    /**
     * Characterization for the global (no-entity-filter) {@code calculate*} path: per-model breakdown
     * over the whole table. A unique priced model isolates the assertion from other rows.
     */
    @Test
    void testCalculateGlobalCostPinsPerModelBreakdown() {
        String model = "GLOBAL-COST-MODEL-" + UUID.randomUUID();
        modelPricingRepository.upsert(new ModelPricing(UUID.randomUUID(), model,
            new BigDecimal("0.50"), new BigDecimal("1.50"), new BigDecimal("0.10"),
            new BigDecimal("0.00"), Instant.now()));
        jdbcTemplate.update("INSERT INTO llm_call_logs (id, conversation_id, user_id, source, role, "
            + "model, prompt_tokens, completion_tokens, cached_tokens, thought_tokens, total_tokens, "
            + "total_cost, created_at) VALUES (?, ?, ?, 'chat', 'assistant', ?, 1000, 500, 200, 0, 1700, 0.001170, now())",
            UUID.randomUUID(), testConvId, testUser.id(), model);

        CostCalculationService.CostReport report =
            costCalculationService.calculateGlobalCost(null, null);

        CostCalculationService.ModelCostBreakdown b = report.breakdowns().stream()
            .filter(x -> model.equals(x.modelName())).findFirst().orElseThrow();
        assertThat(b.promptTokens()).isEqualTo(1000);
        assertThat(b.estimatedCostUsd()).isEqualByComparingTo("0.001170");
    }

    /**
     * Characterization for the two agent-run JOIN variants ({@code calculateCostByAgentRun} and
     * {@code calculateCostByMasProfile}), which read {@code llm_call_logs} joined to
     * {@code agent_runs}. A unique profile isolates the MAS-profile aggregation.
     */
    @Test
    void testCalculateCostByAgentRunAndMasProfile() {
        UUID runId = UUID.randomUUID();
        String profile = "CALC-MAS-PROFILE-" + UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO agent_runs (id, conversation_id, profile_name, status) VALUES (?, ?, ?, ?)",
            runId, testConvId, profile, "completed");
        insertPricedCall(testConvId, testUser.id(), runId, 1000, 500, 200);

        CostCalculationService.CostReport byRun =
            costCalculationService.calculateCostByAgentRun(runId);
        assertThat(byRun.breakdowns()).hasSize(1);
        assertThat(byRun.estimatedCostUsd()).isEqualByComparingTo("0.001170");

        CostCalculationService.CostReport byProfile =
            costCalculationService.calculateCostByMasProfile(profile, null, null);
        assertThat(byProfile.breakdowns()).hasSize(1);
        assertThat(byProfile.estimatedCostUsd()).isEqualByComparingTo("0.001170");
    }

    @Test
    void aReplayedGatewayCallIsBilledAtZeroByTheDashboardToo() {
        UUID replayedConv = UUID.randomUUID();
        conversationRepository.create(replayedConv, testUser.id(), "Replayed", Map.of(), "web");
        UUID forwardedConv = UUID.randomUUID();
        conversationRepository.create(forwardedConv, testUser.id(), "Forwarded", Map.of(), "web");

        insertCallWithGatewayStatus(replayedConv, "REPLAYED");
        insertCallWithGatewayStatus(forwardedConv, null);

        CostCalculationService.FilteredCostExplorerReport report =
            costCalculationService.getFilteredExplorerReport("CONVERSATION",
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(1), List.of(PRICED_MODEL),
                List.of(testUser.id()), null);

        BigDecimal replayed = report.conversations().stream()
            .filter(c -> c.conversationId().equals(replayedConv)).findFirst().orElseThrow()
            .estimatedCostUsd();
        BigDecimal forwarded = report.conversations().stream()
            .filter(c -> c.conversationId().equals(forwardedConv)).findFirst().orElseThrow()
            .estimatedCostUsd();

        assertThat(replayed).as("a replayed call never reached the provider, so it costs nothing")
            .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(forwarded).as("an ordinary call must still be billed in full")
            .isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void everyAggregationPricesAReplayedCallAtZeroWhileStillCountingItsTokens() {
        String model = "GATEWAY-PARITY-MODEL-" + UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO chat_model_pricing (id, model_name, prompt_price_per_million, "
                + "completion_price_per_million, cached_price_per_million, thought_price_per_million, updated_at) "
                + "VALUES (?, ?, 1.0, 1.0, 1.0, 1.0, now())",
            UUID.randomUUID(), model);
        UUID replayedConv = UUID.randomUUID();
        conversationRepository.create(replayedConv, testUser.id(), "Replayed", Map.of(), "web");
        UUID forwardedConv = UUID.randomUUID();
        conversationRepository.create(forwardedConv, testUser.id(), "Forwarded", Map.of(), "web");
        insertGatewayCall(replayedConv, model, "REPLAYED");
        insertGatewayCall(forwardedConv, model, "FORWARDED");

        // Global: exactly one of the two models' worth of cost, and both calls' tokens.
        CostCalculationService.CostReport global =
            costCalculationService.calculateGlobalCost(null, null);
        var byModel = global.breakdowns().stream().filter(b -> model.equals(b.modelName())).toList();
        assertThat(byModel).as("the model must appear — a replay is free, not invisible").isNotEmpty();
        assertThat(byModel.stream().mapToLong(b -> b.promptTokens()).sum())
            .as("both calls' tokens are real and must still be counted").isEqualTo(2000);
        assertThat(byModel.stream().map(b -> b.estimatedCostUsd()).reduce(BigDecimal.ZERO,
            BigDecimal::add)).as("only the forwarded call is billable")
                .isEqualByComparingTo(new BigDecimal("0.001500"));

        // Per-user: same two calls, same expectation.
        CostCalculationService.CostReport perUser =
            costCalculationService.calculateCostByUser(testUser.id(), null, null);
        assertThat(perUser.breakdowns().stream().filter(b -> model.equals(b.modelName()))
            .map(b -> b.estimatedCostUsd()).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(new BigDecimal("0.001500"));

        // MESSAGE level explorer — a different query again.
        CostCalculationService.FilteredCostExplorerReport msg =
            costCalculationService.getFilteredExplorerReport("MESSAGE", null, null, List.of(model),
                List.of(testUser.id()), null);
        assertThat(msg.totalCostUsd()).as("the MESSAGE view must not re-bill the replay")
            .isEqualByComparingTo(new BigDecimal("0.001500"));
    }

    @Test
    void everySourceTheDropdownOffersMatchesAtLeastOneRow() {
        String role = "SRC-ROLE-" + UUID.randomUUID();
        String source = "SRC-SOURCE-" + UUID.randomUUID();
        String model = "SRC-DROPDOWN-MODEL-" + UUID.randomUUID();
        UUID callId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO llm_call_logs (id, conversation_id, user_id, source, role, "
            + "model, prompt_tokens, completion_tokens, cached_tokens, thought_tokens, "
            + "total_tokens, created_at) VALUES (?, ?, ?, ?, ?, ?, 10, 10, 0, 0, 20, now())",
            callId, testConvId, testUser.id(), source, role, model);

        List<String> offered = costCalculationService.getAvailableSources();

        assertThat(offered)
            .as("the dropdown offers COALESCE(role, source), so a row with a role offers the role")
            .contains(role);
        assertThat(offered)
            .as("and must NOT offer the shadowed source — the filter could never match it")
            .doesNotContain(source);

        // The other half of the contract: the value the dropdown offered actually selects the row.
        assertThat(rawIds(List.of(model), List.of(), List.of(role)))
            .as("filtering by an offered value must return the row it was derived from")
            .containsExactly(callId);
        assertThat(rawIds(List.of(model), List.of(), List.of(source)))
            .as("and the shadowed source matches nothing, which is why it must not be offered")
            .isEmpty();
    }

    @Test
    void aPriceEditUpdatesPricingTableWithoutMutatingHistoricalSpend() {
        String model = "PRICE-EDIT-MODEL-" + UUID.randomUUID();
        UUID callId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO llm_call_logs (id, conversation_id, user_id, source, role, "
            + "model, prompt_tokens, completion_tokens, cached_tokens, thought_tokens, "
            + "total_tokens, total_cost, created_at) VALUES (?, ?, ?, 'chat', 'assistant', ?, "
            + "3000000, 4000000, 1000000, 8000000, 16000000, 1.234567, now())",
            callId, testConvId, testUser.id(), model);

        // INSERT branch: this model has no pricing row yet.
        costCalculationService.updateModelPricing(model, new BigDecimal("1"), new BigDecimal("2"),
            new BigDecimal("4"), new BigDecimal("8"));

        assertThat(costCalculationService.calculateCostByConversation(testConvId, null, null)
            .estimatedCostUsd())
                .as("the dashboard reads the persisted total_cost from the ledger")
                .isEqualByComparingTo("1.234567");

        // UPDATE branch: same model name, every rate doubled.
        costCalculationService.updateModelPricing(model, new BigDecimal("2"), new BigDecimal("4"),
            new BigDecimal("8"), new BigDecimal("16"));

        ModelPricing saved = modelPricingRepository.findByModelName(model).orElseThrow();
        assertThat(saved.promptPricePerMillion())
            .as("argument 2 is the PROMPT rate").isEqualByComparingTo("2");
        assertThat(saved.completionPricePerMillion())
            .as("argument 3 is the COMPLETION rate").isEqualByComparingTo("4");
        assertThat(saved.cachedPricePerMillion())
            .as("argument 4 is the CACHED rate").isEqualByComparingTo("8");
        assertThat(saved.thoughtPricePerMillion())
            .as("argument 5 is the THOUGHT rate").isEqualByComparingTo("16");

        assertThat(costCalculationService.calculateCostByConversation(testConvId, null, null)
            .estimatedCostUsd())
                .as("historical spend remains the persisted total_cost from execution time")
                .isEqualByComparingTo("1.234567");

        assertThat(jdbcTemplate.queryForObject(
            "SELECT total_cost FROM llm_call_logs WHERE id = ?", BigDecimal.class, callId))
                .as("the ledger records what was billed and a price edit never rewrites it")
                .isEqualByComparingTo("1.234567");
    }

    private void insertGatewayCall(UUID conv, String model, String gatewayStatus) {
        BigDecimal totalCost = "REPLAYED".equals(gatewayStatus) ? BigDecimal.ZERO : new BigDecimal("0.001500");
        BigDecimal costAvoided = "REPLAYED".equals(gatewayStatus) ? new BigDecimal("0.001500") : BigDecimal.ZERO;
        jdbcTemplate.update("INSERT INTO llm_call_logs (id, conversation_id, user_id, source, role, "
            + "model, prompt_tokens, completion_tokens, cached_tokens, thought_tokens, "
            + "total_tokens, gateway_cache_status, total_cost, cost_avoided, created_at) "
            + "VALUES (?, ?, ?, 'chat', 'assistant', ?, 1000, 500, 0, 0, 1500, ?, ?, ?, now())",
            UUID.randomUUID(), conv, testUser.id(), model, gatewayStatus, totalCost, costAvoided);
    }

    @Test
    void anUnknownViewLevelFallsBackToTheConversationBranch() {
        String model = "UNKNOWN-VIEW-LEVEL-MODEL-" + UUID.randomUUID();
        insertLog(testConvId, testUser.id(), "assistant", model);

        CostCalculationService.FilteredCostExplorerReport report =
            costCalculationService.getFilteredExplorerReport("bogus", LocalDate.now().minusDays(1),
                LocalDate.now().plusDays(1), List.of(model), List.of(testUser.id()), null);

        assertThat(report.viewLevel())
            .as("the report must name the branch that actually ran, not the caller's string")
            .isEqualTo("CONVERSATION");
        assertThat(report.messages())
            .as("the CONVERSATION branch never populates per-call rows").isEmpty();
        CostCalculationService.FilteredConversationRow row = report.conversations().stream()
            .filter(c -> c.conversationId().equals(testConvId)).findFirst().orElseThrow();
        assertThat(row.totalTokens())
            .as("the fallback still aggregates real spend — it is not an empty result")
            .isEqualTo(20L);
    }

    private void insertCallWithGatewayStatus(UUID conv, String gatewayStatus) {
        BigDecimal totalCost = "REPLAYED".equals(gatewayStatus) ? BigDecimal.ZERO : new BigDecimal("0.001170");
        BigDecimal costAvoided = "REPLAYED".equals(gatewayStatus) ? new BigDecimal("0.001170") : BigDecimal.ZERO;
        jdbcTemplate.update("INSERT INTO llm_call_logs (id, conversation_id, user_id, source, role, "
            + "model, prompt_tokens, completion_tokens, cached_tokens, thought_tokens, "
            + "total_tokens, gateway_cache_status, total_cost, cost_avoided, created_at) "
            + "VALUES (?, ?, ?, 'chat', 'assistant', ?, 1000, 500, 0, 0, 1500, ?, ?, ?, now())",
            UUID.randomUUID(), conv, testUser.id(), PRICED_MODEL, gatewayStatus, totalCost, costAvoided);
    }

    private void insertPricedCall(UUID conv, UUID userId, UUID agentRunId, int prompt, int completion,
        int cached) {
        jdbcTemplate.update("INSERT INTO llm_call_logs (id, conversation_id, agent_run_id, user_id, "
            + "source, role, model, prompt_tokens, completion_tokens, cached_tokens, thought_tokens, "
            + "total_tokens, total_cost, created_at) VALUES (?, ?, ?, ?, 'orchestration', 'specialist', ?, ?, ?, "
            + "?, 0, ?, 0.001170, now())", UUID.randomUUID(), conv, agentRunId, userId, PRICED_MODEL, prompt,
            completion, cached, prompt + completion + cached);
    }

    private Set<UUID> rawIds(List<String> models, List<UUID> users, List<String> sources) {
        CostCalculationService.FilteredCostExplorerReport report =
            costCalculationService.getFilteredExplorerReport("RAW",
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(1), models, users, sources,
                List.of());
        return report.messages().stream()
            .map(CostCalculationService.FilteredMessageRow::id).collect(Collectors.toSet());
    }

    private UUID createUser() {
        String oid = "oid-" + UUID.randomUUID();
        userRepository.upsert(oid, oid + "@example.com", "User " + oid);
        return userRepository.findByEntraOid(oid).orElseThrow().id();
    }

    private LlmCallLog insertLog(UUID conv, UUID userId, String role, String model) {
        LlmCallLog log = new LlmCallLog(UUID.randomUUID(), conv, null, null, userId, "chat", role,
            model, 10, 10, 0, 0, 20, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, 0, Instant.now());
        llmCallLogRepository.insert(log);
        return log;
    }
}
