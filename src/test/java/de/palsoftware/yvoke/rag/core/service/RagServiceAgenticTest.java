package de.palsoftware.yvoke.rag.core.service;

import de.palsoftware.yvoke.rag.core.model.AgenticRequest;
import de.palsoftware.yvoke.rag.core.model.RagResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.chat.core.tool.AskClarifyingQuestionToolCallback;
import de.palsoftware.yvoke.llm.core.service.LlmClient;
import de.palsoftware.yvoke.llm.core.model.LlmMessage;
import de.palsoftware.yvoke.llm.core.model.LlmRequest;
import de.palsoftware.yvoke.llm.core.model.LlmResponseChunk;
import de.palsoftware.yvoke.llm.core.model.LlmToolCallDelta;
import de.palsoftware.yvoke.llm.core.model.LlmToolCall;
import de.palsoftware.yvoke.llm.core.model.LlmUsage;
import de.palsoftware.yvoke.rag.retrieval.HybridSearch;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.Optional;
import de.palsoftware.yvoke.rag.prompt.SystemPrompt;
import de.palsoftware.yvoke.rag.prompt.SystemPromptService;
import de.palsoftware.yvoke.rag.prompt.SystemPromptType;
import de.palsoftware.yvoke.llm.core.model.LlmTool;
import java.util.concurrent.CancellationException;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

public class RagServiceAgenticTest {

    private HybridSearch hybridSearch;
    private LlmClient llmClient;
    private CitationVerifier citationVerifier;
    private RagService ragService;

    @BeforeEach
    public void setUp() {
        hybridSearch = mock(HybridSearch.class);
        llmClient = mock(LlmClient.class);
        citationVerifier = mock(CitationVerifier.class);

        ragService = new RagService(hybridSearch, llmClient, citationVerifier, new ObjectMapper(),
            6, 4096, 0);
    }

    @Test
    public void testAgenticToolCallingLoopSuccess() {
        // 1. Mock a ToolCallback for oim_search
        ToolCallback mockSearchTool = mock(ToolCallback.class);
        ToolDefinition toolDef = mock(ToolDefinition.class);
        when(toolDef.name()).thenReturn("oim_search");
        when(mockSearchTool.getToolDefinition()).thenReturn(toolDef);
        when(mockSearchTool.call(anyString())).thenReturn("mock search chunk response text");

        ragService.getToolRegistry().put("oim_search", mockSearchTool);

        // 2. Mock LlmClient stream responses for the two turns
        doAnswer(new Answer<Void>() {
            private int callCount = 0;

            @Override
            public Void answer(InvocationOnMock inv) {
                Consumer<LlmResponseChunk> cb = inv.getArgument(1);
                if (callCount == 0) {
                    cb.accept(new LlmResponseChunk(null, null, List.of(new LlmToolCallDelta(0,
                        "call_123", "oim_search", "{\"query\":\"Person\"}")),
                        new LlmUsage(10, 20, 30, 0, 0)));
                } else if (callCount == 1) {
                    cb.accept(new LlmResponseChunk("Final stream output.", null, null,
                        new LlmUsage(40, 50, 90, 0, 0)));
                }
                callCount++;
                return null;
            }
        }).when(llmClient).generateStream(any(LlmRequest.class), any());

        // 4. Run the method
        List<String> tokens = new ArrayList<>();
        RagResult result =
            ragService
                .generateAgenticAnswer(
                    AgenticRequest.builder().query("Find Person table")
                        .modelOverride("model-override").history(Collections.emptyList()).build(),
                    tokens::add);

        String answer = String.join("", tokens);

        // Verify that the tool execution progress was printed
        assertThat(answer).contains("🔧 *Calling tool:* oim_search");
        // Verify that the final text streamed is present
        assertThat(answer).contains("Final stream output.");

        // Verify that the tool was called exactly once with expected arguments
        verify(mockSearchTool, times(1)).call("{\"query\":\"Person\"}");
    }

    /**
     * S5.11. Pressing Stop interrupts the generating thread, and a blocking tool (an HTTP client, a
     * JDBC call, a nested specialist run) surfaces that interrupt as an ordinary unchecked
     * exception. The tool-failure catch is therefore the one place that has to tell "this tool
     * broke" from "the user cancelled": the first is recovered by handing the model a neutral
     * string and carrying on, the second must abort the whole run and re-raise, with the interrupt
     * flag restored so {@code ChatMessageService} can persist {@code cancelled} rather than
     * {@code error}.
     *
     * <p>
     * Lose the detection and a Stop is swallowed into "the oim_search tool could not be completed":
     * the loop keeps going, keeps billing turns and keeps calling tools, while the browser has
     * already rendered the cancellation notice for the aborted fetch — the user is shown a stopped
     * generation that is in fact still running to completion behind them.
     *
     * <p>
     * No test in this class or in {@code RagServiceTest} has ever set the interrupt flag, so none
     * of {@code RagService}'s four checkpoints executes. The batch here deliberately holds TWO
     * calls with the halting one first, and that is what makes the regression observable at all:
     * with a single-call batch the flag stays set and the loop-head checkpoint re-raises
     * {@link CancellationException} one iteration later, so the run ends in the same exception type
     * either way and the detection could be deleted unnoticed. After a halt the loop exits cleanly
     * instead — so a regression RETURNS a perfectly normal {@code RagResult} built on a cancelled
     * run.
     *
     * <p>
     * The second half covers the other arm of the rule: an interrupt that arrives only as a
     * {@code getCause()}, its flag already consumed by whoever caught the
     * {@link InterruptedException} first — the common shape once a driver wraps it. There the
     * explicit {@code Thread.currentThread().interrupt()} is the only thing that puts the flag back
     * for the caller to see.
     */
    @Test
    public void aStopDuringToolExecutionCancelsTheRunInsteadOfBecomingAnOrdinaryToolResult() {
        AskClarifyingQuestionToolCallback clarify =
            new AskClarifyingQuestionToolCallback(new ObjectMapper());
        ToolCallback search = namedTool("oim_search");
        ragService.getToolRegistry().put("ask_clarifying_question", clarify);
        ragService.getToolRegistry().put("oim_search", search);

        // One turn, one batch, two calls: the halting tool first, then the tool the Stop lands in.
        doAnswer(inv -> {
            Consumer<LlmResponseChunk> cb = inv.getArgument(1);
            cb.accept(new LlmResponseChunk(null, null,
                List.of(
                    new LlmToolCallDelta(0, "call_clarify", "ask_clarifying_question",
                        "{\"question\":\"Which collection?\",\"options\":[\"a\",\"b\"]}"),
                    new LlmToolCallDelta(1, "call_search", "oim_search", "{\"query\":\"Person\"}")),
                new LlmUsage(10, 20, 30, 0, 0)));
            return null;
        }).when(llmClient).generateStream(any(LlmRequest.class), any());

        AgenticRequest request =
            AgenticRequest.builder().query("q").modelOverride("m").history(Collections.emptyList())
                .allowedTools(List.of("ask_clarifying_question", "oim_search")).build();

        try {
            // (1) Stop while the tool is blocked: the flag is set and the blocking call dies with
            // an ordinary unchecked exception — what an interrupted HTTP/JDBC client throws.
            // doAnswer(...).when(...) rather than when(...): re-stubbing through when() would
            // invoke the previous answer and interrupt this thread during setup.
            doAnswer(inv -> {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("connection closed");
            }).when(search).call(anyString());

            assertThatThrownBy(() -> ragService.generateAgenticAnswer(request, tok -> {
            })).as("a Stop must abort the run, not become a tool result the model answers around")
                .isInstanceOf(CancellationException.class)
                .hasMessageContaining("during tool execution");
            assertThat(Thread.interrupted())
                .as("the flag is how the caller tells a Stop from a fault — it must survive")
                .isTrue();

            // (2) The same Stop arriving only as a CAUSE, its flag already consumed by the wrapper.
            doAnswer(inv -> {
                throw new IllegalStateException("tool failed", new InterruptedException("stopped"));
            }).when(search).call(anyString());

            assertThatThrownBy(() -> ragService.generateAgenticAnswer(request, tok -> {
            })).as("a wrapped interrupt is still a cancellation")
                .isInstanceOf(CancellationException.class)
                .hasMessageContaining("during tool execution");
            assertThat(Thread.interrupted())
                .as("an interrupt a wrapper consumed must be restored, not swallowed").isTrue();
        } finally {
            // Never leave the flag set: JUnit reuses this thread for the next test.
            Thread.interrupted();
        }
    }

