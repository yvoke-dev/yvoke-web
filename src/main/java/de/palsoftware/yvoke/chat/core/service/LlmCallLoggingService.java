package de.palsoftware.yvoke.chat.core.service;

import de.palsoftware.yvoke.chat.core.model.ModelPricing;
import de.palsoftware.yvoke.chat.core.repository.ModelPricingRepository;
import de.palsoftware.yvoke.llm.core.event.LlmCallLoggedEvent;
import de.palsoftware.yvoke.llm.core.model.LlmCallLog;
import de.palsoftware.yvoke.llm.core.repository.LlmCallLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

@Service
public class LlmCallLoggingService {

    private static final Logger log = LoggerFactory.getLogger(LlmCallLoggingService.class);

    private final LlmCallLogRepository llmCallLogRepository;
    private final ModelPricingRepository modelPricingRepository;

    public LlmCallLoggingService(LlmCallLogRepository llmCallLogRepository,
        ModelPricingRepository modelPricingRepository) {
        this.llmCallLogRepository = llmCallLogRepository;
        this.modelPricingRepository = modelPricingRepository;
    }

    @EventListener
    public void onLlmCall(LlmCallLoggedEvent event) {
        try {
            Optional<ModelPricing> pricingOpt =
                modelPricingRepository.findByModelName(event.model());

            BigDecimal pPrice =
                pricingOpt.map(ModelPricing::promptPricePerMillion).orElse(BigDecimal.ZERO);
            BigDecimal cPrice =
                pricingOpt.map(ModelPricing::completionPricePerMillion).orElse(BigDecimal.ZERO);
            BigDecimal caPrice =
                pricingOpt.map(ModelPricing::cachedPricePerMillion).orElse(BigDecimal.ZERO);
            BigDecimal tPrice =
                pricingOpt.map(ModelPricing::thoughtPricePerMillion).orElse(BigDecimal.ZERO);

            BigDecimal listCost =
                calculateCost(event.promptTokens(), pPrice, event.completionTokens(), cPrice,
                    event.cachedTokens(), caPrice, event.thoughtTokens(), tPrice);

            // A gateway cache HIT never reached the provider, so nothing was charged: the list
            // price becomes avoided spend instead of billed spend. Everything else — including a
            // status the app could not parse — bills in full, because an unreadable header must
            // never zero a real charge. Splitting the one figure this way (rather than storing
            // list price and subtracting later) keeps SUM(total_cost) correct with no predicate,
            // which is what let the over-billing go unnoticed in the first place.
            boolean replayed = event.replayedByGateway();
            BigDecimal totalCost = replayed ? BigDecimal.ZERO : listCost;
            BigDecimal costAvoided = replayed ? listCost : BigDecimal.ZERO;

            LlmCallLog callLog = new LlmCallLog(UUID.randomUUID(), event.conversationId(),
                event.messageId(), event.agentRunId(), event.userId(), event.source(), event.role(),
                event.model(), event.promptTokens(), event.completionTokens(), event.cachedTokens(),
                event.thoughtTokens(), event.totalTokens(), pPrice, cPrice, caPrice, tPrice,
                totalCost, event.durationMs(), null,
                event.gatewayCacheStatus() == null ? null : event.gatewayCacheStatus().name(),
                event.gatewayLogId(), costAvoided);

            llmCallLogRepository.insert(callLog);
            log.info(
                "Logged LLM call for model `{}` (source={}, gateway={}): tokens={}, cost=${}"
                    + ", avoided=${}",
                event.model(), event.source(),
                event.gatewayCacheStatus() == null ? "none" : event.gatewayCacheStatus(),
                event.totalTokens(), totalCost, costAvoided);
        } catch (Exception e) {
            log.warn("Failed to log LLM call event for model {}: {}", event.model(),
                e.getMessage());
        }
    }

    /**
     * Persisted {@code total_cost} for one LLM call. Delegates to {@link PricingCalculator} — the
     * single source of truth for the token→USD formula (MNT-03) — so the logged/persisted cost and
     * the cost-dashboard reports are computed identically (scale-8 per bucket, scale-6 total).
     */
    public static BigDecimal calculateCost(int prompt, BigDecimal pPrice, int completion,
        BigDecimal cPrice, int cached, BigDecimal caPrice, int thought, BigDecimal tPrice) {
        return PricingCalculator.cost(prompt, completion, cached, thought, pPrice, cPrice, caPrice,
            tPrice);
    }
}
