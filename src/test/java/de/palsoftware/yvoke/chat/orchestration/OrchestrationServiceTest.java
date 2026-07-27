package de.palsoftware.yvoke.chat.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.errors.ClientException;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProperties.Profile;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProperties.RoleConfig;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProperties.RoleDefaults;
import de.palsoftware.yvoke.llm.core.model.LlmMessage;
import de.palsoftware.yvoke.llm.core.model.LlmPart;
import de.palsoftware.yvoke.rag.core.model.AgenticRequest;
import de.palsoftware.yvoke.rag.core.model.RagResult;
import de.palsoftware.yvoke.rag.core.service.RagService;
import de.palsoftware.yvoke.rag.prompt.Playbook;
import de.palsoftware.yvoke.rag.prompt.PlaybookService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.tool.ToolCallback;

/**
 * Control-flow tests for {@link OrchestrationService} using a stubbed {@link RagService} that
 * drives the per-run tools (call_specialist / submit_review) exactly as a real LLM would — no LLM,
 * no DB.
 */
public class OrchestrationServiceTest {

    private RagService ragService;
    private PlaybookService playbookService;
    private AgentRunRepository runRepository;
    private AgentStepRepository stepRepository;
    private OrchestrationService service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // Tunables for the stubbed RagService behaviour.
    private int rejectFirstNReviews = 0; // reviewer rejects this many times, then approves
    private boolean orchestratorClarifies = false;
    private final AtomicInteger reviewCount = new AtomicInteger(0);

    private static final UUID CONV = UUID.randomUUID();
    private static final UUID MSG = UUID.randomUUID();
    private static final UUID RUN = UUID.randomUUID();

    @BeforeEach
    public void setUp() {
        ragService = mock(RagService.class);
        playbookService = mock(PlaybookService.class);
        runRepository = mock(AgentRunRepository.class);
        stepRepository = mock(AgentStepRepository.class);

        OrchestratorProperties props = new OrchestratorProperties(2, 8,
            new RoleDefaults(new RoleConfig("pro", "high"), new RoleConfig("pro", "high"),
                new RoleConfig("flash", "medium")),
            List.of(new Profile("OIM", "oim-orchestrator", "oim-orchestrator-reviewer",
                List.of("spec-a", "spec-b"), null, null, null)));

        service = new OrchestrationService(ragService, playbookService, props, null, runRepository,
            stepRepository, objectMapper, null);

        when(playbookService.getPlaybook(any())).thenAnswer(inv -> {
            String name = inv.getArgument(0);
            return Optional.of(playbook(name));
        });

        when(ragService.generateAgenticAnswer(any(), any())).thenAnswer(inv -> {
            AgenticRequest req = inv.getArgument(0);
            @SuppressWarnings("unchecked")
            Consumer<String> sink = inv.getArgument(1);
            return handleAgentCall(req, sink);
        });
    }

    private RagResult handleAgentCall(AgenticRequest req, Consumer<String> sink) throws Exception {
        String extraName = req.extraTools() == null || req.extraTools().isEmpty() ? ""
            : req.extraTools().get(0).getToolDefinition().name();

        if ("call_specialist".equals(extraName)) {
            // Orchestrator turn.
            if (orchestratorClarifies) {
                sink.accept(
                    "<clarifying-question>\n  <question>Which version?</question>\n</clarifying-question>");
                return result("", "Which version?", List.of("9.3.1", "10.0"));
            }
            // Delegate to one specialist, then synthesise.
            for (ToolCallback tc : req.extraTools()) {
                tc.call("{\"playbook_name\":\"spec-a\",\"question\":\"sub-question\"}");
            }
            return result("final orchestrated answer [chunk_id=" + UUID.randomUUID() + "]", null,
                null);
        }

        if ("submit_review".equals(extraName)) {
            boolean approve = reviewCount.getAndIncrement() >= rejectFirstNReviews;
            for (ToolCallback tc : req.extraTools()) {
                tc.call("{\"approved\":" + approve + ",\"feedback\":\"needs work\"}");
            }
            return result("review done", null, null);
        }

        // Specialist turn — returns an answer plus a tool-result (evidence).
        return specialistResult();
    }