    /**
     * A tool result is MODEL INPUT (SEC-17). It is appended to the conversation, and in
     * orchestrated mode the orchestrator's synthesised text becomes the user-visible answer — so an
     * exception class, HTTP status or provider name handed back here can be paraphrased straight
     * past the generic error wording that exists to keep that detail away from end users. The rich
     * diagnosis belongs in the log and the agent-run trace. The loop must also CONTINUE: one failed
     * tool is not a failed answer, the model is told to state the gap instead.
     */
    @Test
    public void aThrowingToolHandsTheModelProviderNeutralTextAndTheLoopContinues() {
        ToolCallback failing = mock(ToolCallback.class);
        ToolDefinition toolDef = mock(ToolDefinition.class);
        when(toolDef.name()).thenReturn("oim_search");
        when(failing.getToolDefinition()).thenReturn(toolDef);
        when(failing.call(anyString())).thenThrow(new IllegalStateException(
            "com.google.genai.errors.ApiException: 429 RESOURCE_EXHAUSTED quota project oim-prod"));
        ragService.getToolRegistry().put("oim_search", failing);

        List<LlmRequest> requests = new ArrayList<>();
        doAnswer(new Answer<Void>() {
            private int callCount = 0;

            @Override
            public Void answer(InvocationOnMock inv) {
                requests.add(inv.getArgument(0));
                Consumer<LlmResponseChunk> cb = inv.getArgument(1);
                if (callCount == 0) {
                    cb.accept(new LlmResponseChunk(null, null, List.of(
                        new LlmToolCallDelta(0, "call_1", "oim_search", "{\"query\":\"Person\"}")),
                        new LlmUsage(10, 20, 30, 0, 0)));
                } else {
                    cb.accept(new LlmResponseChunk("Answered despite the gap.", null, null,
                        new LlmUsage(1, 1, 2, 0, 0)));
                }
                callCount++;
                return null;
            }
        }).when(llmClient).generateStream(any(LlmRequest.class), any());

        List<String> tokens = new ArrayList<>();
        ragService.generateAgenticAnswer(AgenticRequest.builder().query("Find Person table")
            .modelOverride("m").history(Collections.emptyList()).build(), tokens::add);

        // The loop continued to a second turn and produced an answer.
        assertThat(requests).hasSize(2);
        assertThat(String.join("", tokens)).contains("Answered despite the gap.");

        // What the model was handed back must name the tool and nothing about the failure.
        String toolResult = requests.get(1).messages().stream().filter(m -> "tool".equals(m.role()))
            .map(LlmMessage::content).reduce("", (a, b) -> a + b);
        assertThat(toolResult).contains("oim_search").contains("could not be completed");
        assertThat(toolResult).doesNotContain("429").doesNotContain("ApiException")
            .doesNotContain("RESOURCE_EXHAUSTED").doesNotContain("quota").doesNotContain("oim-prod")
            .doesNotContain("IllegalStateException");
    }

    /**
     * A blank per-conversation prompt is a DELETED prompt, not an empty one. The chat UI stores the
     * override as plain text and clearing the field posts {@code ""} rather than removing the
     * column value, so {@code ""} and {@code "   "} are exactly what a user produces by wiping the
     * box — and the only sane reading of that gesture is "go back to the global default".
     *
     * <p>
     * If the guard degrades to a bare null check, that wipe instead sends the model a system
     * message whose content is the empty string. Nothing fails: the request is well formed, the
     * provider accepts it, tokens are billed and an answer streams back — it is simply an
     * *ungoverned* answer, with none of the corpus rules, citation discipline or refusal behaviour
     * the active prompt carries. The regression is invisible from every angle an operator can see;
     * it looks like the model just got worse.
     *
     * <p>
     * No existing test can notice, because the shared {@code setUp} harness builds
     * {@code RagService} through the 7-argument convenience constructor, which passes {@code null}
     * for the {@link SystemPromptService} — so the fallback branch resolves to {@code ""} anyway
     * and both sides of the condition look identical. Wiring a real prompt service is what makes
     * the two branches distinguishable at all. The final case pins the other direction too: a
     * genuine override must still beat the default, so this cannot be satisfied by always falling
     * back.
     */
    @Test
    public void aBlankSystemPromptOverrideFallsBackToTheActiveDefaultRatherThanToNothing() {
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        when(systemPromptService.getDefaultChatPromptName()).thenReturn("oim-agentic");
        when(systemPromptService.getPrompt("oim-agentic"))
            .thenReturn(Optional.of(new SystemPrompt("oim-agentic", SystemPromptType.CHAT, "ACTIVE",
                "the global default")));

        RagService service = new RagService(hybridSearch, llmClient, citationVerifier,
            new ObjectMapper(), 6, 4096, 0, null, true, systemPromptService, List.of());

        List<LlmRequest> seen = new ArrayList<>();
        doAnswer(inv -> {
            seen.add(inv.getArgument(0));
            Consumer<LlmResponseChunk> cb = inv.getArgument(1);
            cb.accept(new LlmResponseChunk("done", null, null, new LlmUsage(1, 1, 2, 0, 0)));
            return null;
        }).when(llmClient).generateStream(any(LlmRequest.class), any());

        List<String> overrides = new ArrayList<>();
        overrides.add("");
        overrides.add("   ");
        overrides.add(null);
        overrides.add("PER-CONVERSATION");
        for (String override : overrides) {
            service.generateAgenticAnswer(
                AgenticRequest.builder().query("q").modelOverride("m")
                    .history(Collections.emptyList()).systemPromptOverride(override).build(),
                tok -> {
                });
        }

        List<String> systemPrompts =
            seen.stream().map(req -> req.messages().stream().filter(m -> "system".equals(m.role()))
                .map(LlmMessage::content).findFirst().orElse(null)).toList();

        assertThat(systemPrompts).hasSize(4);
        assertThat(systemPrompts.get(0)).as("an empty override is a deleted override, not a gag")
            .isEqualTo("ACTIVE");
        assertThat(systemPrompts.get(1)).as("whitespace is not a system prompt either")
            .isEqualTo("ACTIVE");
        assertThat(systemPrompts.get(2)).as("no override at all falls back the same way")
            .isEqualTo("ACTIVE");
        assertThat(systemPrompts.get(3))
            .as("a real override must still win, or the fallback is just a hardcode")
            .isEqualTo("PER-CONVERSATION");
    }

