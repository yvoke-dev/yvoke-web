package de.palsoftware.yvoke.llm.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.palsoftware.yvoke.llm.core.context.LlmCallContextHolder;
import de.palsoftware.yvoke.llm.core.event.LlmCallLoggedEvent;
import de.palsoftware.yvoke.llm.core.model.GatewayCacheStatus;
import de.palsoftware.yvoke.llm.core.model.LlmCallFailedException;
import de.palsoftware.yvoke.llm.core.model.LlmGatewayInfo;
import de.palsoftware.yvoke.llm.core.model.LlmMessage;
import de.palsoftware.yvoke.llm.core.model.LlmRequest;
import de.palsoftware.yvoke.llm.core.model.LlmResponse;
import de.palsoftware.yvoke.llm.core.model.LlmResponseChunk;
import de.palsoftware.yvoke.llm.core.model.LlmUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the single accounting seam.
 *
 * <p>
 * Before this class existed, every caller had to remember to extract usage and publish
 * {@code LlmCallLoggedEvent} itself. One of them ({@code GeneralSummarizer}) never did, and nothing
 * detected it for the life of the codebase. These tests exist so that accounting is a property of
 * calling the LLM at all, not of remembering to account for it.
 */
class AccountingLlmClientTest {

    private final List<LlmCallLoggedEvent> published = new ArrayList<>();

    private static final LlmRequest REQUEST = new LlmRequest("gemini-3.1-flash",
        List.of(new LlmMessage("user", "hi")), 0.0, 100, List.of(), null);

    @AfterEach
    void clearContext() {
        LlmCallContextHolder.clear();
    }

    private AccountingLlmClient clientFor(LlmClient delegate) {
        return new AccountingLlmClient(delegate,
            event -> published.add((LlmCallLoggedEvent) event));
    }

