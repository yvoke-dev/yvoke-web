package de.palsoftware.yvoke.llm.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.ThinkingLevel;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import de.palsoftware.yvoke.llm.core.model.LlmMessage;
import de.palsoftware.yvoke.llm.core.model.LlmPart;
import de.palsoftware.yvoke.llm.core.model.LlmRequest;
import de.palsoftware.yvoke.llm.core.model.LlmResponse;
import de.palsoftware.yvoke.llm.core.model.LlmResponseChunk;
import de.palsoftware.yvoke.llm.core.model.LlmTool;
import de.palsoftware.yvoke.llm.core.model.LlmToolCall;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.ArrayList;
import de.palsoftware.yvoke.llm.core.model.LlmCallFailedException;

@Timeout(60)
class GeminiLlmClientTest {

    // ------------------------------------------------------------------------
    // buildConfig() unit tests
    // ------------------------------------------------------------------------

    @Test
    void testBuildConfigAppliesTemperature() {
        GenerateContentConfig config =
            buildConfig(false, null, new LlmRequest("model", List.of(), 0.5, 100, List.of(), null));

        assertNotNull(config);
        assertTrue(config.temperature().isPresent(), "Temperature must be applied");
        assertEquals(0.5f, config.temperature().get(), "Temperature must match the request value");
        assertEquals(100, config.maxOutputTokens().orElse(0));
    }

    @Test
    void testBuildConfigOmitsNonPositiveMaxTokens() {
        GenerateContentConfig config =
            buildConfig(false, null, new LlmRequest("model", List.of(), 0.0, 0, List.of(), null));

        assertFalse(config.maxOutputTokens().isPresent(),
            "maxOutputTokens must not be sent when the request value is 0");
    }

    @Test
    void testBuildConfigPrefersPerRequestThinkingLevel() {
        GenerateContentConfig config = buildConfig(true, "low",
            new LlmRequest("model", List.of(), 0.0, 100, List.of(), "high"));

        assertTrue(config.thinkingConfig().isPresent(), "Thinking config must be present");
        assertEquals(ThinkingLevel.Known.HIGH,
            config.thinkingConfig().get().thinkingLevel().orElseThrow().knownEnum(),
            "Per-request thinkingLevel must override the client default");
    }

    @Test
    void testBuildConfigFallsBackToClientThinkingLevel() {
        GenerateContentConfig config =
            buildConfig(true, "low", new LlmRequest("model", List.of(), 0.0, 100, List.of(), null));

        assertTrue(config.thinkingConfig().isPresent(), "Thinking config must be present");
        assertEquals(ThinkingLevel.Known.LOW,
            config.thinkingConfig().get().thinkingLevel().orElseThrow().knownEnum(),
            "Falls back to the client-wide thinkingLevel when the request omits it");
    }

    @Test
    void testBuildConfigIgnoresInvalidThinkingLevel() {
        GenerateContentConfig config = buildConfig(true, "bogus",
            new LlmRequest("model", List.of(), 0.0, 100, List.of(), null));

        assertTrue(config.thinkingConfig().isPresent(), "Thinking config must still be present");
        assertFalse(config.thinkingConfig().get().thinkingLevel().isPresent(),
            "An invalid thinkingLevel string must be dropped, not forwarded to the API");
    }

    @Test
    void testBuildConfigSkipsThinkingForNonThinkingModel() {
        GenerateContentConfig config = buildConfig(true, "high",
            new LlmRequest("gemini-2.0-flash", List.of(), 0.0, 100, List.of(), null));

        assertFalse(config.thinkingConfig().isPresent(),
            "Thinking config must not be attached to a non-thinking (legacy) model");
    }

    @Test
    void testBuildConfigAppliesSeed() {
        GenerateContentConfig config = buildConfig(false, null,
            new LlmRequest("model", List.of(), 0.0, 100, List.of(), null, null, null, 42));

        assertTrue(config.seed().isPresent(), "Seed must be applied");
        assertEquals(42, config.seed().get());
    }

