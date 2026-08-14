package de.palsoftware.yvoke.chat.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.InstanceOfAssertFactories.type;
import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.errors.ClientException;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProperties.Profile;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProperties.RoleConfig;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProperties.RoleDefaults;
import de.palsoftware.yvoke.llm.core.context.LlmCallContextHolder;
import de.palsoftware.yvoke.llm.core.model.LlmMessage;
import de.palsoftware.yvoke.llm.core.model.LlmPart;
import de.palsoftware.yvoke.rag.core.model.SeenChunks;
import de.palsoftware.yvoke.rag.retrieval.ChunkBlocks;
import de.palsoftware.yvoke.rag.retrieval.HybridSearchResult;
import de.palsoftware.yvoke.rag.retrieval.TelemetryInfo;
import java.util.Map;
import de.palsoftware.yvoke.rag.core.model.AgenticRequest;
import de.palsoftware.yvoke.rag.core.model.RagResult;
import de.palsoftware.yvoke.rag.core.service.RagService;
import de.palsoftware.yvoke.rag.prompt.Playbook;
import de.palsoftware.yvoke.rag.prompt.PlaybookService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.tool.ToolCallback;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

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

    /** Extra JSON fields the scripted reviewer appends to its verdict, e.g. citation_fixes. */
    private String rejectionExtras = "";
    private boolean orchestratorClarifies = false;
    private String specialistClarifyingQuestion = null; // when set, the specialist asks the user
    private final AtomicInteger reviewCount = new AtomicInteger(0);
    private final List<String> specialistToolResults =
        Collections.synchronizedList(new ArrayList<>());

    private final AtomicReference<LlmCallContextHolder.Context> contextInsideOrchestratorBeforeDelegation =
        new AtomicReference<>();
    private final AtomicReference<LlmCallContextHolder.Context> contextInsideOrchestratorAfterDelegation =
        new AtomicReference<>();

    /** The chunk the orchestrator's draft cites — so it must reach the reviewer as text. */
    private static final UUID CITED_CHUNK = UUID.fromString("8f7c1a2b-3d4e-4f50-8a1b-2c3d4e5f6071");

    /** Retrieved by the same specialist and cited by nobody — the manifest's whole purpose. */
    private static final UUID UNCITED_CHUNK =
        UUID.fromString("2c3d4e5f-1122-4333-8444-666666666666");

    private static final String CITED_BODY = "The Person table holds identities.";
    private static final String UNCITED_BODY = "Install the kit before configuring anything.";

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
            contextInsideOrchestratorBeforeDelegation.set(LlmCallContextHolder.get());
            for (ToolCallback tc : req.extraTools()) {
                specialistToolResults
                    .add(tc.call("{\"playbook_name\":\"spec-a\",\"question\":\"sub-question\"}"));
            }
            // The specialist frame has now returned. Whatever it did to the ThreadLocal must be
            // undone by the time the orchestrator resumes on this same thread.
            contextInsideOrchestratorAfterDelegation.set(LlmCallContextHolder.get());
            // Cites a chunk the specialist actually retrieved. A random id here would make the
            // draft cite nothing the evidence contains, so cite-scoping would withhold everything
            // and the reviewer assertions would be testing the fallback rather than the feature.
            return result("final orchestrated answer [chunk_id=" + CITED_CHUNK + "]", null, null);
        }

        if ("submit_review".equals(extraName)) {
            boolean approve = reviewCount.getAndIncrement() >= rejectFirstNReviews;
            for (ToolCallback tc : req.extraTools()) {
                tc.call("{\"approved\":" + approve + ",\"feedback\":\"needs work\""
                    + rejectionExtras + "}");
            }
            return result("review done", null, null);
        }

        // Specialist turn — returns an answer plus a tool-result (evidence).
        return specialistResult();
    }

    // ------------------------------------------------------------------------------------------

    /**
     * The specialist-call budget is per RUN, not per round, and exhausting it must be a RETURNED
     * STRING rather than a thrown exception. The distinction is the one CLAUDE.md draws: the model
     * can correct its own behaviour (here, stop delegating and synthesise from what it already
     * has), so it gets told; only infrastructure failures throw. Throwing instead would abort a run
     * that was proceeding perfectly well and surface a system error to the user. Resetting the
     * counter per review round would also defeat the cap entirely — a rejected review restarts the
     * orchestrator, so a per-round budget is effectively unbounded.
     */
    @Test
    public void specialistBudgetIsPerRunAndRefusalIsAReturnedStringNotAnException() {
        rejectFirstNReviews = 2; // forces three orchestrator rounds, i.e. three delegations
        OrchestratorProperties oneCall = new OrchestratorProperties(2, 1,
            new RoleDefaults(new RoleConfig("pro", "high"), new RoleConfig("pro", "high"),
                new RoleConfig("flash", "medium")),
            List.of(new Profile("OIM", "oim-orchestrator", "oim-orchestrator-reviewer",
                List.of("spec-a", "spec-b"), null, null, null)));
        OrchestrationService budgeted = new OrchestrationService(ragService, playbookService,
            oneCall, null, runRepository, stepRepository, objectMapper, null);

        OrchestrationService.OrchestrationResult r =
            budgeted.runOrchestration(CONV, MSG, "question", List.of(), RUN, "OIM");

        assertThat(r).as("exhausting the budget must not abort the run").isNotNull();
        assertThat(specialistToolResults).as("the model must be TOLD, not thrown at")
            .anyMatch(s -> s != null && s.contains("budget exhausted"));
        // Per-run: only the first delegation actually ran a specialist, across all three rounds.
        verify(stepRepository, times(1)).insert(any(), eq(RUN), anyInt(), eq("specialist"),
            anyInt(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), anyInt(),
            anyInt(), anyInt());
    }

    /**
     * A specialist runs re-entrantly inside the orchestrator's own agentic loop, on the SAME thread
     * — {@code call_specialist} is an inline tool handler, so {@code runAgent} calls itself. Its
     * {@code finally} must therefore RESTORE the caller's {@link LlmCallContextHolder} context, not
     * clear it: clearing would leave every orchestrator call after the first delegation (including
     * the final synthesis) with no conversation, run or user, so that spend would be logged as
     * {@code source="unknown"} and vanish from every per-conversation and per-run cost view.
     * Simplifying the {@code finally} to {@code clear()} is the natural-looking refactor that
     * breaks this, and nothing else in the suite would notice.
     */
    @Test
    public void nestedSpecialistRestoresTheOrchestratorsCallContext() {
        rejectFirstNReviews = 0;

        service.runOrchestration(CONV, MSG, "cross-topic question", List.of(), RUN, "OIM");

        LlmCallContextHolder.Context before = contextInsideOrchestratorBeforeDelegation.get();
        LlmCallContextHolder.Context after = contextInsideOrchestratorAfterDelegation.get();

        assertThat(before).as("the orchestrator frame must have set its own context").isNotNull();
        assertThat(before.role()).isEqualTo("orchestrator");
        assertThat(after).as("the specialist frame must not leave the orchestrator unattributed")
            .isNotNull();
        assertThat(after.role()).isEqualTo("orchestrator");
        assertThat(after.conversationId()).isEqualTo(CONV);
        assertThat(after.agentRunId()).isEqualTo(RUN);
        assertThat(after.source()).isEqualTo("orchestrator");
    }

    /**
     * Two different strings, deliberately. The specialist's MODEL query is its playbook template
     * prepended to the sub-question ({@code template + "\n\n---\n\n" + question}) — that template
     * is the specialist's entire instruction set, so dropping it turns a tuned specialist into a
     * bare model answering a fragment of a question with none of its search strategy. The TRACE
     * input stored on {@code agent_steps} is the bare sub-question, because the trace exists to
     * show an operator what the orchestrator DELEGATED; burying it under a multi-kilobyte template
     * that is identical on every call of that playbook makes the timeline unreadable and hides the
     * one field that differs between steps. Passing {@code query} to both — the obvious
     * simplification, since {@code runAgent} already takes them adjacently — is silent: the run
     * still succeeds and the answer is unchanged, only the timeline degrades.
     */
    @Test
    public void theSpecialistReceivesItsPlaybookTemplateWhileTheTraceKeepsTheBareSubQuestion() {
        rejectFirstNReviews = 0;

        service.runOrchestration(CONV, MSG, "cross-topic question", List.of(), RUN, "OIM");

        ArgumentCaptor<AgenticRequest> requests = ArgumentCaptor.forClass(AgenticRequest.class);
        verify(ragService, times(3)).generateAgenticAnswer(requests.capture(), any());
        // The specialist is the only turn run without a per-run tool (call_specialist /
        // submit_review).
        AgenticRequest specialist = requests.getAllValues().stream()
            .filter(r -> r.extraTools() == null || r.extraTools().isEmpty()).findFirst()
            .orElseThrow();

        assertThat(specialist.query())
            .as("the specialist must be instructed by its own playbook, not by the fragment alone")
            .isEqualTo("TEMPLATE for spec-a\n\n---\n\nsub-question");

        ArgumentCaptor<String> traceInput = ArgumentCaptor.forClass(String.class);
        verify(stepRepository).insert(any(), eq(RUN), anyInt(), eq("specialist"), anyInt(), any(),
            any(), any(), traceInput.capture(), any(), any(), any(), anyInt(), anyInt(), anyInt(),
            anyInt(), anyInt());

        assertThat(traceInput.getValue())
            .as("the timeline records what was delegated, not the boilerplate around it")
            .isEqualTo("sub-question");
    }

    /**
     * The reviewer's entire job is to check the candidate answer against the evidence the
     * specialists actually retrieved — its task text says so in as many words ("the ONLY basis for
     * validation"). Handing it the conversation history, or any retrieval tool, converts validation
     * into a second generation: it can then "confirm" a claim from the chat log or from a fresh
     * search that the answer was never grounded in, which is precisely the failure the review round
     * exists to catch. The corruption is invisible — an approved run looks identical either way,
     * the status is still {@code done}, the tokens still accumulate and every repository write
     * still happens — so no other test in this suite or the IT suite would go red. And the edit is
     * a one-token slip: {@code history} is a parameter in scope, sitting in the same positional
     * slot of the same 12-argument {@code runAgent} call that the orchestrator turn (which
     * legitimately passes it) makes twenty lines above. {@code allowedTools} is asserted exactly
     * rather than by containment for the same reason: adding {@code search_corpus} to that list is
     * a one-word change that would otherwise break nothing.
     */
    @Test
    public void theReviewerIsEvidenceBoundWithNoHistoryAndNoSearchTools() {
        rejectFirstNReviews = 0;
        List<LlmMessage> history = List.of(new LlmMessage("user", "an earlier turn",
            List.of(new LlmPart("text", "an earlier turn", null, null)), null, null, null));

        service.runOrchestration(CONV, MSG, "cross-topic question", history, RUN, "OIM");

        ArgumentCaptor<AgenticRequest> requests = ArgumentCaptor.forClass(AgenticRequest.class);
        verify(ragService, times(3)).generateAgenticAnswer(requests.capture(), any());

        AgenticRequest orchestrator = requests.getAllValues().stream()
            .filter(q -> q.extraTools() != null && !q.extraTools().isEmpty()
                && "call_specialist".equals(q.extraTools().get(0).getToolDefinition().name()))
            .findFirst().orElseThrow();
        AgenticRequest reviewer = requests.getAllValues().stream()
            .filter(q -> q.extraTools() != null && !q.extraTools().isEmpty()
                && "submit_review".equals(q.extraTools().get(0).getToolDefinition().name()))
            .findFirst().orElseThrow();

        assertThat(orchestrator.history())
            .as("history WAS available — the reviewer is denied it deliberately, not by accident")
            .isEqualTo(history);
        assertThat(reviewer.history())
            .as("the reviewer judges the answer against the evidence, never against the chat")
            .isNull();
        // get_section is deliberately absent, and its absence is the contract rather than an
        // economy. It resolves a chunk id to the whole enclosing SECTION, so a reviewer holding it
        // judges claims against sibling text no specialist retrieved — the evidence base silently
        // becomes wider than what the answer was built from. With the cited sources supplied in
        // full there is nothing it is entitled to fetch: a claim those sources do not support is a
        // citation defect for the review loop to correct, not something to go looking for.
        assertThat(reviewer.allowedTools())
            .as("validate-only: no tool may widen the reviewer's evidence base")
            .containsExactly("verify_citations");
    }

    /**
     * The orchestrator decides WHICH specialist gets a sub-question, and the only account of what
     * each one is for lives in this roster. {@code CallSpecialistTool}'s schema does carry the bare
     * names as an enum, so losing the roster does not make delegation impossible — it makes it
     * blind: the model sees {@code spec-a} and {@code spec-b} with nothing to tell them apart and
     * routes a database question to the install-kit specialist as readily as to the right one. That
     * failure is completely silent, because the wrong specialist still answers, the reviewer still
     * validates that answer against the evidence THAT specialist gathered, and the run still ends
     * {@code done} with every agent_runs / agent_steps write intact — the only casualty is answer
     * quality, which no assertion in this suite measures. Nothing else would notice the loss
     * either: the stubbed RagService picks the playbook name itself instead of reading it out of
     * the prompt, so every existing test delegates to {@code spec-a} whether the roster was
     * rendered or not.
     */
    @Test
    public void theOrchestratorSystemPromptCarriesTheSpecialistRoster() {
        rejectFirstNReviews = 0;

        service.runOrchestration(CONV, MSG, "cross-topic question", List.of(), RUN, "OIM");

        ArgumentCaptor<AgenticRequest> requests = ArgumentCaptor.forClass(AgenticRequest.class);
        verify(ragService, times(3)).generateAgenticAnswer(requests.capture(), any());
        AgenticRequest orchestrator = requests.getAllValues().stream()
            .filter(q -> q.extraTools() != null && !q.extraTools().isEmpty()
                && "call_specialist".equals(q.extraTools().get(0).getToolDefinition().name()))
            .findFirst().orElseThrow();

        // The separator renderRoster writes is an EM DASH (U+2014), spelled below as a unicode
        // escape rather than pasted, so the expectation cannot be turned into a hyphen or an en
        // dash by an editor or an encoding-unaware copy without anyone seeing it.
        assertThat(orchestrator.systemPromptOverride())
            .as("the playbook is the base of the prompt and the roster is appended to it")
            .startsWith("TEMPLATE for oim-orchestrator").contains("\n\n## Available specialists\n")
            .contains("- **spec-a** — spec-a title: spec-a description\n")
            .contains("- **spec-b** — spec-b title: spec-b description\n");
    }

    /**
     * S6.14: the reviewer's task text tells it, in as many words, that the evidence block is "the
     * ONLY basis for validation". When the orchestrator answers from its own weights and delegates
     * to nobody there IS no evidence — and that is precisely the run most in need of a rejection,
     * because an ungrounded answer is exactly what the review round exists to catch. Rendering the
     * section as a bare header followed by whitespace does not communicate that: a model reading a
     * heading with nothing under it is as likely to treat it as "omitted for brevity" as "empty",
     * and it then approves on its own knowledge, which is the failure dressed up as a passed
     * review. The literal turns the absence into a fact stated in the prompt.
     *
     * <p>
     * Nothing else in this suite reaches the branch at all: every other test's stubbed orchestrator
     * delegates to {@code spec-a}, so {@code st.evidence} is never empty and the {@code if} is
     * never taken. Deleting the marker leaves the run {@code done}, the status, the tokens and
     * every repository write identical, and the whole file green.
     */
    @Test
    public void aReviewWithNoHarvestedEvidenceSaysSoExplicitly() {
        // An orchestrator that answers directly: it never invokes call_specialist, so nothing is
        // harvested and the evidence list reaches the reviewer empty.
        // doAnswer(...).when(...) rather than when(...).thenAnswer(...): re-stubbing through when()
        // would first invoke the answer registered in setUp with null arguments.
        doAnswer(inv -> {
            AgenticRequest req = inv.getArgument(0);
            String extraName = req.extraTools() == null || req.extraTools().isEmpty() ? ""
                : req.extraTools().get(0).getToolDefinition().name();
            if ("submit_review".equals(extraName)) {
                for (ToolCallback tc : req.extraTools()) {
                    tc.call("{\"approved\":true,\"feedback\":\"\"}");
                }
                return result("review done", null, null);
            }
            return result("an answer nobody was asked to ground", null, null);
        }).when(ragService).generateAgenticAnswer(any(), any());

        service.runOrchestration(CONV, MSG, "cross-topic question", List.of(), RUN, "OIM");

        ArgumentCaptor<AgenticRequest> requests = ArgumentCaptor.forClass(AgenticRequest.class);
        // Orchestrator + reviewer only: the absence of a third call IS the "delegated to nobody".
        verify(ragService, times(2)).generateAgenticAnswer(requests.capture(), any());
        AgenticRequest reviewer = requests.getAllValues().stream()
            .filter(q -> q.extraTools() != null && !q.extraTools().isEmpty()
                && "submit_review".equals(q.extraTools().get(0).getToolDefinition().name()))
            .findFirst().orElseThrow();

        assertThat(reviewer.query())
            .as("an empty evidence section must be STATED, not left as a header over whitespace")
            .contains("## Evidence gathered by the specialists (the ONLY basis for validation)\n"
                + "(no evidence was captured)");
    }

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

    /**
     * S6.5/S6.6. A reviewer that never calls {@code submit_review} has reviewed NOTHING, and the
     * fallback is what decides whether that silence is read as consent. Degrade it to an approval —
     * the shape {@code new Verdict(true, "", List.of())} — and an answer no reviewer ever judged
     * ships as {@code done}, with {@code agent_runs} recording an approved verdict: the review
     * round becomes a cost the product pays and a guarantee it does not provide, and the failure is
     * invisible because an unreviewed answer looks exactly like a reviewed one.
     *
     * <p>
     * The second half is what the rejection is FOR. The orchestrator is re-run with its own draft
     * and the feedback appended, and that appended text is the only difference between round 1 and
     * round 2 — everything else (question, history, system prompt, tools) is identical. Lose the
     * append and every round asks the model the byte-identical question, so it produces the
     * byte-identical answer, so the reviewer rejects it again: a rejected run burns
     * {@code maxReviewRounds + 1} full pro-model turns to deliver the same text it already had
     * after the first one, flagged. The draft half of the append is pinned by
     * {@link #aRevisionCarriesTheAnswerItIsAskedToRevise()}, which is where the reasoning for
     * including it lives; this test pins the exact rendering the two halves produce together.
     *
     * <p>
     * Neither string appears anywhere in the test sources today. The stubbed reviewer in
     * {@code setUp} ALWAYS calls {@code submit_review}, so the fallback is unreachable from every
     * other test; and {@code evidenceAccumulatesAcrossReviewRounds…} captures only the REVIEWER
     * requests, never round 2's orchestrator query. Both assertions here are on outputs a
     * regression cannot fake: how many rounds actually ran, and the exact query the second
     * orchestrator turn received.
     */
    @Test
    public void aReviewerThatSubmitsNoVerdictRejectsAndItsFeedbackReachesTheNextRound() {
        // A reviewer that answers in prose instead of calling its tool — a truncated response, a
        // refusal, or a model that "reviewed" in text. doAnswer(...).when(...) rather than
        // when(...).thenAnswer(...): re-stubbing through when() would first invoke the answer
        // registered in setUp with null arguments.
        doAnswer(inv -> {
            AgenticRequest req = inv.getArgument(0);
            String extraName = req.extraTools() == null || req.extraTools().isEmpty() ? ""
                : req.extraTools().get(0).getToolDefinition().name();
            if ("submit_review".equals(extraName)) {
                return result("The answer looks fine to me.", null, null);
            }
            return result("candidate answer", null, null);
        }).when(ragService).generateAgenticAnswer(any(), any());

        OrchestrationService.OrchestrationResult r =
            service.runOrchestration(CONV, MSG, "cross-topic question", List.of(), RUN, "OIM");

        ArgumentCaptor<AgenticRequest> requests = ArgumentCaptor.forClass(AgenticRequest.class);
        // maxReviewRounds=2, never approved: rounds 0, 1 and 2 each run an orchestrator and a
        // reviewer turn. An approving fallback breaks out of the loop after the first pair.
        verify(ragService, times(6)).generateAgenticAnswer(requests.capture(), any());
        List<AgenticRequest> orchestratorTurns = requests.getAllValues().stream()
            .filter(q -> q.extraTools() != null && !q.extraTools().isEmpty()
                && "call_specialist".equals(q.extraTools().get(0).getToolDefinition().name()))
            .toList();

        assertThat(orchestratorTurns).as("a missing verdict is a rejection, so the run revised")
            .hasSize(3);
        assertThat(orchestratorTurns.get(0).query())
            .as("round 1 is asked the bare question — the header only exists after a rejection")
            .isEqualTo("cross-topic question");
        // A synthesised rejection carries no structured lists at all, which is exactly the case
        // isCitationOnly() must answer FALSE for: with nothing itemised, "every objection is a
        // citation fix" is unknowable, and banning delegation on that basis would strand a run
        // that genuinely needs a search.
        assertThat(orchestratorTurns.get(1).query())
            .as("a revision must be told WHAT to revise, or it reproduces the same answer")
            .isEqualTo("## Reviewer feedback to address\nReviewer did not submit a verdict."
                + "\n\nRevise the answer you just gave. Keep everything the reviewer did not "
                + "object to. Delegate again only for material that is genuinely missing above.");
        assertThat(orchestratorTurns.get(1).priorMessages())
            .as("the question and the draft are in the conversation this turn continues")
            .isNotNull();

        assertThat(r.status()).as("silence is not approval").isEqualTo("delivered_flagged");
        assertThat(r.content()).as("and the user is told why, in the reviewer's own words")
            .contains("Reviewer notes: Reviewer did not submit a verdict.");
    }

    /**
     * A revision must carry the answer it is revising. The prompt says "revise your previous
     * answer", and the previous answer was never in it — so the orchestrator was asked to edit a
     * text it had never seen, with the evidence behind it held only by the reviewer. It cannot
     * revise under those conditions; it can only research the question again from nothing, which is
     * exactly what it did.
     *
     * <p>
     * Measured on run {@code ec419a8a}, where the reviewer's sole objection was one mis-numbered
     * citation: round 2 re-ran the full specialist search at 216,667 prompt tokens to fix a
     * reference number it could have corrected by editing one character. The run spent 539,391
     * tokens, roughly double what it needed, and died on {@code HTTP 429} at the last reviewer turn
     * — a rate limit the duplicated research is what provoked. From the user's seat the visible
     * symptom is the answer restarting from the beginning instead of finishing.
     *
     * <p>
     * The orchestrator therefore keeps ONE conversation for the whole run: round 2 continues the
     * transcript that produced the draft instead of opening a fresh call. That is what
     * {@code priorMessages} is for — {@code history} cannot serve, because
     * {@code RagService.buildInitialMessages} flattens it to user/assistant content strings and
     * drops every {@code tool} message, which is precisely the material a revision needs. Asserting
     * on the seed rather than on rendered prompt text is deliberate: the draft is carried
     * structurally now, so a string assertion over {@code query()} would pass while proving
     * nothing.
     */
    @Test
    public void aRevisionContinuesTheConversationThatProducedTheDraft() {
        rejectFirstNReviews = 1;

        service.runOrchestration(CONV, MSG, "cross-topic question", List.of(), RUN, "OIM");

        List<AgenticRequest> orchestratorTurns = capturedTurns("call_specialist");
        assertThat(orchestratorTurns).as("one rejection, so two orchestrator turns").hasSize(2);

        assertThat(orchestratorTurns.get(0).priorMessages())
            .as("the first turn starts a conversation, so it seeds nothing").isNull();
        assertThat(orchestratorTurns.get(0).query()).isEqualTo("cross-topic question");

        List<LlmMessage> seed = orchestratorTurns.get(1).priorMessages();
        assertThat(seed).as("the revision must continue the previous turn's conversation")
            .isNotNull();
        assertThat(seed).as("and that conversation ends with the draft under review")
            .last(as(type(LlmMessage.class))).satisfies(m -> {
                assertThat(m.role()).isEqualTo("assistant");
                assertThat(m.content()).contains("final orchestrated answer");
            });
        assertThat(orchestratorTurns.get(1).query())
            .as("the new turn says only what is new — what to change").contains("needs work");
        assertThat(orchestratorTurns.get(1).history())
            .as("the chat history is already inside the seed; re-sending it would duplicate")
            .isNull();
    }

    /**
     * The reviewer must not inherit the orchestrator's conversation. It is a validate-only check
     * against the supplied evidence, and a reviewer holding the orchestrator's transcript would see
     * its own previous notes and every earlier draft — it would be reviewing a negotiation it took
     * part in rather than judging an answer against sources.
     */
    @Test
    public void theReviewerNeverInheritsTheOrchestratorsConversation() {
        rejectFirstNReviews = 1;

        service.runOrchestration(CONV, MSG, "cross-topic question", List.of(), RUN, "OIM");

        assertThat(capturedTurns("submit_review")).hasSize(2).allSatisfy(r -> {
            assertThat(r.priorMessages()).as("no seeded transcript").isNull();
            assertThat(r.history()).as("and no chat history either").isNull();
        });
    }

    /**
     * The trace must read in the order the work happened. A specialist runs NESTED inside the
     * orchestrator's turn — {@code call_specialist} re-enters {@code runAgent} on the same thread —
     * so it COMPLETES first, and allocating {@code seq} after the agentic call returned numbered
     * every specialist ahead of the orchestrator that delegated to it. On the run that prompted
     * this, the recorded trace opened with a specialist at seq 1 and the orchestrator that asked
     * for it at seq 2: the trace showed the work before the decision that caused it, and since
     * {@code AgentStepRepository.findByRunId} orders by seq with no tiebreaker, that is exactly
     * what the admin page rendered.
     */
    @Test
    public void theTraceIsNumberedInTheOrderStepsStartedNotFinished() {
        rejectFirstNReviews = 0;

        service.runOrchestration(CONV, MSG, "cross-topic question", List.of(), RUN, "OIM");

        ArgumentCaptor<Integer> seqs = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<String> roles = ArgumentCaptor.forClass(String.class);
        verify(stepRepository, atLeastOnce()).insert(any(), any(), seqs.capture(), roles.capture(),
            anyInt(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), anyInt(),
            anyInt(), anyInt());

        // Sorted by seq, which is exactly how AgentStepRepository.findByRunId reads them back and
        // therefore how the admin trace renders. Insertion order is deliberately NOT what is
        // asserted: rows are written when a step finishes, and the whole point is that the number
        // no longer follows that.
        Map<Integer, String> bySeq = new TreeMap<>();
        for (int i = 0; i < seqs.getAllValues().size(); i++) {
            bySeq.put(seqs.getAllValues().get(i), roles.getAllValues().get(i));
        }
        assertThat(bySeq).as("the orchestrator delegated, so it started before its specialist")
            .containsExactly(entry(1, "orchestrator"), entry(2, "specialist"),
                entry(3, "reviewer"));
    }

    /**
     * The draft alone is not enough to revise from. The evidence behind it — the specialists' tool
     * output — was rendered into the reviewer's task and nowhere else, so a revising orchestrator
     * holding its own answer still could not check a claim against a source without delegating
     * again. On the observed run that is precisely what the extra round bought: one targeted
     * specialist call, 35,750 prompt tokens, to look up a chunk id that was already sitting in the
     * evidence the reviewer had been reading.
     *
     * <p>
     * It costs what the reviewer already pays for the same block (~25k prompt tokens on that run)
     * and replaces a re-search an order of magnitude larger. The orchestrator keeps
     * {@code call_specialist}, so feedback that genuinely demands new material can still be
     * delegated — this only removes the need to delegate for material already in hand.
     *
     * <p>
     * Note what the conversation seed does NOT contain: a specialist's raw tool output lives inside
     * its own nested run, so the orchestrator only ever saw the specialist's synthesised answer.
     * The evidence therefore still has to travel as text, and it is sent as a DELTA — anything sent
     * in an earlier round is already in the transcript, so re-sending it would duplicate the
     * largest block in the prompt once per round.
     */
    @Test
    public void aRevisionCarriesTheEvidenceSoItNeedNotDelegateAgain() {
        rejectFirstNReviews = 1;

        service.runOrchestration(CONV, MSG, "cross-topic question", List.of(), RUN, "OIM");

        List<AgenticRequest> orchestratorTurns = capturedTurns("call_specialist");
        assertThat(orchestratorTurns).hasSize(2);
        assertThat(orchestratorTurns.get(0).query())
            .as("the first turn gathers evidence, so it must not be handed any")
            .doesNotContain("Source evidence");
        assertThat(orchestratorTurns.get(1).query()).as(
            "the revision must carry the specialists' output, attributed as the reviewer sees it")
            .contains("Source evidence").contains("[spec-a · query_json_objects]")
            .contains("query_json_objects rows: [from 9.2.2 to 9.3]");
    }

    /** The turns captured on the mocked {@code ragService}, identified by their extra tool. */
    private List<AgenticRequest> capturedTurns(String extraToolName) {
        ArgumentCaptor<AgenticRequest> requests = ArgumentCaptor.forClass(AgenticRequest.class);
        verify(ragService, atLeastOnce()).generateAgenticAnswer(requests.capture(), any());
        return requests.getAllValues().stream()
            .filter(q -> q.extraTools() != null && !q.extraTools().isEmpty()
                && extraToolName.equals(q.extraTools().get(0).getToolDefinition().name()))
            .toList();
    }

    /**
     * Evidence is cumulative across review rounds because the ANSWER is cumulative: on a rejection
     * the orchestrator revises rather than restarts, and a revision keeps the claims round 1
     * already grounded while fixing the ones the reviewer flagged. Rebuilding the evidence list per
     * round — the natural-looking hygiene edit, since "stale evidence from the previous attempt"
     * sounds like something to clear — makes round 2's reviewer judge a revised answer against only
     * the delegations round 2 happened to make, so it rejects correct, grounded claims as
     * unsupported and the run ends {@code delivered_flagged} with a ⚠️ note on an answer that was
     * in fact fine. The per-specialist header matters for the same reason: with several specialists
     * in a profile the reviewer has to know which agent produced which tool output, and anonymous
     * blocks make an attribution error unnoticeable. Nothing else asserts on the reviewer's task
     * text at all — the existing rejection test checks only the status, the round count and the
     * number of reviewer steps, all of which stay exactly the same when the evidence is dropped.
     */
    @Test
    public void evidenceAccumulatesAcrossReviewRoundsWithPerSpecialistAttribution() {
        rejectFirstNReviews = 1; // reject once, approve the revision

        OrchestrationService.OrchestrationResult r =
            service.runOrchestration(CONV, MSG, "cross-topic question", List.of(), RUN, "OIM");

        assertThat(r.status()).isEqualTo("done");
        verify(runRepository).finish(eq(RUN), eq(MSG), eq("done"), eq(1), any(), anyInt(), anyInt(),
            anyInt(), anyInt(), anyInt(), any());

        ArgumentCaptor<AgenticRequest> requests = ArgumentCaptor.forClass(AgenticRequest.class);
        verify(ragService, times(6)).generateAgenticAnswer(requests.capture(), any());
        List<AgenticRequest> reviews = requests.getAllValues().stream()
            .filter(q -> q.extraTools() != null && !q.extraTools().isEmpty()
                && "submit_review".equals(q.extraTools().get(0).getToolDefinition().name()))
            .toList();
        assertThat(reviews).as("one reviewer turn per round, and the run took two rounds")
            .hasSize(2);

        // Exactly what collectEvidence writes: "[" + specialist + <space><MIDDLE DOT U+00B7><space>
        // + tool + "]" then a NEWLINE (not a space) before the tool output. The separator is
        // written
        // as a unicode escape, not pasted, so it cannot be silently swapped for a bullet or an
        // interpunct lookalike by an editor or an encoding-unaware copy.
        String block =
            "[spec-a · query_json_objects]\n" + "query_json_objects rows: [from 9.2.2 to 9.3]";

        assertThat(reviews.get(0).query().split(Pattern.quote(block), -1).length - 1)
            .as("round 1 harvested one attributed specialist tool result").isEqualTo(1);
        assertThat(reviews.get(1).query().split(Pattern.quote(block), -1).length - 1)
            .as("round 2 must still see round 1's evidence alongside its own, not instead of it")
            .isEqualTo(2);

        // The same invariant for CHUNK evidence, where identity is the id rather than the text.
        // Stating it as "the block appears literally twice" would pin the duplication itself as the
        // contract — which is what de-duplication exists to remove — so the accumulation is
        // asserted
        // on the attribution and the ids, and the saving on the body.
        String round2 = reviews.get(1).query();
        assertThat(count(round2, "[spec-a · search_corpus]"))
            .as("both rounds' search evidence is present and still attributed").isEqualTo(2);
        // The two-space prefix is the chunk HEADER form. A bare "id=" would also match the draft's
        // own "[chunk_id=…]" citation, which the reviewer's prompt quotes back under "## Candidate
        // answer" — counting that as evidence would make this assertion pass for the wrong reason.
        assertThat(count(round2, "  id=" + CITED_CHUNK))
            .as("...each keeping its own header, so no attribution is lost to de-duplication")
            .isEqualTo(2);
        assertThat(count(round2, CITED_BODY)).as("...but the text itself travels once")
            .isEqualTo(1);
        assertThat(round2).contains(ChunkBlocks.SHOWN_ABOVE);
    }

    private static int count(String haystack, String needle) {
        return haystack.split(Pattern.quote(needle), -1).length - 1;
    }

    /**
     * The reviewer's prompt is the largest message this system sends — measured at 125,440 chars on
     * average in production, where two of them outweighed all 31 specialist tool results that
     * produced them. It now receives the sources the answer cites and nothing else, which is both
     * the saving and the point: a citation asserts "this source supports this statement", and only
     * a reviewer restricted to that source is actually testing the assertion.
     */
    @Test
    public void theReviewerGetsTheCitedSourcesAndNothingElse() {
        service.runOrchestration(CONV, MSG, "cross-topic question", List.of(), RUN, "OIM");

        String review = capturedTurns("submit_review").get(0).query();

        assertThat(review).as("the draft cites this chunk, so its text must be there")
            .contains(CITED_BODY);
        assertThat(review).as("nothing cites this one, so its text goes")
            .doesNotContain(UNCITED_BODY);
        assertThat(review)
            .as("...and so does its name — a title the reviewer cannot read is not evidence, "
                + "it is an invitation to approve on the strength of a plausible label")
            .doesNotContain(UNCITED_CHUNK.toString());
        assertThat(review).as("evidence that is not chunk text can never be cited or recovered")
            .contains("query_json_objects rows: [from 9.2.2 to 9.3]");
    }

    /**
     * Without this the reviewer cannot tell "the corpus has nothing on this" from "you did not cite
     * it", so it phrases its objection as the former — and the orchestrator answers by delegating a
     * fresh search for material it is already holding, which is the expensive fix instead of the
     * free one.
     */
    @Test
    public void theReviewerIsToldUncitedSourcesExistWithoutBeingShownThem() {
        service.runOrchestration(CONV, MSG, "cross-topic question", List.of(), RUN, "OIM");

        String review = capturedTurns("submit_review").get(0).query();

        assertThat(review).contains("Do NOT search for new information");
        assertThat(review).as("it must know its evidence is scoped, not exhaustive")
            .contains("those are not shown here");
        assertThat(review).as("and be steered towards asking for a citation")
            .contains("must cite what supports the claim");
    }

    /**
     * The expensive failure this pair of fields exists to prevent, measured on a live run: the
     * reviewer rejected a draft over a mis-citation and said so precisely — *"the correct name IS
     * supported by [4], so either swap the citation to [4] or remove [5]"* — and the orchestrator
     * answered by running another specialist costing 347,969 prompt tokens. The whole run went 4×
     * over its baseline and still ended `delivered_flagged`.
     *
     * <p>
     * Prose could not carry that instruction reliably, so the verdict carries the distinction
     * instead: a citation fix is applied from evidence already in hand, and the revision prompt has
     * to say so in a way the orchestrator cannot read as an invitation to research.
     */
    @Test
    public void aCitationOnlyRejectionTellsTheOrchestratorNotToDelegate() {
        rejectFirstNReviews = 1;
        rejectionExtras = ",\"citation_fixes\":[\"swap [5] to [4] on the SAP HCM claim\"]";

        service.runOrchestration(CONV, MSG, "cross-topic question", List.of(), RUN, "OIM");

        String revision = capturedTurns("call_specialist").get(1).query();

        assertThat(revision).contains("swap [5] to [4] on the SAP HCM claim");
        assertThat(revision)
            .as("the orchestrator must be told this needs no retrieval, in as many words")
            .contains("already supplied").contains("do NOT call call_specialist");
    }

    /**
     * The other half: a claim that genuinely has no evidence behind it cannot be fixed by
     * renumbering, so delegation must stay available or the run cannot converge at all.
     */
    @Test
    public void anUnsupportedClaimStillPermitsDelegation() {
        rejectFirstNReviews = 1;
        rejectionExtras = ",\"unsupported_claims\":[\"SAP BW versions are not covered anywhere\"]";

        service.runOrchestration(CONV, MSG, "cross-topic question", List.of(), RUN, "OIM");

        String revision = capturedTurns("call_specialist").get(1).query();

        assertThat(revision).contains("SAP BW versions are not covered anywhere");
        assertThat(revision).as("delegation is the correct remedy for genuinely missing material")
            .contains("delegate");
        assertThat(revision).doesNotContain("do NOT call call_specialist");
    }

    /**
     * The reviser must never be cite-scoped. The draft is what is being changed, the reviewer's
     * objection is normally that a claim is unsupported, and the fix therefore needs exactly the
     * material the draft does not yet cite. Scoping it hands the orchestrator back only what it
     * already used, and it delegates again to re-fetch evidence it was holding — the 216,667-token
     * re-research this area exists to prevent.
     */
    @Test
    public void theReviserIsNeverCiteScoped() {
        rejectFirstNReviews = 1;

        service.runOrchestration(CONV, MSG, "cross-topic question", List.of(), RUN, "OIM");

        List<AgenticRequest> orchestratorTurns = capturedTurns("call_specialist");
        assertThat(orchestratorTurns).hasSize(2);
        String revision = orchestratorTurns.get(1).query();

        assertThat(revision).contains("Reviewer feedback to address");
        assertThat(revision).as("the cited body is still there").contains(CITED_BODY);
        assertThat(revision)
            .as("and so is the uncited one — re-citing it is the cheap fix for 'unsupported', "
                + "and withholding it forces a fresh delegation instead")
            .contains(UNCITED_BODY);
    }

    /**
     * Where repeat suppression and cite-scoping meet. Suppression replaced a second sighting with a
     * reference, so the only full copy of that chunk may sit in a different evidence entry; if
     * cite-scoping removes that entry while keeping the reference, the reviewer is told to look
     * further up its prompt for text that is no longer anywhere in it.
     */
    @Test
    public void noReferenceInTheReviewersPromptPointsAtAMissingBody() {
        rejectFirstNReviews = 1;

        service.runOrchestration(CONV, MSG, "cross-topic question", List.of(), RUN, "OIM");

        for (AgenticRequest review : capturedTurns("submit_review")) {
            String text = review.query();
            if (text.contains(ChunkBlocks.SHOWN_ABOVE)) {
                assertThat(text)
                    .as("a reference is only honest while the body is in the same "
                        + "message — the reviewer has no history and cannot search")
                    .contains(CITED_BODY);
            }
        }
    }

    /**
     * A specialist is never asked the user's question — it is asked a sub-question the orchestrator
     * wrote, so it is the agent most likely to be handed something ambiguous (which OneIM version?
     * which of the five kinds named {@code Person}?). Without {@code ask_clarifying_question} its
     * only options are to guess or to answer the wrong reading, and once the orchestrator
     * synthesises that into prose the guess is indistinguishable from a grounded answer — the
     * reviewer cannot catch it either, because the evidence genuinely supports the claim the
     * specialist chose to make. The tool is also what keeps the whole "SPECIALIST NEEDS
     * CLARIFICATION" branch of {@code runSpecialist} reachable at all; stop offering it and that
     * branch, and the sibling test pinning it, describe a path production can no longer take.
     *
     * <p>
     * The other half of the rule is that the escape hatch is ADDED to the playbook's tools rather
     * than replacing them — a specialist stripped of {@code search_corpus} answers from model
     * weights alone while still looking like a retrieval agent in the trace. No existing test can
     * see that half: they all use the {@code playbook(name)} helper, whose tool list is
     * {@code List.of()}, and against an empty list "append" and "replace" produce the same list.
     * Hence a playbook with real tools here, asserted in order.
     */
    @Test
    public void aSpecialistAlwaysGetsAskClarifyingQuestionOnTopOfItsPlaybookTools() {
        rejectFirstNReviews = 0;
        // doAnswer(...).when(...) rather than when(...).thenAnswer(...): re-stubbing through when()
        // invokes the answer registered in setUp with null arguments before it can be replaced.
        doAnswer(inv -> {
            String name = inv.getArgument(0);
            if ("spec-a".equals(name)) {
                return Optional.of(new Playbook(name, name + " title", name + " description",
                    "TEMPLATE for " + name, List.of("search_corpus", "get_section"), false,
                    Instant.now(), Instant.now()));
            }
            return Optional.of(playbook(name));
        }).when(playbookService).getPlaybook(any());

        service.runOrchestration(CONV, MSG, "cross-topic question", List.of(), RUN, "OIM");

        ArgumentCaptor<AgenticRequest> requests = ArgumentCaptor.forClass(AgenticRequest.class);
        verify(ragService, times(3)).generateAgenticAnswer(requests.capture(), any());
        // The specialist is the only turn run without a per-run tool (call_specialist /
        // submit_review).
        AgenticRequest specialist = requests.getAllValues().stream()
            .filter(q -> q.extraTools() == null || q.extraTools().isEmpty()).findFirst()
            .orElseThrow();

        assertThat(specialist.allowedTools())
            .as("the playbook's own tools survive and the escape hatch is appended to them")
            .containsExactly("search_corpus", "get_section", "ask_clarifying_question");
    }

    /**
     * S6.8, the two clauses the tool-union test cannot see: a specialist gets NO conversation
     * history, and its playbook's {@code codeExecution} flag is honoured.
     *
     * <p>
     * History is what makes a specialist a clean sub-agent. It is handed one sub-question and the
     * tools to answer it from the corpus; give it the chat log as well and it can answer from the
     * LOG instead — restating what was said three turns ago as though it had just retrieved it,
     * which the reviewer cannot catch because the claim is genuinely consistent with the evidence
     * it was shown. It also silently multiplies cost: the orchestrator already carries the history,
     * and every delegation would re-upload it, on a path where one turn can make a dozen specialist
     * calls. The orchestrator, by contrast, MUST carry it — a follow-up question is meaningless
     * without it — so this test asserts both sides of the asymmetry rather than just the null.
     *
     * <p>
     * {@code codeExecution} is a capability the playbook DECLARES. Drop it on the way through and
     * the specialist runs without it: no error, no log line, just an agent that cannot do the
     * arithmetic or table-manipulation its playbook was written around, answering worse for reasons
     * nothing in the trace explains. Note that the orchestrator's flag is hardcoded {@code false},
     * so "pass the playbook's flag" and "pass false" are indistinguishable everywhere else in this
     * suite: {@code playbook(name)} builds every fixture with {@code codeExecution=false}, and the
     * sibling {@code aSpecialistAlwaysGetsAskClarifyingQuestionOnTopOfItsPlaybookTools} — the only
     * test that builds a richer playbook — also sets it {@code false} and asserts only on
     * {@code allowedTools}. Hence a {@code true} here.
     */
    @Test
    public void aSpecialistRunsWithNoHistoryAndWithItsPlaybooksCodeExecutionFlag() {
        rejectFirstNReviews = 0;
        // doAnswer(...).when(...) rather than when(...).thenAnswer(...): re-stubbing through when()
        // invokes the answer registered in setUp with null arguments before it can be replaced.
        doAnswer(inv -> {
            String name = inv.getArgument(0);
            if ("spec-a".equals(name)) {
                return Optional.of(new Playbook(name, name + " title", name + " description",
                    "TEMPLATE for " + name, List.of("search_corpus"), true, Instant.now(),
                    Instant.now()));
            }
            return Optional.of(playbook(name));
        }).when(playbookService).getPlaybook(any());

        List<LlmMessage> history = List.of(new LlmMessage("user", "an earlier question"),
            new LlmMessage("assistant", "an earlier answer"));

        service.runOrchestration(CONV, MSG, "cross-topic question", history, RUN, "OIM");

        ArgumentCaptor<AgenticRequest> requests = ArgumentCaptor.forClass(AgenticRequest.class);
        verify(ragService, times(3)).generateAgenticAnswer(requests.capture(), any());

        // The specialist is the only turn run without a per-run tool (call_specialist /
        // submit_review).
        AgenticRequest specialist = requests.getAllValues().stream()
            .filter(q -> q.extraTools() == null || q.extraTools().isEmpty()).findFirst()
            .orElseThrow();
        assertThat(specialist.history())
            .as("a specialist answers from retrieval, never from the conversation log").isNull();
        assertThat(specialist.codeExecution())
            .as("the capability the playbook declares must reach the run").isTrue();

        AgenticRequest orchestrator = requests.getAllValues().stream()
            .filter(q -> q.extraTools() != null && !q.extraTools().isEmpty()
                && "call_specialist".equals(q.extraTools().get(0).getToolDefinition().name()))
            .findFirst().orElseThrow();
        assertThat(orchestrator.history())
            .as("the orchestrator is the one agent that DOES carry the conversation")
            .isEqualTo(history);
        assertThat(orchestrator.codeExecution())
            .as("...and it never gets code execution, whatever its specialists declare").isFalse();
    }

    /**
     * S6.8: a specialist can legitimately produce nothing — a response truncated at the token cap,
     * or one that ended on a tool call, carries no assistant text part and streams no tokens, so
     * {@code extractAnswerText} falls through both of its sources and yields "". Returned raw, the
     * orchestrator receives an EMPTY tool result, which it cannot distinguish from a specialist
     * that ran fine and had nothing to add: it synthesises around the hole, the reviewer then
     * validates that synthesis against evidence that never mentioned the missing piece, and the run
     * ends {@code done} with a confident answer built on a delegation that silently failed. The
     * substitution converts silence into a statement the model can act on — re-delegate with a
     * narrower question, or tell the user it does not know.
     *
     * <p>
     * Dropping the {@code isBlank()} substitution ("just return the answer") changes nothing any
     * other test observes: the sibling clarification test asserts on a prefixed string, the budget
     * test looks only for the word "exhausted", and every other stubbed specialist here returns a
     * well-formed text part, so this branch is otherwise never executed.
     */
    @Test
    public void aBlankSpecialistAnswerIsReportedAsSuchRatherThanAsAnEmptyToolResult() {
        // doAnswer(...).when(...) rather than when(...).thenAnswer(...): re-stubbing through when()
        // would first invoke the answer registered in setUp with null arguments.
        doAnswer(inv -> {
            AgenticRequest req = inv.getArgument(0);
            String extraName = req.extraTools() == null || req.extraTools().isEmpty() ? ""
                : req.extraTools().get(0).getToolDefinition().name();

            if ("call_specialist".equals(extraName)) {
                for (ToolCallback tc : req.extraTools()) {
                    specialistToolResults.add(
                        tc.call("{\"playbook_name\":\"spec-a\",\"question\":\"sub-question\"}"));
                }
                return result("final orchestrated answer", null, null);
            }
            if ("submit_review".equals(extraName)) {
                for (ToolCallback tc : req.extraTools()) {
                    tc.call("{\"approved\":true,\"feedback\":\"\"}");
                }
                return result("review done", null, null);
            }

            // The specialist turn: no assistant text part and nothing streamed into the sink, so
            // neither source of an answer has anything to give.
            LlmMessage partless = new LlmMessage("assistant", null, List.of(), null, null, null);
            return new RagResult(List.of(), List.of(partless), null, List.of(), 5, 10, 15, 0, 2,
                null, null);
        }).when(ragService).generateAgenticAnswer(any(), any());

        OrchestrationService.OrchestrationResult r =
            service.runOrchestration(CONV, MSG, "cross-topic question", List.of(), RUN, "OIM");

        assertThat(specialistToolResults).as("exactly one delegation happened").hasSize(1);
        assertThat(specialistToolResults.get(0))
            .as("the orchestrator must be able to tell an empty answer from a silent one")
            .isEqualTo("(the specialist returned no answer)");
        assertThat(r.status()).as("a silent specialist is not a run failure").isEqualTo("done");
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

    /**
     * A specialist's clarifying question is addressed to its CALLER — the orchestrator — and must
     * never leak out as the run's own clarification. Both travel the same channel
     * ({@code RagResult.clarifyingQuestion}), but the orchestrator's is a user-facing escalation
     * that finishes the run with status {@code clarify} and skips review entirely, while a
     * specialist's is a mid-delegation stall the orchestrator is expected to resolve from context
     * and re-delegate. Returned unprefixed it is indistinguishable from a specialist ANSWER: the
     * orchestrator would synthesise the question into its final text and the user would be handed a
     * question dressed up as a reviewed answer. The prefix names the playbook that asked and the
     * trailing instruction says what to do about it — together they are the whole mechanism.
     * Nothing else in the suite would notice the loss: the run still returns {@code done}, every
     * agent_runs / agent_steps verification still passes, and the only observable difference is the
     * string handed back through {@code call_specialist}, which the sibling budget test inspects
     * only for the word "exhausted".
     */
    @Test
    public void aSpecialistClarificationIsReturnedToTheOrchestratorAndNeverBecomesTheRunsClarifyOutcome() {
        specialistClarifyingQuestion = "Which OneIM version do you mean?";
        rejectFirstNReviews = 0;

        OrchestrationService.OrchestrationResult r =
            service.runOrchestration(CONV, MSG, "cross-topic question", List.of(), RUN, "OIM");

        assertThat(specialistToolResults).as("exactly one delegation happened").hasSize(1);
        assertThat(specialistToolResults.get(0))
            .as("the orchestrator must be able to tell a stalled specialist from an answer")
            .startsWith("SPECIALIST NEEDS CLARIFICATION (spec-a): Which OneIM version do you mean?")
            .contains("re-call this specialist");

        assertThat(r.status()).as("a specialist stall is not a user-facing clarification")
            .isEqualTo("done");
        assertThat(r.content()).as("the question must not reach the user as the answer")
            .doesNotContain("Which OneIM version do you mean?");
        verify(runRepository, never()).finish(any(), any(), eq("clarify"), anyInt(), any(),
            anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), any());
    }

    /**
     * {@code seq} is the only total order agent_steps has — {@code findByRunId} is
     * {@code ORDER BY seq ASC}, and the schema backs it with a plain, NON-unique index
     * ({@code idx_agent_steps_run_seq}) — and the counter has two writers that must agree on one
     * idiom: {@code runAgent}'s success path and {@code recordFailedStep}'s failure path. Changing
     * only the success path to {@code getAndIncrement} looks like a no-op, because on a clean run
     * the numbers stay distinct and ascending; it is not, because the two writers then hand out the
     * SAME number as soon as a step fails and the run continues — which is the ordinary case, not
     * an exotic one: {@code RagService} catches a throwing tool and returns the model an error
     * string, so a dead specialist is recovered and the orchestrator still completes. Its step
     * reuses the failed step's seq, and the two rows are rendered in whatever order Postgres
     * chooses, which is the same non-unique-sort-key corruption that once printed answers above
     * their questions in the message log. Every existing assertion here passes {@code anyInt()} for
     * this argument, so nothing in the suite has ever looked at the value.
     */
    @Test
    public void stepSeqIsOnePerRunCounterStartingAtOneAcrossAllRoles() {
        rejectFirstNReviews = 0;

        service.runOrchestration(CONV, MSG, "cross-topic question", List.of(), RUN, "OIM");

        ArgumentCaptor<Integer> seq = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<String> role = ArgumentCaptor.forClass(String.class);
        verify(stepRepository, times(3)).insert(any(), eq(RUN), seq.capture(), role.capture(),
            anyInt(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), anyInt(),
            anyInt(), anyInt());

        assertThat(role.getAllValues())
            .as("the nested specialist completes first, then the orchestrator, then the reviewer")
            .containsExactly("specialist", "orchestrator", "reviewer");
        assertThat(seq.getAllValues())
            .as("one counter for the whole run: the first step is 1 and no two steps collide")
            .containsExactlyInAnyOrder(1, 2, 3);
        // Rows are WRITTEN in completion order and NUMBERED in start order, so the specialist that
        // finished first carries the higher number — see
        // theTraceIsNumberedInTheOrderStepsStartedNotFinished, which owns that guarantee.
        assertThat(seq.getAllValues().get(0)).as("the nested specialist started second")
            .isEqualTo(2);
    }

    /**
     * S6.6: {@code round} is a 0-based LOOP INDEX and {@code flagNote} is USER-FACING TEXT, so the
     * two cannot be the same number. The note is the only thing that tells a reader an answer was
     * delivered against the reviewer's judgement, and how hard the system tried before giving up;
     * printing the index makes it under-report by one on every flagged run, and at
     * {@code maxReviewRounds=0} — a perfectly reasonable production setting for "review once, never
     * revise" — it reads "after 0 attempt(s)" on an answer that was in fact reviewed, which is not
     * an off-by-one any more but a claim that no review happened.
     *
     * <p>
     * The two numbers are deliberately different and both are correct in their own place: the value
     * persisted to {@code agent_runs.review_rounds} IS the 0-based counter (it records how many
     * REVISIONS were made, and the existing rejection test pins it as 2), while the prose counts
     * ATTEMPTS. That is exactly why "unify them" looks like a cleanup. No existing test can see the
     * difference: {@code rejectedTwice_deliversFlagged} runs the same three-round scenario but
     * asserts only that the phrase "did not pass automated review" is present.
     */
    @Test
    public void theFlaggedAnswerNoteCountsAttemptsNotTheZeroBasedRoundIndex() {
        rejectFirstNReviews = 99; // never approved: with maxReviewRounds=2, rounds 0, 1 and 2 run

        OrchestrationService.OrchestrationResult r =
            service.runOrchestration(CONV, MSG, "question", List.of(), RUN, "OIM");

        assertThat(r.status()).isEqualTo("delivered_flagged");
        assertThat(r.content())
            .as("three reviewer turns ran, so the user must be told three attempts were made")
            .contains("did not pass automated review after 3 attempt(s)")
            .doesNotContain("after 2 attempt(s)");

        // Three reviewer turns really did happen — the prose is not merely larger by one.
        verify(stepRepository, times(3)).insert(any(), eq(RUN), anyInt(), eq("reviewer"), anyInt(),
            any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), anyInt(), anyInt(),
            anyInt());
        // ...while the PERSISTED counter stays the 0-based index it has always been: the two
        // numbers are different on purpose, which is what makes "unify them" tempting.
        verify(runRepository).finish(eq(RUN), eq(MSG), eq("delivered_flagged"), eq(2), any(),
            anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), any());
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

    /**
     * The streamed buffer is the last-resort source for an answer, and it is the RAW transcript the
     * user was watching: {@code RagService} writes a {@code 🔧 *Calling tool:* …} banner into the
     * sink for every tool call, and a thinking model's {@code <think>} block arrives on the same
     * channel. It is reached whenever the provider's final assistant message carries no text parts
     * — what a response truncated at the token cap, or one that ended on a tool call, actually
     * looks like — and at that point the buffer becomes the answer verbatim. Unstripped it is used
     * twice: it is handed to the REVIEWER as the candidate answer, so a whole pro-model review is
     * spent judging tool chatter against evidence it cannot match, and then it is delivered to the
     * user with the model's private reasoning inside it, which is exactly what
     * {@code show-thinking=false} promises can never happen. Nothing else in this suite executes
     * the branch at all: every stubbed result carries a well-formed text part, so
     * {@code stripThinkAndTools} could be deleted from the fallback with the suite still green.
     */
    @Test
    public void theAnswerFallbackStripsThinkBlocksAndToolBannersFromTheStreamedBuffer() {
        // doAnswer(...).when(...) rather than when(...).thenAnswer(...): re-stubbing through when()
        // would first invoke the answer registered in setUp with null arguments.
        doAnswer(inv -> {
            AgenticRequest req = inv.getArgument(0);
            Consumer<String> sink = inv.getArgument(1);
            String extraName = req.extraTools() == null || req.extraTools().isEmpty() ? ""
                : req.extraTools().get(0).getToolDefinition().name();

            if ("call_specialist".equals(extraName)) {
                sink.accept("<think>weighing two readings of the question</think>\n");
                // The banner marker is the WRENCH (U+1F527) that stripThinkAndTools matches on,
                // spelled as its surrogate-pair escape rather than pasted so the expectation cannot
                // be mangled by an encoding-unaware edit without anyone seeing it.
                sink.accept("🔧 *Calling tool:* search_corpus({})\n");
                sink.accept("real answer");
                // No text part anywhere: the streamed buffer is the only account of the answer.
                LlmMessage partless =
                    new LlmMessage("assistant", null, List.of(), null, null, null);
                return new RagResult(List.of(), List.of(partless), null, List.of(), 10, 20, 30, 0,
                    5, null, null);
            }

            if ("submit_review".equals(extraName)) {
                for (ToolCallback tc : req.extraTools()) {
                    tc.call("{\"approved\":true,\"feedback\":\"\"}");
                }
                return result("review done", null, null);
            }

            return specialistResult();
        }).when(ragService).generateAgenticAnswer(any(), any());

        OrchestrationService.OrchestrationResult r =
            service.runOrchestration(CONV, MSG, "cross-topic question", List.of(), RUN, "OIM");

        assertThat(r.status()).isEqualTo("done");
        assertThat(r.content())
            .as("neither the model's private reasoning nor its tool chatter may reach the user")
            .isEqualTo("real answer");
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

    /**
     * S6 Data: the five token columns on {@code agent_runs} — and the five the
     * {@code OrchestrationResult} hands to {@code ChatMessageService} for {@code messages} — are
     * the SUM over every agent in the run: the orchestrator turn, every specialist delegation and
     * every reviewer turn. The only thing that makes them a sum is {@code RunState.accumulate}'s
     * {@code +=}; turn one of those into {@code =} (the natural "just record the result" edit) and
     * the run reports the LAST agent's usage as the whole run's.
     *
     * <p>
     * A multi-agent turn is the most expensive thing the product does, so that under-reports MAS
     * spend by the entire delegation tree — and it does it invisibly, because every figure stays
     * internally consistent: {@code agent_runs}, the message row and the cost dashboard all agree
     * on a number that is simply far too small, nothing throws, and the per-step rows (which carry
     * each agent's own tokens) still look right next to a run total that no longer adds up to them.
     *
     * <p>
     * Every {@code runRepository.finish(...)} verification in this file matches all five token
     * arguments with {@code anyInt()}, so no test has ever looked at a token total. The shared stub
     * in {@code setUp} also gives the orchestrator and the reviewer identical counts, which would
     * make "last agent wins" indistinguishable from a correct sum on several fields — hence the
     * per-role, decade-separated counts below (specialist 1×, reviewer 10×, orchestrator 100×):
     * each expected total decomposes to exactly one combination of agents, so a dropped, doubled or
     * overwritten contribution cannot land on the right number by accident.
     */
    @Test
    public void theRunTotalsAreTheSumOverEveryAgentNotTheLastOneToFinish() {
        LlmMessage orchestratorMsg = new LlmMessage("assistant", "final orchestrated answer",
            List.of(new LlmPart("text", "final orchestrated answer", null, null)), null, null,
            null);
        LlmMessage reviewerMsg = new LlmMessage("assistant", "review done",
            List.of(new LlmPart("text", "review done", null, null)), null, null, null);
        LlmMessage specialistMsg = new LlmMessage("assistant", "specialist answer",
            List.of(new LlmPart("text", "specialist answer", null, null)), null, null, null);

        doAnswer(inv -> {
            AgenticRequest req = inv.getArgument(0);
            String extraName = req.extraTools() == null || req.extraTools().isEmpty() ? ""
                : req.extraTools().get(0).getToolDefinition().name();
            if ("call_specialist".equals(extraName)) {
                for (ToolCallback tc : req.extraTools()) {
                    tc.call("{\"playbook_name\":\"spec-a\",\"question\":\"sub-question\"}");
                }
                return new RagResult(List.of(), List.of(orchestratorMsg), null, List.of(), 100, 200,
                    300, 400, 500);
            }
            if ("submit_review".equals(extraName)) {
                for (ToolCallback tc : req.extraTools()) {
                    tc.call("{\"approved\":true,\"feedback\":\"\"}");
                }
                return new RagResult(List.of(), List.of(reviewerMsg), null, List.of(), 10, 20, 30,
                    40, 50);
            }
            return new RagResult(List.of(), List.of(specialistMsg), null, List.of(), 1, 2, 3, 4, 5);
        }).when(ragService).generateAgenticAnswer(any(), any());

        OrchestrationService.OrchestrationResult r =
            service.runOrchestration(CONV, MSG, "cross-topic question", List.of(), RUN, "OIM");

        verify(runRepository).finish(eq(RUN), eq(MSG), eq("done"), eq(0), any(), eq(111), eq(222),
            eq(333), eq(444), eq(555), isNull());

        assertThat(r.promptTokens()).isEqualTo(111);
        assertThat(r.completionTokens()).isEqualTo(222);
        assertThat(r.totalTokens()).isEqualTo(333);
        assertThat(r.cachedTokens()).isEqualTo(444);
        assertThat(r.thoughtTokens()).isEqualTo(555);
    }

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

    /**
     * S6.11, both halves at once: the outcome write is BEST-EFFORT and it runs OFF the interrupt.
     *
     * <p>
     * Cancellation reaches this service as a thread interrupt — {@code ChatCancellationService}
     * interrupts the generating thread, which is what the stub below reproduces — and a JDBC write
     * on an interrupted thread can be refused outright. {@code finishOffInterrupt} therefore clears
     * the flag around {@code finish} and restores it afterwards. Replace
     * {@code Thread.interrupted()} with the read-only {@code isInterrupted()} — the "why is this
     * clearing a flag?" cleanup — and the {@code agent_runs} row for every stopped run is silently
     * lost: the run is left open forever in the admin trace with no end time, no token totals and
     * no status, while the chat shows a perfectly normal "[Generation stopped by user]". Restoring
     * the flag matters just as much in the other direction: the caller ({@code ChatMessageService})
     * is still unwinding a cancellation and does its own interrupt-sensitive write next.
     *
     * <p>
     * The best-effort half is the other assertion. A recording failure must never REPLACE the
     * exception that ended the run: swallow the write failure and the user's Stop stays a
     * {@code CancellationException}, which is the only thing that distinguishes a stop from a fault
     * all the way up through {@code ChatMessageService} (status {@code cancelled} vs
     * {@code error}). Let the JDBC exception escape instead and a stopped run is captioned as a
     * system error, which is exactly the inversion the {@code cancelled} status was introduced to
     * end.
     *
     * <p>
     * Nothing in the suite reaches either path: no test makes {@code runRepository} or
     * {@code stepRepository} throw, and the sibling {@code cancellation_isRecordedAsCancelled_
     * notAsAFailedRun} cancels on a thread whose interrupt flag is never set, so the clear/restore
     * pair is dead code from its point of view.
     */
    @Test
    public void aCancelledRunRecordsItsOutcomeOffTheInterruptAndAFailedWriteKeepsTheCancellation() {
        AtomicBoolean interruptSetDuringWrite = new AtomicBoolean(true);

        doAnswer(inv -> {
            Thread.currentThread().interrupt();
            throw new CancellationException("stopped");
        }).when(ragService).generateAgenticAnswer(any(), any());

        doAnswer(inv -> {
            interruptSetDuringWrite.set(Thread.currentThread().isInterrupted());
            throw new RuntimeException("connection is closed");
        }).when(runRepository).finish(any(), any(), any(), anyInt(), any(), anyInt(), anyInt(),
            anyInt(), anyInt(), anyInt(), any());

        try {
            assertThatThrownBy(
                () -> service.runOrchestration(CONV, MSG, "question", List.of(), RUN, "OIM"))
                .as("a failed trace write must never replace the exception that ended the run")
                .isInstanceOf(CancellationException.class).hasMessage("stopped");

            assertThat(interruptSetDuringWrite)
                .as("the outcome write must run with the interrupt flag cleared").isFalse();
            assertThat(Thread.currentThread().isInterrupted())
                .as("...and the flag must be handed back to the still-cancelling caller").isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    /**
     * A specialist returns TWO kinds of tool output, and the pair is deliberate.
     *
     * <p>
     * The {@code query_json_objects} message is chunk-free, so it exercises the pass-through half
     * of {@link EvidenceDigest} — it is what the older tests in this class assert on, and their
     * assertions stay literally true because that entry is never reduced. The {@code search_corpus}
     * message is real {@code ChunkBlocks.format} output carrying one cited and one uncited chunk,
     * which is what production evidence actually looks like and what makes the cite-scoping
     * assertions mean anything. With only the JSON entry the whole reviewer path would render
     * byte-identically and every test here would pass while pinning nothing.
     */
    private RagResult specialistResult() {
        LlmMessage assistant = new LlmMessage("assistant", "specialist answer",
            List.of(new LlmPart("text", "specialist answer", null, null)), null, null, null);
        LlmMessage jsonMsg = new LlmMessage("tool", "query_json_objects rows: [from 9.2.2 to 9.3]",
            null, null, "call-1", "query_json_objects");
        LlmMessage searchMsg = new LlmMessage("tool",
            ChunkBlocks.format(List.of(fixtureChunk(CITED_CHUNK, CITED_BODY, "Person"),
                fixtureChunk(UNCITED_CHUNK, UNCITED_BODY, "install-kit.md")), SeenChunks.NONE),
            null, null, "call-2", "search_corpus");
        return new RagResult(List.of(UUID.randomUUID()), List.of(assistant, jsonMsg, searchMsg),
            null, List.of(UUID.randomUUID()), 5, 10, 15, 0, 2, specialistClarifyingQuestion, null);
    }

    private static HybridSearchResult fixtureChunk(UUID id, String body, String title) {
        return new HybridSearchResult(id, UUID.randomUUID(), body, List.of("Columns"), "Columns", 2,
            0, "10.0", title, "table", "OIM", Map.of(), 0.9,
            new TelemetryInfo(true, false, 1, 0, 1));
    }
}
