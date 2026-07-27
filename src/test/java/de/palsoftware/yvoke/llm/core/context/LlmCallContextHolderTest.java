package de.palsoftware.yvoke.llm.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Pins save-and-restore nesting, which is what orchestration depends on.
 *
 * <p>
 * A specialist runs nested inside the orchestrator's own agentic loop on the same thread —
 * {@code call_specialist} is an inline tool handler, so {@code OrchestrationService.runAgent}
 * re-enters itself. When the inner frame cleared this ThreadLocal on the way out, every
 * orchestrator call after the first delegation, including the final synthesis, was logged with no
 * conversation, run or user, and vanished from every per-entity cost view.
 */
class LlmCallContextHolderTest {

    @AfterEach
    void clear() {
        LlmCallContextHolder.clear();
    }

    @Test
    void testNestedContextRestoresTheOuterFrame() {
        UUID outerConv = UUID.randomUUID();
        UUID outerRun = UUID.randomUUID();
        LlmCallContextHolder.set(outerConv, null, outerRun, null, "orchestrator", "orchestrator");

        LlmCallContextHolder.Context previous = LlmCallContextHolder.get();
        try {
            LlmCallContextHolder.set(UUID.randomUUID(), null, UUID.randomUUID(), null,
                "orchestrator", "specialist");
            assertEquals("specialist", LlmCallContextHolder.get().role());
        } finally {
            LlmCallContextHolder.set(previous.conversationId(), previous.messageId(),
                previous.agentRunId(), previous.userId(), previous.source(), previous.role());
        }

        LlmCallContextHolder.Context after = LlmCallContextHolder.get();
        assertEquals(outerConv, after.conversationId(), "outer conversation must survive");
        assertEquals(outerRun, after.agentRunId(), "outer run must survive");
        assertEquals("orchestrator", after.role());
    }

    /** callWithContext already implements the correct discipline; prove it. */
    @Test
    void testCallWithContextRestoresTheOuterFrame() throws Exception {
        UUID outerConv = UUID.randomUUID();
        LlmCallContextHolder.set(outerConv, null, null, null, "orchestrator", "orchestrator");

        String inner = LlmCallContextHolder.callWithContext(UUID.randomUUID(), null, null, null,
            "orchestrator", "specialist", () -> LlmCallContextHolder.get().role());

        assertEquals("specialist", inner);
        assertEquals(outerConv, LlmCallContextHolder.get().conversationId());
        assertEquals("orchestrator", LlmCallContextHolder.get().role());
    }

    /** An outermost frame with nothing to restore must leave the thread clean. */
    @Test
    void testOutermostFrameClearsCompletely() throws Exception {
        LlmCallContextHolder.callWithContext(UUID.randomUUID(), null, null, null, "chat",
            "assistant", () -> null);

        assertNull(LlmCallContextHolder.get());
    }
}
