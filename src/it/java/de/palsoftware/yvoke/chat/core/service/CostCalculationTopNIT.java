package de.palsoftware.yvoke.chat.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import de.palsoftware.yvoke.chat.core.model.ModelPricing;
import de.palsoftware.yvoke.chat.core.repository.ConversationRepository;
import de.palsoftware.yvoke.chat.core.repository.ModelPricingRepository;
import de.palsoftware.yvoke.shared.user.repository.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Characterization coverage for the cost dashboard's top-N methods
 * ({@code getTopUsersByCost}/{@code getTopConversationsByCost}/{@code getMasProfilesByCost}), which
 * previously had <b>zero</b> tests. Unlike the explorer (which reads {@code llm_call_logs}), these
 * read {@code messages} + {@code agent_steps}, aggregate per entity×model, price each group via
 * {@link PricingCalculator}, then sort by cost descending. This IT pins that end-to-end behavior —
 * per-entity token totals, the scale-8/scale-6 cost, and the cost-descending order — so the planned
 * move of this SQL into a query repository (Wave 3.2 Slice 3) can be proven behaviour-preserving.
 */
@SpringBootTest(properties = {"spring.flyway.enabled=true",
    "spring.flyway.locations=filesystem:docker/db/migration"})
public class CostCalculationTopNIT {

    private static final String MODEL = "OIM-TOPN-MODEL";
    private static final String PROFILE = "OIM-TOPN-PROFILE";

    @Autowired
    private CostCalculationService costCalculationService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private ModelPricingRepository modelPricingRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID user1;
    private UUID user2;
    private UUID user3;
    private UUID conv1;
    private UUID conv2;
    private UUID conv3;

    @BeforeEach
    void setUp() {
        cleanup();
        // Pricing (per million): prompt 0.50 / completion 1.50 / cached 0.10 / thought 0.
        modelPricingRepository.upsert(new ModelPricing(UUID.randomUUID(), MODEL,
            new BigDecimal("0.50"), new BigDecimal("1.50"), new BigDecimal("0.10"),
            new BigDecimal("0.00"), Instant.now()));

        user1 = createUser("oim-topn-u1@example.com", "TopN User One");
        user2 = createUser("oim-topn-u2@example.com", "TopN User Two");
        user3 = createUser("oim-topn-u3@example.com", "TopN User Three");
        conv1 = UUID.randomUUID();
        conv2 = UUID.randomUUID();
        conv3 = UUID.randomUUID();
        conversationRepository.create(conv1, user1, "OIM-TOPN-C1", Map.of(), "web");
        conversationRepository.create(conv2, user2, "OIM-TOPN-C2", Map.of(), "web");
        conversationRepository.create(conv3, user3, "OIM-TOPN-C3", Map.of(), "web");

        // conv1/user1: uncached prompt 800@0.50=0.0004; completion 500@1.50=0.00075; cached
        // 200@0.10=0.00002 -> 0.001170, tokens 1700.
        insertCall(conv1, user1, 1000, 500, 200, 0);
        // conv2/user2: uncached 100@0.50=0.00005; completion 50@1.50=0.000075 -> 0.000125, tokens 150.
        insertCall(conv2, user2, 100, 50, 0, 0);

        // MAS profile on its own conv3/user3 so it does not roll into the message-only totals above
        // (getTopUsers/getTopConversations read llm_call_logs). prompt 2000@0.50 =
        // 0.001000, tokens 2000.
        UUID runId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO agent_runs (id, conversation_id, profile_name, status) VALUES (?, ?, ?, ?)",
            runId, conv3, PROFILE, "completed");
        insertCall(conv3, user3, runId, 2000, 0, 0, 0, null);
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    void topUsersByCostAreRankedWithCorrectTotals() {
        List<CostCalculationService.UserCostRow> rows =
            costCalculationService.getTopUsersByCost(null, null, 100);

        CostCalculationService.UserCostRow r1 = rowFor(rows, user1);
        CostCalculationService.UserCostRow r2 = rowFor(rows, user2);
        assertThat(r1.totalTokens()).isEqualTo(1700);
        assertThat(r1.estimatedCostUsd()).isEqualByComparingTo("0.001170");
        assertThat(r2.totalTokens()).isEqualTo(150);
        assertThat(r2.estimatedCostUsd()).isEqualByComparingTo("0.000125");
        assertThat(indexOf(rows, user1)).isLessThan(indexOf(rows, user2));
    }

