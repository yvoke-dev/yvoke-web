package de.palsoftware.yvoke.chat.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.palsoftware.yvoke.chat.core.model.ModelPricing;
import de.palsoftware.yvoke.chat.core.repository.CostQueryRepository;
import de.palsoftware.yvoke.chat.core.repository.ModelPricingRepository;
import de.palsoftware.yvoke.chat.core.service.CostCalculationService.CostReport;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pins that the read path prices a gateway-replayed call at zero.
 *
 * <p>
 * This is not redundant with {@code LlmCallLoggingServiceTest}. The dashboard never reads the
 * persisted {@code total_cost} — it re-derives cost from the stored token counts at <i>current</i>
 * rates — so the write path and the read path are two independent cost implementations. Teaching
 * only the writer that a replay is free left the dashboard still charging for it, with the ledger
 * and the screen disagreeing and nothing checking. These tests exist so that cannot silently happen
 * again.
 */
@ExtendWith(MockitoExtension.class)
class CostGatewayRepricingTest {

    private static final String MODEL = "gemini-3.6-flash";

    @Mock
    private ModelPricingRepository pricingRepository;

    @Mock
    private CostQueryRepository costQueryRepository;

    private CostCalculationService service;

    @BeforeEach
    void setUp() {
        service = new CostCalculationService(pricingRepository, costQueryRepository, 5000);
    }

    /** One million prompt tokens + 100k completion at the rates above. */
    private static final String ONE_UNIT_COST = "2.50";

    private static Map<String, Object> tokenRow(String gatewayStatus) {
        Map<String, Object> row = new HashMap<>();
        row.put("model", MODEL);
        row.put("gateway_cache_status", gatewayStatus);
        row.put("p_tokens", 1_000_000L);
        row.put("c_tokens", 100_000L);
        row.put("ca_tokens", 0L);
        row.put("t_tokens", 0L);
        if ("REPLAYED".equals(gatewayStatus)) {
            row.put("total_cost", BigDecimal.ZERO);
            row.put("cost_avoided", new BigDecimal(ONE_UNIT_COST));
        } else {
            row.put("total_cost", new BigDecimal(ONE_UNIT_COST));
            row.put("cost_avoided", BigDecimal.ZERO);
        }
        return row;
    }

    @Test
    void testReplayedGroupContributesNoCost() {
        when(costQueryRepository.globalModelTokenRows(null, null))
            .thenReturn(List.of(tokenRow("REPLAYED")));

        CostReport report = service.calculateGlobalCost(null, null);

        assertThat(report.estimatedCostUsd()).isEqualByComparingTo("0");
    }

    /** Tokens still describe the exchange the model would have processed, so they are kept. */
    @Test
    void testReplayedGroupStillContributesTokens() {
        when(costQueryRepository.globalModelTokenRows(null, null))
            .thenReturn(List.of(tokenRow("REPLAYED")));

        CostReport report = service.calculateGlobalCost(null, null);

        assertThat(report.totalPromptTokens()).isEqualTo(1_000_000L);
        assertThat(report.totalCompletionTokens()).isEqualTo(100_000L);
    }

    @Test
    void testForwardedGroupIsPricedNormally() {
        when(costQueryRepository.globalModelTokenRows(null, null))
            .thenReturn(List.of(tokenRow("FORWARDED")));

        assertThat(service.calculateGlobalCost(null, null).estimatedCostUsd())
            .isEqualByComparingTo(ONE_UNIT_COST);
    }

    /** Fail-closed: a status the app could not classify must still be billed. */
    @Test
    void testUnrecognizedGroupIsPricedNormally() {
        when(costQueryRepository.globalModelTokenRows(null, null))
            .thenReturn(List.of(tokenRow("UNRECOGNIZED")));

        assertThat(service.calculateGlobalCost(null, null).estimatedCostUsd())
            .isEqualByComparingTo(ONE_UNIT_COST);
    }

    /** No gateway in the path — the overwhelming majority of historical rows. */
    @Test
    void testNullStatusIsPricedNormally() {
        when(costQueryRepository.globalModelTokenRows(null, null))
            .thenReturn(List.of(tokenRow(null)));

        assertThat(service.calculateGlobalCost(null, null).estimatedCostUsd())
            .isEqualByComparingTo(ONE_UNIT_COST);
    }

    /** The realistic shape: the same model split into a replayed group and a forwarded one. */
    @Test
    void testMixedGroupsChargeOnlyTheForwardedHalf() {
        when(costQueryRepository.globalModelTokenRows(null, null))
            .thenReturn(List.of(tokenRow("REPLAYED"), tokenRow("FORWARDED")));

        CostReport report = service.calculateGlobalCost(null, null);

        assertThat(report.estimatedCostUsd()).isEqualByComparingTo(ONE_UNIT_COST);
        assertThat(report.totalPromptTokens()).isEqualTo(2_000_000L);
    }

    /**
     * The queries group by (model, gateway_cache_status) so each row can be priced correctly, but
     * the breakdown is per MODEL. Letting the split through would list a model twice on the
     * dashboard with nothing on screen explaining why.
     */
    @Test
    void testABreakdownIsEmittedOncePerModelNotOncePerCacheStatus() {
        when(costQueryRepository.globalModelTokenRows(null, null))
            .thenReturn(List.of(tokenRow("REPLAYED"), tokenRow("FORWARDED")));

        CostReport report = service.calculateGlobalCost(null, null);

        assertThat(report.breakdowns()).hasSize(1);
        assertThat(report.breakdowns().get(0).modelName()).isEqualTo(MODEL);
        assertThat(report.breakdowns().get(0).promptTokens()).isEqualTo(2_000_000L);
        assertThat(report.breakdowns().get(0).estimatedCostUsd())
            .isEqualByComparingTo(ONE_UNIT_COST);
    }

    private static Map<String, Object> userRow(UUID userId, String gatewayStatus) {
        Map<String, Object> row = new HashMap<>();
        row.put("user_id", userId);
        row.put("display_name", "Cap User");
        row.put("email", "cap@example.com");
        row.put("model", MODEL);
        row.put("gateway_cache_status", gatewayStatus);
        row.put("p_tokens", 1_000_000L);
        row.put("c_tokens", 100_000L);
        row.put("ca_tokens", 0L);
        row.put("t_tokens", 0L);
        if ("REPLAYED".equals(gatewayStatus)) {
            row.put("total_cost", BigDecimal.ZERO);
            row.put("cost_avoided", new BigDecimal(ONE_UNIT_COST));
        } else {
            row.put("total_cost", new BigDecimal(ONE_UNIT_COST));
            row.put("cost_avoided", BigDecimal.ZERO);
        }
        return row;
    }

    /**
     * The Users / Conversations / MAS tabs used to read the messages and agent_steps token mirrors,
     * which carry no gateway status — so they kept charging list price for replayed calls while
     * every llm_call_logs-based view had stopped. They now read the ledger.
     */
    @Test
    void testTopUsersChargeOnlyTheForwardedHalf() {
        UUID userId = UUID.randomUUID();
        when(costQueryRepository.topUserTokenRows(null, null))
            .thenReturn(List.of(userRow(userId, "REPLAYED"), userRow(userId, "FORWARDED")));

        List<CostCalculationService.UserCostRow> top = service.getTopUsersByCost(null, null, 10);

        assertThat(top).hasSize(1);
        assertThat(top.get(0).estimatedCostUsd()).isEqualByComparingTo(ONE_UNIT_COST);
        assertThat(top.get(0).totalTokens()).isEqualTo(2_200_000L);
    }
}