    /**
     * S5.6. The turn that runs after the iteration cap is the FORCED FINAL one, and the only thing
     * that actually forces it is offering no tools at all. A model handed the same catalogue one
     * more time does what it did on the previous {@code maxIterations} turns — it calls another
     * tool — and that turn then produces no text, at which point {@code generateAgenticAnswer}
     * throws "empty response". So the user's question dies AT the cap instead of getting the
     * partial answer the cap exists to salvage, after every turn's tokens have already been billed,
     * and the exception names a malformed model response rather than the loop that caused it.
     *
     * <p>
     * {@code testAgenticToolCallingLoopHitsCap} cannot see this: it scripts the turn after the cap
     * to return text unconditionally, so the forced turn behaves identically whether or not it was
     * offered tools, and it asserts on the ⚠️ banner rather than on the request. The request is the
     * only place the difference exists at all — the cap is enforced by what is NOT sent.
     */
    @Test
    public void theForcedFinalTurnAfterTheCapOffersNoToolsAtAll() {
        ToolCallback search = namedTool("oim_search");
        when(search.call(anyString())).thenReturn("mock response");

        RagService capped = new RagService(hybridSearch, llmClient, citationVerifier,
            new ObjectMapper(), 2, 4096, 0);
        capped.getToolRegistry().put("oim_search", search);

        List<LlmRequest> requests = new ArrayList<>();
        int[] turnNo = {0};
        doAnswer(inv -> {
            requests.add(inv.getArgument(0));
            Consumer<LlmResponseChunk> cb = inv.getArgument(1);
            if (turnNo[0]++ < 2) {
                cb.accept(new LlmResponseChunk(null, null,
                    List.of(new LlmToolCallDelta(0, "call_" + turnNo[0], "oim_search", "{}")),
                    new LlmUsage(10, 20, 30, 0, 0)));
            } else {
                cb.accept(new LlmResponseChunk("Forced answer due to cap.", null, null,
                    new LlmUsage(1, 1, 2, 0, 0)));
            }
            return null;
        }).when(llmClient).generateStream(any(LlmRequest.class), any());

        capped.generateAgenticAnswer(AgenticRequest.builder().query("q").modelOverride("m")
            .history(Collections.emptyList()).allowedTools(List.of("oim_search")).build(), tok -> {
            });

        assertThat(requests).hasSize(3);
        assertThat(requests.get(0).tools().stream().map(LlmTool::name).toList())
            .as("the pre-cap turns are ordinary agentic turns").containsExactly("oim_search");
        assertThat(requests.get(1).tools().stream().map(LlmTool::name).toList())
            .containsExactly("oim_search");
        assertThat(requests.get(2).tools())
            .as("the forced final turn must offer nothing, or the model just calls another tool")
            .isEmpty();
    }

    /**
     * S5 failure modes. {@code modelOverride} is not optional: every agentic run names the model it
     * runs on, and this guard is the only thing that turns "nobody chose a model" into a clean,
     * local failure.
     *
     * <p>
     * By the time {@code generateAgenticAnswer} is entered the SSE stream is already open and the
     * user's message already persisted, so a missing model that slips past this line does not fail
     * here — it fails inside the provider, one billed round-trip later, as an opaque transport
     * error that {@code ChatMessageService} maps to the generic notice. The user is shown
     * "something went wrong" for what is a configuration mistake, and the run's recorded error
     * carries the provider's wording instead of the cause.
     *
     * <p>
     * Nothing executes the guard today: every {@code AgenticRequest} built anywhere in this suite
     * sets a model, so neither branch of the condition is reached and deleting the whole block
     * leaves the suite green. The {@code never()} verification is the load-bearing half — it is
     * what asserts "before the first LLM call" rather than merely "eventually throws", which is the
     * distinction the rule is about. The blank case is checked as well as the null one because a
     * form that posts an empty model field produces {@code ""}, not {@code null}.
     */
    @Test
    public void aMissingModelOverrideThrowsBeforeTheFirstLlmCall() {
        assertThatThrownBy(() -> ragService.generateAgenticAnswer(
            AgenticRequest.builder().query("q").history(Collections.emptyList()).build(), tok -> {
            })).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("modelOverride");

        assertThatThrownBy(() -> ragService.generateAgenticAnswer(AgenticRequest.builder()
            .query("q").modelOverride("   ").history(Collections.emptyList()).build(), tok -> {
            })).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("modelOverride");

        verify(llmClient, never()).generateStream(any(LlmRequest.class), any());
    }