    @Test
    void topConversationsByCostAreRankedWithCorrectTotals() {
        List<CostCalculationService.ConversationCostRow> rows =
            costCalculationService.getTopConversationsByCost(null, null, 100);

        CostCalculationService.ConversationCostRow c1 = rows.stream()
            .filter(r -> r.conversationId().equals(conv1)).findFirst().orElseThrow();
        CostCalculationService.ConversationCostRow c2 = rows.stream()
            .filter(r -> r.conversationId().equals(conv2)).findFirst().orElseThrow();
        assertThat(c1.totalTokens()).isEqualTo(1700);
        assertThat(c1.estimatedCostUsd()).isEqualByComparingTo("0.001170");
        assertThat(c2.estimatedCostUsd()).isEqualByComparingTo("0.000125");

        int i1 = indexOfConv(rows, conv1);
        int i2 = indexOfConv(rows, conv2);
        assertThat(i1).isLessThan(i2);
    }

    @Test
    void masProfilesByCostCarryPricedTotals() {
        List<CostCalculationService.MasProfileCostRow> rows =
            costCalculationService.getMasProfilesByCost(null, null);

        CostCalculationService.MasProfileCostRow p = rows.stream()
            .filter(r -> PROFILE.equals(r.profileName())).findFirst().orElseThrow();
        assertThat(p.totalTokens()).isEqualTo(2000);
        assertThat(p.estimatedCostUsd()).isEqualByComparingTo("0.001000");
    }

    // ---- helpers ----

    private static CostCalculationService.UserCostRow rowFor(
        List<CostCalculationService.UserCostRow> rows, UUID userId) {
        return rows.stream().filter(r -> r.userId().equals(userId)).findFirst().orElseThrow();
    }

    private static int indexOf(List<CostCalculationService.UserCostRow> rows, UUID userId) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).userId().equals(userId)) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfConv(List<CostCalculationService.ConversationCostRow> rows,
        UUID convId) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).conversationId().equals(convId)) {
                return i;
            }
        }
        return -1;
    }

    private UUID createUser(String email, String name) {
        String oid = "oid-" + UUID.randomUUID();
        userRepository.upsert(oid, email, name);
        return userRepository.findByEntraOid(oid).orElseThrow().id();
    }

    /**
     * Seeds the ledger, not the {@code messages} mirror. The top-N queries read
     * {@code llm_call_logs} because that is the only source carrying
     * {@code gateway_cache_status} — the mirrors have no cache signal, so reading them kept
     * charging list price for calls the gateway replayed.
     */
    private void insertCall(UUID convId, UUID userId, int prompt, int completion, int cached,
        int thought) {
        insertCall(convId, userId, null, prompt, completion, cached, thought, null);
    }

    private void insertCall(UUID convId, UUID userId, UUID agentRunId, int prompt, int completion,
        int cached, int thought, String gatewayCacheStatus) {
        jdbcTemplate.update("INSERT INTO llm_call_logs (id, conversation_id, user_id, "
            + "agent_run_id, source, role, model, prompt_tokens, completion_tokens, cached_tokens, "
            + "thought_tokens, total_tokens, gateway_cache_status, created_at) "
            + "VALUES (?, ?, ?, ?, 'chat', 'assistant', ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
            UUID.randomUUID(), convId, userId, agentRunId, MODEL, prompt, completion, cached,
            thought, prompt + completion + cached + thought, gatewayCacheStatus);
    }

    private void cleanup() {
        jdbcTemplate.update("DELETE FROM conversations WHERE title LIKE 'OIM-TOPN-%'");
        jdbcTemplate.update("DELETE FROM users WHERE email LIKE 'oim-topn-%'");
        jdbcTemplate.update("DELETE FROM chat_model_pricing WHERE model_name = ?", MODEL);
    }

    /**
     * End to end for the source change: these tabs read llm_call_logs precisely so a gateway
     * replay can be priced at zero. Against the messages/agent_steps mirrors it could not be —
     * they carry no cache signal — so the tabs kept charging list price for calls that cost
     * nothing.
     */
    @Test
    void aGatewayReplayedCallContributesTokensButNoCostToTheTopNTabs() {
        UUID user4 = createUser("oim-topn-u4@example.com", "TopN User Four");
        UUID conv4 = UUID.randomUUID();
        conversationRepository.create(conv4, user4, "OIM-TOPN-C4", Map.of(), "web");

        // Same shape twice: one replayed, one forwarded. Only the forwarded one is billable.
        insertCall(conv4, user4, null, 1000, 500, 0, 0, "REPLAYED");
        insertCall(conv4, user4, null, 1000, 500, 0, 0, "FORWARDED");

        CostCalculationService.UserCostRow row =
            rowFor(costCalculationService.getTopUsersByCost(null, null, 100), user4);

        // Tokens describe both exchanges; cost covers only the forwarded one:
        // 1000@0.50 = 0.000500 + 500@1.50 = 0.000750 -> 0.001250.
        assertThat(row.totalTokens()).isEqualTo(3000L);
        assertThat(row.estimatedCostUsd()).isEqualByComparingTo(new BigDecimal("0.001250"));
    }
}
