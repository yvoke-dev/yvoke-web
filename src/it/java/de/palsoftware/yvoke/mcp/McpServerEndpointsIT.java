package de.palsoftware.yvoke.mcp;

import de.palsoftware.yvoke.rag.prompt.PlaybookService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "app.security.mock=true",
    "logging.level.org.springframework.web=DEBUG",
    "logging.level.org.springframework.security=DEBUG",
    "logging.level.io.modelcontextprotocol=DEBUG",
    "logging.level.org.springframework.ai=DEBUG"
})
@Timeout(value = 15, unit = TimeUnit.SECONDS)
public class McpServerEndpointsIT {

    @LocalServerPort
    private int port;

    @Autowired
    private PlaybookService playbookService;

    private class McpSession implements AutoCloseable {
        private final String sessionId;
        private final HttpClient httpClient;
        private final CompletableFuture<HttpResponse<Stream<String>>> sseFuture;

        public McpSession(String sessionId, HttpClient httpClient, CompletableFuture<HttpResponse<Stream<String>>> sseFuture) {
            this.sessionId = sessionId;
            this.httpClient = httpClient;
            this.sseFuture = sseFuture;
        }

        public String getSessionId() {
            return sessionId;
        }

        public HttpClient getHttpClient() {
            return httpClient;
        }