    /**
     * S5.11. Models emit tool names that do not exist — a typo, a name from another deployment's
     * catalogue, a tool the allow-list denied this run. That is an ordinary, frequent event, and
     * the loop's contract is that it is recoverable: the unknown name comes back as a plain tool
     * result ({@code "Error: Tool <name> not found."}) appended to the conversation, and the model
     * gets another turn to correct itself. It is the same shape as the tool-FAILURE branch, and for
     * the same reason — the model can fix its own arguments, so hand the mistake back to it.
     *
     * <p>
     * Turn that branch into a throw — the natural "this can't happen, registry lookups always
     * succeed" cleanup — and one hallucinated tool name kills the entire answer: the SSE stream is
     * already open and the user message already persisted, so the user gets the generic error
     * notice for a run that was one corrected turn away from succeeding.
     *
     * <p>
     * Nothing executes that branch today: every stubbed tool call in this class names a tool that
     * was put into the registry, so {@code callback == null} is unreachable in the whole suite. The
     * assertions here are the outcome, not the input — a SECOND request was issued, its
     * conversation carries the error as a {@code tool} message, the registered tool was never
     * invoked (a fuzzy fallback to the nearest name would be a different bug), and the answer from
     * the recovered turn actually reaches the user.
     */
    @Test
    public void aHallucinatedToolNameComesBackAsAToolResultAndTheLoopContinues() {
        ToolCallback registered = namedTool("oim_search");
        ragService.getToolRegistry().put("oim_search", registered);

        List<LlmRequest> requests = new ArrayList<>();
        int[] turnNo = {0};
        doAnswer(inv -> {
            requests.add(inv.getArgument(0));
            Consumer<LlmResponseChunk> cb = inv.getArgument(1);
            if (turnNo[0]++ == 0) {
                // "oim_serch" — one letter off the registered tool, the everyday failure.
                cb.accept(new LlmResponseChunk(null, null,
                    List.of(
                        new LlmToolCallDelta(0, "call_1", "oim_serch", "{\"query\":\"Person\"}")),
                    new LlmUsage(10, 20, 30, 0, 0)));
            } else {
                cb.accept(new LlmResponseChunk("Recovered and answered.", null, null,
                    new LlmUsage(1, 1, 2, 0, 0)));
            }
            return null;
        }).when(llmClient).generateStream(any(LlmRequest.class), any());

        List<String> tokens = new ArrayList<>();
        RagResult result = ragService.generateAgenticAnswer(
            AgenticRequest.builder().query("Find the Person table").modelOverride("m")
                .history(Collections.emptyList()).allowedTools(List.of("oim_search")).build(),
            tokens::add);

        assertThat(requests).as("an unknown tool name must cost a turn, not the answer").hasSize(2);

        String toolSeen = requests.get(1).messages().stream().filter(m -> "tool".equals(m.role()))
            .map(LlmMessage::content).reduce("", (a, b) -> a + b);
        assertThat(toolSeen).as("the model is told which name failed, as an ordinary tool result")
            .isEqualTo("Error: Tool oim_serch not found.");

        verify(registered, never()).call(anyString());

        assertThat(String.join("", tokens)).contains("Recovered and answered.");
        assertThat(result.messages().stream().filter(m -> "tool".equals(m.role())).count())
            .as("the recovery is part of the persisted turn, not an invisible detour").isEqualTo(1);
    }

    @Test
    public void testAgenticToolCallingLoopHitsCap() {
        // 1. Mock a ToolCallback that gets called repeatedly
        ToolCallback mockSearchTool = mock(ToolCallback.class);
        ToolDefinition toolDef = mock(ToolDefinition.class);
        when(toolDef.name()).thenReturn("oim_search");
        when(mockSearchTool.getToolDefinition()).thenReturn(toolDef);
        when(mockSearchTool.call(anyString())).thenReturn("mock response");

        ragService.getToolRegistry().put("oim_search", mockSearchTool);

        // 2. Mock LlmClient stream responses for hits cap (max iterations = 3)
        doAnswer(new Answer<Void>() {
            private int callCount = 0;

            @Override
            public Void answer(InvocationOnMock inv) {
                Consumer<LlmResponseChunk> cb = inv.getArgument(1);
                if (callCount < 3) {
                    cb.accept(new LlmResponseChunk(null, null,
                        List.of(new LlmToolCallDelta(0, "call_1", "oim_search", "{}")),
                        new LlmUsage(10, 20, 30, 0, 0)));
                } else if (callCount == 3) {
                    cb.accept(new LlmResponseChunk("Forced answer due to cap.", null, null,
                        new LlmUsage(40, 50, 90, 0, 0)));
                }
                callCount++;
                return null;
            }
        }).when(llmClient).generateStream(any(LlmRequest.class), any());

        // Create a custom RagService with maxIterations = 3 to test the cap
        RagService customRagService = new RagService(hybridSearch, llmClient, citationVerifier,
            new ObjectMapper(), 3, 4096, 0);
        customRagService.getToolRegistry().put("oim_search", mockSearchTool);

        // 4. Run the method
        List<String> tokens = new ArrayList<>();
        RagResult result =
            customRagService
                .generateAgenticAnswer(
                    AgenticRequest.builder().query("Find Person table")
                        .modelOverride("model-override").history(Collections.emptyList()).build(),
                    tokens::add);

        String answer = String.join("", tokens);

        // It should call the tool exactly 3 times before breaking out
        verify(mockSearchTool, times(3)).call("{}");
        assertThat(answer).contains("Forced answer due to cap.");
        assertThat(answer).contains(
            "⚠️ *[System: The maximum loop count of 3 iterations was hit. The response was cut off to prevent an infinite loop.]*");
    }

    /**
     * The citation filter is a DESTRUCTIVE stage: {@code CitationStreamingFilter} deletes any
     * bracketed run {@link CitationVerifier#isFabricated} condemns, and it is deliberately wired to
     * {@code chunk.content()} only. Reasoning is the model's private scratchpad — it names ids it
     * is still deciding whether to trust, quotes shapes back to itself and writes brackets that are
     * not citations at all — so running it through the same filter would silently carve holes in
     * the thinking trace, which is the only record of HOW an answer was reached and the first thing
     * an operator reads when an answer looks wrong.
     *
     * <p>
     * Nothing would report the loss. The run is removed mid-stream, the {@code <think>} block still
     * opens and closes, the answer still completes and the token counts are unchanged; it reads as
     * the model simply not having mentioned the id. And the deletion is worst exactly where the
     * trace matters most — a chunk id the model was reasoning about but had not yet cited is
     * precisely the id whose absence makes the trace unusable.
     *
     * <p>
     * No existing test can notice. {@code testAgenticToolCallingLoopWithReasoningOnly} streams
     * bracket-free reasoning, so both wirings produce byte-identical output, and every other test
     * here stubs {@link CitationVerifier} without ever making it return true — the filter is
     * present but inert. Only a run the verifier condemns, appearing in BOTH channels of the same
     * turn, separates "reasoning bypasses the filter" from "reasoning happens not to be filtered
     * yet".
     */
    @Test
    public void aBracketRunInReasoningReachesTheSinkUnfilteredWhileTheSameRunInContentIsStripped() {
        String citation = "8f7c1a2b-3d4e-4f50-8a1b-2c3d4e5f6071";
        when(citationVerifier.isFabricated("[" + citation + "]")).thenReturn(true);

        doAnswer(inv -> {
            Consumer<LlmResponseChunk> cb = inv.getArgument(1);
            cb.accept(new LlmResponseChunk("Answer refs [" + citation + "] here.",
                "Thinking about [" + citation + "] now.", null, new LlmUsage(1, 1, 2, 0, 0)));
            return null;
        }).when(llmClient).generateStream(any(LlmRequest.class), any());

        List<String> tokens = new ArrayList<>();
        ragService.generateAgenticAnswer(AgenticRequest.builder().query("q").modelOverride("m")
            .history(Collections.emptyList()).build(), tokens::add);

        String answer = String.join("", tokens);

        assertThat(answer).as("reasoning never reaches the filter, so the run survives verbatim")
            .contains("<think>\nThinking about [" + citation + "] now.\n</think>");
        assertThat(answer.substring(answer.indexOf("</think>")))
            .as("the same run in content IS stripped, which is what makes the two channels differ")
            .doesNotContain(citation);
        assertThat(answer).endsWith("Answer refs  here.");
    }

