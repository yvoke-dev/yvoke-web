package de.palsoftware.yvoke.llm.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Unit coverage for {@link OpenRouterLlmClient} (MNT-12): request building (role mapping, tool
 * declarations, reasoning + stream options), response/usage parsing (incl. the cached- and
 * reasoning-token details dug out of the raw usage JSON), streaming chunk decoding (content,
 * reasoning, tool-call deltas, usage) and error mapping. The client speaks the OpenAI Chat
 * Completions wire, so — as with {@code GeminiLlmClientTest} — these run against an ephemeral mock
 * HTTP server bound to the client's {@code baseUrl}, exercising the real SDK serialization path.
 *
 * <p>
 * The retry <em>policy</em> itself is covered by {@code LlmRetryTest}; here we only assert the
 * client's own contribution — that a non-transient failure surfaces as a {@code RuntimeException}
 * rather than a checked exception or a silent null.
 */
@Timeout(60)
class OpenRouterLlmClientTest {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterLlmClientTest.class);

    // ------------------------------------------------------------------------
    // Request building
    // ------------------------------------------------------------------------

    @Test
    void testGenerateBuildsRequestWithRolesToolsAndReasoning() throws Exception {
        AtomicReference<String> receivedPath = new AtomicReference<>();
        AtomicReference<String> receivedBody = new AtomicReference<>();

        withMockServer(exchange -> {
            receivedPath.set(exchange.getRequestURI().toString());
            receivedBody.set(readBody(exchange));
            respondJson(exchange, 200, completionJson("ok"));
        }, client -> {
            LlmTool tool = new LlmTool("search_kb", "search the KB",
                Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string"))));
            LlmRequest request = new LlmRequest("deepseek/deepseek-v4.1-flash", List
                .of(new LlmMessage("system", "be brief"), new LlmMessage("user", "hello there")),
                0.4, 256, List.of(tool));
            client.generate(request);

            assertTrue(receivedPath.get().contains("/chat/completions"),
                "must POST to the chat completions endpoint");
            String body = receivedBody.get();
            assertTrue(body.contains("deepseek/deepseek-v4.1-flash"), "model must be serialized");
            assertTrue(body.contains("temperature"), "temperature must be serialized");
            assertTrue(body.contains("max_completion_tokens"), "max tokens must be serialized");
            assertTrue(body.contains("be brief"), "system message must be present");
            assertTrue(body.contains("hello there"), "user message must be present");
            assertTrue(body.contains("system") && body.contains("user"),
                "both roles must be mapped");
            assertTrue(body.contains("search_kb"), "the function tool must be declared");
            assertTrue(body.contains("include_reasoning"),
                "OpenRouter reasoning must be requested via include_reasoning");
        });
    }

    @Test
    void testGenerateStreamSetsStreamOptionsIncludeUsage() throws Exception {
        AtomicReference<String> receivedBody = new AtomicReference<>();

        withMockServer(exchange -> {
            receivedBody.set(readBody(exchange));
            respondSse(exchange, chunkJson("hi", null, null, false), finalChunkJson(3, 1, 4));
        }, client -> {
            client.generateStream(userRequest("hello"), c -> {
            });

            String body = receivedBody.get();
            assertTrue(body.contains("\"stream\":true") || body.contains("\"stream\": true"),
                "a streaming call must set stream=true");
            assertTrue(body.contains("stream_options") && body.contains("include_usage"),
                "streaming must opt into usage via stream_options.include_usage");
        });
    }

    // ------------------------------------------------------------------------
    // Response / usage parsing
    // ------------------------------------------------------------------------

    @Test
    void testGenerateParsesContentAndUsageDetails() throws Exception {
        withMockServer(exchange -> respondJson(exchange, 200, """
            {
              "id": "chatcmpl-1", "object": "chat.completion", "created": 1,
              "model": "deepseek/deepseek-chat",
              "choices": [
                {"index": 0, "message": {"role": "assistant", "content": "Hello from OpenRouter"},
                 "finish_reason": "stop"}
              ],
              "usage": {
                "prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15,
                "prompt_tokens_details": {"cached_tokens": 4},
                "completion_tokens_details": {"reasoning_tokens": 3}
              }
            }
            """), client -> {
            LlmResponse response = client.generate(userRequest("hi"));

            assertNotNull(response);
            assertEquals("Hello from OpenRouter", response.content());
            assertEquals(10, response.usage().promptTokens());
            assertEquals(5, response.usage().completionTokens());
            assertEquals(15, response.usage().totalTokens());
            assertEquals(4, response.usage().cachedTokens(),
                "cached_tokens must be dug out of prompt_tokens_details");
            assertEquals(3, response.usage().thoughtTokens(),
                "reasoning_tokens must be dug out of completion_tokens_details");
        });
    }

    @Test
    void testGenerateToleratesMissingUsageDetails() throws Exception {
        withMockServer(exchange -> respondJson(exchange, 200, completionJson("plain answer")),
            client -> {
                LlmResponse response = client.generate(userRequest("hi"));

                assertEquals("plain answer", response.content());
                assertEquals(0, response.usage().cachedTokens(),
                    "absent prompt_tokens_details must default cached tokens to 0, not fail");
                assertEquals(0, response.usage().thoughtTokens());
            });
    }

    // ------------------------------------------------------------------------
    // Streaming
    // ------------------------------------------------------------------------

    @Test
    void testGenerateStreamParsesContentChunksAndUsage() throws Exception {
        withMockServer(exchange -> respondSse(exchange, chunkJson("Hello ", null, null, false),
            chunkJson("world!", null, null, false), finalChunkJson(5, 2, 7)), client -> {
                List<LlmResponseChunk> chunks = new ArrayList<>();
                client.generateStream(userRequest("hi"), chunks::add);

                String text = chunks.stream().map(LlmResponseChunk::content).filter(c -> c != null)
                    .reduce("", String::concat);
                assertEquals("Hello world!", text, "content deltas must be delivered in order");

                assertFalse(chunks.isEmpty(), "at least one chunk must be delivered");
                assertEquals(7,
                    chunks.stream().map(LlmResponseChunk::usage).filter(u -> u != null)
                        .reduce((a, b) -> b).orElseThrow().totalTokens(),
                    "the final chunk's usage must be parsed");
            });
    }

    @Test
    void testGenerateStreamParsesReasoningContent() throws Exception {
        withMockServer(exchange -> respondSse(exchange,
            chunkJson(null, "thinking hard", null, false), chunkJson("answer", null, null, false)),
            client -> {
                List<LlmResponseChunk> chunks = new ArrayList<>();
                client.generateStream(userRequest("hi"), chunks::add);

                String reasoning = chunks.stream().map(LlmResponseChunk::reasoning)
                    .filter(r -> r != null).reduce("", String::concat);
                assertEquals("thinking hard", reasoning,
                    "deepseek-style reasoning_content must be extracted from the delta");
            });
    }

    @Test
    void testGenerateStreamParsesToolCallDeltas() throws Exception {
        withMockServer(exchange -> respondSse(exchange, chunkJson(null, null, "search_kb", true)),
            client -> {
                List<LlmResponseChunk> chunks = new ArrayList<>();
                client.generateStream(userRequest("hi"), chunks::add);

                LlmResponseChunk withTool = chunks.stream()
                    .filter(c -> c.toolCallDeltas() != null && !c.toolCallDeltas().isEmpty())
                    .findFirst().orElseThrow();
                assertEquals(1, withTool.toolCallDeltas().size());
                assertEquals("search_kb", withTool.toolCallDeltas().get(0).name());
                assertEquals("call_1", withTool.toolCallDeltas().get(0).id());
                assertTrue(withTool.toolCallDeltas().get(0).argumentsDelta().contains("query"),
                    "argument fragments must be carried through");
            });
    }

    // ------------------------------------------------------------------------
    // Error mapping
    // ------------------------------------------------------------------------

    @Test
    void testNonTransientErrorMapsToRuntimeException() throws Exception {
        AtomicInteger attempts = new AtomicInteger(0);
        withMockServer(exchange -> {
            attempts.incrementAndGet();
            respondJson(exchange, 400,
                "{\"error\":{\"message\":\"bad request\",\"type\":\"invalid_request_error\"}}");
        }, client -> assertThrows(RuntimeException.class,
            () -> client.generate(userRequest("hi"))));
        assertTrue(attempts.get() >= 1, "the client must have attempted the call");
    }

    // ------------------------------------------------------------------------
    // Wave 1 Client Hardening Tests
    // ------------------------------------------------------------------------

    @Test
    void establishmentRetriesOnTransient503AndSucceeds() throws Exception {
        AtomicInteger attempts = new AtomicInteger(0);
        withMockServer(exchange -> {
            if (attempts.incrementAndGet() < 3) {
                respondJson(exchange, 503,
                    "{\"error\":{\"message\":\"service unavailable\",\"code\":503}}");
            } else {
                respondSse(exchange, chunkJson("recovered", null, null, false),
                    finalChunkJson(5, 2, 7));
            }
        }, client -> {
            List<LlmResponseChunk> chunks = new ArrayList<>();
            client.generateStream(userRequest("hi"), chunks::add);

            assertEquals(3, attempts.get(), "must retry establishment exactly 3 times");
            String content = chunks.stream().map(LlmResponseChunk::content).filter(Objects::nonNull)
                .reduce("", String::concat);
            assertEquals("recovered", content);
        });
    }

    @Test
    void establishmentExhaustionThrowsLlmCallFailedException() throws Exception {
        AtomicInteger attempts = new AtomicInteger(0);
        withMockServer(exchange -> {
            attempts.incrementAndGet();
            respondJson(exchange, 503,
                "{\"error\":{\"message\":\"service unavailable\",\"code\":503}}");
        }, client -> {
            Exception ex =
                assertThrows(Exception.class, () -> client.generateStream(userRequest("hi"), c -> {
                }));
            assertEquals(3, attempts.get(), "must attempt establishment 3 times before exhausting");
            assertTrue(ex.toString().contains("503")
                || (ex.getCause() != null && ex.getCause().toString().contains("503")));
        });
    }

    @Test
    void midStreamDisconnectDoesNotRetry() throws Exception {
        AtomicInteger attempts = new AtomicInteger(0);
        withMockServer(exchange -> {
            attempts.incrementAndGet();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 1000);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(("data: " + chunkJson("first half", null, null, false).replace("\n", "")
                    + "\n\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
                // Abruptly close stream without delivering all 1000 bytes or [DONE] sentinel
            }
        }, client -> {
            List<LlmResponseChunk> chunks = new ArrayList<>();
            assertThrows(Exception.class,
                () -> client.generateStream(userRequest("hi"), chunks::add));
            assertEquals(1, attempts.get(), "mid-stream failures must never be retried");
            assertFalse(chunks.isEmpty(), "chunk emitted before disconnect must be delivered");
            assertEquals("first half", chunks.get(0).content());
        });
    }

    @Test
    void truncationFinishReasonDeliversPartialContentWithoutThrowing() throws Exception {
        withMockServer(exchange -> respondSse(exchange,
            """
                {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,
                 "model":"deepseek/deepseek-chat",
                 "choices":[{"index":0,"delta":{"content":"truncated text"},"finish_reason":"length"}]}""",
            """
                {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,
                 "model":"deepseek/deepseek-chat",
                 "choices":[{"index":0,"delta":{},"finish_reason":null}],
                 "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}"""),
            client -> {
                List<LlmResponseChunk> chunks = new ArrayList<>();
                client.generateStream(userRequest("hi"), chunks::add);

                String text = chunks.stream().map(LlmResponseChunk::content)
                    .filter(Objects::nonNull).reduce("", String::concat);
                assertEquals("truncated text", text);
                List<LlmResponseChunk> markers =
                    chunks.stream().filter(LlmResponseChunk::endOfCall).toList();
                assertEquals(1, markers.size(), "must emit endOfCall marker");
                assertNotNull(markers.get(0).usage());
                assertEquals(15, markers.get(0).usage().totalTokens());
            });
    }

    @Test
    void emptyTurnRecoversOnSecondAttemptAndEmitsBothEndOfCallMarkers() throws Exception {
        AtomicInteger attempts = new AtomicInteger(0);
        withMockServer(exchange -> {
            if (attempts.incrementAndGet() == 1) {
                respondSse(exchange, """
                    {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,
                     "model":"deepseek/deepseek-chat",
                     "choices":[{"index":0,"delta":{},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":10,"completion_tokens":0,"total_tokens":10}}""");
            } else {
                respondSse(exchange, chunkJson("finally answered", null, null, false), """
                    {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,
                     "model":"deepseek/deepseek-chat",
                     "choices":[{"index":0,"delta":{},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}""");
            }
        }, client -> {
            List<LlmResponseChunk> chunks = new ArrayList<>();
            client.generateStream(userRequest("hi"), chunks::add);

            assertEquals(2, attempts.get(), "clean empty turn must be re-requested once");
            List<LlmResponseChunk> markers =
                chunks.stream().filter(LlmResponseChunk::endOfCall).toList();
            assertEquals(2, markers.size(), "must emit an endOfCall marker for each attempt");
            assertEquals(10, markers.get(0).usage().totalTokens());
            assertEquals(15, markers.get(1).usage().totalTokens());
            String text = chunks.stream().map(LlmResponseChunk::content).filter(Objects::nonNull)
                .reduce("", String::concat);
            assertEquals("finally answered", text);
        });
    }

    @Test
    void emptyTurnExhaustionThrowsAndEmitsBothEndOfCallMarkers() throws Exception {
        AtomicInteger attempts = new AtomicInteger(0);
        withMockServer(exchange -> {
            attempts.incrementAndGet();
            respondSse(exchange, """
                {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,
                 "model":"deepseek/deepseek-chat",
                 "choices":[{"index":0,"delta":{},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":10,"completion_tokens":0,"total_tokens":10}}""");
        }, client -> {
            List<LlmResponseChunk> chunks = new ArrayList<>();
            LlmCallFailedException ex = assertThrows(LlmCallFailedException.class,
                () -> client.generateStream(userRequest("hi"), chunks::add));

            assertEquals(2, attempts.get(), "must exhaust after 2 attempts");
            List<LlmResponseChunk> markers =
                chunks.stream().filter(LlmResponseChunk::endOfCall).toList();
            assertEquals(2, markers.size(), "must emit both endOfCall markers");
            assertNotNull(ex.usage());
            assertEquals(10, ex.usage().totalTokens());
        });
    }

    @Test
    void contentFilterEmitsEndOfCallBeforeThrowingWithUsage() throws Exception {
        withMockServer(exchange -> respondSse(exchange,
            """
                {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,
                 "model":"deepseek/deepseek-chat",
                 "choices":[{"index":0,"delta":{"content":"blocked content"},"finish_reason":"content_filter"}]}""",
            """
                {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,
                 "model":"deepseek/deepseek-chat",
                 "choices":[],
                 "usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12}}"""),
            client -> {
                List<LlmResponseChunk> chunks = new ArrayList<>();
                LlmCallFailedException ex = assertThrows(LlmCallFailedException.class,
                    () -> client.generateStream(userRequest("hi"), chunks::add));

                List<LlmResponseChunk> markers =
                    chunks.stream().filter(LlmResponseChunk::endOfCall).toList();
                assertEquals(1, markers.size(),
                    "endOfCall must be received by consumer before exception is thrown");
                assertEquals(12, markers.get(0).usage().totalTokens());
                assertNotNull(ex.usage());
                assertEquals(12, ex.usage().totalTokens());
            });
    }

    @Test
    void refusalDoesNotTriggerEmptyTurnRetry() throws Exception {
        AtomicInteger attempts = new AtomicInteger(0);
        withMockServer(exchange -> {
            attempts.incrementAndGet();
            respondSse(exchange,
                """
                    {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,
                     "model":"deepseek/deepseek-chat",
                     "choices":[{"index":0,"delta":{"refusal":"I cannot fulfill this request"},"finish_reason":null}]}""",
                finalChunkJson(10, 5, 15));
        }, client -> {
            List<LlmResponseChunk> chunks = new ArrayList<>();
            client.generateStream(userRequest("hi"), chunks::add);

            assertEquals(1, attempts.get(), "refusal must not trigger empty turn retry");
            String text = chunks.stream().map(LlmResponseChunk::content).filter(Objects::nonNull)
                .reduce("", String::concat);
            assertEquals("I cannot fulfill this request", text);
        });
    }

    @Test
    void deepseekV4FlashSetsReasoningEffortAndRetainsTemperature() throws Exception {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        withMockServer(exchange -> {
            receivedBody.set(readBody(exchange));
            respondJson(exchange, 200, completionJson("ok"));
        }, client -> {
            LlmRequest request = new LlmRequest("deepseek/deepseek-v4.1-flash",
                List.of(new LlmMessage("user", "hello")), 0.7, 256, List.of());
            client.generate(request);

            String body = receivedBody.get();
            assertTrue(body.contains("include_reasoning"), "include_reasoning must be true");
            assertTrue(body.contains("\"reasoning\"") && body.contains("\"effort\":\"high\""),
                "reasoning.effort must be high");
            assertTrue(
                body.contains("\"temperature\":0.7") || body.contains("\"temperature\": 0.7"),
                "temperature must be retained for DeepSeek V4.1 Flash");
        });
    }

    @Test
    void openaiO3MiniOmitsTemperatureAndPassesReasoningEffort() throws Exception {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        withMockServer(exchange -> {
            receivedBody.set(readBody(exchange));
            respondJson(exchange, 200, completionJson("ok"));
        }, client -> {
            LlmRequest request = new LlmRequest("openai/o3-mini",
                List.of(new LlmMessage("user", "hello")), 0.7, 256, List.of());
            client.generate(request);

            String body = receivedBody.get();
            assertFalse(body.contains("temperature"),
                "temperature must be omitted for openai/o3-mini");
            assertTrue(body.contains("include_reasoning"), "include_reasoning must be true");
            assertTrue(body.contains("\"reasoning\"") && body.contains("\"effort\":\"high\""),
                "reasoning.effort must be high");
        });
    }

    @Test
    void missingOrPlaceholderBaseUrlDefaultsSafely() {
        assertEquals("https://openrouter.ai/api/v1", OpenRouterLlmClient.resolveBaseUrl(null));
        assertEquals("https://openrouter.ai/api/v1", OpenRouterLlmClient.resolveBaseUrl(""));
        assertEquals("https://openrouter.ai/api/v1", OpenRouterLlmClient.resolveBaseUrl("   "));
        assertEquals("https://openrouter.ai/api/v1",
            OpenRouterLlmClient.resolveBaseUrl("placeholder-openrouter-url"));
        assertEquals("https://openrouter.ai/api/v1",
            OpenRouterLlmClient.resolveBaseUrl("https://openrouter.ai/api/v1/"));
        assertEquals("http://127.0.0.1:8080",
            OpenRouterLlmClient.resolveBaseUrl("http://127.0.0.1:8080/"));
    }

    @Test
    void attributionHeadersAreSent() throws Exception {
        AtomicReference<Map<String, List<String>>> headersRef = new AtomicReference<>();
        withMockServer(exchange -> {
            headersRef.set(exchange.getRequestHeaders());
            respondJson(exchange, 200, completionJson("ok"));
        }, client -> {
            client.generate(userRequest("hello"));

            Map<String, List<String>> headers = headersRef.get();
            assertTrue(headers.containsKey("Http-referer") || headers.containsKey("HTTP-Referer"),
                "HTTP-Referer header must be present");
            assertTrue(headers.containsKey("X-title") || headers.containsKey("X-Title"),
                "X-Title header must be present");
            String referer = headers
                .getOrDefault("Http-referer", headers.getOrDefault("HTTP-Referer", List.of()))
                .get(0);
            String title =
                headers.getOrDefault("X-title", headers.getOrDefault("X-Title", List.of())).get(0);
            assertEquals("https://app.yvoke.dev", referer);
            assertEquals("Yvoke", title);
        });
    }

    @Test
    void noUserOrConversationIdForwardedUpstream() throws Exception {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        withMockServer(exchange -> {
            receivedBody.set(readBody(exchange));
            respondJson(exchange, 200, completionJson("ok"));
        }, client -> {
            client.generate(userRequest("hello"));
            String body = receivedBody.get();
            assertFalse(body.contains("\"user\":"), "user field must not be sent upstream");
        });
    }

    @Test
    void structuredOutputsSuppressedWhenToolsPresent() throws Exception {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        withMockServer(exchange -> {
            receivedBody.set(readBody(exchange));
            respondJson(exchange, 200, completionJson("ok"));
        }, client -> {
            LlmTool tool = new LlmTool("test_tool", "desc", Map.of("type", "object"));
            LlmRequest request = new LlmRequest("deepseek/deepseek-chat",
                List.of(new LlmMessage("user", "hello")), 0.0, 100, List.of(tool), null,
                "application/json", Map.of("type", "object"), null);
            client.generate(request);

            String body = receivedBody.get();
            assertTrue(body.contains("test_tool"), "tools must be present");
            assertFalse(body.contains("response_format"),
                "response_format must be suppressed when tools present");
        });
    }

    @Test
    void structuredOutputsEmittedWhenToolsAbsent() throws Exception {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        withMockServer(exchange -> {
            receivedBody.set(readBody(exchange));
            respondJson(exchange, 200, completionJson("ok"));
        }, client -> {
            LlmRequest request =
                new LlmRequest("deepseek/deepseek-chat", List.of(new LlmMessage("user", "hello")),
                    0.0, 100, List.of(), null, "application/json", Map.of("type", "object"), null);
            client.generate(request);

            String body = receivedBody.get();
            assertTrue(body.contains("response_format"),
                "response_format must be present when tools absent");
        });
    }

    @Test
    void seedIsForwardedWhenPresent() throws Exception {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        withMockServer(exchange -> {
            receivedBody.set(readBody(exchange));
            respondJson(exchange, 200, completionJson("ok"));
        }, client -> {
            LlmRequest request =
                new LlmRequest("deepseek/deepseek-chat", List.of(new LlmMessage("user", "hello")),
                    0.0, 100, List.of(), null, null, null, 42);
            client.generate(request);

            String body = receivedBody.get();
            assertTrue(body.contains("\"seed\":42") || body.contains("\"seed\": 42"),
                "seed must be forwarded");
        });
    }

    @Test
    void closedClientRejectsSubsequentCalls() throws Exception {
        withMockServer(exchange -> respondJson(exchange, 200, completionJson("ok")), client -> {
            client.close();
            assertThrows(IllegalStateException.class, () -> client.generate(userRequest("hi")),
                "generate must reject when closed");
            assertThrows(IllegalStateException.class,
                () -> client.generateStream(userRequest("hi"), c -> {
                }), "generateStream must reject when closed");
        });
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    @FunctionalInterface
    private interface ClientCallback {
        void accept(OpenRouterLlmClient client) throws Exception;
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
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            OpenRouterLlmClient client =
                new OpenRouterLlmClient(baseUrl, "mock-api-key", new ObjectMapper());
            test.accept(client);
        } finally {
            server.stop(0);
        }
    }

    private static LlmRequest userRequest(String text) {
        return new LlmRequest("deepseek/deepseek-chat", List.of(new LlmMessage("user", text)), 0.0,
            100, List.of());
    }

    /** A minimal, valid OpenAI ChatCompletion body carrying the given assistant content. */
    private static String completionJson(String content) {
        return """
            {
              "id": "chatcmpl-1", "object": "chat.completion", "created": 1,
              "model": "deepseek/deepseek-chat",
              "choices": [
                {"index": 0, "message": {"role": "assistant", "content": "%s"},
                 "finish_reason": "stop"}
              ],
              "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}
            }
            """.formatted(content);
    }

    /**
     * One streaming chunk. Supply at most one of content / reasoning / toolName; when {@code
     * toolName} is set a single function tool-call delta (id {@code call_1}) is emitted.
     */
    private static String chunkJson(String content, String reasoning, String toolName,
        boolean tool) {
        String delta;
        if (toolName != null) {
            delta = """
                {"tool_calls":[{"index":0,"id":"call_1","type":"function",
                 "function":{"name":"%s","arguments":"{\\"query\\":\\"x\\"}"}}]}"""
                .formatted(toolName);
        } else if (reasoning != null) {
            delta = "{\"reasoning_content\":\"%s\"}".formatted(reasoning);
        } else if (content != null) {
            delta = "{\"content\":\"%s\"}".formatted(content);
        } else {
            delta = "{}";
        }
        return """
            {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,
             "model":"deepseek/deepseek-chat",
             "choices":[{"index":0,"delta":%s,"finish_reason":null}]}""".formatted(delta);
    }

    private static String finalChunkJson(int prompt, int completion, int total) {
        return """
            {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,
             "model":"deepseek/deepseek-chat",
             "choices":[{"index":0,"delta":{},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":%d,"completion_tokens":%d,"total_tokens":%d}}"""
            .formatted(prompt, completion, total);
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

    /** Writes each JSON payload as one {@code data: ...} SSE event, then the [DONE] sentinel. */
    private static void respondSse(HttpExchange exchange, String... jsonPayloads)
        throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream os = exchange.getResponseBody()) {
            for (String payload : jsonPayloads) {
                os.write(("data: " + payload.replace("\n", "").strip() + "\n\n")
                    .getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
        }
    }
}
