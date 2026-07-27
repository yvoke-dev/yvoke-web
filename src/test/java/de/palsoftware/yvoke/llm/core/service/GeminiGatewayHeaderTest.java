package de.palsoftware.yvoke.llm.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.palsoftware.yvoke.llm.core.model.GatewayCacheStatus;
import de.palsoftware.yvoke.llm.core.model.LlmMessage;
import de.palsoftware.yvoke.llm.core.model.LlmRequest;
import de.palsoftware.yvoke.llm.core.model.LlmResponse;
import de.palsoftware.yvoke.llm.core.model.LlmResponseChunk;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Proves the Cloudflare AI Gateway cache header actually reaches the client through the genai SDK.
 *
 * <p>
 * The SDK exposes response headers on {@code GenerateContentResponse.sdkHttpResponse()} — for
 * streaming it stamps them onto <b>every</b> chunk. That is a load-bearing assumption for billing,
 * and it is an SDK implementation detail, so it is pinned here rather than assumed: if an upgrade
 * ever stops carrying headers through, cache hits would silently start being billed again.
 *
 * <p>
 * Note that {@code com.sun.net.httpserver.Headers} normalises header names to {@code Cf-aig-…},
 * which is exactly the casing production never sends — so these tests also exercise the
 * case-insensitive lookup in {@code LlmGatewayInfo}.
 */
@Timeout(60)
class GeminiGatewayHeaderTest {

    private static final String BODY = """
        {"candidates":[{"content":{"parts":[{"text":"hi"}]},"finishReason":"STOP"}],
         "usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":6,"totalTokenCount":11}}""";

    private static final String SSE_BODY = "data: " + """
        {"candidates":[{"content":{"parts":[{"text":"hi"}]},"finishReason":"STOP"}],\
        "usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":6,"totalTokenCount":11}}"""
        .replace("\n", "") + "\n\n";

    private static LlmRequest request() {
        return new LlmRequest("gemini-2.5-flash", List.of(new LlmMessage("user", "Hi")), 0.0, 100,
            List.of(), null);
    }

    /** Serves one canned response, stamped with the given cf-aig-* headers. */
    private static HttpServer serverReturning(String body, String contentType,
        Map<String, String> headers) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", (HttpExchange exchange) -> {
            try (InputStream is = exchange.getRequestBody()) {
                is.readAllBytes();
            }
            headers.forEach((k, v) -> exchange.getResponseHeaders().set(k, v));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        return server;
    }

    private static GeminiLlmClient clientFor(HttpServer server) {
        return new GeminiLlmClient("dummy-api-key", new ObjectMapper(), false, null,
            "http://127.0.0.1:" + server.getAddress().getPort() + "/google-ai-studio/");
    }

    @Test
    void testNonStreamingSurfacesCacheHit() throws Exception {
        HttpServer server = serverReturning(BODY, "application/json",
            Map.of("cf-aig-cache-status", "HIT", "cf-aig-log-id", "01KZ6A5K"));
        try (GeminiLlmClient client = clientFor(server)) {
            LlmResponse response = client.generate(request());

            assertNotNull(response.gateway(), "gateway info must survive the SDK");
            assertEquals(GatewayCacheStatus.REPLAYED, response.gateway().cacheStatus());
            assertTrue(response.gateway().replayed());
            assertEquals("01KZ6A5K", response.gateway().logId());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testNonStreamingSurfacesCacheMiss() throws Exception {
        HttpServer server =
            serverReturning(BODY, "application/json", Map.of("cf-aig-cache-status", "MISS"));
        try (GeminiLlmClient client = clientFor(server)) {
            LlmResponse response = client.generate(request());

            assertEquals(GatewayCacheStatus.FORWARDED, response.gateway().cacheStatus());
        } finally {
            server.stop(0);
        }
    }

    /** No gateway in the path: null, which must stay distinguishable from a miss. */
    @Test
    void testNonStreamingWithoutGatewayHeadersYieldsNull() throws Exception {
        HttpServer server = serverReturning(BODY, "application/json", Map.of());
        try (GeminiLlmClient client = clientFor(server)) {
            assertNull(client.generate(request()).gateway());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testStreamingSurfacesCacheHitOnChunks() throws Exception {
        HttpServer server = serverReturning(SSE_BODY, "text/event-stream",
            Map.of("cf-aig-cache-status", "HIT", "cf-aig-log-id", "01KZ6A5M"));
        try (GeminiLlmClient client = clientFor(server)) {
            List<LlmResponseChunk> chunks = new ArrayList<>();
            Consumer<LlmResponseChunk> collect = chunks::add;

            client.generateStream(request(), collect);

            assertTrue(chunks.stream().anyMatch(c -> c.gateway() != null),
                "at least one chunk must carry the gateway status");
            LlmResponseChunk stamped =
                chunks.stream().filter(c -> c.gateway() != null).findFirst().orElseThrow();
            assertEquals(GatewayCacheStatus.REPLAYED, stamped.gateway().cacheStatus());
            assertEquals("01KZ6A5M", stamped.gateway().logId());
        } finally {
            server.stop(0);
        }
    }
}