    @Test
    public void testAgenticToolCallingLoopWithReasoningOnly() {
        // 1. Mock a ToolCallback for oim_search
        ToolCallback mockSearchTool = mock(ToolCallback.class);
        ToolDefinition toolDef = mock(ToolDefinition.class);
        when(toolDef.name()).thenReturn("oim_search");
        when(mockSearchTool.getToolDefinition()).thenReturn(toolDef);
        when(mockSearchTool.call(anyString())).thenReturn("mock search chunk response text");

        ragService.getToolRegistry().put("oim_search", mockSearchTool);

        // 2. Mock LlmClient stream responses for the two turns:
        // Turn 1 has reasoning and tool call, but no content.
        // Turn 2 has reasoning and final content.
        doAnswer(new Answer<Void>() {
            private int callCount = 0;

            @Override
            public Void answer(InvocationOnMock inv) {
                Consumer<LlmResponseChunk> cb = inv.getArgument(1);
                if (callCount == 0) {
                    cb.accept(new LlmResponseChunk(null, "Reasoning in turn 1.",
                        List.of(new LlmToolCallDelta(0, "call_123", "oim_search", "{}")),
                        new LlmUsage(10, 20, 30, 0, 0)));
                } else if (callCount == 1) {
                    cb.accept(new LlmResponseChunk("Final content in turn 2.",
                        "Reasoning in turn 2.", null, new LlmUsage(40, 50, 90, 0, 0)));
                }
                callCount++;
                return null;
            }
        }).when(llmClient).generateStream(any(LlmRequest.class), any());

        // 3. Run the method
        List<String> tokens = new ArrayList<>();
        RagResult result = ragService.generateAgenticAnswer(
            AgenticRequest.builder().query("Find Person").modelOverride("model-override")
                .history(Collections.emptyList()).build(),
            tokens::add);

        String answer = String.join("", tokens);

        // Verify that thinking tags are properly opened and closed in each turn
        // Turn 1 should be wrapped: <think>\nReasoning in turn 1.\n</think>\n\n
        // Turn 2 should have its own thinking: <think>\nReasoning in turn
        // 2.\n</think>\n\nFinal content in turn 2.
        assertThat(answer).contains("<think>\nReasoning in turn 1.\n</think>\n\n");
        assertThat(answer)
            .contains("<think>\nReasoning in turn 2.\n</think>\n\nFinal content in turn 2.");
    }

    /**
     * The halt is evaluated once per TURN, after the whole tool batch — never between two calls of
     * the same batch. Gemini routinely emits several function calls in one response, and the model
     * chose all of them against the same state; skipping the later ones because an earlier one set
     * {@code haltRequested} would drop work the model believes it has done.
     *
     * <p>
     * The concrete damage is a malformed conversation, not just a missing search. Every tool call
     * appended to the assistant message MUST be answered by a matching tool result: providers
     * reject — or silently mis-parse — a history where a {@code function_call} has no
     * {@code function_response}. So a break inside {@link RagService#executeToolCalls} leaves the
     * turn structurally invalid, and it is persisted that way: the next user message replays this
     * history and the failure surfaces one turn later, in a request that looks unrelated to the
     * clarifying question that actually caused it.
     *
     * <p>
     * No existing test would notice. {@code testAgenticToolCallingLoopHaltsOnClarifyingQuestion}
     * scripts a batch of exactly ONE tool call, so the loop body executes once whether or not it
     * breaks, and the assertion it makes — that the second TURN never happens — stays green under
     * the break. A batch of two is the only shape that separates "halt after the batch" from "halt
     * inside it".
     */
    @Test
    public void aSecondToolInTheSameBatchStillRunsAfterAHaltingTool() {
        AskClarifyingQuestionToolCallback clarify =
            new AskClarifyingQuestionToolCallback(new ObjectMapper());
        ToolCallback search = namedTool("oim_search");
        when(search.call(anyString())).thenReturn("mock search chunk response text");
        ragService.getToolRegistry().put("ask_clarifying_question", clarify);
        ragService.getToolRegistry().put("oim_search", search);

        // One turn, one batch, two calls: the halting tool first so it can suppress the second.
        doAnswer(new Answer<Void>() {
            private int callCount = 0;

            @Override
            public Void answer(InvocationOnMock inv) {
                Consumer<LlmResponseChunk> cb = inv.getArgument(1);
                if (callCount == 0) {
                    cb.accept(new LlmResponseChunk(null, null,
                        List.of(
                            new LlmToolCallDelta(0, "call_clarify", "ask_clarifying_question",
                                "{\"question\":\"Which collection?\",\"options\":[\"a\",\"b\"]}"),
                            new LlmToolCallDelta(1, "call_search", "oim_search",
                                "{\"query\":\"Person\"}")),
                        new LlmUsage(10, 20, 30, 0, 0)));
                } else {
                    cb.accept(new LlmResponseChunk("Should not generate this.", null, null,
                        new LlmUsage(40, 50, 90, 0, 0)));
                }
                callCount++;
                return null;
            }
        }).when(llmClient).generateStream(any(LlmRequest.class), any());

        List<String> tokens = new ArrayList<>();
        RagResult result = ragService.generateAgenticAnswer(
            AgenticRequest.builder().query("Search docs").modelOverride("m")
                .history(Collections.emptyList())
                .allowedTools(List.of("ask_clarifying_question", "oim_search")).build(),
            tokens::add);

        // The second tool ran, even though the first one had already requested the halt.
        verify(search, times(1)).call("{\"query\":\"Person\"}");

        // ...and BOTH calls were answered, in the order the model made them, so the persisted
        // turn is a valid function_call/function_response pairing.
        List<String> toolResultNames = result.messages().stream()
            .filter(m -> "tool".equals(m.role())).map(LlmMessage::toolName).toList();
        assertThat(toolResultNames).containsExactly("ask_clarifying_question", "oim_search");

        // The halt itself is unchanged: it ends the LOOP after the batch, so there is no turn two.
        verify(llmClient, times(1)).generateStream(any(LlmRequest.class), any());
        assertThat(result.clarifyingQuestion()).isEqualTo("Which collection?");
        assertThat(String.join("", tokens)).doesNotContain("Should not generate this.");
    }