    @Test
    void testBuildConfigAppliesStructuredOutput() {
        Map<String, Object> schema =
            Map.of("type", "object", "properties", Map.of("answer", Map.of("type", "string")));
        GenerateContentConfig config = buildConfig(false, null, new LlmRequest("model", List.of(),
            0.0, 100, List.of(), null, "application/json", schema, null));

        assertEquals("application/json", config.responseMimeType().orElse(null));
        assertTrue(config.responseJsonSchema().isPresent(),
            "Raw JSON response schema must be applied (lossless)");
    }

    @Test
    void testBuildConfigIgnoresStructuredOutputWhenToolsPresent() {
        LlmTool tool = new LlmTool("do_thing", "does a thing", Map.of("type", "object"));
        GenerateContentConfig config = buildConfig(false, null, new LlmRequest("model", List.of(),
            0.0, 100, List.of(tool), null, "application/json", null, null));

        assertFalse(config.responseMimeType().isPresent(),
            "Structured output must be ignored when tools are present");
        assertTrue(config.tools().isPresent(), "Tools must still be configured");
    }

    @Test
    void testBuildConfigEnablesCodeExecutionAlone() {
        GenerateContentConfig config = buildConfig(false, null,
            new LlmRequest("model", List.of(), 0.0, 100, List.of(), null, true));

        assertTrue(config.tools().isPresent(), "Tools must be configured");
        assertTrue(config.tools().get().stream().anyMatch(t -> t.codeExecution().isPresent()),
            "A code-execution tool must be present");
        assertFalse(
            config.toolConfig().flatMap(tc -> tc.includeServerSideToolInvocations()).orElse(false),
            "Server-side tool invocations flag is only needed when combined with function tools");
    }

    @Test
    void testBuildConfigCombinesCodeExecutionWithFunctionTools() {
        LlmTool tool = new LlmTool("do_thing", "does a thing", Map.of("type", "object"));
        GenerateContentConfig config = buildConfig(false, null,
            new LlmRequest("model", List.of(), 0.0, 100, List.of(tool), null, true));

        assertTrue(config.tools().isPresent(), "Tools must be configured");
        assertTrue(
            config.tools().get().stream()
                .anyMatch(t -> !t.functionDeclarations().orElse(List.of()).isEmpty()),
            "Function declarations must be present");
        assertTrue(config.tools().get().stream().anyMatch(t -> t.codeExecution().isPresent()),
            "A code-execution tool must be present alongside function tools");
        assertTrue(
            config.toolConfig().flatMap(tc -> tc.includeServerSideToolInvocations()).orElse(false),
            "Combining built-in + function tools must opt into server-side tool invocations");
    }

    // ------------------------------------------------------------------------
    // Mock-server integration tests (exercise the real HTTP wire)
    // ------------------------------------------------------------------------

    @Test
    void testMockServerGenerateContent() throws Exception {
        AtomicReference<String> receivedPath = new AtomicReference<>();
        AtomicReference<String> receivedBody = new AtomicReference<>();
        AtomicReference<String> receivedAuthHeader = new AtomicReference<>();

        withMockServer(exchange -> {
            receivedPath.set(exchange.getRequestURI().toString());
            receivedAuthHeader.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            receivedBody.set(readBody(exchange));
            respondJson(exchange, 200,
                """
                    {
                      "candidates": [
                        {
                          "content": { "parts": [ { "text": "Hello from mock server" } ], "role": "model" },
                          "finishReason": "STOP"
                        }
                      ],
                      "usageMetadata": {
                        "promptTokenCount": 5, "candidatesTokenCount": 10, "totalTokenCount": 15
                      }
                    }
                    """);
        }, client -> {
            LlmRequest request = new LlmRequest("gemini-1.5-flash",
                List.of(new LlmMessage("user", "Hello")), 0.7, 100, List.of());
            LlmResponse response = client.generate(request);

            assertNotNull(response);
            assertEquals("Hello from mock server", response.content());
            assertEquals(5, response.usage().promptTokens());
            assertEquals(10, response.usage().completionTokens());
            assertEquals(15, response.usage().totalTokens());

            assertTrue(
                receivedPath.get().contains("/v1beta/models/gemini-1.5-flash:generateContent"));
            assertEquals("mock-api-key", receivedAuthHeader.get());
            // The request body carries the fixes end-to-end: the user message and the applied
            // temperature (which was previously dropped by buildConfig).
            assertTrue(receivedBody.get().contains("Hello"),
                "request body must contain the user message");
            assertTrue(receivedBody.get().contains("temperature"),
                "temperature must be serialized onto the wire");
        });
    }

