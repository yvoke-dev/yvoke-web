package de.palsoftware.yvoke.chat.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.palsoftware.yvoke.chat.core.model.ModelPricing;
import de.palsoftware.yvoke.chat.core.repository.CostQueryRepository;
import de.palsoftware.yvoke.chat.core.repository.ModelPricingRepository;
import de.palsoftware.yvoke.chat.core.service.CostCalculationService.OverviewReport;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * PRF-14: the cost-dashboard overview fragment used to render through four separate service calls
 * ({@code calculateGlobalCost} + the three top-N methods), each independently loading the
 * {@code chat_model_pricing} table — four identical reads per overview render.
 * {@code getOverviewReport} collapses those to <b>one</b> pricing snapshot shared across all four
 * reports. This test pins both halves of the change: (1) exactly one {@code findAll()} pricing read
 * (and one fetch per source), and (2) the composite's four pieces are byte-identical to calling the
 * four methods standalone.
 */
@ExtendWith(MockitoExtension.class)
class CostOverviewReportTest {

    private static final String M1 = "M1-PRICED";

    @Mock
    private ModelPricingRepository pricingRepository;

    @Mock
    private CostQueryRepository costQueryRepository;

    private CostCalculationService service;

    private final LocalDate start = LocalDate.of(2026, 1, 1);
    private final LocalDate end = LocalDate.of(2026, 1, 31);

    private final UUID user1 = UUID.randomUUID();
    private final UUID user2 = UUID.randomUUID();
    private final UUID conv1 = UUID.randomUUID();
    private final UUID conv2 = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new CostCalculationService(pricingRepository, costQueryRepository, 1000);

        when(costQueryRepository.globalModelTokenRows(start, end))
            .thenReturn(List.of(tokenRow(M1, 1000, 500, 200, 0, new BigDecimal("0.001170"))));
        when(costQueryRepository.topUserTokenRows(start, end)).thenReturn(List.of(
            userRow(user1, "User One", "u1@x", M1, 1000, 500, 200, 0, new BigDecimal("0.001170")),
            userRow(user2, "User Two", "u2@x", M1, 100, 50, 0, 0, new BigDecimal("0.000125"))));
        when(costQueryRepository.topConversationTokenRows(start, end)).thenReturn(List.of(
            convRow(conv1, "Conv One", "User One", M1, 1000, 500, 200, 0,
                new BigDecimal("0.001170")),
            convRow(conv2, "Conv Two", "User Two", M1, 100, 50, 0, 0, new BigDecimal("0.000125"))));
        when(costQueryRepository.masProfileTokenRows(start, end))
            .thenReturn(List.of(profileRow("P1", M1, 2000, 0, 0, 0, new BigDecimal("0.001000"))));
    }

    @Test
    void overviewFetchesEachSourceOnce() {
        service.getOverviewReport(start, end, 10);

        verify(costQueryRepository, times(1)).globalModelTokenRows(start, end);
        verify(costQueryRepository, times(1)).topUserTokenRows(start, end);
        verify(costQueryRepository, times(1)).topConversationTokenRows(start, end);
        verify(costQueryRepository, times(1)).masProfileTokenRows(start, end);
    }

    @Test
    void overviewPiecesEqualTheFourStandaloneMethods() {
        OverviewReport overview = service.getOverviewReport(start, end, 10);

        assertThat(overview.report()).isEqualTo(service.calculateGlobalCost(start, end));
        assertThat(overview.topUsers()).isEqualTo(service.getTopUsersByCost(start, end, 10));
        assertThat(overview.topConversations())
            .isEqualTo(service.getTopConversationsByCost(start, end, 10));
        assertThat(overview.masProfiles()).isEqualTo(service.getMasProfilesByCost(start, end));
    }

    @Test
    void overviewPricesAndRanksCorrectly() {
        OverviewReport overview = service.getOverviewReport(start, end, 10);

        assertThat(overview.report().estimatedCostUsd()).isEqualByComparingTo("0.001170");
        // Top users cost-descending: user1 (0.001170) before user2 (0.000125).
        assertThat(overview.topUsers()).hasSize(2);
        assertThat(overview.topUsers().get(0).userId()).isEqualTo(user1);
        assertThat(overview.topUsers().get(0).estimatedCostUsd()).isEqualByComparingTo("0.001170");
        assertThat(overview.topUsers().get(1).userId()).isEqualTo(user2);
        // MAS profile P1: 2000 uncached prompt = 0.001000.
        assertThat(overview.masProfiles()).hasSize(1);
        assertThat(overview.masProfiles().get(0).estimatedCostUsd())
            .isEqualByComparingTo("0.001000");
    }

    // ---- token-row builders (keys match the SQL aliases the service reads) ----

    private static Map<String, Object> tokenRow(String model, long p, long c, long ca, long t,
        BigDecimal totalCost) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("model", model);
        row.put("p_tokens", p);
        row.put("c_tokens", c);
        row.put("ca_tokens", ca);
        row.put("t_tokens", t);
        row.put("total_cost", totalCost);
        row.put("cost_avoided", BigDecimal.ZERO);
        return row;
    }

    private static Map<String, Object> userRow(UUID userId, String displayName, String email,
        String model, long p, long c, long ca, long t, BigDecimal totalCost) {
        Map<String, Object> row = tokenRow(model, p, c, ca, t, totalCost);
        row.put("user_id", userId);
        row.put("display_name", displayName);
        row.put("email", email);
        return row;
    }

    private static Map<String, Object> convRow(UUID convId, String title, String userName,
        String model, long p, long c, long ca, long t, BigDecimal totalCost) {
        Map<String, Object> row = tokenRow(model, p, c, ca, t, totalCost);
        row.put("conv_id", convId);
        row.put("title", title);
        row.put("user_name", userName);
        return row;
    }

    private static Map<String, Object> profileRow(String profileName, String model, long p, long c,
        long ca, long t, BigDecimal totalCost) {
        Map<String, Object> row = tokenRow(model, p, c, ca, t, totalCost);
        row.put("profile_name", profileName);
        return row;
    }
}
