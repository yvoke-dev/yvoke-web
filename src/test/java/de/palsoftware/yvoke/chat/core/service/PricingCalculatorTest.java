package de.palsoftware.yvoke.chat.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import de.palsoftware.yvoke.chat.core.model.ModelPricing;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins the single cost formula extracted from {@code CostCalculationService} (MNT-03), locking the
 * load-bearing rounding rules: per-million prices, per-bucket scale-8 HALF_UP rounding BEFORE
 * summation with a scale-6 final result (matching the persisted {@code llm_call_logs.total_cost}
 * write path in {@code LlmCallLoggingService}), cached tokens subtracted from prompt (floored at
 * zero), and null price / unknown model treated as zero. This formula previously had no unit test.
 */
class PricingCalculatorTest {

    private static ModelPricing pricing(String prompt, String completion, String cached,
        String thought) {
        return new ModelPricing(null, "m", bd(prompt), bd(completion), bd(cached), bd(thought),
            null);
    }

    private static BigDecimal bd(String v) {
        return v == null ? null : new BigDecimal(v);
    }

    @Test
    void completionOnlyPerMillion() {
        // 1,000,000 completion tokens @ $3.00/M = $3.000000
        assertThat(PricingCalculator.cost(0, 1_000_000, 0, 0, null, bd("3.00"), null, null))
            .isEqualByComparingTo("3.000000");
    }

    @Test
    void cachedTokensSubtractedFromPromptAndBilledSeparately() {
        // prompt 1000, cached 400 -> uncached 600 @ $2.00/M = 0.001200; cached 400 @ $0.50/M =
        // 0.000200; total 0.001400
        BigDecimal c = PricingCalculator.cost(1000, 0, 400, 0, bd("2.00"), null, bd("0.50"), null);
        assertThat(c).isEqualByComparingTo("0.001400");
    }

    @Test
    void uncachedPromptFlooredAtZeroWhenPromptBelowCached() {
        // prompt 100 < cached 500 -> uncached 0; only cached billed: 500 @ $0.50/M = 0.000250
        assertThat(PricingCalculator.cost(100, 0, 500, 0, bd("2.00"), null, bd("0.50"), null))
            .isEqualByComparingTo("0.000250");
    }

    @Test
    void perBucketRoundingUsesScaleEightIntermediate() {
        // 1 prompt + 1 completion token, each @ $2.50/M: per-token 0.0000025. At the scale-8
        // intermediate precision (matching the persisted write path) each bucket is exact
        // (0.00000250), so the sum is 0.00000500 and the scale-6 final result is 0.000005.
        // (Under the old scale-6 intermediate this rounded each bucket up to 0.000003 -> 0.000006.)
        assertThat(PricingCalculator.cost(1, 1, 0, 0, bd("2.50"), bd("2.50"), null, null))
            .isEqualByComparingTo("0.000005");
    }

    @Test
    void nullPriceFieldTreatedAsZero() {
        // prompt priced, completion price null -> completion contributes nothing
        BigDecimal c =
            PricingCalculator.cost(1_000_000, 1_000_000, 0, 0, pricing("1.00", null, null, null));
        assertThat(c).isEqualByComparingTo("1.000000");
    }

    @Test
    void nullPricingRowIsZero() {
        assertThat(PricingCalculator.cost(1000, 1000, 0, 0, (ModelPricing) null))
            .isEqualByComparingTo("0");
    }

    @Test
    void unknownModelInMapIsZero() {
        assertThat(PricingCalculator.cost("no-such-model", 1000, 1000, 0, 0, Map.of()))
            .isEqualByComparingTo("0");
    }

    @Test
    void allThreeOverloadsAgree() {
        ModelPricing p = pricing("1.50", "3.00", "0.25", "5.00");
        Map<String, ModelPricing> map = Map.of("m", p);

        BigDecimal viaMap = PricingCalculator.cost("m", 2000, 3000, 500, 100, map);
        BigDecimal viaRow = PricingCalculator.cost(2000, 3000, 500, 100, p);
        BigDecimal viaFields = PricingCalculator.cost(2000, 3000, 500, 100, bd("1.50"), bd("3.00"),
            bd("0.25"), bd("5.00"));

        assertThat(viaMap).isEqualByComparingTo(viaRow);
        assertThat(viaRow).isEqualByComparingTo(viaFields);
    }

    @Test
    void resultIsAlwaysScaleSix() {
        assertThat(PricingCalculator.cost(0, 1_000_000, 0, 0, null, bd("3.00"), null, null).scale())
            .isEqualTo(6);
        assertThat(PricingCalculator.cost("x", 1, 1, 1, 1, Map.of()).scale()).isEqualTo(6);
    }
}