    @Test
    void testMockServerGenerateStream() throws Exception {
        AtomicReference<String> receivedPath = new AtomicReference<>();

        withMockServer(exchange -> {
            receivedPath.set(exchange.getRequestURI().toString());
            respondSse(exchange, """
                {"candidates":[{"content":{"parts":[{"text":"Hello "}]}}]}""",
                """
                    {"candidates":[{"content":{"parts":[{"text":"world!"}],"role":"model"},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":2,"totalTokenCount":7}}""");
        }, client -> {
            List<LlmResponseChunk> chunks = new ArrayList<>();
            client.generateStream(userRequest("Hello"), chunks::add);

            assertNotNull(chunks);
            assertEquals(2, chunks.size());
            assertEquals("Hello ", chunks.get(0).content());
            assertEquals("world!", chunks.get(1).content());
            assertEquals(7, chunks.get(1).usage().totalTokens());

            assertTrue(receivedPath.get()
                .contains("/v1beta/models/gemini-1.5-flash:streamGenerateContent"));
        });
    }

    @Test
    void testMockServerStreamAccumulatesTextPartsWithinChunk() throws Exception {
        // Regression for the parseChunk scalar-overwrite bug: multiple text parts in a SINGLE chunk
        // must be concatenated, not overwritten by the last one.
        withMockServer(exchange -> respondSse(exchange,
            """
                {"candidates":[{"content":{"parts":[{"text":"Hello "},{"text":"world"}],"role":"model"},"finishReason":"STOP"}]}"""),
            client -> {
                List<LlmResponseChunk> chunks = new ArrayList<>();
                client.generateStream(userRequest("Hi"), chunks::add);

                assertNotNull(chunks);
                assertEquals(1, chunks.size());
                assertEquals("Hello world", chunks.get(0).content(),
                    "Multiple text parts in one chunk must be concatenated, not overwritten");
            });
    }

    @Test
    void testMockServerStreamSeparatesThoughtAndText() throws Exception {
        withMockServer(exchange -> respondSse(exchange,
            """
                {"candidates":[{"content":{"parts":[{"thought":true,"text":"reasoning"},{"text":"answer"}],"role":"model"},"finishReason":"STOP"}]}"""),
            client -> {
                List<LlmResponseChunk> chunks = new ArrayList<>();
                client.generateStream(userRequest("Hi"), chunks::add);

                assertNotNull(chunks);
                assertEquals(1, chunks.size());
                assertEquals("reasoning", chunks.get(0).reasoning());
                assertEquals("answer", chunks.get(0).content());
            });
    }

    @Test
    void testMockServerRetryOnTransientFailure() throws Exception {
        AtomicInteger attemptCount = new AtomicInteger(0);

        withMockServer(exchange -> {
            if (attemptCount.incrementAndGet() < 3) {
                exchange.sendResponseHeaders(503, 0);
                exchange.close();
            } else {
                respondJson(exchange, 200, """
                    {"candidates":[{"content":{"parts":[{"text":"Success after retry"}]}}]}""");
            }
        }, client -> {
            LlmResponse response = client.generate(userRequest("Hello"));

            assertNotNull(response);
            assertEquals("Success after retry", response.content());
            assertEquals(3, attemptCount.get(), "Should have completed on the 3rd attempt");
        });
    }