    // ------------------------------------------------------------------------------------------

    @Test
    public void happyPath_approvedFirstTime() {
        rejectFirstNReviews = 0;

        OrchestrationService.OrchestrationResult r =
            service.runOrchestration(CONV, MSG, "cross-topic question", List.of(), RUN, "OIM");

        assertThat(r.status()).isEqualTo("done");
        assertThat(r.content()).contains("final orchestrated answer");
        assertThat(r.content()).doesNotContain("did not pass automated review");

        verify(runRepository).create(eq(RUN), eq(CONV), eq("OIM"), any());
        verify(runRepository).finish(eq(RUN), eq(MSG), eq("done"), eq(0), any(), anyInt(), anyInt(),
            anyInt(), anyInt(), anyInt(), any());
        verify(stepRepository).insert(any(), eq(RUN), anyInt(), eq("orchestrator"), anyInt(), any(),
            any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), anyInt(), anyInt(),
            anyInt());
        verify(stepRepository).insert(any(), eq(RUN), anyInt(), eq("specialist"), anyInt(), any(),
            any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), anyInt(), anyInt(),
            anyInt());
        verify(stepRepository).insert(any(), eq(RUN), anyInt(), eq("reviewer"), anyInt(), any(),
            any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), anyInt(), anyInt(),
            anyInt());
    }

    @Test
    public void rejectedTwice_deliversFlagged() {
        rejectFirstNReviews = 99; // always reject

        OrchestrationService.OrchestrationResult r =
            service.runOrchestration(CONV, MSG, "question", List.of(), RUN, "OIM");

        assertThat(r.status()).isEqualTo("delivered_flagged");
        assertThat(r.content()).contains("did not pass automated review");
        // 3 review attempts (round 0,1,2), review_rounds recorded == 2.
        verify(runRepository).finish(eq(RUN), eq(MSG), eq("delivered_flagged"), eq(2), any(),
            anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), any());
        verify(stepRepository, times(3)).insert(any(), eq(RUN), anyInt(), eq("reviewer"), anyInt(),
            any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), anyInt(), anyInt(),
            anyInt());
    }

    @Test
    public void specialistOrOrchestratorClarify_skipsReview() {
        orchestratorClarifies = true;

        OrchestrationService.OrchestrationResult r =
            service.runOrchestration(CONV, MSG, "what's new", List.of(), RUN, "OIM");

        assertThat(r.status()).isEqualTo("clarify");
        assertThat(r.content()).contains("clarifying-question");
        verify(runRepository).finish(eq(RUN), eq(MSG), eq("clarify"), anyInt(), any(), anyInt(),
            anyInt(), anyInt(), anyInt(), anyInt(), any());
        // No reviewer step when a clarification is surfaced.
        verify(stepRepository, never()).insert(any(), any(), anyInt(), eq("reviewer"), anyInt(),
            any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), anyInt(), anyInt(),
            anyInt());
    }

    // --- Failure reporting -------------------------------------------------------------------
    //
    // The run this suite was extended for died on an HTTP 429 and recorded exactly "429 . " in
    // agent_runs.error, because the catch persisted only e.getMessage(). Everything needed to
    // explain it — the exception type, the failing role, the round — was in scope and discarded,
    // so diagnosing the run meant reading container logs.

    @Test
    public void providerFailure_recordsADiagnosisRatherThanTheBareSdkMessage() {
        doThrow(new ClientException(429, "", "")).when(ragService).generateAgenticAnswer(any(),
            any());

        assertThatThrownBy(
            () -> service.runOrchestration(CONV, MSG, "question", List.of(), RUN, "OIM"))
            .isInstanceOf(ClientException.class);

        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(runRepository).finish(eq(RUN), eq(MSG), eq("error"), anyInt(), any(), anyInt(),
            anyInt(), anyInt(), anyInt(), anyInt(), error.capture());

        assertThat(error.getValue()).isNotEqualTo("429 . ");
        assertThat(error.getValue()).contains("ClientException").contains("429")
            .contains("rate limit");
        assertThat(error.getValue()).contains("role=orchestrator").contains("round=0");
    }

    /**
     * A user pressing Stop is not a fault. {@link java.util.concurrent.CancellationException} is a
     * RuntimeException, so it landed in the same catch and was filed as a failed run with a
     * meaningless error string.
     */
    @Test
    public void cancellation_isRecordedAsCancelled_notAsAFailedRun() {
        doThrow(new CancellationException("stopped")).when(ragService).generateAgenticAnswer(any(),
            any());

        assertThatThrownBy(
            () -> service.runOrchestration(CONV, MSG, "question", List.of(), RUN, "OIM"))
            .isInstanceOf(CancellationException.class);

        verify(runRepository).finish(eq(RUN), eq(MSG), eq("cancelled"), anyInt(), any(), anyInt(),
            anyInt(), anyInt(), anyInt(), anyInt(), isNull());
    }

    @Test
    public void providerFailure_persistsTheStepThatDied() {
        doThrow(new ClientException(429, "", "")).when(ragService).generateAgenticAnswer(any(),
            any());

        assertThatThrownBy(
            () -> service.runOrchestration(CONV, MSG, "question", List.of(), RUN, "OIM"))
            .isInstanceOf(ClientException.class);

        ArgumentCaptor<String> stepError = ArgumentCaptor.forClass(String.class);
        verify(stepRepository).insertFailed(any(), eq(RUN), anyInt(), eq("orchestrator"), anyInt(),
            eq("oim-orchestrator"), any(), any(), any(), any(), stepError.capture());
        assertThat(stepError.getValue()).contains("429");
    }

    /** A user Stop is not a crash, so it must not litter the timeline with a failed step. */
    @Test
    public void cancellation_persistsNoFailedStep() {
        doThrow(new CancellationException("stopped")).when(ragService).generateAgenticAnswer(any(),
            any());

        assertThatThrownBy(
            () -> service.runOrchestration(CONV, MSG, "question", List.of(), RUN, "OIM"))
            .isInstanceOf(CancellationException.class);

        verify(stepRepository, never()).insertFailed(any(), any(), anyInt(), any(), anyInt(), any(),
            any(), any(), any(), any(), any());
    }

    /**
     * Playbook resolution runs before the run row is created, so a missing playbook used to leave
     * no agent_runs row at all — the same "go read the logs" symptom this work exists to remove.
     */
    @Test
    public void failureBeforeTheRunStarts_stillLeavesAVisibleRun() {
        when(playbookService.getPlaybook(any())).thenReturn(Optional.empty());

        assertThatThrownBy(
            () -> service.runOrchestration(CONV, MSG, "question", List.of(), RUN, "OIM"))
            .isInstanceOf(IllegalStateException.class);

        verify(runRepository).create(eq(RUN), eq(CONV), eq("OIM"), any());
        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(runRepository).finish(eq(RUN), any(), eq("error"), anyInt(), any(), anyInt(),
            anyInt(), anyInt(), anyInt(), anyInt(), error.capture());
        assertThat(error.getValue()).contains("playbook");
    }

    // ------------------------------------------------------------------------------------------

    private static Playbook playbook(String name) {
        return new Playbook(name, name + " title", name + " description", "TEMPLATE for " + name,
            List.of(), false, Instant.now(), Instant.now());
    }

    private static RagResult result(String answerText, String clarifyingQuestion,
        List<String> options) {
        LlmMessage assistant = new LlmMessage("assistant", answerText,
            List.of(new LlmPart("text", answerText, null, null)), null, null, null);
        return new RagResult(List.of(), List.of(assistant), null, List.of(), 10, 20, 30, 0, 5,
            clarifyingQuestion, options);
    }

    private static RagResult specialistResult() {
        LlmMessage assistant = new LlmMessage("assistant", "specialist answer",
            List.of(new LlmPart("text", "specialist answer", null, null)), null, null, null);
        LlmMessage toolMsg = new LlmMessage("tool", "query_json_objects rows: [from 9.2.2 to 9.3]",
            null, null, "call-1", "query_json_objects");
        return new RagResult(List.of(UUID.randomUUID()), List.of(assistant, toolMsg), null,
            List.of(UUID.randomUUID()), 5, 10, 15, 0, 2, null, null);
    }
}
