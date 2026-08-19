package de.palsoftware.yvoke.llm.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.azure.ai.openai.models.ChatCompletionsFunctionToolDefinition;
import com.azure.ai.openai.models.ChatCompletionsJsonResponseFormat;
import com.azure.ai.openai.models.ChatCompletionsJsonSchemaResponseFormat;
import com.azure.ai.openai.models.ChatCompletionsOptions;
import com.azure.ai.openai.models.ChatRequestDeveloperMessage;
import com.azure.ai.openai.models.ChatRequestSystemMessage;
import com.azure.ai.openai.models.ChatRequestToolMessage;
import com.azure.ai.openai.models.ChatRequestUserMessage;
import com.azure.core.http.HttpClient;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.HttpResponse;
import com.azure.core.http.jdk.httpclient.JdkHttpClientBuilder;
import com.azure.core.util.Context;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import de.palsoftware.yvoke.llm.core.model.LlmCallFailedException;
import de.palsoftware.yvoke.llm.core.model.LlmMessage;
import de.palsoftware.yvoke.llm.core.model.LlmRequest;
import de.palsoftware.yvoke.llm.core.model.LlmResponse;
import de.palsoftware.yvoke.llm.core.model.LlmResponseChunk;
import de.palsoftware.yvoke.llm.core.model.LlmTool;
import de.palsoftware.yvoke.llm.core.model.LlmToolCallDelta;
import de.palsoftware.yvoke.llm.core.model.LlmUsage;
import de.palsoftware.yvoke.llm.core.model.GatewayCacheStatus;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

@Timeout(60)
class AzureOpenAiLlmClientTest {

    private static final Logger log = LoggerFactory.getLogger(AzureOpenAiLlmClientTest.class);

    /** A deployment name the reasoning heuristic must NOT classify as a reasoning model. */
    private static final String CHAT_DEPLOYMENT = "gpt-4o";
    /** A deployment name the reasoning heuristic MUST classify as a reasoning model. */
    private static final String REASONING_DEPLOYMENT = "gpt-5.6-luna";

    /**
     * A turn that finishes cleanly having emitted neither content nor a tool call, with the token
     * split observed live on 2026-08-17: the model spent 1,024 of 1,040 completion tokens reasoning
     * and then stopped with nothing to say.
     */
    private static final String EMPTY_TURN_EVENT =
        "{\"id\":\"c\",\"created\":1,\"choices\":[{\"index\":0,\"delta\":{},"
            + "\"finish_reason\":\"stop\"}]}";
    private static final String EMPTY_TURN_USAGE_EVENT =
        "{\"id\":\"c\",\"created\":1,\"choices\":[],\"usage\":{\"prompt_tokens\":68169,"
            + "\"completion_tokens\":1040,\"total_tokens\":69209,"
            + "\"completion_tokens_details\":{\"reasoning_tokens\":1024}}}";

    // ------------------------------------------------------------------------
    // buildOptions() unit tests
    // ------------------------------------------------------------------------