    @Test
    void testMockServerNoRetryOnNonTransientFailure() throws Exception {
        AtomicInteger attemptCount = new AtomicInteger(0);

        withMockServer(exchange -> {
            attemptCount.incrementAndGet();
            exchange.sendResponseHeaders(403, 0); // Forbidden: not transient
            exchange.close();
        }, client -> {
            assertThrows(RuntimeException.class, () -> client.generate(userRequest("Hello")));
            assertEquals(1, attemptCount.get(),
                "A non-transient (403) failure must not be retried");
        });
    }

    @Test
    void testMockServerSafetyBlockThrowsDescriptiveError() throws Exception {
        withMockServer(exchange -> respondJson(exchange, 200, """
            {"candidates":[{"finishReason":"SAFETY"}]}"""), client -> {
            RuntimeException ex =
                assertThrows(RuntimeException.class, () -> client.generate(userRequest("Hello")));
            assertTrue(ex.getMessage().contains("finishReason"),
                "A blocked response must surface the finish reason, not a silent null");
        });
    }

    /**
     * A safety/recitation block is not a free call. The provider read the whole prompt, charged for
     * it, and reported the count in {@code usageMetadata} — on the RAG paths that prompt routinely
     * runs to hundreds of thousands of tokens, so these are the most expensive calls in the system,
     * not the cheapest.
     *
     * <p>
     * The accounting seam is {@code AccountingLlmClient}, which publishes a usage event so the call
     * lands in {@code llm_call_logs}. On the streaming path it observes usage from the chunks it
     * sees; on this non-streaming path the only carrier is the exception, because {@code generate}
     * has nothing to return. Throwing a plain exception here — or passing {@code null} for usage,
     * which reads as a harmless simplification since "the call failed" — leaves the call with no
     * row in {@code llm_call_logs} at all. The damage is silent and cumulative: the tokens are
     * billed by the provider and invisible in every internal view, so the cost dashboard, the
     * per-user report and any budget alarm all under-report by exactly the calls most worth
     * noticing. Note the usage must be read BEFORE the throw, which is the ordering this test
     * really pins.
     *
     * <p>
     * No existing test would notice: {@code testMockServerSafetyBlockThrowsDescriptiveError}
     * asserts only that the message names the finish reason, and it is satisfied by a
     * {@code RuntimeException} carrying no usage at all — it does not even assert the exception
     * type, so the whole {@link LlmCallFailedException} contract could be deleted underneath it.
     */
    @Test
    void aBlockedResponseCarriesTheUsageTheProviderAlreadyBilled() throws Exception {
        withMockServer(exchange -> respondJson(exchange, 200,
            """
                {"candidates":[{"finishReason":"SAFETY"}],"usageMetadata":{"promptTokenCount":1234,"candidatesTokenCount":6,"totalTokenCount":1240}}"""),
            client -> {
                LlmCallFailedException ex = assertThrows(LlmCallFailedException.class,
                    () -> client.generate(userRequest("Hello")));

                assertNotNull(ex.usage(),
                    "a blocked call still consumed tokens; dropping the usage loses the whole "
                        + "llm_call_logs row");
                assertEquals(1234, ex.usage().promptTokens(),
                    "the prompt the provider actually read must be billed");
                assertEquals(1240, ex.usage().totalTokens(),
                    "the total the provider reported must survive the failure");
                assertTrue(ex.getMessage().contains("finishReason=SAFETY"),
                    "the reason must still be surfaced, not swallowed by the usage plumbing");
            });
    }

