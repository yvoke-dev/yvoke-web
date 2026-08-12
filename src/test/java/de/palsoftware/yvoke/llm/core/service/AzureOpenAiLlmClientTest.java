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
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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

                Map<String, StringBuilder> argumentsById = accumulate(deltas);
                assertEquals(List.of("call_a", "call_b"), List.copyOf(argumentsById.keySet()),
                    "both calls must survive as distinct entries");
                assertEquals("{\"q\":1}", argumentsById.get("call_a").toString());
                assertEquals("{\"id\":2}", argumentsById.get("call_b").toString());
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
                assertEquals(69209, e.usage().totalTokens());
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

    @Test
    void aContentFilteredStreamIsSurfacedRatherThanTruncatedSilently() throws Exception {
        withMockServer(exchange -> respondSse(exchange, List.of(
            "{\"id\":\"c\",\"created\":1,\"choices\":[{\"index\":0,\"delta\":{\"content\":\"partial\"},\"finish_reason\":\"content_filter\"}]}")),
            client -> assertThrows(IllegalStateException.class,
                () -> client.generateStream(userRequest("hi"), chunk -> {
                })));
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
    private static Map<String, StringBuilder> accumulate(List<LlmToolCallDelta> deltas) {
        Map<String, StringBuilder> byId = new LinkedHashMap<>();
        String current = null;
        for (LlmToolCallDelta delta : deltas) {
            assertEquals(-1, delta.index(),
                "the SDK cannot supply an index, so deltas must be keyed by id");
            if (delta.id() != null) {
                current = delta.id();
                byId.computeIfAbsent(current, k -> new StringBuilder());
            }
            if (delta.argumentsDelta() != null && current != null) {
                byId.get(current).append(delta.argumentsDelta());
            }
        }
        return byId;
    }

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

    private static void withMockServer(HttpHandler handler, ClientCallback test) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
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
                "medium", "", downgradingTransport()));
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
        HttpClient delegate = AzureOpenAiLlmClient.arrayBacked(new JdkHttpClientBuilder()
            .responseTimeout(Duration.ZERO).readTimeout(Duration.ofSeconds(45)).build());
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

    private static void writeEvent(OutputStream os, String json) throws IOException {
        os.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
        os.flush();
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
