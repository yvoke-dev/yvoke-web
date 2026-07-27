package de.palsoftware.yvoke.chat.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BiFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CallSpecialistTool} — the MAS orchestrator's delegation gate. The whitelist
 * ({@code specialistNames}) is the control that stops the orchestrator delegating to a playbook
 * outside the active profile; a regression would let a hallucinated {@code playbook_name} through.
 * A single-element whitelist keeps the "not an available specialist" error string deterministic
 * (the suffix joins an unordered Set).
 */
class CallSpecialistToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private BiFunction<String, String, String> handler;
    private CallSpecialistTool tool;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        handler = mock(BiFunction.class);
        tool = new CallSpecialistTool(objectMapper, List.of("spec-a"), handler);
    }

    @Test
    void validDelegation_invokesHandler_returnsResult() {
        when(handler.apply("spec-a", "How do I configure X?")).thenReturn("SPECIALIST ANSWER");

        String out =
            tool.call("{\"playbook_name\":\"spec-a\",\"question\":\"How do I configure X?\"}");

        assertThat(out).isEqualTo("SPECIALIST ANSWER");
        verify(handler).apply("spec-a", "How do I configure X?");
    }

    @Test
    void unknownSpecialist_returnsNotAvailableError_handlerNotCalled() {
        String out = tool.call("{\"playbook_name\":\"spec-x\",\"question\":\"anything\"}");

        assertThat(out)
            .isEqualTo("Error: 'spec-x' is not an available specialist. Choose one of: spec-a.");
        verifyNoInteractions(handler);
    }

    @Test
    void missingPlaybookName_reportedAsNull() {
        String out = tool.call("{\"question\":\"anything\"}");

        assertThat(out)
            .isEqualTo("Error: 'null' is not an available specialist. Choose one of: spec-a.");
        verifyNoInteractions(handler);
    }

    @Test
    void blankQuestion_rejected() {
        String out = tool.call("{\"playbook_name\":\"spec-a\",\"question\":\"   \"}");

        assertThat(out).isEqualTo("Error: 'question' must not be empty.");
        verifyNoInteractions(handler);
    }

    @Test
    void missingQuestion_rejected() {
        String out = tool.call("{\"playbook_name\":\"spec-a\"}");

        assertThat(out).isEqualTo("Error: 'question' must not be empty.");
        verifyNoInteractions(handler);
    }

    @Test
    void malformedJson_returnsParseError_handlerNotCalled() {
        String out = tool.call("not json{");

        assertThat(out).startsWith("Error parsing arguments:");
        verifyNoInteractions(handler);
    }

    /**
     * A specialist that dies on a provider fault must not be reported as a bad tool call. The
     * handler invocation used to sit inside the JSON-parse try, so an HTTP 429 from the nested run
     * was logged as "Failed to parse call_specialist arguments" and handed back as an ordinary tool
     * result — after which RagService logged the call as having executed successfully. The model
     * can correct its own arguments; it cannot correct a 429, so the two must not share a catch.
     */
    @Test
    void handlerFailure_propagates_ratherThanMasqueradingAsParseError() {
        RuntimeException providerFailure = new IllegalStateException("429 . ");
        when(handler.apply("spec-a", "anything")).thenThrow(providerFailure);

        assertThatThrownBy(
            () -> tool.call("{\"playbook_name\":\"spec-a\",\"question\":\"anything\"}"))
            .isSameAs(providerFailure);
    }

    /** Cancellation must reach the run loop intact rather than becoming a tool result. */
    @Test
    void handlerCancellation_propagates() {
        CancellationException cancelled = new CancellationException("stopped");
        when(handler.apply("spec-a", "anything")).thenThrow(cancelled);

        assertThatThrownBy(
            () -> tool.call("{\"playbook_name\":\"spec-a\",\"question\":\"anything\"}"))
            .isSameAs(cancelled);
    }
}