    /**
     * S5.10. The 🔧 banner, the clarifying-question XML and the ⚠️ cap warning are USER chrome:
     * they go out with a bare {@code sink.accept} precisely so they never enter
     * {@code turn.emitted}, and therefore never become part of the assistant message the model is
     * replayed.
     *
     * <p>
     * Put them into that message and the model is shown its own tool use twice, in two formats, in
     * the same turn: once as the {@code function_call} part that is the real protocol, and once as
     * English prose claiming a tool was called. Nothing fails — the request is well formed and the
     * answer still streams — but that is exactly the history that teaches an agent to NARRATE a
     * search instead of performing one, which is unfalsifiable from the outside because a run where
     * the model wrote about searching looks like a run where it chose not to search. It also pays
     * the banner's prompt tokens again on every later turn of a long agentic run.
     *
     * <p>
     * The existing tests all assert the banner reached the SINK
     * ({@code testAgenticToolCallingLoopSuccess}) and none of them ever open the second request.
     * The two behaviours are only distinguishable by reading the assistant message of a turn that
     * actually made a tool call, which is why the first turn here streams text AND a tool call
     * rather than a tool call alone.
     */
    @Test
    public void theToolCallBannerReachesTheUserSinkButNotTheAssistantMessageTheModelSees() {
        ToolCallback search = namedTool("oim_search");
        when(search.call(anyString())).thenReturn("mock search chunk response text");
        ragService.getToolRegistry().put("oim_search", search);

        List<LlmRequest> requests = new ArrayList<>();
        int[] turnNo = {0};
        doAnswer(inv -> {
            requests.add(inv.getArgument(0));
            Consumer<LlmResponseChunk> cb = inv.getArgument(1);
            if (turnNo[0]++ == 0) {
                cb.accept(new LlmResponseChunk("Let me look that up.", null,
                    List.of(
                        new LlmToolCallDelta(0, "call_1", "oim_search", "{\"query\":\"Person\"}")),
                    new LlmUsage(10, 20, 30, 0, 0)));
            } else {
                cb.accept(new LlmResponseChunk("Here is the answer.", null, null,
                    new LlmUsage(1, 1, 2, 0, 0)));
            }
            return null;
        }).when(llmClient).generateStream(any(LlmRequest.class), any());

        List<String> tokens = new ArrayList<>();
        RagResult result =
            ragService.generateAgenticAnswer(
                AgenticRequest.builder().query("q").modelOverride("m")
                    .history(Collections.emptyList()).allowedTools(List.of("oim_search")).build(),
                tokens::add);

        assertThat(String.join("", tokens))
            .as("the user sees the banner — it is the only sign a tool ran")
            .contains("🔧 *Calling tool:* oim_search({\"query\":\"Person\"})");

        assertThat(requests).hasSize(2);
        String assistantSeen =
            requests.get(1).messages().stream().filter(m -> "assistant".equals(m.role()))
                .map(LlmMessage::content).reduce("", (a, b) -> a + b);
        assertThat(assistantSeen).as("the model is replayed its own text and nothing else")
            .isEqualTo("Let me look that up.").doesNotContain("🔧");

        String toolSeen = requests.get(1).messages().stream().filter(m -> "tool".equals(m.role()))
            .map(LlmMessage::content).reduce("", (a, b) -> a + b);
        assertThat(toolSeen).as("the tool result is the tool's own output, not the banner")
            .isEqualTo("mock search chunk response text");

        assertThat(result.messages().stream().filter(m -> "assistant".equals(m.role()))
            .map(LlmMessage::content).reduce("", (a, b) -> a + b))
            .as("and the persisted turn carries no chrome either").doesNotContain("🔧");
    }

    @Test
    public void testAgenticToolCallingLoopHaltsOnClarifyingQuestion() {
        // Register the real AskClarifyingQuestionToolCallback
        AskClarifyingQuestionToolCallback tool =
            new AskClarifyingQuestionToolCallback(new ObjectMapper());
        ragService.getToolRegistry().put("ask_clarifying_question", tool);

        // Mock LLM response to call ask_clarifying_question
        doAnswer(new Answer<Void>() {
            private int callCount = 0;

            @Override
            public Void answer(InvocationOnMock inv) {
                Consumer<LlmResponseChunk> cb = inv.getArgument(1);
                if (callCount == 0) {
                    cb.accept(new LlmResponseChunk(null, null,
                        List.of(new LlmToolCallDelta(0, "call_clarify", "ask_clarifying_question",
                            "{\"question\":\"Which collection?\",\"options\":[\"a\",\"b\"]}")),
                        new LlmUsage(10, 20, 30, 0, 0)));
                } else {
                    // This second turn should NOT be executed because the loop halts
                    cb.accept(new LlmResponseChunk("Should not generate this.", null, null,
                        new LlmUsage(40, 50, 90, 0, 0)));
                }
                callCount++;
                return null;
            }
        }).when(llmClient).generateStream(any(LlmRequest.class), any());

        List<String> tokens = new ArrayList<>();
        RagResult result = ragService.generateAgenticAnswer(
            AgenticRequest.builder().query("Search docs").modelOverride("model-override")
                .history(Collections.emptyList()).build(),
            tokens::add);

        String answer = String.join("", tokens);

        // Verify that it streamed the structured XML representation instead of standard tool text
        assertThat(answer).contains("<clarifying-question>");
        assertThat(answer).contains("<question>Which collection?</question>");
        assertThat(answer).contains("<option>a</option>");
        assertThat(answer).contains("<option>b</option>");
        assertThat(answer).contains("</clarifying-question>");

        // Verify that the loop stopped and didn't generate turn 2 content
        assertThat(answer).doesNotContain("Should not generate this.");
    }

    /**
     * S5.10. What the chat layer persists as an assistant message is what the SINK received —
     * banner lines included — so the chrome the model never saw during the run comes BACK to it on
     * the next user turn, as ordinary assistant prose. {@code cleanAssistantContent} is the only
     * thing standing between the two.
     *
     * <p>
     * Without it, a long conversation replays a growing transcript of "🔧 *Calling tool:*
     * search_corpus({...})" lines that no {@code function_response} ever answers. The model is
     * being shown a history in which calling a tool means WRITING A SENTENCE about calling a tool —
     * which is exactly the failure where an agent narrates searches it never ran and cites chunks
     * it never retrieved, and it is invisible from the outside because such a run looks identical
     * to one where the model simply chose not to search. It also re-bills every banner as prompt
     * tokens on every subsequent turn.
     *
     * <p>
     * The four accepted prefixes are not decoration. The bold and plain forms come from different
     * writers, and the {@code ??} pair is the same two lines after the emoji has been mangled by a
     * non-UTF-8 hop (a Windows console, a mis-declared column) — which is the form that actually
     * reaches the replay in the field, and the one a UTF-8-only test would never produce. No
     * existing test in this class replays any history at all, so the whole method is uncovered.
     */
    @Test
    public void replayedAssistantHistoryHasItsToolBannerLinesStripped() {
        List<LlmMessage> history = List.of(new LlmMessage("user", "earlier question"),
            new LlmMessage("assistant", "🔧 *Calling tool:* search_corpus({})\nreal text"),
            new LlmMessage("assistant", "🔧 Calling tool: get_section({})\nsecond text"),
            new LlmMessage("assistant", "?? *Calling tool:* get_toc({})\nthird text"),
            new LlmMessage("assistant", "?? Calling tool: list_documents({})\nfourth text"));

        List<LlmRequest> seen = new ArrayList<>();
        doAnswer(inv -> {
            seen.add(inv.getArgument(0));
            Consumer<LlmResponseChunk> cb = inv.getArgument(1);
            cb.accept(new LlmResponseChunk("done", null, null, new LlmUsage(1, 1, 2, 0, 0)));
            return null;
        }).when(llmClient).generateStream(any(LlmRequest.class), any());

        ragService.generateAgenticAnswer(
            AgenticRequest.builder().query("follow-up").modelOverride("m").history(history).build(),
            tok -> {
            });

        List<String> replayedAssistant = seen.get(0).messages().stream()
            .filter(m -> "assistant".equals(m.role())).map(LlmMessage::content).toList();

        assertThat(replayedAssistant)
            .as("every banner form is removed and only the model's real prose is replayed")
            .containsExactly("real text", "second text", "third text", "fourth text");
    }

