package de.palsoftware.yvoke.chat.core.service;

import de.palsoftware.yvoke.chat.core.model.ModelPricing;
import de.palsoftware.yvoke.chat.core.repository.ModelPricingRepository;
import de.palsoftware.yvoke.llm.core.event.LlmCallLoggedEvent;
import de.palsoftware.yvoke.llm.core.model.GatewayCacheStatus;
import de.palsoftware.yvoke.llm.core.model.LlmCallLog;
import de.palsoftware.yvoke.llm.core.repository.LlmCallLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class LlmCallLoggingServiceTest {

    @Mock
    private LlmCallLogRepository llmCallLogRepository;

    @Mock
    private ModelPricingRepository modelPricingRepository;

    @InjectMocks
    private LlmCallLoggingService service;

    @Test
    void onLlmCall_calculatesCostAtCallTimeAndInsertsLog() {
        String model = "gemini-3.6-flash";
        ModelPricing pricing = new ModelPricing(UUID.randomUUID(), model, new BigDecimal("1.50"), // prompt
            new BigDecimal("10.00"), // completion
            new BigDecimal("0.20"), // cached
            new BigDecimal("10.00"), // thought
            Instant.now());

        when(modelPricingRepository.findByModelName(model)).thenReturn(Optional.of(pricing));

        UUID convId = UUID.randomUUID();
        UUID msgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        LlmCallLoggedEvent event = new LlmCallLoggedEvent(convId, msgId, null, userId, "chat",
            "assistant", model, 3_000_000, 500_000, 2_000_000, 0, 5_500_000, 450);

        service.onLlmCall(event);

        ArgumentCaptor<LlmCallLog> captor = ArgumentCaptor.forClass(LlmCallLog.class);
        verify(llmCallLogRepository).insert(captor.capture());

        LlmCallLog logged = captor.getValue();
        assertThat(logged.model()).isEqualTo(model);
        assertThat(logged.source()).isEqualTo("chat");
        assertThat(logged.role()).isEqualTo("assistant");
        assertThat(logged.promptTokens()).isEqualTo(3_000_000);
        assertThat(logged.completionTokens()).isEqualTo(500_000);
        assertThat(logged.cachedTokens()).isEqualTo(2_000_000);
        assertThat(logged.totalCost()).isEqualByComparingTo(new BigDecimal("6.900000"));
    }

    private ModelPricing pricingFor(String model) {
        return new ModelPricing(UUID.randomUUID(), model, new BigDecimal("1.50"),
            new BigDecimal("10.00"), new BigDecimal("0.20"), new BigDecimal("10.00"),
            Instant.now());
    }

    private LlmCallLog logFor(LlmCallLoggedEvent event) {
        service.onLlmCall(event);
        ArgumentCaptor<LlmCallLog> captor = ArgumentCaptor.forClass(LlmCallLog.class);
        verify(llmCallLogRepository).insert(captor.capture());
        return captor.getValue();
    }

    /**
     * A gateway cache HIT means the provider was never called and charged nothing, so the billed
     * cost is zero and the money the cache saved is recorded separately. The token counts stay: the
     * replayed body reports what the model would have processed.
     */
    @Test
    void onLlmCall_replayedByGateway_billsZeroAndRecordsAvoidedCost() {
        String model = "gemini-3.6-flash";
        when(modelPricingRepository.findByModelName(model))
            .thenReturn(Optional.of(pricingFor(model)));

        LlmCallLog logged = logFor(
            new LlmCallLoggedEvent(null, null, null, null, "kg_extraction", "kg_extractor", model,
                1_000_000, 100_000, 0, 0, 1_100_000, 12, GatewayCacheStatus.REPLAYED, "01KZ6A5K"));

        assertThat(logged.totalCost()).isEqualByComparingTo("0");
        assertThat(logged.costAvoided()).isEqualByComparingTo("2.50");
        assertThat(logged.gatewayCacheStatus()).isEqualTo("REPLAYED");
        assertThat(logged.gatewayLogId()).isEqualTo("01KZ6A5K");
        assertThat(logged.promptTokens()).isEqualTo(1_000_000);
        assertThat(logged.totalTokens()).isEqualTo(1_100_000);
    }

    @Test
    void onLlmCall_forwardedByGateway_billsNormallyAndAvoidsNothing() {
        String model = "gemini-3.6-flash";
        when(modelPricingRepository.findByModelName(model))
            .thenReturn(Optional.of(pricingFor(model)));

        LlmCallLog logged = logFor(
            new LlmCallLoggedEvent(null, null, null, null, "kg_extraction", "kg_extractor", model,
                1_000_000, 100_000, 0, 0, 1_100_000, 12, GatewayCacheStatus.FORWARDED, "01KZ6A5M"));

        assertThat(logged.totalCost()).isEqualByComparingTo("2.50");
        assertThat(logged.costAvoided()).isEqualByComparingTo("0");
        assertThat(logged.gatewayCacheStatus()).isEqualTo("FORWARDED");
    }

    /** Fail-closed: a status the app could not parse must bill in full. */
    @Test
    void onLlmCall_unrecognizedGatewayStatus_billsInFull() {
        String model = "gemini-3.6-flash";
        when(modelPricingRepository.findByModelName(model))
            .thenReturn(Optional.of(pricingFor(model)));

        LlmCallLog logged =
            logFor(new LlmCallLoggedEvent(null, null, null, null, "chat", "assistant", model,
                1_000_000, 100_000, 0, 0, 1_100_000, 12, GatewayCacheStatus.UNRECOGNIZED, null));

        assertThat(logged.totalCost()).isEqualByComparingTo("2.50");
        assertThat(logged.costAvoided()).isEqualByComparingTo("0");
    }

    /**
     * No gateway in the path at all: billed, and the status stays null rather than becoming a miss.
     */
    @Test
    void onLlmCall_withoutGateway_billsNormallyAndLeavesStatusNull() {
        String model = "gemini-3.6-flash";
        when(modelPricingRepository.findByModelName(model))
            .thenReturn(Optional.of(pricingFor(model)));

        LlmCallLog logged = logFor(new LlmCallLoggedEvent(null, null, null, null, "chat",
            "assistant", model, 1_000_000, 100_000, 0, 0, 1_100_000, 12));

        assertThat(logged.totalCost()).isEqualByComparingTo("2.50");
        assertThat(logged.costAvoided()).isEqualByComparingTo("0");
        assertThat(logged.gatewayCacheStatus()).isNull();
    }
}
