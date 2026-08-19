package de.palsoftware.yvoke.llm.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.ApiClient;
import com.google.genai.types.HttpRetryOptions;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import de.palsoftware.yvoke.llm.core.model.LlmMessage;
import de.palsoftware.yvoke.llm.core.model.LlmRequest;
import de.palsoftware.yvoke.llm.core.model.LlmResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

@Timeout(60)
class CloudflareGeminiLlmClientTest {

    @Test
    void testRequestRoutesThroughCloudflareAndIncludesAuthHeader() throws Exception {
        AtomicReference<String> requestedUri = new AtomicReference<>();
        AtomicReference<String> authHeader = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                requestedUri.set(exchange.getRequestURI().toString());
                authHeader.set(exchange.getRequestHeaders().getFirst("cf-aig-authorization"));

                try (InputStream is = exchange.getRequestBody()) {
                    is.readAllBytes();
                }

                // Return a mock Gemini response
                String responseBody = "{\n" + "  \"candidates\": [{\n" + "    \"content\": {\n"
                    + "      \"parts\": [{\n"
                    + "        \"text\": \"Hello from mock Cloudflare Gateway\"\n" + "      }]\n"
                    + "    },\n" + "    \"finishReason\": \"STOP\"\n" + "  }],\n"
                    + "  \"usageMetadata\": {\n" + "    \"promptTokenCount\": 5,\n"
                    + "    \"candidatesTokenCount\": 6,\n" + "    \"totalTokenCount\": 11\n"
                    + "  }\n" + "}";
                byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
        });
        server.start();

        try {
            int port = server.getAddress().getPort();

            String mockAccountId = "my-account-123";
            String mockGatewayId = "my-gateway-456";
            String mockGatewayToken = "my-secret-token";

            try (GeminiLlmClient client =
                new GeminiLlmClient("dummy-api-key", new ObjectMapper(), false, null,
                    "http://127.0.0.1:" + port
                        + "/v1/my-account-123/my-gateway-456/google-ai-studio/",
                    Map.of("cf-aig-authorization", "Bearer " + mockGatewayToken))) {

                LlmRequest request = new LlmRequest("gemini-2.5-flash",
                    List.of(new LlmMessage("user", "Hi")), 0.0, 100, List.of(), null);
                LlmResponse response = client.generate(request);

                assertEquals("Hello from mock Cloudflare Gateway", response.content());
                assertEquals(5, response.usage().promptTokens());
                assertEquals(6, response.usage().completionTokens());

                // Verify that the requested URI contains the gateway path
                assertNotNull(requestedUri.get());
                assertTrue(requestedUri.get().contains(
                    "/v1/my-account-123/my-gateway-456/google-ai-studio/v1beta/models/gemini-2.5-flash:generateContent"));

                // Verify that the authorization header was correctly passed
                assertEquals("Bearer " + mockGatewayToken, authHeader.get());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testCloudflareGeminiLlmClientInstantiation() throws Exception {
        try (CloudflareGeminiLlmClient client = new CloudflareGeminiLlmClient("dummy-api-key",
            new ObjectMapper(), true, "medium", "my-account", "my-gateway", "my-token")) {

            assertNotNull(client);
            Field clientField = GeminiLlmClient.class.getDeclaredField("client");
            clientField.setAccessible(true);
            Object clientObj = clientField.get(client);
            assertNotNull(clientObj);
        }
    }

    @Test
    void testCloudflareGeminiLlmClientSendsMetadataWithUsername() throws Exception {
        AtomicReference<String> metadataHeader = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                metadataHeader.set(exchange.getRequestHeaders().getFirst("cf-aig-metadata"));

                try (InputStream is = exchange.getRequestBody()) {
                    is.readAllBytes();
                }

                String responseBody = "{\n" + "  \"candidates\": [{\n" + "    \"content\": {\n"
                    + "      \"parts\": [{\n" + "        \"text\": \"Hello\"\n" + "      }]\n"
                    + "    },\n" + "    \"finishReason\": \"STOP\"\n" + "  }],\n"
                    + "  \"usageMetadata\": {\n" + "    \"promptTokenCount\": 1,\n"
                    + "    \"candidatesTokenCount\": 1,\n" + "    \"totalTokenCount\": 2\n"
                    + "  }\n" + "}";
                byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
        });
        server.start();

        try {
            int port = server.getAddress().getPort();

            SecurityContext originalContext = SecurityContextHolder.getContext();
            try {
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken("test-user-999", "password", List.of());
                context.setAuthentication(auth);
                SecurityContextHolder.setContext(context);

                try (CloudflareGeminiLlmClient client =
                    new CloudflareGeminiLlmClient("dummy-api-key", new ObjectMapper(), false, null,
                        "http://127.0.0.1:" + port + "/google-ai-studio/", "dummy-token")) {

                    LlmRequest request = new LlmRequest("gemini-2.5-flash",
                        List.of(new LlmMessage("user", "Hi")), 0.0, 100, List.of(), null);
                    client.generate(request);

                    assertEquals("{\"user\":\"test-user-999\"}", metadataHeader.get());
                }
            } finally {
                SecurityContextHolder.setContext(originalContext);
            }
        } finally {
            server.stop(0);
        }
    }

    /**
     * Regression test for the timeouts silently dropped on the Cloudflare path.
     *
     * <p>
     * {@code GeminiLlmClient} asks the SDK for a 300s timeout via
     * {@code HttpOptions.timeout(300_000)}, but {@code ApiClient.createHttpClient} only applies
     * timeouts in the branch where it builds its own {@code OkHttpClient}. When a custom client is
     * supplied — which {@code CloudflareGeminiLlmClient} always does — the SDK just calls
     * {@code customClient.newBuilder()}, so the client keeps OkHttp's 10s connect/read/write
     * defaults and has no call timeout. A thinking model taking longer than 10s to emit its first
     * SSE token would then die with a SocketTimeoutException.
     *
     * <p>
     * The custom client must therefore configure the same timeouts the SDK would have.
     */
    @Test
    void testCloudflareCustomHttpClientPreservesSdkTimeouts() throws Exception {
        try (CloudflareGeminiLlmClient client = new CloudflareGeminiLlmClient("dummy-api-key",
            new ObjectMapper(), true, "medium", "my-account", "my-gateway", "my-token")) {

            OkHttpClient httpClient = httpClientOf(client);

            assertEquals(0, httpClient.callTimeoutMillis(),
                "a callTimeout spans the whole call INCLUDING draining the SSE body, so any value "
                    + "here is a hard cap on how long an answer may take — the one bound an LLM "
                    + "stream must not have");
            assertEquals(10_000, httpClient.connectTimeoutMillis(),
                "TCP/TLS only; a hung connect must not wait for the read budget");
            assertEquals(300_000, httpClient.readTimeoutMillis(),
                "the socket read timeout is the liveness bound: it covers the wait for response "
                    + "headers AND every later gap between tokens, which is exactly the pair an "
                    + "LLM call needs");
            assertEquals(0, httpClient.writeTimeoutMillis(),
                "a ~600k-token prompt must not trip a per-chunk upload timer");
        }
    }

    /**
     * The plain path must land on the SAME shape as the Cloudflare one.
     *
     * <p>
     * It previously did not, and could not: {@code HttpOptions.timeout()} is applied only on the
     * transport the SDK builds itself, so one setting produced a wall-clock generation cap here and
     * nothing at all on the Cloudflare path. Both now take their configuration from
     * {@code GeminiLlmClient#httpClientBuilder}, so a divergence is not expressible.
     */
    @Test
    void testPlainGeminiClientGetsSdkTimeouts() throws Exception {
        try (GeminiLlmClient client =
            new GeminiLlmClient("dummy-api-key", new ObjectMapper(), false, null)) {

            OkHttpClient httpClient = httpClientOf(client);

            assertEquals(0, httpClient.callTimeoutMillis());
            assertEquals(10_000, httpClient.connectTimeoutMillis());
            assertEquals(300_000, httpClient.readTimeoutMillis());
            assertEquals(0, httpClient.writeTimeoutMillis());
        }
    }

    /**
     * The SDK installs its own {@code RetryInterceptor} on every client — the
     * {@code addInterceptor} call in {@code ApiClient.createHttpClient} sits outside the
     * custom-vs-default branch, so supplying a custom {@link OkHttpClient} does not opt out of it.
     * Left at its default of 5 attempts it multiplies our own {@code LlmRetry} loop: 3 outer
     * attempts became up to 15 HTTP requests, each re-uploading a ~600k-token prompt into the very
     * quota that returned the 429. Pinning it to 1 leaves {@code LlmRetry} as the single retry
     * authority, with a backoff long enough for a quota window to actually reset.
     */
    @Test
    void testCloudflareClientDoesNotStackSdkRetriesOnTopOfLlmRetry() throws Exception {
        try (CloudflareGeminiLlmClient client = new CloudflareGeminiLlmClient("dummy-api-key",
            new ObjectMapper(), true, "medium", "my-account", "my-gateway", "my-token")) {

            assertEquals(1, sdkRetryAttemptsOf(client),
                "the SDK must make exactly one HTTP attempt; LlmRetry owns retrying");
        }
    }

    @Test
    void testPlainGeminiClientDoesNotStackSdkRetriesOnTopOfLlmRetry() throws Exception {
        try (GeminiLlmClient client =
            new GeminiLlmClient("dummy-api-key", new ObjectMapper(), false, null)) {

            assertEquals(1, sdkRetryAttemptsOf(client));
        }
    }

    /** Reads the attempt count out of the SDK's own retry interceptor. */
    private static int sdkRetryAttemptsOf(GeminiLlmClient client) throws Exception {
        for (Interceptor interceptor : httpClientOf(client).interceptors()) {
            if (!interceptor.getClass().getSimpleName().equals("RetryInterceptor")) {
                continue;
            }
            Field optionsField = interceptor.getClass().getDeclaredField("retryOptions");
            optionsField.setAccessible(true);
            HttpRetryOptions options = (HttpRetryOptions) optionsField.get(interceptor);
            assertNotNull(options);
            return options.attempts()
                .orElseThrow(() -> new AssertionError("retry attempts left at the SDK default"));
        }
        throw new AssertionError(
            "SDK RetryInterceptor not found — did the SDK stop installing it?");
    }

    /** Reaches the {@link OkHttpClient} the SDK actually issues requests with. */
    private static OkHttpClient httpClientOf(GeminiLlmClient client) throws Exception {
        Field clientField = GeminiLlmClient.class.getDeclaredField("client");
        clientField.setAccessible(true);
        Object genaiClient = clientField.get(client);

        Field apiClientField = genaiClient.getClass().getDeclaredField("apiClient");
        apiClientField.setAccessible(true);
        ApiClient apiClient = (ApiClient) apiClientField.get(genaiClient);

        return apiClient.httpClient();
    }

    private static void assertNotNull(Object obj) {
        if (obj == null) {
            throw new AssertionError("Object was null");
        }
    }
}