    /**
     * {@code priorMessages} continues a conversation the caller already holds, verbatim — system
     * prompt, tool calls and tool results included — and only the new user turn is appended.
     *
     * <p>
     * This is the channel {@code history} cannot be: the assertion below on the {@code tool}
     * message is the whole reason the field exists, because {@link RagService#buildInitialMessages}
     * keeps only {@code user}/{@code assistant} content strings and silently discards every tool
     * result. An agent handed its own past conversation through {@code history} therefore loses the
     * evidence in it — which is what made the orchestrator re-research a question it had already
     * answered on every review round.
     *
     * <p>
     * The system prompt is deliberately NOT re-applied over index 0: the earlier turns were
     * answering the instructions already in the seed, and overwriting them would rewrite the
     * question those answers belong to.
     */
    @Test
    public void priorMessagesContinueTheConversationVerbatimInsteadOfRebuildingIt() {
        List<LlmMessage> prior = List.of(new LlmMessage("system", "SEEDED SYSTEM PROMPT"),
            new LlmMessage("user", "original question"),
            new LlmMessage("assistant", "draft", null,
                List.of(new LlmToolCall("call-1", "function", "search_corpus", "{}")), null, null),
            new LlmMessage("tool", "TOOL EVIDENCE", null, null, "call-1", "search_corpus"));

        List<LlmRequest> seen = new ArrayList<>();
        doAnswer(inv -> {
            seen.add(inv.getArgument(0));
            Consumer<LlmResponseChunk> cb = inv.getArgument(1);
            cb.accept(new LlmResponseChunk("revised", null, null, new LlmUsage(1, 1, 2, 0, 0)));
            return null;
        }).when(llmClient).generateStream(any(LlmRequest.class), any());

        ragService.generateAgenticAnswer(AgenticRequest.builder().query("now fix the citation")
            .modelOverride("m").systemPromptOverride("IGNORED OVERRIDE")
            .history(List.of(new LlmMessage("user", "unrelated chat history"))).priorMessages(prior)
            .build(), tok -> {
            });

        List<LlmMessage> sent = seen.get(0).messages();
        assertThat(sent).as("the seed verbatim, plus exactly one new user turn")
            .hasSize(prior.size() + 1);
        assertThat(sent.get(0).content())
            .as("the seed's own system prompt stands; the override must not rewrite it")
            .isEqualTo("SEEDED SYSTEM PROMPT");
        assertThat(sent.get(2).toolCalls()).as("tool CALLS survive, which history cannot carry")
            .isNotNull();
        assertThat(sent.get(3)).as("and so do tool RESULTS — the evidence history silently drops")
            .satisfies(m -> {
                assertThat(m.role()).isEqualTo("tool");
                assertThat(m.content()).isEqualTo("TOOL EVIDENCE");
                assertThat(m.toolCallId()).isEqualTo("call-1");
            });
        assertThat(sent).as("history is ignored when a seed is supplied, not merged into it")
            .noneSatisfy(m -> assertThat(m.content()).contains("unrelated chat history"));
        assertThat(sent.get(sent.size() - 1)).satisfies(m -> {
            assertThat(m.role()).isEqualTo("user");
            assertThat(m.content()).isEqualTo("now fix the citation");
        });
    }

    /**
     * The allow-list DENIES by default: a non-null list offers exactly its members, and **null is
     * treated the same as empty** — nothing but the always-include set.
     *
     * <p>
     * Null used to skip the filter entirely and hand over the whole catalogue, which is fail-OPEN
     * and backwards for an allow-list: the one input a caller can produce by accident — a field
     * left unset, a record built without that argument — granted the most access. Nothing depended
     * on it. Every producer already passes a real list: chat forwards {@code playbook.tools()} (the
     * repository writes {@code new String[0]} rather than NULL, so it is never null), the
     * orchestrator and reviewer pass hardcoded lists at the call site, and the specialist path
     * already coalesced null to empty itself. So the branch was unreachable in practice and existed
     * only as a trap for the next caller.
     */
    @Test
    public void theToolAllowListDeniesByDefaultSoNullIsTreatedAsEmpty() {
        ragService.getToolRegistry().put("oim_search", namedTool("oim_search"));
        ragService.getToolRegistry().put("get_section", namedTool("get_section"));

        assertThat(offeredToolNames(List.of("oim_search"))).contains("oim_search")
            .doesNotContain("get_section");
        assertThat(offeredToolNames(List.of())).as("an empty list is a restriction, not a wildcard")
            .doesNotContain("oim_search", "get_section");
        assertThat(offeredToolNames(null))
            .as("null must deny like empty, not grant the whole catalogue")
            .doesNotContain("oim_search", "get_section");
    }

    /**
     * S5.2. The active chat prompt is named by configuration but IS content: an admin can rename it
     * in the prompts console, delete it, or import a set that does not contain it. When the name
     * stops resolving, the answer must still run ungoverned rather than not run at all.
     *
     * <p>
     * The alternative is far worse than it looks. {@code loadAgenticSystemPrompt} is reached from
     * {@code buildInitialMessages}, before the first token streams and before any tool runs, so a
     * throw there fails EVERY chat request in the deployment at once, for every user, from a code
     * path with no fallback, no warning and no admin screen that reports the mismatch — until
     * someone works out which row name the config expects. An empty prompt degrades one dimension
     * of answer quality for as long as the row is missing; a throw takes the product down.
     *
     * <p>
     * {@code aBlankSystemPromptOverrideFallsBackToTheActiveDefaultRatherThanToNothing} cannot cover
     * this: it stubs the lookup to a prompt that is present, so it only ever exercises the
     * value-bearing side of the {@code Optional}. Every other test here builds {@code RagService}
     * through the 7-argument convenience constructor, which passes a {@code null}
     * {@link SystemPromptService} and short-circuits before the {@code Optional} exists at all.
     */
    @Test
    public void anUnknownActiveChatPromptYieldsAnEmptySystemPromptInsteadOfThrowing() {
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        when(systemPromptService.getDefaultChatPromptName()).thenReturn("deleted-prompt");
        when(systemPromptService.getPrompt("deleted-prompt")).thenReturn(Optional.empty());

        RagService service = new RagService(hybridSearch, llmClient, citationVerifier,
            new ObjectMapper(), 6, 4096, 0, null, true, systemPromptService, List.of());

        List<LlmRequest> seen = new ArrayList<>();
        doAnswer(inv -> {
            seen.add(inv.getArgument(0));
            Consumer<LlmResponseChunk> cb = inv.getArgument(1);
            cb.accept(new LlmResponseChunk("an ungoverned but real answer", null, null,
                new LlmUsage(1, 1, 2, 0, 0)));
            return null;
        }).when(llmClient).generateStream(any(LlmRequest.class), any());

        List<String> tokens = new ArrayList<>();
        service.generateAgenticAnswer(AgenticRequest.builder().query("q").modelOverride("m")
            .history(Collections.emptyList()).build(), tokens::add);

        assertThat(String.join("", tokens)).as("the answer still runs")
            .contains("an ungoverned but real answer");
        assertThat(seen.get(0).messages().stream().filter(m -> "system".equals(m.role()))
            .map(LlmMessage::content).findFirst().orElse(null))
            .as("a missing prompt row is an empty prompt, never an exception").isEmpty();
    }