    @Test
    void testMockServerEmptyCandidatesWithPromptFeedbackThrows() throws Exception {
        withMockServer(exchange -> respondJson(exchange, 200, """
            {"candidates":[],"promptFeedback":{"blockReason":"SAFETY"}}"""), client -> {
            RuntimeException ex =
                assertThrows(RuntimeException.class, () -> client.generate(userRequest("Hello")));
            assertTrue(ex.getMessage().contains("no candidates"));
        });
    }

    @Test
    void testMockServerStreamEstablishmentRetry() throws Exception {
        AtomicInteger attemptCount = new AtomicInteger(0);

        withMockServer(exchange -> {
            if (attemptCount.incrementAndGet() < 3) {
                exchange.sendResponseHeaders(503, 0);
                exchange.close();
            } else {
                respondSse(exchange,
                    """
                        {"candidates":[{"content":{"parts":[{"text":"Stream success after retry"}],"role":"model"},"finishReason":"STOP"}]}""");
            }
        }, client -> {
            List<LlmResponseChunk> chunks = new ArrayList<>();
            client.generateStream(userRequest("Hello"), chunks::add);

            assertNotNull(chunks);
            assertEquals(1, chunks.size());
            assertEquals("Stream success after retry", chunks.get(0).content());
            assertEquals(3, attemptCount.get());
        });
    }

    @Test
    void testMockServerStreamFunctionCall() throws Exception {
        withMockServer(exchange -> respondSse(exchange,
            """
                {"candidates":[{"content":{"parts":[{"functionCall":{"name":"search_kb","args":{"query":"test"},"id":"call_999"}}],"role":"model"}}]}"""),
            client -> {
                List<LlmResponseChunk> chunks = new ArrayList<>();
                client.generateStream(userRequest("Hello"), chunks::add);

                assertNotNull(chunks);
                assertEquals(1, chunks.size());
                LlmResponseChunk chunk = chunks.get(0);
                assertNotNull(chunk.toolCallDeltas());
                assertEquals(1, chunk.toolCallDeltas().size());
                assertEquals("search_kb", chunk.toolCallDeltas().get(0).name());
                assertEquals("call_999", chunk.toolCallDeltas().get(0).id());
                assertTrue(chunk.toolCallDeltas().get(0).argumentsDelta().contains("test"));
                assertTrue(chunk.toolCallDeltas().get(0).complete(),
                    "Gemini delivers whole function calls, so the delta must be marked complete");
            });
    }