    @Test
    void anEndpointIsRequiredSoTheKeyCannotBeSentToPublicOpenAi() {
        for (String endpoint : new String[] {null, "", "   "}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new AzureOpenAiLlmClient(endpoint, "key", new ObjectMapper(), true, "medium",
                    ""));
            assertTrue(e.getMessage().contains("endpoint"),
                "the failure must name the missing endpoint, got: " + e.getMessage());
        }
    }

    @Test
    void aChatModelGetsTheRequestedTemperature() {
        ChatCompletionsOptions options = buildOptions(client(),
            new LlmRequest(CHAT_DEPLOYMENT, List.of(user("hi")), 0.5, 100, List.of()));

        assertEquals(0.5, options.getTemperature(), "temperature must reach a non-reasoning model");
        assertNull(options.getReasoningEffort(),
            "a chat model must not be sent a reasoning effort");
        assertEquals(100, options.getMaxCompletionTokens());
    }

    @Test
    void aReasoningModelGetsEffortAndNeverATemperature() {
        ChatCompletionsOptions options = buildOptions(client(),
            new LlmRequest(REASONING_DEPLOYMENT, List.of(user("hi")), 0.0, 100, List.of(), "high"));

        assertNull(options.getTemperature(),
            "a reasoning model rejects any temperature but its own default");
        assertNotNull(options.getReasoningEffort());
        assertEquals("high", options.getReasoningEffort().toString());
    }

    /**
     * The bug this pins: tying "send no temperature" to "a reasoning effort was resolved" makes
     * every call 400 whenever thinking is off or the level is unusable, because the model still
     * rejects the temperature. Temperature is decided by the MODEL, not by the effort.
     */
    @Test
    void aReasoningModelGetsNoTemperatureEvenWithThinkingDisabled() {
        AzureOpenAiLlmClient thinkingOff = new AzureOpenAiLlmClient("http://127.0.0.1:1", "key",
            new ObjectMapper(), false, "medium", "");

        ChatCompletionsOptions options = buildOptions(thinkingOff,
            new LlmRequest(REASONING_DEPLOYMENT, List.of(user("hi")), 0.0, 100, List.of()));

        assertNull(options.getReasoningEffort(), "thinking is disabled");
        assertNull(options.getTemperature(),
            "a reasoning model must never be sent a temperature, effort or no effort");
    }

    @Test
    void anUnusableThinkingLevelLeavesTheModelDefaultInPlace() {
        AzureOpenAiLlmClient nonsenseLevel = new AzureOpenAiLlmClient("http://127.0.0.1:1", "key",
            new ObjectMapper(), true, "ludicrous", "");

        ChatCompletionsOptions options = buildOptions(nonsenseLevel,
            new LlmRequest(REASONING_DEPLOYMENT, List.of(user("hi")), 0.0, 100, List.of()));

        assertNull(options.getReasoningEffort(), "an invalid level must not be forwarded");
        assertNull(options.getTemperature());
    }

    /**
     * A reasoning effort must not be sent alongside tools.
     *
     * <p>
     * gpt-5.6-luna answers this exact combination with
     * {@code 400 "Function tools with reasoning_effort are not supported for this model in
     * /v1/chat/completions. Please use /v1/responses instead."} — and since every agentic turn
     * carries tools, that is a 400 on <b>every</b> answer the app produces. Each half is fine on
     * its own: tools alone and effort alone both return 200 against the live deployment.
     */
    @Test
    void aReasoningModelSentToolsGetsNoReasoningEffort() {
        ChatCompletionsOptions options =
            buildOptions(client(), new LlmRequest(REASONING_DEPLOYMENT, List.of(user("hi")), 0.0,
                100, List.of(new LlmTool("search", "Search", Map.of("type", "object"))), "high"));

        assertNull(options.getReasoningEffort(),
            "reasoning_effort alongside tools is a 400 on every agentic turn");
        assertNotNull(options.getTools(), "the tools themselves must still be sent");
        assertNull(options.getTemperature(),
            "dropping the effort must not resurrect the temperature a reasoning model rejects");
    }

    @Test
    void aNonPositiveMaxTokensIsOmittedRatherThanTruncatingTheAnswer() {
        ChatCompletionsOptions options = buildOptions(client(),
            new LlmRequest(CHAT_DEPLOYMENT, List.of(user("hi")), 0.0, 0, List.of()));

        assertNull(options.getMaxCompletionTokens());
        assertNull(options.getMaxTokens(), "the legacy max_tokens is never used: reasoning models "
            + "reject it and max_completion_tokens covers both families");
    }

    @Test
    void theSeedIsForwardedWhenPresent() {
        ChatCompletionsOptions options = buildOptions(client(), new LlmRequest(CHAT_DEPLOYMENT,
            List.of(user("hi")), 0.0, 100, List.of(), null, null, null, 42, false));

        assertEquals(42L, options.getSeed());
    }

    @Test
    void aSystemMessageBecomesADeveloperMessageOnlyOnReasoningModels() {
        ChatCompletionsOptions chat = buildOptions(client(), new LlmRequest(CHAT_DEPLOYMENT,
            List.of(new LlmMessage("system", "be brief"), user("hi")), 0.0, 100, List.of()));
        ChatCompletionsOptions reasoning =
            buildOptions(client(), new LlmRequest(REASONING_DEPLOYMENT,
                List.of(new LlmMessage("system", "be brief"), user("hi")), 0.0, 100, List.of()));

        assertInstanceOf(ChatRequestSystemMessage.class, chat.getMessages().get(0));
        assertInstanceOf(ChatRequestDeveloperMessage.class, reasoning.getMessages().get(0));
        assertInstanceOf(ChatRequestUserMessage.class, chat.getMessages().get(1));
    }

    @Test
    void aToolMessageCarriesItsToolCallId() {
        ChatCompletionsOptions options = buildOptions(client(),
            new LlmRequest(CHAT_DEPLOYMENT,
                List.of(new LlmMessage("tool", "42", null, "call_7", "search_corpus")), 0.0, 100,
                List.of()));

        ChatRequestToolMessage tool =
            assertInstanceOf(ChatRequestToolMessage.class, options.getMessages().get(0));
        assertEquals("call_7", tool.getToolCallId());
    }

    @Test
    void aToolSchemaSurvivesVerbatimIncludingKeywordsOutsideTheOpenApiSubset() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put("properties", Map.of("q", Map.of("type", "string")));
        ChatCompletionsOptions options =
            buildOptions(client(), new LlmRequest(CHAT_DEPLOYMENT, List.of(user("hi")), 0.0, 100,
                List.of(new LlmTool("search", "Search the corpus", schema))));

        assertEquals(1, options.getTools().size());
        ChatCompletionsFunctionToolDefinition definition = assertInstanceOf(
            ChatCompletionsFunctionToolDefinition.class, options.getTools().get(0));
        assertEquals("search", definition.getFunction().getName());
        assertEquals("Search the corpus", definition.getFunction().getDescription());
        String parameters = definition.getFunction().getParameters().toString();
        assertTrue(parameters.contains("additionalProperties"),
            "a lossy schema round-trip would drop additionalProperties, got: " + parameters);
    }

    @Test
    void withoutToolsNeitherToolsNorToolChoiceIsSent() {
        ChatCompletionsOptions options = buildOptions(client(),
            new LlmRequest(CHAT_DEPLOYMENT, List.of(user("hi")), 0.0, 100, List.of()));

        assertNull(options.getTools());
        assertNull(options.getToolChoice(), "the service rejects a tool_choice with no tools");
    }

    @Test
    void aResponseSchemaBecomesAJsonSchemaFormatAndAMimeTypeAloneBecomesJsonMode() {
        ChatCompletionsOptions withSchema =
            buildOptions(client(), new LlmRequest(CHAT_DEPLOYMENT, List.of(user("hi")), 0.0, 100,
                List.of(), null, "application/json", Map.of("type", "object"), null, false));
        ChatCompletionsOptions mimeOnly = buildOptions(client(), new LlmRequest(CHAT_DEPLOYMENT,
            List.of(user("hi")), 0.0, 100, List.of(), null, "application/json", null, null, false));

        assertInstanceOf(ChatCompletionsJsonSchemaResponseFormat.class,
            withSchema.getResponseFormat());
        assertInstanceOf(ChatCompletionsJsonResponseFormat.class, mimeOnly.getResponseFormat());
    }

    @Test
    void codeExecutionIsIgnoredWithoutAffectingAnythingElse() {
        LlmRequest plain =
            new LlmRequest(CHAT_DEPLOYMENT, List.of(user("hi")), 0.0, 100, List.of(), null, false);
        LlmRequest withCodeExecution =
            new LlmRequest(CHAT_DEPLOYMENT, List.of(user("hi")), 0.0, 100, List.of(), null, true);

        ChatCompletionsOptions options = buildOptions(client(), withCodeExecution);

        assertNull(options.getTools(), "Azure chat completions has no code-execution tool to add");
        assertEquals(buildOptions(client(), plain).getTemperature(), options.getTemperature());
    }

    @Test
    void reasoningDetectionUsesWholeTokensAndAnExplicitListWins() {
        AzureOpenAiLlmClient auto = client();
        assertTrue(auto.isReasoningModel("gpt-5.6-luna"));
        assertTrue(auto.isReasoningModel("o3-mini"));
        assertTrue(auto.isReasoningModel("prod-o4"));
        assertFalse(auto.isReasoningModel("gpt-4o"), "gpt-4o is not an o-series reasoning model");
        assertFalse(auto.isReasoningModel("text-embedding-3-large"));

        // An operator-chosen deployment name the heuristic cannot possibly recognise.
        AzureOpenAiLlmClient explicit = new AzureOpenAiLlmClient("http://127.0.0.1:1", "key",
            new ObjectMapper(), true, "medium", "reasoning-prod, chatty");

        assertTrue(explicit.isReasoningModel("reasoning-prod"));
        assertTrue(explicit.isReasoningModel("CHATTY"), "matching must be case-insensitive");
        assertFalse(explicit.isReasoningModel("gpt-5.6-luna"),
            "an explicit list replaces the heuristic rather than adding to it");
    }

    // ------------------------------------------------------------------------
    // Wire tests
    // ------------------------------------------------------------------------

    @Test
    void aNonStreamingCallMapsContentUsageAndGatewayStatus() throws Exception {
        withMockServer(exchange -> {
            exchange.getResponseHeaders().add("cf-aig-cache-status", "HIT");
            respondJson(exchange, 200, """
                {"id":"c1","created":1,"choices":[{"index":0,"finish_reason":"stop",
                 "message":{"role":"assistant","content":"Hello there"}}],
                 "usage":{"prompt_tokens":11,"completion_tokens":7,"total_tokens":18,
                  "prompt_tokens_details":{"cached_tokens":5},
                  "completion_tokens_details":{"reasoning_tokens":3}}}""");
        }, client -> {
            LlmResponse response = client.generate(userRequest("hi"));

            assertEquals("Hello there", response.content());
            assertEquals(11, response.usage().promptTokens());
            assertEquals(7, response.usage().completionTokens());
            assertEquals(18, response.usage().totalTokens());
            assertEquals(5, response.usage().cachedTokens());
            assertEquals(3, response.usage().thoughtTokens());
            assertNotNull(response.gateway(), "the gateway header must be read off the response");
            assertEquals(GatewayCacheStatus.REPLAYED, response.gateway().cacheStatus());
        });
    }

    @Test
    void anEmptyResponseFailsButStillCarriesTheTokensItBurned() throws Exception {
        withMockServer(exchange -> respondJson(exchange, 200, """
            {"id":"c1","created":1,"choices":[],
             "usage":{"prompt_tokens":9,"completion_tokens":0,"total_tokens":9}}"""), client -> {
            LlmCallFailedException e = assertThrows(LlmCallFailedException.class,
                () -> client.generate(userRequest("hi")));

            assertNotNull(e.usage(), "usage must be read before throwing, or the call is "
                + "never billed and appears in no ledger");
            assertEquals(9, e.usage().promptTokens());
            assertTrue(e.getMessage().contains("no choices"), e.getMessage());
        });
    }

    @Test
    void aStreamedCallEmitsContentAndFinalUsageAndAsksForUsageOnTheWire() throws Exception {
        StringBuilder requestBody = new StringBuilder();
        withMockServer(exchange -> {
            requestBody.append(readBody(exchange));
            exchange.getResponseHeaders().add("cf-aig-cache-status", "MISS");
            respondSse(exchange, List.of(
                "{\"id\":\"c\",\"created\":1,\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hel\"}}]}",
                "{\"id\":\"c\",\"created\":1,\"choices\":[{\"index\":0,\"delta\":{\"content\":\"lo\"},\"finish_reason\":\"stop\"}]}",
                "{\"id\":\"c\",\"created\":1,\"choices\":[],\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":2,\"total_tokens\":6}}"));
        }, client -> {
            List<LlmResponseChunk> chunks = new ArrayList<>();
            client.generateStream(userRequest("hi"), chunks::add);

            String text = chunks.stream().map(LlmResponseChunk::content).filter(c -> c != null)
                .reduce("", String::concat);
            assertEquals("Hello", text);
            LlmResponseChunk last = chunks.get(chunks.size() - 1);
            assertNotNull(last.usage(), "the final event carries usage");
            assertEquals(6, last.usage().totalTokens());
            assertEquals(GatewayCacheStatus.FORWARDED, last.gateway().cacheStatus());
        });

        assertTrue(requestBody.toString().contains("\"stream_options\""),
            "stream_options must be on the wire or a streamed call reports no tokens at all; body: "
                + requestBody);
        assertTrue(requestBody.toString().contains("\"include_usage\":true"),
            requestBody.toString());
    }

    /**
     * The trap this pins: the SDK models a streamed tool call as id + type only and discards the
     * {@code index} the protocol uses to tell parallel calls apart, so its own sample handles a
     * single call by reading {@code toolCalls.get(0)}. Emitting {@code index = -1} lets
     * {@link de.palsoftware.yvoke.rag.core.service.ToolCallAccumulator} key by id instead —
     * collapse this to first-call-only handling and the second call's arguments land on the first.
     */
    /**
     * The flag must be on the wire, not inherited from a service default nobody can see.
     *
     * <p>
     * azure-json omits a null {@code Boolean} entirely, so leaving {@code parallelToolCalls} unset
     * sent no field at all and the service decided — to {@code true}, as this repo's own captured
     * fixtures show. Whether parallel calls are allowed is the single input that decides whether
     * the indexless reassembly in {@code ToolCallAccumulator} can be ambiguous at all, so it must
     * be a visible decision in the code rather than a property of the deployment.
     */
    @Test
    void parallelToolCallsIsSentExplicitlyRatherThanLeftToTheServiceDefault() {
        LlmTool tool = new LlmTool("search", "d", Map.of("type", "object"));
        ChatCompletionsOptions options = buildOptions(client(),
            new LlmRequest(CHAT_DEPLOYMENT, List.of(user("hi")), 0.0, 100, List.of(tool)));

        assertEquals(Boolean.TRUE, options.isParallelToolCalls(),
            "the value must be stated, so that changing it is a one-line decision");
    }

    @Test
    void twoParallelToolCallsAreKeptApartDespiteTheMissingIndex() throws Exception {
        withMockServer(exchange -> respondSse(exchange, List.of(
            "{\"id\":\"c\",\"created\":1,\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_a\",\"type\":\"function\",\"function\":{\"name\":\"search\",\"arguments\":\"\"}}]}}]}",
            "{\"id\":\"c\",\"created\":1,\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"q\\\":1}\"}}]}}]}",
            "{\"id\":\"c\",\"created\":1,\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":1,\"id\":\"call_b\",\"type\":\"function\",\"function\":{\"name\":\"fetch\",\"arguments\":\"\"}}]}}]}",
            "{\"id\":\"c\",\"created\":1,\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":1,\"function\":{\"arguments\":\"{\\\"id\\\":2}\"}}]},\"finish_reason\":\"tool_calls\"}]}")),
            client -> {
                List<LlmToolCallDelta> deltas = new ArrayList<>();
                client.generateStream(userRequest("hi"), chunk -> {
                    if (chunk.toolCallDeltas() != null) {
                        deltas.addAll(chunk.toolCallDeltas());
                    }
                });

                // Asserted on the delta stream, which is what this client owns and what it hands
                // to ToolCallAccumulator. The previous version of this test reassembled the deltas
                // with a LOCAL helper keyed by id — a different rule from production's, which has
                // no index and therefore appends an id-less fragment to the most recently OPENED
                // call. The two agree only on a contiguous fixture like this one, so the test was
                // green while proving nothing about the code that runs. Reassembly is now pinned
                // where it lives, by ToolCallAccumulatorTest
                // #theAzureParallelShapeReassemblesIntoTwoDistinctCalls, against this exact
                // sequence.
                assertEquals(4, deltas.size(), "one delta per streamed tool-call fragment");
                assertTrue(deltas.stream().allMatch(d -> d.index() == -1),
                    "the SDK discards the wire index, so every delta must say so explicitly");
                assertEquals(List.of("call_a", "call_b"),
                    deltas.stream().map(LlmToolCallDelta::id).filter(Objects::nonNull).toList(),
                    "each call is opened by exactly one id-bearing fragment, in order");
                assertEquals("search", deltas.get(0).name());
                assertEquals("{\"q\":1}", deltas.get(1).argumentsDelta());
                assertNull(deltas.get(1).id(),
                    "the continuation carries no id — that is what makes attribution positional");
                assertEquals("fetch", deltas.get(2).name());
                assertEquals("{\"id\":2}", deltas.get(3).argumentsDelta());
            });
    }

    /**
     * Proves the stream is consumed incrementally rather than buffered whole: the server holds the
     * response open until the client has already delivered the first chunk. A buffering
     * implementation deadlocks here and fails on the class-level timeout instead of passing.
     */
    @Test
    void chunksReachTheConsumerBeforeTheResponseIsComplete() throws Exception {
        CountDownLatch firstChunkSeen = new CountDownLatch(1);
        withMockServer(exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            writeEvent(out,
                "{\"id\":\"c\",\"created\":1,\"choices\":[{\"index\":0,\"delta\":{\"content\":\"first\"}}]}");
            try {
                if (!firstChunkSeen.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("client never received the first chunk: the "
                        + "response is being buffered whole rather than streamed");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted waiting for the first chunk", e);
            }
            writeEvent(out,
                "{\"id\":\"c\",\"created\":1,\"choices\":[{\"index\":0,\"delta\":{\"content\":\"second\"},\"finish_reason\":\"stop\"}]}");
            out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.close();
        }, client -> {
            List<String> seen = new ArrayList<>();
            client.generateStream(userRequest("hi"), chunk -> {
                if (chunk.content() != null) {
                    seen.add(chunk.content());
                    firstChunkSeen.countDown();
                }
            });
            assertEquals(List.of("first", "second"), seen);
        });
    }

    /**
     * The SDK installs its own retry policy on every client. Left in place it multiplies our
     * {@link de.palsoftware.yvoke.llm.core.LlmRetry} loop, re-uploading a large prompt into the
     * very quota that failed. Three LlmRetry attempts must be exactly three HTTP requests.
     */
    @Test
    void theSdkAddsNoRetriesOnTopOfLlmRetry() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        withMockServer(exchange -> {
            requests.incrementAndGet();
            respondJson(exchange, 503, "{\"error\":{\"message\":\"overloaded\"}}");
        }, client -> {
            assertThrows(RuntimeException.class, () -> client.generate(userRequest("hi")));
            assertEquals(3, requests.get(),
                "LlmRetry owns retrying; the SDK must make exactly one attempt per call");
        });
    }

    /**
     * The streaming path must retry too. {@code getChatCompletionsStreamWithResponse} returns a
     * <em>cold</em> Flux: building it performs no HTTP at all, so wrapping only that call in
     * {@link de.palsoftware.yvoke.llm.core.LlmRetry} retried nothing — the request was issued
     * later, by the subscription, outside the loop. A live orchestrated run hit three 429s and gave
     * up on each instantly, with not one retry logged, while the non-streaming sibling test above
     * passed throughout: it exercises {@code generate}, where {@code .block()} keeps the call
     * inside the loop. Every answer in this app streams, so the retry that mattered was the one not
     * running.
     */
    @Test
    void theStreamingPathRetriesATransientFailureBeforeAnyChunkIsEmitted() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        withMockServer(exchange -> {
            requests.incrementAndGet();
            respondJson(exchange, 503, "{\"error\":{\"message\":\"overloaded\"}}");
        }, client -> {
            assertThrows(RuntimeException.class,
                () -> client.generateStream(userRequest("hi"), chunk -> {
                }));
            assertEquals(3, requests.get(),
                "establishing the stream must be retried exactly as often as a blocking call");
        });
    }

    /**
     * Retrying is only safe while nothing has reached the consumer. Once a chunk is emitted the
     * caller has already rendered it, so a second attempt would replay the answer from the start —
     * the reason the retry is scoped to establishment rather than wrapped around the whole loop.
     */
    @Test
    void aFailureAfterTheFirstChunkIsNotRetried() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        withMockServer(exchange -> {
            requests.incrementAndGet();
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                writeEvent(os, "{\"id\":\"c\",\"created\":1,\"choices\":[{\"index\":0,"
                    + "\"delta\":{\"content\":\"partial\"}}]}");
                // A second event the parser cannot read: a failure that can only surface once the
                // consumer has already been handed the chunk above.
                writeEvent(os, "{ this is not json");
            }
        }, client -> {
            List<String> seen = new ArrayList<>();
            assertThrows(RuntimeException.class,
                () -> client.generateStream(userRequest("hi"), chunk -> {
                    if (chunk.content() != null) {
                        seen.add(chunk.content());
                    }
                }));
            assertEquals(List.of("partial"), seen, "the chunk that arrived must not be replayed");
            assertEquals(1, requests.get(),
                "a mid-stream failure cannot be resumed and must not re-request");
        });
    }

    /**
     * A stream that ends having produced neither text nor a tool call must say so, naming the
     * evidence it had. Reported as a success it pushed the diagnosis onto the caller, which could
     * only guess — an orchestrated run died as "possible MALFORMED_FUNCTION_CALL or safety block"
     * while the finish reason and the reasoning/visible token split sat unread in the response.
     */
    @Test
    void aStreamThatProducesNothingFailsWithItsOwnEvidence() throws Exception {
        withMockServer(exchange -> respondSse(exchange, List.of(
            "{\"id\":\"c\",\"created\":1,\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}",
            "{\"id\":\"c\",\"created\":1,\"choices\":[],\"usage\":{\"prompt_tokens\":68169,\"completion_tokens\":1040,\"total_tokens\":69209,\"completion_tokens_details\":{\"reasoning_tokens\":1024}}}")),
            client -> {
                LlmCallFailedException e = assertThrows(LlmCallFailedException.class,
                    () -> client.generateStream(userRequest("hi"), chunk -> {
                    }));

                assertTrue(e.getMessage().contains("finishReason=stop"), e.getMessage());
                assertTrue(e.getMessage().contains("reasoning=1024"),
                    "the reasoning/visible split is the evidence that explains an empty turn: "
                        + e.getMessage());
                assertNotNull(e.usage(), "the provider charged for these tokens");
                // Both attempts were charged, so the failure carries what both burned. The
                // property this has always protected — an empty turn is never free — is unchanged;
                // only the number of attempts behind it is.
                assertEquals(69209 * 2, e.usage().totalTokens());
            });
    }

    /**
     * An empty turn is the one mid-stream failure that IS safe to retry, and the reason is exactly
     * the rule {@link #aFailureAfterTheFirstChunkIsNotRetried()} states: retrying is safe while
     * nothing has reached the consumer. "Empty" is defined as neither content nor a tool call, so
     * by construction the consumer has been handed nothing to replay.
     *
     * <p>
     * This cannot be done by marking the exception transient in {@code LlmRetry}: the throw happens
     * while consuming the stream, outside the establishment call that {@code withRetry} wraps, so
     * the classification would never be consulted. Widening the retry to cover the consume loop
     * instead would retry genuine mid-stream faults — a truncated SSE event surfaces as a Jackson
     * {@code IOException}, which {@code isTransient} already answers true for — and replay the
     * answer from the start.
     */
    @Test
    void anEmptyStreamIsRetriedBecauseNothingReachedTheConsumer() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        withMockServer(exchange -> {
            if (requests.incrementAndGet() == 1) {
                respondSse(exchange, List.of(EMPTY_TURN_EVENT, EMPTY_TURN_USAGE_EVENT));
            } else {
                respondSse(exchange,
                    List.of("{\"id\":\"c\",\"created\":1,\"choices\":[{\"index\":0,"
                        + "\"delta\":{\"content\":\"recovered\"},\"finish_reason\":\"stop\"}]}"));
            }
        }, client -> {
            List<String> seen = new ArrayList<>();
            client.generateStream(userRequest("hi"), chunk -> {
                if (chunk.content() != null) {
                    seen.add(chunk.content());
                }
            });
            assertEquals(List.of("recovered"), seen,
                "the second attempt's answer must reach the consumer");
            assertEquals(2, requests.get(), "an empty turn must be re-requested exactly once");
        });
    }

    /**
     * The retry is bounded at one. An empty turn that repeats is systematic rather than a glitch,
     * and these prompts run to hundreds of thousands of tokens — re-sending one three times to
     * discover the same nothing costs more than the round it was trying to save.
     */
    @Test
    void anEmptyStreamThatRepeatsFailsRatherThanRetryingForever() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        withMockServer(exchange -> {
            requests.incrementAndGet();
            respondSse(exchange, List.of(EMPTY_TURN_EVENT, EMPTY_TURN_USAGE_EVENT));
        }, client -> {
            assertThrows(LlmCallFailedException.class,
                () -> client.generateStream(userRequest("hi"), chunk -> {
                }));
            assertEquals(2, requests.get(), "one retry, not an unbounded loop");
        });
    }

    /**
     * The abandoned attempt burned real tokens the provider billed for, and the accounting
     * decorator above this client keeps only the LAST usage it observes (it calls {@code set}, not
     * {@code add}, and publishes one row per {@code generateStream}). So a retry that reports only
     * the winning attempt's usage would silently under-bill every recovery — the ledger would show
     * a cheap successful call where two full prompts were charged.
     */
    @Test
    void theRetryCarriesTheTokensTheAbandonedAttemptAlreadyBurned() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        withMockServer(exchange -> {
            if (requests.incrementAndGet() == 1) {
                respondSse(exchange, List.of(EMPTY_TURN_EVENT, EMPTY_TURN_USAGE_EVENT));
            } else {
                respondSse(exchange, List.of(
                    "{\"id\":\"c\",\"created\":1,\"choices\":[{\"index\":0,"
                        + "\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}",
                    "{\"id\":\"c\",\"created\":1,\"choices\":[],\"usage\":{\"prompt_tokens\":100,"
                        + "\"completion_tokens\":10,\"total_tokens\":110,"
                        + "\"completion_tokens_details\":{\"reasoning_tokens\":4}}}"));
            }
        }, client -> {
            AtomicReference<LlmUsage> last = new AtomicReference<>();
            client.generateStream(userRequest("hi"), chunk -> {
                if (chunk.usage() != null) {
                    last.set(chunk.usage());
                }
            });
            LlmUsage usage = last.get();
            assertNotNull(usage, "the successful attempt still reports usage");
            assertEquals(68169 + 100, usage.promptTokens(),
                "the abandoned attempt's prompt was charged too");
            assertEquals(1040 + 10, usage.completionTokens());
            assertEquals(69209 + 110, usage.totalTokens());
            assertEquals(1024 + 4, usage.thoughtTokens());
        });
    }

    /** A refusal is output the model chose to send, not an empty turn. */
    @Test
    void aRefusalCountsAsContentRatherThanNothing() throws Exception {
        withMockServer(exchange -> respondSse(exchange, List.of(
            "{\"id\":\"c\",\"created\":1,\"choices\":[{\"index\":0,\"delta\":{\"refusal\":\"I can't help with that.\"},\"finish_reason\":\"stop\"}]}")),
            client -> {
                List<LlmResponseChunk> chunks = new ArrayList<>();
                client.generateStream(userRequest("hi"), chunks::add);

                assertEquals("I can't help with that.", chunks.get(0).content());
            });
    }

    /**
     * A content-filtered stream must fail AND still report what the provider billed.
     *
     * <p>
     * The trap this pins is that the obvious fix does not work. Hoisting a usage read above the
     * throw recovers nothing, because under {@code stream_options.include_usage} the counts do not
     * ride the delta that carries the finish reason: {@code ChatCompletionStreamOptions}
     * (azure-ai-openai 1.0.0-beta.16) documents that "an additional chunk will be streamed before
     * {@code data: [DONE]}" whose {@code choices} is empty and that "all other chunks will also
     * include a usage field, but with a null value". Throwing from inside the chunk parser
     * cancelled iteration before that event was ever pulled, so the tokens were unreachable by
     * construction — {@code AccountingLlmClient} published nothing and the call left no
     * {@code llm_call_logs} row, the exact loss {@code generate()} was fixed for. The turn must
     * therefore be allowed to drain and the verdict taken afterwards.
     *
     * <p>
     * This test previously asserted only {@code IllegalStateException} against a single-event
     * fixture with no usage at all, so it was satisfied by an exception carrying nothing.
     */
    @Test
    void aContentFilteredStreamIsSurfacedRatherThanTruncatedSilently() throws Exception {
        withMockServer(exchange -> respondSse(exchange, List.of(
            "{\"id\":\"c\",\"created\":1,\"choices\":[{\"index\":0,\"delta\":{\"content\":\"partial\"},\"finish_reason\":\"content_filter\"}]}",
            "{\"id\":\"c\",\"created\":1,\"choices\":[],\"usage\":{\"prompt_tokens\":180000,\"completion_tokens\":12,\"total_tokens\":180012}}")),
            client -> {
                LlmCallFailedException e = assertThrows(LlmCallFailedException.class,
                    () -> client.generateStream(userRequest("hi"), chunk -> {
                    }));

                assertTrue(e.getMessage().contains("content_filter"),
                    "the reason must be named: " + e.getMessage());
                assertNotNull(e.usage(),
                    "a filtered turn is fully billed; dropping the usage loses the whole "
                        + "llm_call_logs row");
                assertEquals(180000, e.usage().promptTokens(),
                    "the counts arrive on a LATER choices-empty event, so the stream must be "
                        + "drained before the verdict is taken");
                assertEquals(180012, e.usage().totalTokens());
            });
    }

    /**
     * A finish reason this client does not recognise must deliver the answer, not destroy it.
     *
     * <p>
     * {@code CompletionsFinishReason} is an {@code ExpandableStringEnum}: {@code fromString} mints
     * and caches an instance for any wire value, so a vendor-specific or newly-introduced reason is
     * an ordinary value that a closed {@code Set.of} of four constants simply does not contain. The
     * guard was written as "not benign ⇒ fatal", which fails CLOSED — and destructively, because
     * the parser ran before the chunk was handed on, so the content on that same delta went with
     * it. An answer already on the user's screen was replaced by a system error on the strength of
     * a string this client had never seen.
     *
     * <p>
     * The classification is therefore three-way, not two: a closed FATAL set decides failure, the
     * benign set is documentation of what is known-normal, and anything in neither is unknown —
     * delivered, with a warning. Note the sibling {@link GeminiLlmClient} had the mirror-image bug,
     * failing OPEN on an unknown reason; the two providers behaved oppositely on identical events
     * by accident rather than by policy.
     */
    @Test
    void anUnrecognisedFinishReasonDeliversTheAnswerInsteadOfDestroyingIt() throws Exception {
        withMockServer(exchange -> respondSse(exchange, List.of(
            "{\"id\":\"c\",\"created\":1,\"choices\":[{\"index\":0,\"delta\":{\"content\":\"the answer\"},\"finish_reason\":\"some_future_reason\"}]}",
            "{\"id\":\"c\",\"created\":1,\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2,\"total_tokens\":12}}")),
            client -> {
                List<LlmResponseChunk> chunks = new ArrayList<>();
                client.generateStream(userRequest("hi"), chunks::add);

                assertTrue(chunks.stream().anyMatch(c -> "the answer".equals(c.content())),
                    "an unknown finish reason is not evidence of a failure, and the text the model "
                        + "already produced must reach the caller");
            });
    }

    @Test
    void anInterruptedStreamIsReportedAsCancellationNotFailure() throws Exception {
        withMockServer(exchange -> respondSse(exchange, List.of(
            "{\"id\":\"c\",\"created\":1,\"choices\":[{\"index\":0,\"delta\":{\"content\":\"a\"}}]}",
            "{\"id\":\"c\",\"created\":1,\"choices\":[{\"index\":0,\"delta\":{\"content\":\"b\"},\"finish_reason\":\"stop\"}]}")),
            client -> {
                try {
                    assertThrows(CancellationException.class,
                        () -> client.generateStream(userRequest("hi"),
                            chunk -> Thread.currentThread().interrupt()));
                } finally {
                    Thread.interrupted();
                }
            });
    }

    // ------------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------------

    /**
     * Mirrors {@code ToolCallAccumulator}'s incremental branch: an id-bearing fragment opens or
     * finds a call, an id-less one appends to the most recent. Deliberately re-implemented here so
     * this test stays a test of the client's delta shape rather than of the accumulator.
     */
    private static AzureOpenAiLlmClient client() {
        return new AzureOpenAiLlmClient("http://127.0.0.1:1", "key", new ObjectMapper(), true,
            "medium", "");
    }

    private static ChatCompletionsOptions buildOptions(AzureOpenAiLlmClient client,
        LlmRequest request) {
        return client.buildOptions(request);
    }

    private static LlmMessage user(String text) {
        return new LlmMessage("user", text);
    }

    private static LlmRequest userRequest(String text) {
        return new LlmRequest(CHAT_DEPLOYMENT, List.of(user(text)), 0.0, 100, List.of());
    }

    private interface ClientCallback {
        void accept(AzureOpenAiLlmClient client) throws Exception;
    }

    /** Deadlines short enough that a timeout is proven in milliseconds, not minutes. */
    private static final AzureOpenAiLlmClient.Deadlines FAST =
        new AzureOpenAiLlmClient.Deadlines(Duration.ofMillis(300), Duration.ofMillis(300));

    /**
     * A handler that accepts the request and then never answers, counting arrivals. Released in a
     * {@code finally} so {@code server.stop(0)} is not blocked by a parked handler thread.
     */
    private static HttpHandler neverAnswers(AtomicInteger requests, CountDownLatch release) {
        return exchange -> {
            requests.incrementAndGet();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // HttpHandler.handle declares only IOException, so the interrupt cannot escape.
        };
    }

    /**
     * The defect: nothing bounded the wait for response HEADERS, so a service that completed TLS,
     * accepted the prompt and then went silent hung the calling thread forever.
     *
     * <p>
     * None of the three configured timeouts could cover it. {@code connectionTimeout} ends at TCP/
     * TLS; {@code readTimeout} is armed on the body Flux, which does not exist until headers
     * arrive; {@code responseTimeout} is the JDK's {@code HttpRequest.timeout()}, which stays armed
     * through body consumption and would therefore cap total generation — which is why it is
     * disabled, and that reasoning is right. The bound has to come from a layer that sees
     * headers-received as a distinct event, which is the transport wrapper.
     *
     * <p>
     * Asserting the request COUNT is the point: it proves the failure surfaced inside
     * {@code LlmRetry.withRetry}, i.e. as a retryable transport error, rather than merely escaping
     * from somewhere. A bound that threw something {@code isTransient} rejected would show 1.
     */
    @Test
    @Timeout(30)
    void aServiceThatNeverSendsResponseHeadersFailsAndIsRetried() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        try {
            withMockServer(neverAnswers(requests, release), FAST,
                client -> assertThrows(RuntimeException.class,
                    () -> client.generateStream(userRequest("hi"), chunk -> {
                    })));
            assertEquals(3, requests.get(),
                "the headers timeout must reach LlmRetry as a transient failure, so all three "
                    + "attempts are made");
        } finally {
            release.countDown();
        }
    }

    /** The same wait on the blocking path, which had a bare {@code .block()} with no duration. */
    @Test
    @Timeout(30)
    void aNonStreamingCallThatNeverAnswersFailsInsteadOfBlockingForever() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        try {
            withMockServer(neverAnswers(requests, release), FAST,
                client -> assertThrows(RuntimeException.class,
                    () -> client.generate(userRequest("hi"))));
            assertEquals(3, requests.get(),
                "GeneralSummarizer and DocumentKgExtractor call this from a JobWorker thread; "
                    + "unbounded, N stalls exhaust the ingest pool with nothing logged");
        } finally {
            release.countDown();
        }
    }

    /**
     * The guard rail, and the constraint that makes this hard: the deadline must bound the wait for
     * headers and NOTHING after it. Answers legitimately run for minutes.
     *
     * <p>
     * The first SSE event is written immediately on purpose. {@code sendResponseHeaders(200, 0)}
     * only buffers the header block — it flushes when there is content to send — so a fixture that
     * sends headers and then sleeps would keep the client waiting on headers and go red against a
     * correct implementation. Writing one event immediately and then stalling for far longer than
     * the deadline is what actually discriminates a headers bound from a per-element one.
     */
    @Test
    @Timeout(30)
    void headersThatArriveInTimeDoNotBoundTheGenerationThatFollows() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        withMockServer(exchange -> {
            requests.incrementAndGet();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                writeEvent(os,
                    "{\"id\":\"c\",\"created\":1,\"choices\":[{\"index\":0,\"delta\":{\"content\":\"a\"}}]}");
                sleepQuietly(1200);
                writeEvent(os, "{\"id\":\"c\",\"created\":1,\"choices\":[{\"index\":0,"
                    + "\"delta\":{\"content\":\"b\"},\"finish_reason\":\"stop\"}]}");
            }
        }, new AzureOpenAiLlmClient.Deadlines(Duration.ofMillis(300), Duration.ofSeconds(30)),
            client -> {
                StringBuilder seen = new StringBuilder();
                client.generateStream(userRequest("hi"), chunk -> {
                    if (chunk.content() != null) {
                        seen.append(chunk.content());
                    }
                });
                assertEquals("ab", seen.toString(),
                    "a gap four times the deadline, after headers, must not fail the call");
            });
        assertEquals(1, requests.get(), "a healthy generation must not be retried");
    }

    /**
     * The two waits are different in kind and must not share a number. A buffered completion sends
     * no headers until the answer is finished, so on that path time-to-headers IS time-to-whole-
     * answer: giving it the streaming establishment budget would fail legitimate slow work — a KG
     * extraction at 4096 output tokens — and then retry it three times.
     */
    @Test
    @Timeout(30)
    void aNonStreamingCallGetsTheWholeAnswerBudgetNotTheEstablishmentOne() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        withMockServer(exchange -> {
            requests.incrementAndGet();
            sleepQuietly(900);
            respondJson(exchange, 200,
                "{\"id\":\"c\",\"created\":1,\"choices\":[{\"index\":0,"
                    + "\"message\":{\"role\":\"assistant\",\"content\":\"slow but fine\"},"
                    + "\"finish_reason\":\"stop\"}]}");
        }, new AzureOpenAiLlmClient.Deadlines(Duration.ofMillis(300), Duration.ofSeconds(20)),
            client -> assertEquals("slow but fine", client.generate(userRequest("hi")).content()));
        assertEquals(1, requests.get(),
            "the whole-answer budget applies, not the 300ms establishment one");
    }

    /** There must be no way to express "wait forever". */
    @Test
    void aDeadlineOfZeroOrLessIsRejected() {
        for (Duration bad : new Duration[] {null, Duration.ZERO, Duration.ofSeconds(-1)}) {
            assertThrows(IllegalArgumentException.class,
                () -> new AzureOpenAiLlmClient.Deadlines(bad, Duration.ofSeconds(1)));
            assertThrows(IllegalArgumentException.class,
                () -> new AzureOpenAiLlmClient.Deadlines(Duration.ofSeconds(1), bad));
        }
    }

    private static void writeEvent(OutputStream os, String json) throws IOException {
        os.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void withMockServer(HttpHandler handler, ClientCallback test) throws Exception {
        withMockServer(handler, AzureOpenAiLlmClient.Deadlines.DEFAULT, test);
    }

    /** As above, with injected deadlines so a timeout can be proven without waiting for one. */
    private static void withMockServer(HttpHandler handler,
        AzureOpenAiLlmClient.Deadlines deadlines, ClientCallback test) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // A real executor, not the default caller-runs one: a handler that deliberately parks (the
        // never-answers tests) would otherwise block the dispatcher thread, so retry #2 is never
        // even accepted and the request count reads 1 whether the deadline works or not — a false
        // red pointing at LlmRetry rather than at the transport.
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            try {
                byte[] bodyBytes;
                try (InputStream is = exchange.getRequestBody()) {
                    bodyBytes = is.readAllBytes();
                }
                exchange.setAttribute("cachedRequestBody", bodyBytes);
                handler.handle(exchange);
            } catch (Exception e) {
                log.error("Error in mock server handler", e);
                byte[] err = (e.getMessage() != null ? e.getMessage() : "Error")
                    .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, err.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(err);
                }
            }
        });
        server.start();
        try {
            // The endpoint is declared https so KeyCredentialPolicy will attach the key at all — it
            // refuses to on plain HTTP, which is exactly the guard production wants. The transport
            // below rewrites the scheme on the way out so the request still reaches the plain-HTTP
            // mock server, keeping the guard intact rather than testing around it.
            String endpoint = "https://127.0.0.1:" + server.getAddress().getPort();
            test.accept(new AzureOpenAiLlmClient(endpoint, "mock-api-key", new ObjectMapper(), true,
                "medium", "", downgradingTransport(), deadlines));
        } finally {
            server.stop(0);
        }
    }

    /**
     * A transport that sends an {@code https://} request to the plain-HTTP mock server. It sits
     * below the whole azure-core pipeline, so the credential policy still sees the HTTPS URL it
     * insists on — the guard stays exercised rather than switched off — and everything above it,
     * including the retry policy under test, behaves exactly as in production.
     */
    private static HttpClient downgradingTransport() {
        return DOWNGRADING_TRANSPORT;
    }

    /**
     * One transport for the whole class. azure-core's {@code HttpClient} is not {@code Closeable},
     * so a per-test client would leak its JDK selector threads for the life of the fork; the URL is
     * rewritten per request, so a single instance serves every mock server whatever port it got.
     */
    private static final HttpClient DOWNGRADING_TRANSPORT = buildDowngradingTransport();

    private static HttpClient buildDowngradingTransport() {
        // No arrayBacked() here any more: the client's constructor wraps whatever transport it is
        // given, so wrapping again would double the copy and hide the fact that it is mandatory.
        HttpClient delegate = new JdkHttpClientBuilder().responseTimeout(Duration.ZERO)
            .readTimeout(Duration.ofSeconds(45)).build();
        return new HttpClient() {
            @Override
            public Mono<HttpResponse> send(HttpRequest request) {
                return delegate.send(downgrade(request));
            }

            @Override
            public Mono<HttpResponse> send(HttpRequest request, Context context) {
                return delegate.send(downgrade(request), context);
            }

            @Override
            public HttpResponse sendSync(HttpRequest request, Context context) {
                return delegate.sendSync(downgrade(request), context);
            }
        };
    }

    private static HttpRequest downgrade(HttpRequest request) {
        try {
            URL https = request.getUrl();
            return request
                .setUrl(new URL("http", https.getHost(), https.getPort(), https.getFile()));
        } catch (MalformedURLException e) {
            throw new IllegalStateException("could not rewrite " + request.getUrl(), e);
        }
    }

    private static void respondJson(HttpExchange exchange, int status, String json)
        throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void respondSse(HttpExchange exchange, List<String> events) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream os = exchange.getResponseBody()) {
            for (String event : events) {
                writeEvent(os, event);
            }
            os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
        }
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        byte[] cached = (byte[]) exchange.getAttribute("cachedRequestBody");
        if (cached != null) {
            return new String(cached, StandardCharsets.UTF_8);
        }
        try (InputStream is = exchange.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
