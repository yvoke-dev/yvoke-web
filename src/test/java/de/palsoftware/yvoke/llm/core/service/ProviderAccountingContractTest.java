package de.palsoftware.yvoke.llm.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.azure.credential.AzureApiKeyCredential;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import de.palsoftware.yvoke.llm.core.context.LlmCallContextHolder;
import de.palsoftware.yvoke.llm.core.event.LlmCallLoggedEvent;
import de.palsoftware.yvoke.llm.core.model.LlmCallFailedException;
import de.palsoftware.yvoke.llm.core.model.LlmMessage;
import de.palsoftware.yvoke.llm.core.model.LlmRequest;
import de.palsoftware.yvoke.llm.core.model.LlmResponseChunk;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * End-to-end contract tests running EVERY real provider client ({@link GeminiLlmClient}, {@link
 * AzureOpenAiResponsesLlmClient}, {@link OpenRouterLlmClient}) wrapped in the real {@link
 * AccountingLlmClient} against an ephemeral mock HTTP server.
 *
 * <p>Testing each side only against a mock of the other hid the Gemini blocked-stream billing bug
 * (Gemini threw before {@code endOfCall}, and {@code AccountingLlmClient.generateStream} never read
 * {@code LlmCallFailedException.usage()}) as well as severed partial streams returning silently as
 * complete answers. Running the real pair together pins both sides of the contract at once.
 */
@Timeout(60)
class ProviderAccountingContractTest {

    private final List<LlmCallLoggedEvent> ledger = new ArrayList<>();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        LlmCallContextHolder.clear();
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    // ---------------------------------------------------------------- Gemini

    @Test
    void geminiNormalStreamRecordsOneLedgerRowAndDeliversAnswer() throws Exception {
        String baseUrl =
                startServer(exchange ->
                        respondSseRaw(
                                exchange,
                                false,
                                """
                                {"candidates":[{"content":{"parts":[{"text":"Step 1."}],"role":"model"},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":100,"candidatesTokenCount":15,"totalTokenCount":115}}
                                """));

        try (GeminiLlmClient raw =
                new GeminiLlmClient("test-key", new ObjectMapper(), false, "low", baseUrl)) {
            AccountingLlmClient client = accounted(raw);
            List<LlmResponseChunk> chunks = new ArrayList<>();
            client.generateStream(request("gemini-2.5-flash"), chunks::add);

            assertThat(chunks).noneMatch(LlmResponseChunk::endOfCall);
            assertThat(joinedContent(chunks)).isEqualTo("Step 1.");
            assertThat(ledger).hasSize(1);
            assertThat(ledger.get(0).promptTokens()).isEqualTo(100);
            assertThat(ledger.get(0).totalTokens()).isEqualTo(115);
        }
    }

    @Test
    void geminiSingleChunkSafetyBlockRecordsExactBilledUsageOnce() throws Exception {
        String baseUrl =
                startServer(exchange ->
                        respondSseRaw(
                                exchange,
                                false,
                                """
                                {"candidates":[{"content":{"parts":[{"text":"blocked"}],"role":"model"},"finishReason":"SAFETY"}],"usageMetadata":{"promptTokenCount":216667,"candidatesTokenCount":40,"totalTokenCount":216707,"thoughtsTokenCount":12}}
                                """));

        try (GeminiLlmClient raw =
                new GeminiLlmClient("test-key", new ObjectMapper(), false, "low", baseUrl)) {
            AccountingLlmClient client = accounted(raw);
            List<LlmResponseChunk> chunks = new ArrayList<>();

            assertThatThrownBy(
                            () -> client.generateStream(request("gemini-2.5-flash"), chunks::add))
                    .isInstanceOf(LlmCallFailedException.class)
                    .hasMessageContaining("SAFETY");

            assertThat(chunks).as("blocked chunk text must not reach the caller").isEmpty();
            assertThat(ledger)
                    .as("a SAFETY-blocked stream must still write exactly one ledger row")
                    .hasSize(1);
            assertThat(ledger.get(0).promptTokens()).isEqualTo(216_667);
            assertThat(ledger.get(0).completionTokens()).isEqualTo(40);
            assertThat(ledger.get(0).totalTokens()).isEqualTo(216_707);
            assertThat(ledger.get(0).thoughtTokens()).isEqualTo(12);
        }
    }