    @Test
    void testMockServerAbnormalFinishReasonThrowsException() throws Exception {
        withMockServer(exchange -> respondSse(exchange,
            """
                {"candidates":[{"content":{"parts":[{"text":"Abnormal finish"}],"role":"model"},"finishReason":"OTHER"}]}"""),
            client -> {
                RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> client.generateStream(userRequest("Hello"), c -> {
                    }));
                assertTrue(ex.getMessage().contains("finishReason=OTHER"),
                    "An abnormal finish reason during streaming should throw an exception");
            });
    }

    @Test
    void testMockServerStreamSdkDeserializationErrorPropagates() throws Exception {
        // A chunk the SDK cannot deserialize must surface as a stream error (not hang / not be
        // swallowed). The error originates in the SDK's ResponseStream iterator, mid-stream.
        withMockServer(exchange -> respondSse(exchange, """
            {"candidates":[]}""", """
            {"candidates":[{"content":{"parts":[{"thought":true,"text":null}]}}]}"""), client -> {
            assertThrows(RuntimeException.class,
                () -> client.generateStream(userRequest("Hello"), c -> {
                }));
        });
    }

    /**
     * {@code generateStream} retries the <em>establishment</em> of the stream and nothing after it.
     * The asymmetry is the point: a failure before the first chunk is emitted is invisible to the
     * caller and safe to repeat, while a failure after it cannot be repeated, because a chunk that
     * has already been handed to {@code onChunk} cannot be un-handed. On the live path that
     * consumer is {@code CitationStreamingFilter} feeding an {@code SseEmitter}, so every replayed
     * chunk has already been written to the user's browser and appended to the message being
     * persisted. Wrapping the consumption loop in the retry — the natural-looking tidy-up, since
     * the retry is right there and "the whole operation" reads better than "just the handshake" —
     * makes a mid-stream 503 duplicate the answer's opening on screen and in
     * {@code messages.content}, and re-uploads the entire prompt into the quota that just refused
     * it.
     *
     * <p>
     * The mid-stream failure is a 503 error event on purpose. {@code LlmRetry.isTransient} treats
     * an {@code ApiException} with code 503 as retryable, so this fixture is one the mutated code
     * <em>would</em> retry — a fixture that happens to be non-transient (a malformed chunk, say)
     * would not distinguish the two implementations at all and the test would pass against both.
     * Asserting the server hit count as well as the chunk count matters for the same reason: the
     * duplicate delivery and the duplicate request are separate halves of the damage.
     *
     * <p>
     * {@code testMockServerStreamEstablishmentRetry} pins the other half of the rule (a 503 before
     * the stream opens IS retried), and the two only mean something together — either one alone is
     * satisfied by an implementation that retries everywhere, or nowhere.
     */
    @Test
    void aMidStreamFailureIsNotRetriedAndDoesNotReplayChunks() throws Exception {
        AtomicInteger requestCount = new AtomicInteger(0);

        withMockServer(exchange -> {
            requestCount.incrementAndGet();
            respondSse(exchange,
                """
                    {"candidates":[{"content":{"parts":[{"text":"first half of the answer"}],"role":"model"}}]}""",
                """
                    {"error":{"code":503,"status":"UNAVAILABLE","message":"backend overloaded mid-stream"}}""");
        }, client -> {
            List<LlmResponseChunk> chunks = new ArrayList<>();

            RuntimeException ex = assertThrows(RuntimeException.class,
                () -> client.generateStream(userRequest("Hello"), chunks::add));

            assertTrue(ex.getMessage().contains("503"),
                "the mid-stream provider error must reach the caller, not be swallowed");
            assertEquals(1, requestCount.get(),
                "a failure AFTER the stream was established must not re-issue the request");
            assertEquals(1, chunks.size(),
                "the chunk already handed to the consumer must not be delivered a second time");
            assertEquals("first half of the answer", chunks.get(0).content());
        });
    }

    @Test
    void testMockServerToolResponseSerializedAsUserRole() throws Exception {
        AtomicReference<String> receivedBody = new AtomicReference<>();

        withMockServer(exchange -> {
            receivedBody.set(readBody(exchange));
            respondJson(exchange, 200, """
                {"candidates":[{"content":{"parts":[{"text":"Ok"}]}}]}""");
        }, client -> {
            LlmMessage toolMsg =
                new LlmMessage("tool", "the result", null, null, "call_1", "search_kb");
            LlmRequest request = new LlmRequest("gemini-1.5-flash",
                List.of(new LlmMessage("user", "Hi"), toolMsg), 0.0, 100, List.of());
            client.generate(request);

            String body = receivedBody.get();
            assertTrue(body.contains("functionResponse"),
                "tool message must serialize as a functionResponse part");
            assertTrue(body.contains("search_kb"),
                "functionResponse must carry the real tool name, not the synthetic call id");
            assertFalse(body.contains("\"role\":\"function\""),
                "tool turns must use role 'user', not the non-standard 'function' role");
        });
    }

    @Test
    void testMockServerToolDeclarationPreservesRawSchema() throws Exception {
        AtomicReference<String> receivedBody = new AtomicReference<>();

        withMockServer(exchange -> {
            receivedBody.set(readBody(exchange));
            respondJson(exchange, 200, """
                {"candidates":[{"content":{"parts":[{"text":"Ok"}]}}]}""");
        }, client -> {
            LlmTool tool =
                new LlmTool("search_kb", "search the KB", Map.of("type", "object", "properties",
                    Map.of("query", Map.of("type", "string")), "additionalProperties", false));
            LlmRequest request = new LlmRequest("gemini-1.5-flash",
                List.of(new LlmMessage("user", "Hi")), 0.0, 100, List.of(tool));
            client.generate(request);

            String body = receivedBody.get();
            assertTrue(body.contains("search_kb"), "tool declaration must be sent");
            assertTrue(body.contains("additionalProperties"),
                "raw JSON schema must be preserved on the wire (parametersJsonSchema, lossless)");
        });
    }

    @Test
    void testGenerateHandlesMalformedMessageInputs() throws Exception {
        withMockServer(exchange -> respondJson(exchange, 200, """
            {"candidates":[{"content":{"parts":[{"text":"Ok"}]}}]}"""), client -> {
            LlmToolCall invalidTc =
                new LlmToolCall("call_1", "function", "my_tool", "{invalid-json}", null);

            // 1. Invalid tool-call JSON inside a part: client logs & drops the call; request
            // proceeds.
            LlmMessage msg1 = new LlmMessage("user", null,
                List.of(new LlmPart("function_call", null, invalidTc, null)), null, null, null);
            assertEquals("Ok",
                client
                    .generate(
                        new LlmRequest("gemini-1.5-flash", List.of(msg1), 0.0, 100, List.of()))
                    .content());

            // 2. Invalid tool-call JSON with nothing else: contents end up empty -> guard throws.
            LlmMessage msg2 = new LlmMessage("user", null, null, List.of(invalidTc), null, null);
            assertThrows(IllegalArgumentException.class, () -> client
                .generate(new LlmRequest("gemini-1.5-flash", List.of(msg2), 0.0, 100, List.of())));

            // 3. Invalid base64 thought signature: logged & skipped; request still succeeds.
            LlmMessage msg3 = new LlmMessage("user", null,
                List.of(new LlmPart("text", "Hello", null, "!!!invalid-base64!!!")), null, null,
                null);
            assertEquals("Ok",
                client
                    .generate(
                        new LlmRequest("gemini-1.5-flash", List.of(msg3), 0.0, 100, List.of()))
                    .content());
        });
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    /** A test body that receives a ready, base-URL-bound client and may throw. */
    @FunctionalInterface
    private interface ClientCallback {
        void accept(GeminiLlmClient client) throws Exception;
    }

    /** Builds config through a throwaway client, ensuring the client is always closed. */
    private static GenerateContentConfig buildConfig(boolean enableThinking, String thinkingLevel,
        LlmRequest request) {
        try (GeminiLlmClient client =
            new GeminiLlmClient("dummy-key", new ObjectMapper(), enableThinking, thinkingLevel)) {
            return client.buildConfig(request);
        }
    }

    /**
     * Starts an ephemeral-port mock HTTP server with the given handler, runs the test body against
     * a client pointed at it, and guarantees both the client and the server are closed afterwards.
     */
    private static final Logger log = LoggerFactory.getLogger(GeminiLlmClientTest.class);

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
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            try (GeminiLlmClient client =
                new GeminiLlmClient("mock-api-key", new ObjectMapper(), false, null, baseUrl)) {
                test.accept(client);
            }
        } finally {
            server.stop(0);
        }
    }

    private static LlmRequest userRequest(String text) {
        return new LlmRequest("gemini-1.5-flash", List.of(new LlmMessage("user", text)), 0.0, 100,
            List.of());
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

    private static void respondJson(HttpExchange exchange, int status, String json)
        throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** Writes each JSON payload as one {@code data: ...} SSE event. */
    private static void respondSse(HttpExchange exchange, String... jsonPayloads)
        throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream os = exchange.getResponseBody()) {
            for (String payload : jsonPayloads) {
                os.write(("data: " + payload.strip() + "\n\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
        }
    }
}