        @Override
        public void close() throws Exception {
            if (sessionId != null) {
                try {
                    HttpRequest deleteRequest = HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + port + "/mcp"))
                            .header("Authorization", "Bearer mock-jwt-token")
                            .header("Mcp-Session-Id", sessionId)
                            .DELETE()
                            .build();
                    httpClient.send(deleteRequest, HttpResponse.BodyHandlers.discarding());
                } catch (Exception ignored) {
                }
            }
            if (sseFuture != null) {
                sseFuture.cancel(true);
            }
            if (httpClient instanceof AutoCloseable autoCloseable) {
                autoCloseable.close();
            }
        }
    }

    private McpSession establishSession() throws Exception {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(java.time.Duration.ofSeconds(5))
                .build();

        // 1. Send initialize request to POST /mcp
        HttpRequest initRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/mcp"))
                .header("Authorization", "Bearer mock-jwt-token")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {
                          "jsonrpc": "2.0",
                          "id": "init-1",
                          "method": "initialize",
                          "params": {
                            "protocolVersion": "2024-11-05",
                            "capabilities": {},
                            "clientInfo": {
                              "name": "test-client",
                              "version": "1.0.0"
                            }
                          }
                        }
                        """))
                .build();

        HttpResponse<String> initResponse = httpClient.send(initRequest, HttpResponse.BodyHandlers.ofString());
        System.out.println("DEBUG INIT POST RESPONSE: STATUS=" + initResponse.statusCode() + ", BODY=" + initResponse.body());
        if (initResponse.statusCode() != 200) {
            // This handshake fails intermittently (~1 run in 10) in the FULL suite while passing in
            // isolation, and the body alone has never been enough to tell the two candidate causes
            // apart. The headers are decisive: a WWW-Authenticate: Bearer error=... means Spring
            // Security rejected the mock token (its bearer entry point answers invalid_request with
            // 400, not 401), whereas its absence points at the spring-ai streamable transport
            // refusing the initialize. Dump them so the next occurrence is diagnosable instead of
            // being another dead end.
            System.out.println("DEBUG INIT POST FAILED — port=" + port + " headers="
                + initResponse.headers().map());
        }
        assertEquals(200, initResponse.statusCode());
        assertTrue(initResponse.body().contains("protocolVersion"), "Initialize response should negotiate protocolVersion");

        // Extract session ID from Mcp-Session-Id header
        String sessionId = initResponse.headers().firstValue("Mcp-Session-Id")
                .orElseThrow(() -> new IllegalStateException("Missing Mcp-Session-Id header in initialize response"));
        System.out.println("DEBUG EXTRACTED SESSIONID: [" + sessionId + "]");

        // 2. Establish SSE connection GET /mcp with Mcp-Session-Id asynchronously so it does not block the test
        HttpRequest sseRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/mcp"))
                .header("Authorization", "Bearer mock-jwt-token")
                .header("Accept", "text/event-stream")
                .header("Mcp-Session-Id", sessionId)
                .GET()
                .build();

        CompletableFuture<HttpResponse<Stream<String>>> sseFuture = httpClient.sendAsync(sseRequest, HttpResponse.BodyHandlers.ofLines());

        McpSession session = new McpSession(sessionId, httpClient, sseFuture);

        // 3. Send initialized notification (no response expected, returns 202)
        HttpRequest initializedNotificationRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/mcp"))
                .header("Authorization", "Bearer mock-jwt-token")
                .header("Mcp-Session-Id", sessionId)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {
                          "jsonrpc": "2.0",
                          "method": "notifications/initialized"
                        }
                        """))
                .build();

        HttpResponse<String> initializedNotificationResponse = httpClient.send(initializedNotificationRequest, HttpResponse.BodyHandlers.ofString());
        System.out.println("DEBUG INITIALIZED NOTIFICATION RESPONSE: STATUS=" + initializedNotificationResponse.statusCode());
        assertTrue(initializedNotificationResponse.statusCode() >= 200 && initializedNotificationResponse.statusCode() < 300);

        return session;
    }

    @Test
    public void testSseHandshakeAndConnection() throws Exception {
        try (McpSession session = establishSession()) {
            assertNotNull(session.getSessionId());
            assertTrue(session.getSessionId().length() > 0);
        }
    }

    @Test
    public void testListToolsRpc() throws Exception {
        try (McpSession session = establishSession()) {
            HttpClient httpClient = session.getHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/mcp"))
                    .header("Authorization", "Bearer mock-jwt-token")
                    .header("Mcp-Session-Id", session.getSessionId())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {
                              "jsonrpc": "2.0",
                              "method": "tools/list",
                              "id": "1"
                            }
                            """))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.println("DEBUG TOOLS LIST POST RESPONSE: STATUS=" + response.statusCode() + ", BODY=" + response.body());
            assertEquals(200, response.statusCode());

            String body = response.body();
            assertNotNull(body);
            assertTrue(body.contains("tools"), "Should return the registered tools list");
            assertTrue(body.contains("search_corpus"), "Should list the search_corpus tool");
        }
    }

    @Test
    public void testDynamicPromptsListAndGetRpc() throws Exception {
        // Seed a dynamic prompt
        playbookService.savePlaybook("test-endpoints-it-playbook", "Test Endpoints IT Playbook", "Testing integration details", "Hello context: {query}", java.util.List.of(), false);

        try (McpSession session = establishSession()) {
            HttpClient httpClient = session.getHttpClient();

            // 1. Check prompts/list
            HttpRequest listRequest = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/mcp"))
                    .header("Authorization", "Bearer mock-jwt-token")
                    .header("Mcp-Session-Id", session.getSessionId())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {
                              "jsonrpc": "2.0",
                              "method": "prompts/list",
                              "id": "2"
                            }
                            """))
                    .build();

            HttpResponse<String> listResponse = httpClient.send(listRequest, HttpResponse.BodyHandlers.ofString());
            System.out.println("DEBUG PROMPTS LIST POST RESPONSE: STATUS=" + listResponse.statusCode() + ", BODY=" + listResponse.body());
            assertEquals(200, listResponse.statusCode());

            String listBody = listResponse.body();
            assertNotNull(listBody);
            assertTrue(listBody.contains("test-endpoints-it-playbook"), "Should list seeded prompt");
            assertTrue(listBody.contains("Testing integration details"), "Should contain description");

            // 2. Retrieve template via prompts/get
            HttpRequest getRequest = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/mcp"))
                    .header("Authorization", "Bearer mock-jwt-token")
                    .header("Mcp-Session-Id", session.getSessionId())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {
                              "jsonrpc": "2.0",
                              "method": "prompts/get",
                              "params": {
                                  "name": "test-endpoints-it-playbook"
                              },
                              "id": "3"
                            }
                            """))
                    .build();

            HttpResponse<String> getResponse = httpClient.send(getRequest, HttpResponse.BodyHandlers.ofString());
            System.out.println("DEBUG PROMPTS GET POST RESPONSE: STATUS=" + getResponse.statusCode() + ", BODY=" + getResponse.body());
            assertEquals(200, getResponse.statusCode());

            String getBody = getResponse.body();
            assertNotNull(getBody);
            assertTrue(getBody.contains("Hello context: {query}"), "Should return the prompt template content");

        } finally {
            // Clean up the seeded playbook
            try {
                playbookService.deletePlaybook("test-endpoints-it-playbook");
            } catch (Exception ignored) {}
        }
    }
}