    /**
     * A turn producing neither text nor tool calls MUST throw rather than return "". Gemini emits
     * exactly this on MALFORMED_FUNCTION_CALL and on some safety blocks, and returning it silently
     * would persist a blank assistant message that reads to the user as the model having nothing to
     * say — with no error anywhere and the tokens still billed.
     */
    @Test
    public void aTurnWithNeitherTextNorToolCallsThrowsRatherThanReturningAnEmptyAnswer() {
        doAnswer(inv -> {
            Consumer<LlmResponseChunk> cb = inv.getArgument(1);
            cb.accept(new LlmResponseChunk("", null, null, new LlmUsage(5, 0, 5, 0, 0)));
            return null;
        }).when(llmClient).generateStream(any(LlmRequest.class), any());

        assertThatThrownBy(() -> ragService.generateAgenticAnswer(AgenticRequest.builder()
            .query("q").modelOverride("m").history(Collections.emptyList()).build(), tok -> {
            })).isInstanceOf(IllegalStateException.class).hasMessageContaining("empty response");
    }

    /**
     * A tool's {@code inputSchema} is a JSON string authored by whoever declared the tool — a
     * hand-written {@code @McpTool} annotation, a generated schema, an MCP client's registration —
     * so one malformed declaration is an ordinary content bug, not a code bug, and it must cost
     * that one tool and nothing else.
     *
     * <p>
     * {@code buildLlmTools} runs on EVERY turn of every agentic run, before the request is even
     * built, so letting the parse failure escape turns one bad schema into a total chat outage:
     * every question from every user dies with a Jackson error, whether or not the model would ever
     * have called the offending tool. The blast radius is not even reproducible — the catalogue is
     * a {@code HashMap}, so which tools are reached before the broken one varies per JVM — and the
     * failure surfaces in an unrelated user's chat rather than anywhere near the tool that caused
     * it.
     *
     * <p>
     * No existing test declares a broken schema: every tool in this class comes from
     * {@code namedTool}, which always returns valid JSON, so the catch block is never entered and
     * could be deleted with the suite staying green.
     */
    @Test
    public void aToolWithAnUnparseableSchemaIsOmittedRatherThanKillingTheAnswer() {
        ToolCallback broken = mock(ToolCallback.class);
        ToolDefinition brokenDef = mock(ToolDefinition.class);
        when(brokenDef.name()).thenReturn("oim_search");
        when(brokenDef.description()).thenReturn("d");
        when(brokenDef.inputSchema()).thenReturn("not json");
        when(broken.getToolDefinition()).thenReturn(brokenDef);

        ragService.getToolRegistry().put("oim_search", broken);
        ragService.getToolRegistry().put("get_section", namedTool("get_section"));

        List<LlmRequest> seen = new ArrayList<>();
        doAnswer(inv -> {
            seen.add(inv.getArgument(0));
            Consumer<LlmResponseChunk> cb = inv.getArgument(1);
            cb.accept(new LlmResponseChunk("the answer still arrives", null, null,
                new LlmUsage(1, 1, 2, 0, 0)));
            return null;
        }).when(llmClient).generateStream(any(LlmRequest.class), any());

        List<String> tokens = new ArrayList<>();
        ragService.generateAgenticAnswer(
            AgenticRequest.builder().query("q").modelOverride("m").history(Collections.emptyList())
                .allowedTools(List.of("oim_search", "get_section")).build(),
            tokens::add);

        assertThat(String.join("", tokens)).as("one bad declaration must not fail the answer")
            .contains("the answer still arrives");
        assertThat(seen.get(0).tools().stream().map(LlmTool::name).toList())
            .as("the healthy tool survives; only the unparseable one is dropped")
            .containsExactly("get_section");
    }

    private static ToolCallback namedTool(String name) {
        ToolCallback cb = mock(ToolCallback.class);
        ToolDefinition def = mock(ToolDefinition.class);
        when(def.name()).thenReturn(name);
        when(def.description()).thenReturn("d");
        when(def.inputSchema()).thenReturn("{\"type\":\"object\"}");
        when(cb.getToolDefinition()).thenReturn(def);
        return cb;
    }

    /** Runs one turn and reports which tool names the request actually offered the model. */
    private List<String> offeredToolNames(List<String> allowedTools) {
        return offeredToolNames(allowedTools, List.of());
    }

    /** As above, with run-scoped extra tools that bypass the playbook allow-list. */
    private List<String> offeredToolNames(List<String> allowedTools,
        List<ToolCallback> extraTools) {
        List<LlmRequest> seen = new ArrayList<>();
        doAnswer(inv -> {
            seen.add(inv.getArgument(0));
            Consumer<LlmResponseChunk> cb = inv.getArgument(1);
            cb.accept(new LlmResponseChunk("done", null, null, new LlmUsage(1, 1, 2, 0, 0)));
            return null;
        }).when(llmClient).generateStream(any(LlmRequest.class), any());

        ragService.generateAgenticAnswer(
            AgenticRequest.builder().query("q").modelOverride("m").history(Collections.emptyList())
                .allowedTools(allowedTools).extraTools(extraTools).build(),
            tok -> {
            });
        return seen.get(0).tools() == null ? List.of()
            : seen.get(0).tools().stream().map(LlmTool::name).toList();
    }

    /**
     * Run-scoped tools bypass the playbook allow-list, and MUST still do so when that list is
     * empty.
     *
     * <p>
     * The orchestrator is handed {@code call_specialist} and the reviewer {@code submit_review} as
     * extraTools at the call site — they are not in any playbook's stored tool list and cannot be,
     * because they only exist for the duration of one run. The allow-list now denies by default, so
     * if the always-include carve-out were dropped, an orchestrator would be offered no way to
     * reach a specialist and would silently answer the whole question by itself: a multi-agent run
     * that costs one agent and looks like a normal, slightly thin answer.
     */
    @Test
    public void runScopedExtraToolsAreOfferedEvenWhenTheAllowListIsEmpty() {
        ragService.getToolRegistry().put("oim_search", namedTool("oim_search"));

        List<String> offered = offeredToolNames(List.of(), List.of(namedTool("call_specialist")));

        assertThat(offered).as("an extra tool must survive an allow-list that permits nothing")
            .contains("call_specialist");
        assertThat(offered).as("the allow-list still governs everything that is not an extra tool")
            .doesNotContain("oim_search");
    }
}
