package de.palsoftware.yvoke.llm.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import de.palsoftware.yvoke.llm.core.model.LlmCallFailedException;
import de.palsoftware.yvoke.llm.core.model.LlmMessage;
import de.palsoftware.yvoke.llm.core.model.LlmRequest;
import de.palsoftware.yvoke.llm.core.model.LlmResponse;
import de.palsoftware.yvoke.llm.core.model.LlmResponseChunk;
import de.palsoftware.yvoke.llm.core.model.LlmTool;
import de.palsoftware.yvoke.llm.core.model.LlmToolCall;
import de.palsoftware.yvoke.llm.core.model.LlmToolCallDelta;
import de.palsoftware.yvoke.llm.core.model.LlmUsage;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseIncludable;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The streaming tests replay SSE captured verbatim from the live Azure endpoint
 * ({@code src/test/resources/llm/azure-responses/*.sse}), so the event shapes under test are the
 * service's own rather than a hand-written guess at them.
 */
class AzureOpenAiResponsesLlmClientTest {

    private HttpServer server;

    /** Requests the mock server actually received, and how many of them to answer 503. */
    private final AtomicInteger requests = new AtomicInteger();
    private int failFirst;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ---------------------------------------------------------------- endpoint

    @Test
    void theResponsesBaseUrlIsTheV1SurfaceOfTheResourceRoot() {
        assertThat(AzureOpenAiResponsesLlmClient.baseUrl("https://res.services.ai.azure.com"))
            .isEqualTo("https://res.services.ai.azure.com/openai/v1");
    }

    @Test
    void aTrailingSlashDoesNotDoubleUpInThePath() {
        assertThat(AzureOpenAiResponsesLlmClient.baseUrl("https://res.services.ai.azure.com/"))
            .isEqualTo("https://res.services.ai.azure.com/openai/v1");
    }

    @Test
    void anEndpointThatAlreadyNamesTheV1SurfaceIsLeftAlone() {
        assertThat(
            AzureOpenAiResponsesLlmClient.baseUrl("https://res.services.ai.azure.com/openai/v1"))
            .isEqualTo("https://res.services.ai.azure.com/openai/v1");
    }

    @Test
    void aBlankEndpointIsRejectedRatherThanDefaultedToPublicOpenAi() {
        // openai-java falls back to api.openai.com, which would send the Azure key to a third
        // party.
        assertThatThrownBy(() -> AzureOpenAiResponsesLlmClient.baseUrl("  "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("endpoint is required");
    }

    // ------------------------------------------------------- request building

    @Test
    void aReasoningModelIsSentBothToolsAndAReasoningEffort() {
        // The whole reason this client exists: chat completions answers this pair with a 400.
        ResponseCreateParams params =
            client("medium").buildParams(requestWithTools("gpt-5.6-luna"));

        assertThat(params.tools()).isPresent();
        assertThat(params.tools().get()).hasSize(1);
        assertThat(params.reasoning()).isPresent();
        assertThat(params.reasoning().get().effort()).contains(ReasoningEffort.MEDIUM);
    }

    @Test
    void aReasoningEffortIsAccompaniedByASummaryRequestSoTheThinkingTextArrives() {
        // Without a summary the reasoning is billed and discarded, and reasoning() stays null as it
        // does on chat completions — which would forfeit the second reason for moving surface.
        ResponseCreateParams params = client("high").buildParams(requestWithTools("gpt-5.6-luna"));

        assertThat(params.reasoning().orElseThrow().summary()).isPresent();
    }

    @Test
    void aReasoningModelIsSentNoTemperature() {
        // Reasoning models reject any temperature but their own default.
        ResponseCreateParams params =
            client("medium").buildParams(requestWithTools("gpt-5.6-luna"));

        assertThat(params.temperature()).isEmpty();
    }

    @Test
    void anOrdinaryModelIsSentATemperatureAndNoReasoning() {
        ResponseCreateParams params = client("medium").buildParams(requestWithTools("gpt-4o"));

        assertThat(params.temperature()).contains(0.5);
        assertThat(params.reasoning()).isEmpty();
    }

    @Test
    void theRequestIsNotStoredServerSide() {
        // Azure defaults Responses to store=true; nothing here reads a stored response back.
        assertThat(client("medium").buildParams(requestWithTools("gpt-5.6-luna")).store())
            .contains(false);
    }

    @Test
    void thinkingDisabledLeavesTheModelsOwnDefaultInPlace() {
        AzureOpenAiResponsesLlmClient client =
            new AzureOpenAiResponsesLlmClient((OpenAIClient) null, false, "high", "");

        assertThat(client.resolveReasoningEffort(requestWithTools("gpt-5.6-luna"))).isNull();
    }

    @Test
    void anUnusableThinkingLevelLeavesTheEffortUnsetRatherThanGuessing() {
        // An unknown value is a 400, so it must not be forwarded.
        assertThat(client("enthusiastic").resolveReasoningEffort(requestWithTools("gpt-5.6-luna")))
            .isNull();
    }

    @Test
    void aPerRequestThinkingLevelBeatsTheClientWideDefault() {
        LlmRequest request = new LlmRequest("gpt-5.6-luna", List.of(new LlmMessage("user", "hi")),
            0.5, 1024, List.of(), "low");

        assertThat(client("high").resolveReasoningEffort(request)).isEqualTo(ReasoningEffort.LOW);
    }

    // ------------------------------------------------------- message translation

    @Test
    void aFullAgenticTurnIsTranslatedIntoTheResponsesItemShapes() {
        // buildInput is the translation layer between yvoke's model and the wire, and it has no
        // other safety net: a wrong role or a transposed callId surfaces as a 400 on turn 2, or as
        // an agent that silently loses its own tool results. AzureOpenAiLlmClientTest pins the
        // equivalent mapping for the chat client.
        List<ResponseInputItem> items = inputItems(client("medium").buildParams(agenticTurn()));

        assertThat(items).hasSize(5);
        // Reasoning models take their instructions as `developer`, not `system`.
        assertThat(items.get(0).easyInputMessage().orElseThrow().role())
            .isEqualTo(EasyInputMessage.Role.DEVELOPER);
        assertThat(items.get(1).easyInputMessage().orElseThrow().role())
            .isEqualTo(EasyInputMessage.Role.USER);
        assertThat(items.get(2).easyInputMessage().orElseThrow().role())
            .isEqualTo(EasyInputMessage.Role.ASSISTANT);

        ResponseFunctionToolCall call = items.get(3).functionCall().orElseThrow();
        assertThat(call.callId()).isEqualTo("call_abc");
        assertThat(call.name()).isEqualTo("search_corpus");
        assertThat(call.arguments()).isEqualTo("{\"query\":\"sheep\"}");

        // The result must come back under the SAME call id, or the model cannot pair them.
        ResponseInputItem.FunctionCallOutput output =
            items.get(4).functionCallOutput().orElseThrow();
        assertThat(output.callId()).isEqualTo("call_abc");
        assertThat(output.output().asString()).isEqualTo("3 chunks found");
    }

    @Test
    void anOrdinaryModelGetsASystemRoleRatherThanDeveloper() {
        LlmRequest request =
            new LlmRequest("gpt-4o", agenticTurn().messages(), 0.5, 1024, List.of(), "medium");

        List<ResponseInputItem> items = inputItems(client("medium").buildParams(request));

        assertThat(items.get(0).easyInputMessage().orElseThrow().role())
            .isEqualTo(EasyInputMessage.Role.SYSTEM);
    }

    // ------------------------------------------------- structured output / seed

    @Test
    void aResponseSchemaIsSentAsAJsonSchemaTextFormat() {
        // The reachable caller is DocumentKgExtractor, which passes RESPONSE_SCHEMA with
        // "application/json" through generate(). (GeneralSummarizer passes no schema.) Dropping the
        // schema silently would put "schema":{} on the wire, the extract would yield zero entities,
        // and the ingest job would still report a normal count.
        //
        // Asserting only isPresent() would be satisfied by an EMPTY Schema, so this reaches the
        // contents: neutering the copy loop while leaving the wrapper intact used to keep the whole
        // suite green.
        ResponseCreateParams params = client("medium").buildParams(requestWithSchema(
            Map.of("type", "object", "properties", Map.of("answer", Map.of("type", "string")))));

        var schema =
            params.text().orElseThrow().format().orElseThrow().jsonSchema().orElseThrow().schema();
        assertThat(schema._additionalProperties()).containsKeys("type", "properties");
        assertThat(schema._additionalProperties().get("properties").toString()).contains("answer");
    }

    @Test
    void aBareJsonMimeTypeWithNoSchemaStillAsksForJson() {
        ResponseCreateParams params = client("medium").buildParams(requestWithMimeType());

        assertThat(params.text().orElseThrow().format().orElseThrow().jsonObject()).isPresent();
    }

    @Test
    void structuredOutputIsCombinedWithToolsRatherThanDropped() {
        // Unlike Gemini, this surface allows both; the request under test carries tools too.
        ResponseCreateParams params = client("medium").buildParams(requestWithSchema(
            Map.of("type", "object", "properties", Map.of("answer", Map.of("type", "string")))));

        assertThat(params.tools().orElseThrow()).hasSize(1);
        assertThat(params.text()).isPresent();
    }

    @Test
    void codeExecutionAddsTheCodeInterpreterAlongsideTheFunctionTools() {
        // Verified against the live endpoint: this surface accepts code_interpreter and answers
        // with a code_interpreter_call item. Chat completions has no equivalent and can only warn.
        LlmRequest request =
            new LlmRequest("gpt-5.6-luna", List.of(new LlmMessage("user", "compute 17*23")), 0.5,
                1024, List.of(new LlmTool("get_weather", "Get weather", Map.of("type", "object"))),
                "medium", true);

        ResponseCreateParams params = client("medium").buildParams(request);

        assertThat(params.tools().orElseThrow()).hasSize(2);
        assertThat(params.tools().get().stream().filter(t -> t.codeInterpreter().isPresent()))
            .hasSize(1);
    }

    @Test
    void codeExecutionAsksForTheExecutionOutputsToBeIncluded() {
        // Without the include the done item carries outputs=null, so the result of the run — the
        // half a reader actually wants — never arrives. Verified against the live endpoint.
        ResponseCreateParams params = client("medium").buildParams(codeExecutionRequest());

        assertThat(params.include().orElseThrow())
            .contains(ResponseIncludable.CODE_INTERPRETER_CALL_OUTPUTS);
    }

    @Test
    void withoutCodeExecutionOnlyTheFunctionToolsAreSent() {
        ResponseCreateParams params =
            client("medium").buildParams(requestWithTools("gpt-5.6-luna"));

        assertThat(params.tools().orElseThrow()).hasSize(1);
        assertThat(params.tools().get().get(0).function()).isPresent();
    }

    @Test
    void aToolsInputSchemaReachesTheWireRatherThanAnEmptyParameterObject() {
        // Every other tools() assertion checks arity and kind only, so advertising search_corpus
        // with parameters:{} would pass the whole suite. The model would then call it with {} and
        // SearchCorpusTool would answer "(no matching chunks)" — which an agent reads as "the
        // corpus does not contain this", not as a bug.
        FunctionTool tool = client("medium").buildParams(requestWithTools("gpt-5.6-luna")).tools()
            .orElseThrow().get(0).function().orElseThrow();

        assertThat(tool.name()).isEqualTo("get_weather");
        assertThat(tool.description()).contains("Get weather");
        assertThat(tool.parameters().orElseThrow()._additionalProperties()).containsKeys("type",
            "properties");
        assertThat(
            tool.parameters().orElseThrow()._additionalProperties().get("properties").toString())
            .contains("city");
    }

    // --------------------------------------------------------------- lifecycle

    @Test
    void closingTheClientReleasesTheUnderlyingHttpPool() {
        OpenAIClient underlying = mock(OpenAIClient.class);
        AzureOpenAiResponsesLlmClient client =
            new AzureOpenAiResponsesLlmClient(underlying, true, "medium", "");

        client.close();
        client.close();

        // Idempotent: Spring calls @PreDestroy, and a caller may close() as well.
        verify(underlying, times(1)).close();
    }

    // --------------------------------------------------------------- streaming

    @Test
    void theReasoningSummaryIsSurfacedAsChunkReasoning() throws IOException {
        List<LlmResponseChunk> chunks = replay("reasoning-and-text.sse");

        String reasoning = joined(chunks, LlmResponseChunk::reasoning);
        assertThat(reasoning).startsWith("**Finding the trick in the question**")
            .contains("all but 9 run away");
        // 79 delta events in the recording; every one must reach the caller.
        assertThat(chunks.stream().filter(c -> c.reasoning() != null)).hasSize(79);
    }

    @Test
    void answerTextAndReasoningTextAreKeptApart() throws IOException {
        List<LlmResponseChunk> chunks = replay("reasoning-and-text.sse");

        assertThat(joined(chunks, LlmResponseChunk::content)).isEqualTo("9 sheep are left.");
        assertThat(joined(chunks, LlmResponseChunk::reasoning)).doesNotContain("9 sheep are left.");
    }

    @Test
    void usageIsReportedIncludingTheReasoningTokens() throws IOException {
        // Exact, and over FIVE DISTINCT non-zero numbers on purpose. isPositive() on two fields
        // proved nothing: the fixture used to carry input_tokens == reasoning_tokens == 29, so
        // those two were interchangeable, and cached_tokens was 0 everywhere so a constant-0
        // mutation was invisible. Per CLAUDE.md §6 the ledger and the cost dashboard re-derive
        // money from these counts independently, so a swap corrupts both with nothing failing.
        List<LlmResponseChunk> chunks = replay("reasoning-and-text.sse");

        assertThat(lastUsage(chunks)).isEqualTo(new LlmUsage(137, 64, 201, 41, 29));
    }

    @Test
    void aToolCallArrivesWithItsIdAndNameBeforeItsArguments() throws IOException {
        List<LlmToolCallDelta> deltas = toolDeltas(replay("tool-call.sse"));

        assertThat(deltas.get(0).id()).isNotBlank();
        assertThat(deltas.get(0).name()).isEqualTo("get_weather");
        assertThat(deltas.get(0).argumentsDelta()).isNull();
        assertThat(deltas.stream().skip(1).map(LlmToolCallDelta::argumentsDelta).reduce("",
            (a, b) -> a + (b == null ? "" : b))).isEqualTo("{\"city\":\"Berlin\"}");
    }

    @Test
    void aLeadingReasoningItemDoesNotPushToolCallsOffIndexZero() throws IOException {
        // Responses numbers ALL output items, so in this recording the reasoning item holds
        // output_index 0 and the tool call 1. ToolCallAccumulator treats the index as a list
        // position and pads up to it, so forwarding the raw index would mint a phantom empty tool
        // call ahead of the real one — and RagService would then dispatch a nameless call.
        // The plain tool-call.sse fixture CANNOT catch this: its call is genuinely at index 0.
        List<LlmToolCallDelta> deltas = toolDeltas(replay("reasoning-then-tool-call.sse"));

        assertThat(deltas).isNotEmpty();
        assertThat(deltas).allSatisfy(delta -> assertThat(delta.index()).isZero());
    }

    @Test
    void reasoningAndAToolCallStreamTogetherInOneTurn() throws IOException {
        // The combination chat completions refuses outright, which is why this client exists.
        List<LlmResponseChunk> chunks = replay("reasoning-then-tool-call.sse");

        assertThat(joined(chunks, LlmResponseChunk::reasoning)).isNotBlank();
        assertThat(toolDeltas(chunks)).extracting(LlmToolCallDelta::name).contains("get_weather");
    }

    @Test
    void serverSideCodeExecutionIsSurfacedAsVisibleMarkdown() throws IOException {
        // Matches GeminiLlmClient, which renders its own server-side execution the same way so the
        // run is transparent rather than an unexplained jump to an answer. The frontend already
        // allows the tag (markdown-render.js, and the DOMPurify allow-list in thread.js).
        String content = joined(replay("code-execution.sse"), LlmResponseChunk::content);

        assertThat(content).contains("<code-execution>").contains("```python").contains("17 * 23");
    }

    @Test
    void theExecutionResultIsSurfacedAlongsideTheCode() throws IOException {
        // The code alone shows what was attempted; the logs show what it produced.
        String content = joined(replay("code-execution.sse"), LlmResponseChunk::content);

        assertThat(content).contains("Code execution result").contains("391");
    }

    @Test
    void theCodeBlockPrecedesItsResultInTheStream() throws IOException {
        String content = joined(replay("code-execution.sse"), LlmResponseChunk::content);

        int code = content.indexOf("```python");
        int result = content.indexOf("Code execution result");
        // Both must be PRESENT and ordered. Comparing raw indexes alone passes vacuously when
        // neither is emitted (-1 < -1 is false, but -1 < someIndex is true the moment the answer
        // prose happens to contain the other needle).
        assertThat(code).isNotNegative();
        assertThat(result).isGreaterThan(code);
    }

    @Test
    void twoParallelToolCallsGetDistinctDenseIndexes() throws IOException {
        // Captured live with parallel_tool_calls=true: the reasoning item takes output_index 0 and
        // the two calls take 1 and 2, so densification must map them to 0 and 1. Every other
        // fixture has at most ONE call, which let `denseIndex` be replaced by `return 0;` with the
        // whole suite green. If that regresses, both calls key into one ToolCallAccumulator
        // builder — the second id/name overwrite the first and the argument streams concatenate to
        // {"city":"Berlin"}{"city":"Paris"}, so RagService dispatches one unparseable call.
        List<LlmToolCallDelta> deltas = toolDeltas(replay("parallel-tool-calls.sse"));

        assertThat(deltas).extracting(LlmToolCallDelta::index).containsExactly(0, 0, 1, 1);
    }

    @Test
    void eachParallelCallsArgumentsLandUnderItsOwnIndex() throws IOException {
        // Azure emits each call's arguments in one delta immediately after its item rather than
        // interleaving the two streams — but the mapping must not depend on that, so this asserts
        // per-index grouping rather than arrival order.
        List<LlmToolCallDelta> deltas = toolDeltas(replay("parallel-tool-calls.sse"));

        assertThat(argumentsFor(deltas, 0)).isEqualTo("{\"city\":\"Berlin\"}");
        assertThat(argumentsFor(deltas, 1)).isEqualTo("{\"city\":\"Paris\"}");
        assertThat(idFor(deltas, 0)).isNotEqualTo(idFor(deltas, 1));
        assertThat(deltas).filteredOn(d -> d.name() != null).extracting(LlmToolCallDelta::name)
            .containsExactly("get_weather", "get_weather");
    }

    @Test
    void aTruncatedTurnStillReportsTheTokensItBurned() throws IOException {
        // Without this the stream ends as a silent success carrying no usage, AccountingLlmClient
        // returns early on its `usage == null` guard, and tokens Azure charged for leave no
        // llm_call_logs row at all.
        List<LlmResponseChunk> chunks = replay("truncated-incomplete.sse");

        assertThat(lastUsage(chunks)).isEqualTo(new LlmUsage(200000, 16384, 216384, 0, 16384));
    }

    @Test
    void aTruncatedTurnIsNotTreatedAsAFailure() throws IOException {
        // AzureOpenAiLlmClient puts TOKEN_LIMIT_REACHED in BENIGN_FINISH_REASONS, so a truncated
        // answer is delivered rather than raised. Diverging here would turn today's usable partial
        // answers into hard errors on one provider only.
        List<LlmResponseChunk> chunks = replay("truncated-incomplete.sse");

        assertThat(joined(chunks, LlmResponseChunk::content)).isEqualTo("9 sheep are left.");
    }

    @Test
    void aFailedTurnThrowsCarryingTheTokensItBurned() throws IOException {
        // The non-benign counterpart: AccountingLlmClient catches LlmCallFailedException and
        // publishes e.usage(), so the spend is recorded even though the call failed.
        assertThatThrownBy(() -> replay("failed.sse")).isInstanceOf(LlmCallFailedException.class)
            .hasMessageContaining("server_error")
            .asInstanceOf(InstanceOfAssertFactories.type(LlmCallFailedException.class))
            .extracting(LlmCallFailedException::usage)
            // total is deliberately NOT input+output in this fixture (1200+340=1540, reported
            // 1600), so recomputing the total instead of forwarding what the provider reported is
            // detectable. Every other fixture has them consistent, which made that mutation
            // survive.
            .isEqualTo(new LlmUsage(1200, 340, 1600, 100, 300));
    }

    @Test
    void aTransientFailureEstablishingTheStreamIsRetried() throws IOException {
        // Gemini and the chat-completions client both retry establishment; without it every
        // user-visible answer — they all stream — has no defence against a 429 or a 503.
        failFirst = 2;

        List<LlmResponseChunk> chunks = replay("reasoning-and-text.sse");

        assertThat(requests.get()).isEqualTo(3);
        assertThat(joined(chunks, LlmResponseChunk::content)).isEqualTo("9 sheep are left.");
    }

    @Test
    void aStreamThatDiesPartWayThroughIsNotRestarted() throws IOException {
        // Retrying after chunks have been emitted would replay output the caller already rendered,
        // so establishment is the only retryable part.
        List<LlmResponseChunk> chunks = new ArrayList<>();
        try {
            replayInto(chunks, "truncated.sse");
        } catch (RuntimeException severed) {
            // A severed stream that produced no answer is a failure now, not a quiet success —
            // whether the SDK or the empty-turn guard raises it is beside the point here. Not
            // RETRYING it is what this test owns, and the chunks already delivered must survive.
        }

        assertThat(requests.get()).isEqualTo(1);
        assertThat(joined(chunks, LlmResponseChunk::reasoning)).isNotEmpty();
    }

    // -------------------------------------------------- empty turns and refusals

    @Test
    void aTurnThatOnlyThoughtIsReRequestedRatherThanDeliveredAsABlankAnswer() throws IOException {
        // Measured live on this deployment: finishReason=stop with completionTokens=1040 of which
        // reasoning=1024 — the model spent its whole visible budget thinking and said nothing. It
        // is NOT truncation (that reports incomplete), and returning normally hands the user a
        // blank answer. Safe to re-request for one reason only: empty means nothing reached the
        // consumer, so there is no rendered output a second attempt could duplicate.
        List<LlmResponseChunk> chunks =
            replaySequence("reasoning-only-empty.sse", "reasoning-and-text.sse");

        assertThat(requests.get()).isEqualTo(2);
        assertThat(joined(chunks, LlmResponseChunk::content)).isEqualTo("9 sheep are left.");
    }

    @Test
    void eachAttemptIsBilledAsItsOwnCall() throws IOException {
        // Two HTTP calls, two llm_call_logs rows. The abandoned attempt's 1040 output tokens used
        // to be summed onto the winning chunk instead, because AccountingLlmClient published one
        // row per generateStream — so the attempt was billed but invisible, and every retrying
        // client had to carry its own usage-accumulation code to make that come out right.
        List<LlmResponseChunk> chunks =
            replaySequence("reasoning-only-empty.sse", "reasoning-and-text.sse");

        List<LlmResponseChunk> markers =
            chunks.stream().filter(LlmResponseChunk::endOfCall).toList();
        assertThat(markers).hasSize(2);
        assertThat(markers.get(0).usage()).isEqualTo(new LlmUsage(137, 1040, 1177, 41, 1024));
        assertThat(markers.get(1).usage()).isEqualTo(new LlmUsage(137, 64, 201, 41, 29));
        assertThat(lastUsage(chunks.stream().filter(c -> !c.endOfCall()).toList()))
            .as("the winning attempt reports only what IT cost; the sum would be 1378 total")
            .isEqualTo(new LlmUsage(137, 64, 201, 41, 29));
    }

    /**
     * A turn that ran out of output budget having produced nothing is still a blank answer. It is
     * not re-requested — the budget would simply run out again — but it must not be delivered as a
     * quiet success either, which is what returning on "not cleanly completed" used to do.
     */
    @Test
    void aTruncatedTurnThatProducedNothingFailsRatherThanDeliveringABlankAnswer()
        throws IOException {
        assertThatThrownBy(() -> replaySequence("truncated-empty.sse"))
            .isInstanceOf(LlmCallFailedException.class)
            .hasMessageContaining("no content and no tool calls after 1 attempt(s)");

        assertThat(requests.get())
            .as("a truncated turn exhausts its budget again on a retry; it must cost one request")
            .isEqualTo(1);
    }

    @Test
    void anEmptyTurnThatRepeatsFailsInsteadOfLoopingForever() throws IOException {
        // Bounded at two: an empty turn that repeats is systematic rather than a glitch. Both
        // attempts' tokens are already recorded, one row each, via their end-of-call markers.
        assertThatThrownBy(
            () -> replaySequence("reasoning-only-empty.sse", "reasoning-only-empty.sse"))
            .isInstanceOf(LlmCallFailedException.class)
            .hasMessageContaining("no content and no tool calls after 2 attempt(s)")
            .asInstanceOf(InstanceOfAssertFactories.type(LlmCallFailedException.class))
            .extracting(LlmCallFailedException::usage)
            .as("each attempt already has its own row; the failure names the last one's cost")
            .isEqualTo(new LlmUsage(137, 1040, 1177, 41, 1024));
        assertThat(requests.get()).isEqualTo(2);
    }

    @Test
    void aRefusalIsDeliveredAsContentRatherThanTreatedAsSilence() throws IOException {
        // A refusal arrives on its own event rather than as output text. Dropping it made "I won't
        // answer that" identical to saying nothing — and once the empty-turn guard exists it is
        // worse than identical: the refusal would be re-requested and then reported as a
        // produced-nothing failure, which is a wrong diagnosis of a working model.
        List<LlmResponseChunk> chunks = replay("refusal.sse");

        assertThat(joined(chunks, LlmResponseChunk::content))
            .isEqualTo("I'm sorry, I can't help with that.");
        assertThat(requests.get()).as("a refusal is an answer, so it is not re-requested")
            .isEqualTo(1);
    }

    @Test
    void aReasoningSummaryIsRequestedEvenWhenThinkingIsDisabled() {
        // The effort and the summary are separate concessions. A reasoning model goes on reasoning
        // at its own default whatever we ask, so nesting the summary inside the effort guard threw
        // away the thinking TEXT of a turn that was still being billed for the thinking.
        AzureOpenAiResponsesLlmClient client =
            new AzureOpenAiResponsesLlmClient((OpenAIClient) null, false, "high", "");

        ResponseCreateParams params = client.buildParams(requestWithTools("gpt-5.6-luna"));

        assertThat(params.reasoning().orElseThrow().summary()).isPresent();
        assertThat(params.reasoning().orElseThrow().effort())
            .as("only the LEVEL is given up when thinking is off").isEmpty();
    }

    @Test
    void aReasoningSummaryIsRequestedEvenWhenTheConfiguredLevelIsUnusable() {
        ResponseCreateParams params =
            client("enthusiastic").buildParams(requestWithTools("gpt-5.6-luna"));

        assertThat(params.reasoning().orElseThrow().summary()).isPresent();
        assertThat(params.reasoning().orElseThrow().effort()).isEmpty();
    }

    // ------------------------------------------------- the non-streaming path

    @Test
    void aFailedNonStreamingTurnThrowsCarryingTheTokensItBurned() throws IOException {
        // generate() read only the completed shape, so a failed turn came back as a partial answer
        // that looked finished — and, having thrown nothing, left the caller no evidence at all.
        assertThatThrownBy(() -> blocking(FAILED_RESPONSE))
            .isInstanceOf(LlmCallFailedException.class).hasMessageContaining("server_error")
            .asInstanceOf(InstanceOfAssertFactories.type(LlmCallFailedException.class))
            .extracting(LlmCallFailedException::usage)
            // total deliberately NOT input+output, so recomputing it instead of forwarding what the
            // provider reported is detectable.
            .isEqualTo(new LlmUsage(1200, 340, 1600, 100, 300));
    }

    @Test
    void aTruncatedNonStreamingTurnIsDeliveredRatherThanRaised() throws IOException {
        // Matches the streaming path and AzureOpenAiLlmClient's BENIGN_FINISH_REASONS: a partial
        // answer is usable, and making one provider stricter than its siblings is a divergence.
        assertThat(blocking(INCOMPLETE_RESPONSE).content()).isEqualTo("9 sheep are");
    }

    @Test
    void aNonStreamingRefusalIsReturnedRatherThanDroppedIntoAnEmptyString() throws IOException {
        assertThat(blocking(REFUSAL_RESPONSE).content())
            .isEqualTo("I'm sorry, I can't help with that.");
    }

    // ----------------------------------------------------------------- helpers

    private static final String FAILED_RESPONSE = """
        {"id":"resp_x","object":"response","status":"failed","model":"gpt-5.6-luna",
         "error":{"code":"server_error","message":"The model failed to generate a response."},
         "output":[],
         "usage":{"input_tokens":1200,"input_tokens_details":{"cached_tokens":100},
                  "output_tokens":340,"output_tokens_details":{"reasoning_tokens":300},
                  "total_tokens":1600}}""";

    private static final String INCOMPLETE_RESPONSE = """
        {"id":"resp_x","object":"response","status":"incomplete","model":"gpt-5.6-luna",
         "incomplete_details":{"reason":"max_output_tokens"},
         "output":[{"id":"msg_1","type":"message","status":"incomplete","role":"assistant",
                    "content":[{"type":"output_text","text":"9 sheep are","annotations":[]}]}],
         "usage":{"input_tokens":10,"input_tokens_details":{"cached_tokens":0},
                  "output_tokens":4,"output_tokens_details":{"reasoning_tokens":0},
                  "total_tokens":14}}""";

    private static final String REFUSAL_RESPONSE = """
        {"id":"resp_x","object":"response","status":"completed","model":"gpt-5.6-luna",
         "output":[{"id":"msg_1","type":"message","status":"completed","role":"assistant",
                    "content":[{"type":"refusal",
                                "refusal":"I'm sorry, I can't help with that."}]}],
         "usage":{"input_tokens":10,"input_tokens_details":{"cached_tokens":0},
                  "output_tokens":9,"output_tokens_details":{"reasoning_tokens":0},
                  "total_tokens":19}}""";

    private AzureOpenAiResponsesLlmClient client(String thinkingLevel) {
        return new AzureOpenAiResponsesLlmClient((OpenAIClient) null, true, thinkingLevel, "");
    }

    private static LlmRequest agenticTurn() {
        return new LlmRequest("gpt-5.6-luna",
            List.of(new LlmMessage("system", "You are terse."),
                new LlmMessage("user", "How many sheep?"),
                new LlmMessage("assistant", "Let me look that up.",
                    List.of(new LlmToolCall("call_abc", "function", "search_corpus",
                        "{\"query\":\"sheep\"}")),
                    null, null),
                new LlmMessage("tool", "3 chunks found", null, "call_abc", "search_corpus")),
            0.5, 1024, List.of(), "medium");
    }

    private static List<ResponseInputItem> inputItems(ResponseCreateParams params) {
        return params.input().orElseThrow().asResponse();
    }

    private static LlmRequest codeExecutionRequest() {
        return new LlmRequest("gpt-5.6-luna", List.of(new LlmMessage("user", "compute 17*23")), 0.5,
            1024, List.of(new LlmTool("get_weather", "Get weather", Map.of("type", "object"))),
            "medium", true);
    }

    private static LlmRequest requestWithSchema(Map<String, Object> schema) {
        return new LlmRequest("gpt-5.6-luna", List.of(new LlmMessage("user", "answer")), 0.5, 1024,
            List.of(new LlmTool("get_weather", "Get weather", Map.of("type", "object"))), "medium",
            "application/json", schema, null);
    }

    private static LlmRequest requestWithMimeType() {
        return new LlmRequest("gpt-5.6-luna", List.of(new LlmMessage("user", "answer")), 0.5, 1024,
            List.of(), "medium", "application/json", null, null);
    }

    private static LlmRequest requestWithTools(String model) {
        return new LlmRequest(model, List.of(new LlmMessage("user", "What is the weather?")), 0.5,
            1024, List.of(new LlmTool("get_weather", "Get weather",
                Map.of("type", "object", "properties", Map.of("city", Map.of("type", "string"))))));
    }

    /**
     * Replays a recorded SSE stream through the real client and collects what it emitted. The first
     * {@link #failFirst} requests are answered 503 instead, so a test can prove establishment is
     * retried; {@link #requests} counts every request the client actually made.
     */
    private List<LlmResponseChunk> replay(String fixture) throws IOException {
        List<LlmResponseChunk> chunks = new ArrayList<>();
        replayInto(chunks, fixture);
        return chunks;
    }

    /**
     * As {@link #replay}, but collecting into a caller-supplied list so the chunks delivered before
     * a failure survive it. A severed stream that produced no answer now throws, and a test that
     * assigns from a return value loses its evidence when it does.
     */
    private void replayInto(List<LlmResponseChunk> sink, String fixture) throws IOException {
        String body = new String(
            getClass().getResourceAsStream("/llm/azure-responses/" + fixture).readAllBytes(),
            StandardCharsets.UTF_8);
        AtomicReference<String> request = new AtomicReference<>();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            // Consume the request body before responding, or the socket closes early.
            try (InputStream in = exchange.getRequestBody()) {
                request.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            if (requests.incrementAndGet() <= failFirst) {
                byte[] error =
                    "{\"error\":{\"message\":\"upstream busy\"}}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(503, error.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(error);
                }
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        });
        server.start();

        OpenAIClient transport = OpenAIOkHttpClient.builder()
            .baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).apiKey("test-key")
            .maxRetries(0).build();
        AzureOpenAiResponsesLlmClient client =
            new AzureOpenAiResponsesLlmClient(transport, true, "medium", "");

        client.generateStream(requestWithTools("gpt-5.6-luna"), sink::add);
        assertThat(request.get()).as("the client must actually have sent a request").isNotNull();
    }

    private static String argumentsFor(List<LlmToolCallDelta> deltas, int index) {
        return deltas.stream().filter(d -> d.index() == index).map(LlmToolCallDelta::argumentsDelta)
            .filter(a -> a != null).collect(Collectors.joining());
    }

    private static String idFor(List<LlmToolCallDelta> deltas, int index) {
        return deltas.stream().filter(d -> d.index() == index).map(LlmToolCallDelta::id)
            .filter(id -> id != null).findFirst().orElseThrow();
    }

    /**
     * Replays a DIFFERENT fixture per request, so a test can prove what the client does when the
     * first attempt produces nothing. {@link #replay} serves one body to every request, which
     * cannot distinguish "re-requested and recovered" from "never re-requested at all".
     */
    private List<LlmResponseChunk> replaySequence(String... fixtures) throws IOException {
        List<String> bodies = new ArrayList<>();
        for (String fixture : fixtures) {
            bodies.add(new String(
                getClass().getResourceAsStream("/llm/azure-responses/" + fixture).readAllBytes(),
                StandardCharsets.UTF_8));
        }

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                in.readAllBytes();
            }
            int nth = requests.getAndIncrement();
            String body = bodies.get(Math.min(nth, bodies.size() - 1));
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        });
        server.start();

        List<LlmResponseChunk> chunks = new ArrayList<>();
        try (AzureOpenAiResponsesLlmClient client =
            new AzureOpenAiResponsesLlmClient(transportTo(server), true, "medium", "")) {
            client.generateStream(requestWithTools("gpt-5.6-luna"), chunks::add);
        }
        return chunks;
    }

    /** Answers the non-streaming call with one fixed JSON body. */
    private LlmResponse blocking(String json) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                in.readAllBytes();
            }
            requests.incrementAndGet();
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();

        try (AzureOpenAiResponsesLlmClient client =
            new AzureOpenAiResponsesLlmClient(transportTo(server), true, "medium", "")) {
            return client.generate(requestWithTools("gpt-5.6-luna"));
        }
    }

    private static OpenAIClient transportTo(HttpServer server) {
        return OpenAIOkHttpClient.builder()
            .baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).apiKey("test-key")
            .maxRetries(0).build();
    }

    private static LlmUsage lastUsage(List<LlmResponseChunk> chunks) {
        return chunks.stream().map(LlmResponseChunk::usage).filter(u -> u != null)
            .reduce((a, b) -> b).orElse(null);
    }

    private static List<LlmToolCallDelta> toolDeltas(List<LlmResponseChunk> chunks) {
        List<LlmToolCallDelta> deltas = new ArrayList<>();
        for (LlmResponseChunk chunk : chunks) {
            if (chunk.toolCallDeltas() != null) {
                deltas.addAll(chunk.toolCallDeltas());
            }
        }
        return deltas;
    }

    private static String joined(List<LlmResponseChunk> chunks,
        Function<LlmResponseChunk, String> field) {
        StringBuilder text = new StringBuilder();
        for (LlmResponseChunk chunk : chunks) {
            String value = field.apply(chunk);
            if (value != null) {
                text.append(value);
            }
        }
        return text.toString();
    }

    // -------------------------------------------------- reasoning-model classification

    /**
     * Classification decides what is on the wire, and getting it wrong is a 400 on EVERY call in
     * either direction: a reasoning model rejects any {@code temperature} but its default, and an
     * ordinary one rejects {@code reasoning.effort}. Until now nothing exercised
     * {@link AzureOpenAiResponsesLlmClient#isReasoningModel} at all.
     *
     * <p>
     * {@code DeepSeek-V4-Flash} is the case worth naming: it refuses {@code reasoning.effort} on
     * both the chat-completions and the Responses surface, so it must classify as ordinary — which
     * the heuristic gets right only because nothing in its name matches.
     */
    @Test
    void theDeployedAzureModelsAreClassifiedCorrectlyByTheDefaultHeuristic() {
        AzureOpenAiResponsesLlmClient client = reasoningModels("");

        assertThat(client.isReasoningModel("gpt-5.4-mini")).isTrue();
        assertThat(client.isReasoningModel("gpt-5.6-luna")).isTrue();
        assertThat(client.isReasoningModel("DeepSeek-V4-Flash"))
            .as("DeepSeek refuses reasoning.effort on both surfaces; sending one 400s every call")
            .isFalse();
    }

    /**
     * {@code app.ai.azure-openai.reasoning-models} REPLACES the heuristic rather than adding to it,
     * which is right for its stated purpose — naming deployments that are not called after the
     * model they serve — and is a trap when half-filled.
     *
     * <p>
     * Naming only one of two reasoning deployments silently declassifies the other, which then
     * receives a {@code temperature} and 400s on every call. Pinned here so the semantics cannot be
     * changed, or half-configured, without something going red.
     */
    @Test
    void anExplicitReasoningListReplacesTheHeuristicRatherThanExtendingIt() {
        AzureOpenAiResponsesLlmClient client = reasoningModels("gpt-5.6-luna");

        assertThat(client.isReasoningModel("gpt-5.6-luna")).isTrue();
        assertThat(client.isReasoningModel("gpt-5.4-mini"))
            .as("naming one deployment declassifies every other the heuristic would have caught — "
                + "the list is an override, so it must be complete or empty")
            .isFalse();
        // Mixed case on the CONFIGURED side, which is the side the parser lowercases. A lowercase
        // fixture here proves nothing: dropping the parser's toLowerCase leaves it unchanged, so
        // the assertion stays green against the very bug it names.
        assertThat(reasoningModels("GPT-5.6-Luna").isReasoningModel("gpt-5.6-luna"))
            .as("a deployment name is hand-typed into a manifest; the override must match "
                + "case-insensitively on the configured spelling too")
            .isTrue();
    }

    /**
     * No transport is needed: classification reads only the configured names and the model string.
     * The constructor stores the client without touching it.
     */
    private static AzureOpenAiResponsesLlmClient reasoningModels(String csv) {
        return new AzureOpenAiResponsesLlmClient((OpenAIClient) null, true, "medium", csv);
    }
}