    private static LlmClient generateReturning(LlmResponse response) {
        return new LlmClient() {
            @Override
            public LlmResponse generate(LlmRequest request) {
                return response;
            }

            @Override
            public void generateStream(LlmRequest request, Consumer<LlmResponseChunk> onChunk) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static LlmClient streamingChunks(List<LlmResponseChunk> chunks,
        RuntimeException toThrow) {
        return new LlmClient() {
            @Override
            public LlmResponse generate(LlmRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void generateStream(LlmRequest request, Consumer<LlmResponseChunk> onChunk) {
                chunks.forEach(onChunk);
                if (toThrow != null) {
                    throw toThrow;
                }
            }
        };
    }

    private static LlmResponseChunk chunk(String text, LlmUsage usage) {
        return new LlmResponseChunk(text, null, null, usage);
    }

    @Test
    void testGenerateReturnsDelegateResponseUnchanged() {
        LlmResponse expected = new LlmResponse("hello", new LlmUsage(10, 5, 15, 0, 0));

        LlmResponse actual = clientFor(generateReturning(expected)).generate(REQUEST);

        assertSame(expected, actual);
    }

    @Test
    void testGeneratePublishesOneEventWithTheResponseUsage() {
        LlmResponse response = new LlmResponse("hello", new LlmUsage(10, 5, 15, 3, 2));

        clientFor(generateReturning(response)).generate(REQUEST);

        assertEquals(1, published.size());
        LlmCallLoggedEvent event = published.get(0);
        assertEquals("gemini-3.1-flash", event.model());
        assertEquals(10, event.promptTokens());
        assertEquals(5, event.completionTokens());
        assertEquals(15, event.totalTokens());
        assertEquals(3, event.cachedTokens());
        assertEquals(2, event.thoughtTokens());
    }

    @Test
    void testAttributionIsTakenFromTheContextHolder() {
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID agentRunId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        LlmCallContextHolder.set(conversationId, messageId, agentRunId, userId, "chat",
            "assistant");

        clientFor(generateReturning(new LlmResponse("x", new LlmUsage(1, 1, 2, 0, 0))))
            .generate(REQUEST);

        LlmCallLoggedEvent event = published.get(0);
        assertEquals(conversationId, event.conversationId());
        assertEquals(messageId, event.messageId());
        assertEquals(agentRunId, event.agentRunId());
        assertEquals(userId, event.userId());
        assertEquals("chat", event.source());
        assertEquals("assistant", event.role());
    }

    /**
     * A call with no context must still be logged. Silently dropping it is what let summarizer
     * spend stay invisible; an "unknown" row is visibly wrong and therefore fixable.
     */
    @Test
    void testMissingContextStillLogsUnderUnknownSource() {
        clientFor(generateReturning(new LlmResponse("x", new LlmUsage(1, 1, 2, 0, 0))))
            .generate(REQUEST);

        LlmCallLoggedEvent event = published.get(0);
        assertEquals(LlmCallContextHolder.UNKNOWN_SOURCE, event.source());
        assertNull(event.conversationId());
        assertNull(event.userId());
    }

    @Test
    void testStreamForwardsEveryChunkInOrder() {
        List<LlmResponseChunk> chunks =
            List.of(chunk("a", null), chunk("b", null), chunk("c", new LlmUsage(7, 3, 10, 0, 0)));
        List<String> seen = new ArrayList<>();

        clientFor(streamingChunks(chunks, null)).generateStream(REQUEST,
            c -> seen.add(c.content()));

        assertEquals(List.of("a", "b", "c"), seen);
    }

    /** Usage arrives in the last chunk that carries it; that is the value to bill. */
    @Test
    void testStreamPublishesOnceWithTheLastUsageSeen() {
        List<LlmResponseChunk> chunks = List.of(chunk("a", null),
            chunk("b", new LlmUsage(5, 1, 6, 0, 0)), chunk("c", new LlmUsage(9, 4, 13, 2, 1)));

        clientFor(streamingChunks(chunks, null)).generateStream(REQUEST, c -> {
        });

        assertEquals(1, published.size());
        LlmCallLoggedEvent event = published.get(0);
        assertEquals(9, event.promptTokens());
        assertEquals(4, event.completionTokens());
        assertEquals(13, event.totalTokens());
    }

    /**
     * A stream that dies part-way still consumed tokens the provider billed for. Record what was
     * observed, then let the failure propagate unchanged.
     */
    @Test
    void testStreamThatFailsMidwayStillPublishesObservedUsageAndRethrows() {
        RuntimeException boom = new IllegalStateException("stream died");
        List<LlmResponseChunk> chunks =
            List.of(chunk("a", new LlmUsage(4, 2, 6, 0, 0)), chunk("b", null));

        RuntimeException thrown = assertThrows(IllegalStateException.class,
            () -> clientFor(streamingChunks(chunks, boom)).generateStream(REQUEST, c -> {
            }));

        assertSame(boom, thrown);
        assertEquals(1, published.size());
        assertEquals(4, published.get(0).promptTokens());
    }

    /** Nothing to bill: the provider never reported usage, so no row is invented. */
    @Test
    void testStreamWithNoUsageAtAllPublishesNothing() {
        clientFor(streamingChunks(List.of(chunk("a", null)), null)).generateStream(REQUEST, c -> {
        });

        assertTrue(published.isEmpty());
    }

    @Test
    void testDurationIsRecorded() {
        clientFor(generateReturning(new LlmResponse("x", new LlmUsage(1, 1, 2, 0, 0))))
            .generate(REQUEST);

        Integer durationMs = published.get(0).durationMs();
        assertTrue(durationMs != null && durationMs >= 0, "durationMs should be measured");
    }

    /** Accounting must never break the call it is accounting for. */
    @Test
    void testPublisherFailureDoesNotBreakTheCall() {
        LlmResponse expected = new LlmResponse("hello", new LlmUsage(1, 1, 2, 0, 0));
        AccountingLlmClient client = new AccountingLlmClient(generateReturning(expected), event -> {
            throw new IllegalStateException("event bus down");
        });

        assertSame(expected, client.generate(REQUEST));
    }

    private static final LlmGatewayInfo REPLAYED =
        new LlmGatewayInfo(GatewayCacheStatus.REPLAYED, "01KZ6A5K");

    private static LlmResponseChunk chunk(String text, LlmUsage usage, LlmGatewayInfo gateway) {
        return new LlmResponseChunk(text, null, null, usage, null, gateway);
    }

    /**
     * The decorator is the only link between the header the client read and the billing decision
     * the listener makes. Without this the whole gateway feature is untested end to end: the
     * classifier is proven, the pricing is proven, and the wire between them was not.
     */
    @Test
    void testGenerateForwardsGatewayStatusToTheEvent() {
        LlmResponse response = new LlmResponse("hello", new LlmUsage(10, 5, 15, 0, 0), REPLAYED);

        clientFor(generateReturning(response)).generate(REQUEST);

        LlmCallLoggedEvent event = published.get(0);
        assertEquals(GatewayCacheStatus.REPLAYED, event.gatewayCacheStatus());
        assertEquals("01KZ6A5K", event.gatewayLogId());
        assertTrue(event.replayedByGateway());
    }

    @Test
    void testGenerateWithoutGatewayLeavesStatusNull() {
        clientFor(generateReturning(new LlmResponse("x", new LlmUsage(1, 1, 2, 0, 0))))
            .generate(REQUEST);

        LlmCallLoggedEvent event = published.get(0);
        assertNull(event.gatewayCacheStatus());
        assertNull(event.gatewayLogId());
        assertFalse(event.replayedByGateway());
    }

    /**
     * The SDK stamps headers onto every chunk, including ones carrying no usage, so the gateway
     * status must be tracked independently of usage — reading it off whichever chunk happened to
     * carry both would lose it whenever they arrive separately, and the call would be billed.
     */
    @Test
    void testStreamCapturesGatewayStatusFromAChunkThatCarriesNoUsage() {
        List<LlmResponseChunk> chunks = List.of(chunk("a", null, REPLAYED),
            chunk("b", null, REPLAYED), chunk("c", new LlmUsage(9, 4, 13, 0, 0), null));

        clientFor(streamingChunks(chunks, null)).generateStream(REQUEST, c -> {
        });

        assertEquals(1, published.size());
        LlmCallLoggedEvent event = published.get(0);
        assertEquals(GatewayCacheStatus.REPLAYED, event.gatewayCacheStatus());
        assertEquals(9, event.promptTokens());
    }

    /**
     * A safety- or recitation-blocked call reached the provider and consumed tokens it billed for.
     * Throwing before reading usage left it with no llm_call_logs row at all, so that spend was
     * invisible in every cost view.
     */
    @Test
    void testGenerateRecordsUsageCarriedOnAFailedCallAndRethrows() {
        LlmUsage billed = new LlmUsage(1200, 0, 1200, 0, 0);
        LlmCallFailedException blocked = new LlmCallFailedException(
            "Gemini returned no text content (finishReason=SAFETY)", null, billed);
        LlmClient delegate = new LlmClient() {
            @Override
            public LlmResponse generate(LlmRequest request) {
                throw blocked;
            }

            @Override
            public void generateStream(LlmRequest request, Consumer<LlmResponseChunk> onChunk) {
                throw new UnsupportedOperationException();
            }
        };

        LlmCallFailedException thrown =
            assertThrows(LlmCallFailedException.class, () -> clientFor(delegate).generate(REQUEST));

        assertSame(blocked, thrown);
        assertEquals(1, published.size());
        assertEquals(1200, published.get(0).promptTokens());
    }

    /** Nothing reported, nothing to bill: no row is invented for a failure either. */
    @Test
    void testGenerateWithAFailureCarryingNoUsagePublishesNothing() {
        LlmClient delegate = new LlmClient() {
            @Override
            public LlmResponse generate(LlmRequest request) {
                throw new LlmCallFailedException("blocked", null, null);
            }

            @Override
            public void generateStream(LlmRequest request, Consumer<LlmResponseChunk> onChunk) {
                throw new UnsupportedOperationException();
            }
        };

        assertThrows(LlmCallFailedException.class, () -> clientFor(delegate).generate(REQUEST));

        assertTrue(published.isEmpty());
    }
}
