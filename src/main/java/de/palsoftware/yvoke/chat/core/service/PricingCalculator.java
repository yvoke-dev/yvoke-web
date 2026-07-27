package de.palsoftware.yvoke.chat.core.service;

import de.palsoftware.yvoke.chat.core.model.ModelPricing;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * The single source of truth for turning token counts + model prices into a USD cost (MNT-03).
 * Replaces the cost formula that was duplicated inside {@code CostCalculationService}
 * ({@code getModelCost} and the inline copy in {@code calculateCostFromQuery}).
 *
 * <p>
 * Prices are quoted <b>per million tokens</b> and may be {@code null}: a null price — or an unknown
 * model — contributes zero, never an error. Cached tokens are billed at the cached rate and
 * subtracted from the prompt tokens (uncached prompt is floored at zero). Each of the four buckets
 * is divided to the {@link #BUCKET_SCALE} intermediate precision (HALF_UP) <b>before</b> summation,
 * and the total is rounded to {@link #RESULT_SCALE}. This matches the persisted
 * {@code llm_call_logs.total_cost} write path in {@code LlmCallLoggingService} exactly, so the cost
 * dashboard equals what was actually billed; that service delegates here (single source of truth,
 * MNT-03).
 *
 * <p>
 * Note: {@code BUCKET_SCALE}/{@code RESULT_SCALE} are constants rather than configuration on
 * purpose — there is a single correct billing precision, and the engineering standards discourage
 * speculative config knobs. Promote them to {@code application.yml} only if a real need to tune
 * per-environment billing precision arises.
 *
 * <p>
 * Deliberately dependency-free (no repository/JDBC) so it is trivially unit-testable and callers
 * keep control of the single pricing read.
 */
public final class PricingCalculator {

    private static final BigDecimal MILLION = new BigDecimal("1000000");

    /** Intermediate per-bucket rounding precision (matches the persisted write path). */
    private static final int BUCKET_SCALE = 8;

    /** Final result precision applied to the summed cost. */
    private static final int RESULT_SCALE = 6;

    private PricingCalculator() {}

    /**
     * Core formula: token counts + per-million prices (any null price treated as zero). Rounds each
     * bucket at scale {@value #BUCKET_SCALE} before summing, then returns the total at scale
     * {@value #RESULT_SCALE}.
     */
    public static BigDecimal cost(long promptTokens, long completionTokens, long cachedTokens,
        long thoughtTokens, BigDecimal promptPricePerMillion, BigDecimal completionPricePerMillion,
        BigDecimal cachedPricePerMillion, BigDecimal thoughtPricePerMillion) {
        BigDecimal pPrice = promptPricePerMillion != null ? promptPricePerMillion : BigDecimal.ZERO;
        BigDecimal cPrice =
            completionPricePerMillion != null ? completionPricePerMillion : BigDecimal.ZERO;
        BigDecimal caPrice =
            cachedPricePerMillion != null ? cachedPricePerMillion : BigDecimal.ZERO;
        BigDecimal tPrice =
            thoughtPricePerMillion != null ? thoughtPricePerMillion : BigDecimal.ZERO;

        long uncachedPromptTokens = Math.max(0, promptTokens - cachedTokens);
        BigDecimal pCost = new BigDecimal(uncachedPromptTokens).multiply(pPrice).divide(MILLION,
            BUCKET_SCALE, RoundingMode.HALF_UP);
        BigDecimal cCost = new BigDecimal(completionTokens).multiply(cPrice).divide(MILLION,
            BUCKET_SCALE, RoundingMode.HALF_UP);
        BigDecimal caCost = new BigDecimal(cachedTokens).multiply(caPrice).divide(MILLION,
            BUCKET_SCALE, RoundingMode.HALF_UP);
        BigDecimal tCost = new BigDecimal(thoughtTokens).multiply(tPrice).divide(MILLION,
            BUCKET_SCALE, RoundingMode.HALF_UP);

        return pCost.add(cCost).add(caCost).add(tCost).setScale(RESULT_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Cost from a (nullable) pricing row; a null row contributes zero (all prices treated null).
     */
    public static BigDecimal cost(long promptTokens, long completionTokens, long cachedTokens,
        long thoughtTokens, ModelPricing pricing) {
        return pricing == null
            ? cost(promptTokens, completionTokens, cachedTokens, thoughtTokens, null, null, null,
                null)
            : cost(promptTokens, completionTokens, cachedTokens, thoughtTokens,
                pricing.promptPricePerMillion(), pricing.completionPricePerMillion(),
                pricing.cachedPricePerMillion(), pricing.thoughtPricePerMillion());
    }

    /** Cost for a model looked up in a pricing snapshot; an unknown model contributes zero. */
    public static BigDecimal cost(String model, long promptTokens, long completionTokens,
        long cachedTokens, long thoughtTokens, Map<String, ModelPricing> pricesByModel) {
        return cost(promptTokens, completionTokens, cachedTokens, thoughtTokens,
            pricesByModel.get(model));
    }
}