    @Test
    void geminiEmptyTurnRetryRecordsTwoSeparateLedgerRows() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String baseUrl =
                startServer(exchange ->
                        respondSseRaw(
                                exchange,
                                false,
                                calls.incrementAndGet() == 1
                                        ? """
                                        {"candidates":[{"content":{"parts":[{"thought":true,"text":"hmm"}],"role":"model"},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":137,"candidatesTokenCount":50,"totalTokenCount":187,"thoughtsTokenCount":50}}
                                        """
                                        : """
                                        {"candidates":[{"content":{"parts":[{"text":"Final answer"}],"role":"model"},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":137,"candidatesTokenCount":10,"totalTokenCount":147}}
                                        """));

        try (GeminiLlmClient raw =
                new GeminiLlmClient("test-key", new ObjectMapper(), true, "low", baseUrl)) {
            AccountingLlmClient client = accounted(raw);
            List<LlmResponseChunk> chunks = new ArrayList<>();
            client.generateStream(request("gemini-2.5-flash"), chunks::add);

            assertThat(calls.get()).isEqualTo(2);
            assertThat(joinedContent(chunks)).isEqualTo("Final answer");
            assertThat(ledger).hasSize(2);
            assertThat(ledger.get(0).totalTokens()).isEqualTo(187);
            assertThat(ledger.get(1).totalTokens()).isEqualTo(147);
        }
    }

    @Test
    void geminiSeveredStreamWithPartialContentFailsWithoutRetryAndBillsObservedUsage()
            throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String baseUrl =
                startServer(exchange -> {
                    calls.incrementAndGet();
                    respondSseRaw(
                            exchange,
                            false,
                            """
                            {"candidates":[{"content":{"parts":[{"text":"Step 1: open the conso"}],"role":"model"}}],"usageMetadata":{"promptTokenCount":80,"candidatesTokenCount":6,"totalTokenCount":86}}
                            """);
                });

        try (GeminiLlmClient raw =
                new GeminiLlmClient("test-key", new ObjectMapper(), false, "low", baseUrl)) {
            AccountingLlmClient client = accounted(raw);
            List<LlmResponseChunk> chunks = new ArrayList<>();

            assertThatThrownBy(
                            () -> client.generateStream(request("gemini-2.5-flash"), chunks::add))
                    .isInstanceOf(LlmCallFailedException.class)
                    .hasMessageContaining("without a terminal event");

            assertThat(calls.get()).isEqualTo(1);
            assertThat(joinedContent(chunks)).isEqualTo("Step 1: open the conso");
            assertThat(ledger).hasSize(1);
            assertThat(ledger.get(0).totalTokens()).isEqualTo(86);
        }
    }

    @Test
    void geminiMaxTokensTruncationWithContentStaysBenignAndBillsOnce() throws Exception {
        String baseUrl =
                startServer(exchange ->
                        respondSseRaw(
                                exchange,
                                false,
                                """
                                {"candidates":[{"content":{"parts":[{"text":"Truncated answer"}],"role":"model"},"finishReason":"MAX_TOKENS"}],"usageMetadata":{"promptTokenCount":50,"candidatesTokenCount":100,"totalTokenCount":150}}
                                """));

        try (GeminiLlmClient raw =
                new GeminiLlmClient("test-key", new ObjectMapper(), false, "low", baseUrl)) {
            AccountingLlmClient client = accounted(raw);
            List<LlmResponseChunk> chunks = new ArrayList<>();
            client.generateStream(request("gemini-2.5-flash"), chunks::add);

            assertThat(joinedContent(chunks)).isEqualTo("Truncated answer");
            assertThat(ledger).hasSize(1);
            assertThat(ledger.get(0).totalTokens()).isEqualTo(150);
        }
    }

    // --------------------------------------------------- Azure OpenAI Responses

    @Test
    void azureResponsesLifecycleRecordsAccurateLedgerRowsAcrossOutcomes() throws Exception {
        String normalSse =
                """
                event: response.output_text.delta
                data: {"type":"response.output_text.delta","delta":"Azure ok","item_id":"m1","output_index":0,"content_index":0}

                event: response.completed
                data: {"type":"response.completed","response":{"id":"r1","object":"response","status":"completed","model":"gpt-5.6-luna","output":[],"usage":{"input_tokens":100,"output_tokens":20,"total_tokens":120,"input_tokens_details":{"cached_tokens":10},"output_tokens_details":{"reasoning_tokens":5}}}}

                """;
        String baseUrl = startServer(exchange -> respondRawSseString(exchange, normalSse));

        try (AzureOpenAiResponsesLlmClient raw =
                new AzureOpenAiResponsesLlmClient(baseUrl, "test-key", true, "high", "")) {
            AccountingLlmClient client = accounted(raw);
            List<LlmResponseChunk> chunks = new ArrayList<>();
            client.generateStream(request("gpt-5.6-luna"), chunks::add);

            assertThat(joinedContent(chunks)).isEqualTo("Azure ok");
            assertThat(ledger).hasSize(1);
            assertThat(ledger.get(0).totalTokens()).isEqualTo(120);
            assertThat(ledger.get(0).cachedTokens()).isEqualTo(10);
            assertThat(ledger.get(0).thoughtTokens()).isEqualTo(5);
        }
    }

    @Test
    void azureResponsesFailedStreamRecordsBilledUsageOnce() throws Exception {
        String failedSse =
                """
                event: response.failed
                data: {"type":"response.failed","response":{"id":"r1","object":"response","status":"failed","model":"gpt-5.6-luna","error":{"code":"server_error","message":"boom"},"output":[],"usage":{"input_tokens":1200,"output_tokens":340,"total_tokens":1540}}}

                """;
        String baseUrl = startServer(exchange -> respondRawSseString(exchange, failedSse));

        try (AzureOpenAiResponsesLlmClient raw =
                new AzureOpenAiResponsesLlmClient(baseUrl, "test-key", true, "high", "")) {
            AccountingLlmClient client = accounted(raw);

            assertThatThrownBy(() -> client.generateStream(request("gpt-5.6-luna"), c -> {}))
                    .isInstanceOf(LlmCallFailedException.class);

            assertThat(ledger).hasSize(1);
            assertThat(ledger.get(0).totalTokens()).isEqualTo(1540);
        }
    }

    @Test
    void azureResponsesSeveredStreamWithPartialContentFailsWithoutRetry() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String severedSse =
                """
                event: response.output_text.delta
                data: {"type":"response.output_text.delta","delta":"Step 1: open the conso","item_id":"m1","output_index":0,"content_index":0}

                """;
        String baseUrl =
                startServer(exchange -> {
                    calls.incrementAndGet();
                    respondRawSseString(exchange, severedSse);
                });

        try (AzureOpenAiResponsesLlmClient raw =
                new AzureOpenAiResponsesLlmClient(baseUrl, "test-key", true, "high", "")) {
            AccountingLlmClient client = accounted(raw);
            List<LlmResponseChunk> chunks = new ArrayList<>();

            assertThatThrownBy(() -> client.generateStream(request("gpt-5.6-luna"), chunks::add))
                    .isInstanceOf(LlmCallFailedException.class)
                    .hasMessageContaining("without a terminal event");

            assertThat(calls.get()).isEqualTo(1);
            assertThat(joinedContent(chunks)).isEqualTo("Step 1: open the conso");
            assertThat(ledger).isEmpty();
        }
    }

    @Test
    void azureResponsesStreamSlowerThanReadTimeoutSurvivesWhileChunksKeepArriving()
            throws Exception {
        String baseUrl =
                startServer(exchange -> {
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    try (OutputStream os = exchange.getResponseBody()) {
                        for (String token : List.of("a", "b", "c")) {
                            os.write(
                                    ("event: response.output_text.delta\n"
                                                    + "data: {\"type\":\"response.output_text.delta\",\"delta\":\""
                                                    + token
                                                    + "\",\"item_id\":\"m1\",\"output_index\":0,\"content_index\":0}\n\n")
                                            .getBytes(StandardCharsets.UTF_8));
                            os.flush();
                            sleepQuietly(300);
                        }
                        os.write(
                                ("event: response.completed\n"
                                                + "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"r1\",\"object\":\"response\",\"status\":\"completed\",\"model\":\"gpt-5.6-luna\",\"output\":[],\"usage\":{\"input_tokens\":10,\"output_tokens\":3,\"total_tokens\":13}}}\n\n")
                                        .getBytes(StandardCharsets.UTF_8));
                        os.flush();
                    }
                });

        OpenAIClient shortReadSdkClient =
                OpenAIOkHttpClient.builder()
                        .baseUrl(baseUrl + AzureOpenAiResponsesLlmClient.V1_SUFFIX)
                        .credential(AzureApiKeyCredential.create("test-key"))
                        .maxRetries(0)
                        .timeout(OpenAiHttpTimeouts.forReadTimeout(Duration.ofMillis(700)))
                        .build();
        try (AzureOpenAiResponsesLlmClient raw =
                new AzureOpenAiResponsesLlmClient(shortReadSdkClient, true, "high", "")) {
            AccountingLlmClient client = accounted(raw);
            List<LlmResponseChunk> chunks = new ArrayList<>();
            client.generateStream(request("gpt-5.6-luna"), chunks::add);

            assertThat(joinedContent(chunks)).isEqualTo("abc");
            assertThat(ledger).hasSize(1);
            assertThat(ledger.get(0).totalTokens()).isEqualTo(13);
        }
    }

    // -------------------------------------------------------------- OpenRouter

    @Test
    void openRouterEmptyTurnRetryRecordsTwoLedgerRowsAndContentFilterRecordsOne() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String baseUrl =
                startServer(exchange -> {
                    int n = calls.incrementAndGet();
                    if (n == 1) {
                        respondSseRaw(
                                exchange,
                                true,
                                """
                                {"id":"c1","object":"chat.completion.chunk","created":1,"model":"deepseek/deepseek-chat","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":0,"total_tokens":10}}
                                """);
                    } else if (n == 2) {
                        respondSseRaw(
                                exchange,
                                true,
                                """
                                {"id":"c2","object":"chat.completion.chunk","created":1,"model":"deepseek/deepseek-chat","choices":[{"index":0,"delta":{"content":"Recovered"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":4,"total_tokens":14}}
                                """);
                    } else {
                        respondSseRaw(
                                exchange,
                                true,
                                """
                                {"id":"c3","object":"chat.completion.chunk","created":1,"model":"deepseek/deepseek-chat","choices":[{"index":0,"delta":{"content":"partial"},"finish_reason":"content_filter"}]}
                                """,
                                """
                                {"id":"c3","object":"chat.completion.chunk","created":1,"model":"deepseek/deepseek-chat","choices":[],"usage":{"prompt_tokens":20,"completion_tokens":3,"total_tokens":23}}
                                """);
                    }
                });

        try (OpenRouterLlmClient raw = new OpenRouterLlmClient(baseUrl, "test-key")) {
            AccountingLlmClient client = accounted(raw);
            List<LlmResponseChunk> chunks = new ArrayList<>();
            client.generateStream(request("deepseek/deepseek-chat"), chunks::add);

            assertThat(joinedContent(chunks)).isEqualTo("Recovered");
            assertThat(ledger).hasSize(2);
            assertThat(ledger.get(0).totalTokens()).isEqualTo(10);
            assertThat(ledger.get(1).totalTokens()).isEqualTo(14);

            assertThatThrownBy(
                            () -> client.generateStream(request("deepseek/deepseek-chat"), c -> {}))
                    .isInstanceOf(LlmCallFailedException.class)
                    .hasMessageContaining("content_filter");

            assertThat(ledger).hasSize(3);
            assertThat(ledger.get(2).totalTokens()).isEqualTo(23);
        }
    }

    @Test
    void openRouterSeveredStreamWithPartialContentFailsWithoutRetry() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String baseUrl =
                startServer(exchange -> {
                    calls.incrementAndGet();
                    respondSseRaw(
                            exchange,
                            false,
                            """
                            {"id":"c1","object":"chat.completion.chunk","created":1,"model":"deepseek/deepseek-chat","choices":[{"index":0,"delta":{"content":"Step 1: open the conso"},"finish_reason":null}]}
                            """);
                });

        try (OpenRouterLlmClient raw = new OpenRouterLlmClient(baseUrl, "test-key")) {
            AccountingLlmClient client = accounted(raw);
            List<LlmResponseChunk> chunks = new ArrayList<>();

            assertThatThrownBy(() ->
                            client.generateStream(request("deepseek/deepseek-chat"), chunks::add))
                    .isInstanceOf(LlmCallFailedException.class)
                    .hasMessageContaining("without a terminal event");

            assertThat(calls.get()).isEqualTo(1);
            assertThat(joinedContent(chunks)).isEqualTo("Step 1: open the conso");
            assertThat(ledger).isEmpty();
        }
    }

    @Test
    void openRouterStreamSlowerThanReadTimeoutSurvivesWhileChunksKeepArriving() throws Exception {
        String baseUrl =
                startServer(exchange -> {
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    try (OutputStream os = exchange.getResponseBody()) {
                        for (String token : List.of("a", "b", "c")) {
                            os.write(
                                    ("data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"deepseek/deepseek-chat\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\""
                                                    + token
                                                    + "\"},\"finish_reason\":null}]}\n\n")
                                            .getBytes(StandardCharsets.UTF_8));
                            os.flush();
                            sleepQuietly(300);
                        }
                        os.write(
                                ("data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"deepseek/deepseek-chat\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":3,\"total_tokens\":13}}\n\n"
                                                + "data: [DONE]\n\n")
                                        .getBytes(StandardCharsets.UTF_8));
                        os.flush();
                    }
                });

        OpenAIClient shortReadSdkClient =
                OpenAIOkHttpClient.builder()
                        .baseUrl(baseUrl)
                        .apiKey("test-key")
                        .maxRetries(0)
                        .timeout(OpenAiHttpTimeouts.forReadTimeout(Duration.ofMillis(700)))
                        .build();
        try (OpenRouterLlmClient raw =
                new OpenRouterLlmClient(shortReadSdkClient, true, "high", "")) {
            AccountingLlmClient client = accounted(raw);
            List<LlmResponseChunk> chunks = new ArrayList<>();
            client.generateStream(request("deepseek/deepseek-chat"), chunks::add);

            assertThat(joinedContent(chunks)).isEqualTo("abc");
            assertThat(ledger).hasSize(1);
            assertThat(ledger.get(0).totalTokens()).isEqualTo(13);
        }
    }

    // ---------------------------------------------------------------- Helpers

    private AccountingLlmClient accounted(LlmClient delegate) {
        return new AccountingLlmClient(delegate, event -> ledger.add((LlmCallLoggedEvent) event));
    }

    private static LlmRequest request(String model) {
        return new LlmRequest(model, List.of(new LlmMessage("user", "hello")), 0.0, 100, List.of());
    }

    private static String joinedContent(List<LlmResponseChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (LlmResponseChunk c : chunks) {
            if (c.content() != null) {
                sb.append(c.content());
            }
        }
        return sb.toString();
    }

    private String startServer(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    try (InputStream is = exchange.getRequestBody()) {
                        is.readAllBytes();
                    }
                    handler.handle(exchange);
                });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respondSseRaw(
            HttpExchange exchange, boolean sendDone, String... jsonPayloads) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream os = exchange.getResponseBody()) {
            for (String payload : jsonPayloads) {
                os.write(
                        ("data: " + payload.replace("\n", "").strip() + "\n\n")
                                .getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            if (sendDone) {
                os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
        }
    }

    private static void respondRawSseString(HttpExchange exchange, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
