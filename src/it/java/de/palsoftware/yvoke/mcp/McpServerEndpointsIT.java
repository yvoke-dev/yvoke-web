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
import java.util.List;
import de.palsoftware.yvoke.rag.prompt.PlaybookRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import java.time.Duration;

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

    @Autowired
    private PlaybookRepository playbookRepository;

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
                .connectTimeout(Duration.ofSeconds(5))
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

    /**
     * The MCP annotation scanner is switched OFF ({@code spring.ai.mcp.server.annotation-scanner
     * .enabled=false}), so the ONLY thing that publishes a tool to external MCP clients is the
     * hand-built {@code List<ToolCallback>} in {@code McpToolsConfig} — and every registration
     * there is wrapped in a {@code try/catch} that logs the failure and carries on, with duplicate
     * names dropped at DEBUG. A tool can therefore vanish from the protocol surface without any
     * startup error: the desktop client and every external agent just stop being offered it, and
     * the model, which only knows what {@code tools/list} told it, reports the missing capability
     * as missing DATA ("that manual has no table of contents") instead of as a broken server.
     *
     * <p>
     * {@code testListToolsRpc} above cannot catch this. It asserts {@code search_corpus}, which is
     * one of the two callbacks appended BY HAND before the classpath-scanning loop runs — so it
     * stays green even if the loop registers nothing at all, i.e. if eight of the ten tools are
     * gone. This test is the end-to-end counterpart of
     * {@code JsonObjectsToolsIT.everyDocumentedToolIsPresentInTheRegisteredCallbackList}: that one
     * proves the bean holds the ten callbacks, this one proves the MCP transport actually publishes
     * them, which is the layer the annotation-scanner setting and the spring-ai wiring live in.
     */
    @Test
    public void toolsListPublishesTheWholeToolCatalogue() throws Exception {
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
                              "id": "4"
                            }
                            """))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.println("DEBUG TOOLS LIST (FULL CATALOGUE) RESPONSE: STATUS=" + response.statusCode() + ", BODY=" + response.body());
            assertEquals(200, response.statusCode());

            String body = response.body();
            assertNotNull(body);
            List<String> expected = List.of("search_corpus", "ask_clarifying_question", "get_toc",
                    "get_section", "list_documents", "get_graph_neighbors", "search_graph_entities",
                    "get_json_schema", "query_json_objects", "verify_citations");
            // Quoted, so a tool merely NAMED inside another tool's description cannot stand in for
            // its own registration: the descriptions cross-reference each other in SINGLE quotes,
            // and any double quote inside a JSON string arrives escaped as \" — so an unescaped
            // "get_toc" can only be the name field of a genuinely registered tool.
            List<String> missing =
                    expected.stream().filter(name -> !body.contains("\"" + name + "\"")).toList();
            assertTrue(missing.isEmpty(),
                    "tools/list is missing " + missing + " — BODY=" + body);
        }
    }

    /**
     * {@code doRegister} captures the {@link de.palsoftware.yvoke.rag.prompt.Playbook} object in its
     * handler closure, and the single line {@code getPlaybook(playbook.name()).orElse(playbook)} is
     * the only thing that makes a later edit visible to an external MCP client. Delete it — it reads
     * like a redundant round-trip, since the object is right there in scope — and every
     * {@code prompts/get} keeps replaying whatever the row said at REGISTRATION time.
     *
     * <p>
     * The edit here is written straight to the row rather than through {@code savePlaybook}, and that
     * is the realistic case rather than a shortcut. On the SDK in use ({@code mcp-core} 2.0.0-RC1)
     * {@code McpAsyncServer.addPrompt} does replace an existing registration — it is a
     * {@code prompts.put} that logs "Replace existing Prompt" — so an admin edit on THIS instance
     * re-registers a fresh closure and would mask the loss. What that does not cover is every edit
     * that never reaches this instance's {@code savePlaybook}: the exports/import tooling (playbooks
     * are DB content, versioned outside Flyway), a direct SQL fix, and an admin edit performed on a
     * SECOND replica — after which this replica serves the stale instructions to the desktop agent and
     * the eval harness until it is restarted, with a success flash shown on the other side and no
     * error anywhere. Note the re-read is also cache-free by construction: the closure calls
     * {@code getPlaybook} on {@code this}, so the {@code @Cacheable} proxy is bypassed and the row is
     * genuinely re-read per call.
     *
     * <p>
     * Nothing existing covers this: {@code testDynamicPromptsListAndGetRpc} and
     * {@code promptsGetCarriesTheToolAndTargetAgentMetadata} each save a playbook once and fetch it
     * once, so the closure is never asked a second question. The description is asserted alongside the
     * template because it is read from the same {@code current} reference and travels only on
     * {@code prompts/get}.
     */
    @Test
    public void aPlaybookEditedAfterRegistrationIsServedFreshByPromptsGet() throws Exception {
        String name = "test-endpoints-it-live-edit";
        playbookService.savePlaybook(name, "Live Edit IT Playbook", "Description before the edit",
                "ORIGINAL instructions: answer {query} from the corpus.", List.of("search_corpus"),
                false);

        try (McpSession session = establishSession()) {
            HttpClient httpClient = session.getHttpClient();

            HttpRequest firstGet = HttpRequest.newBuilder()
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
                                  "name": "test-endpoints-it-live-edit"
                              },
                              "id": "6"
                            }
                            """))
                    .build();

            HttpResponse<String> firstResponse =
                    httpClient.send(firstGet, HttpResponse.BodyHandlers.ofString());
            System.out.println("DEBUG PROMPTS GET (BEFORE EDIT) RESPONSE: STATUS="
                    + firstResponse.statusCode() + ", BODY=" + firstResponse.body());
            assertEquals(200, firstResponse.statusCode());
            assertTrue(firstResponse.body().contains("ORIGINAL instructions"),
                    "the registered playbook must be served at all — BODY=" + firstResponse.body());

            // The edit an MCP client must still see: written straight to the row, exactly as the
            // exports/import tooling and a second replica's admin edit do. Nothing re-registers the
            // prompt here, so only the handler's own re-read at call time can surface it.
            playbookRepository.upsert(name, "Live Edit IT Playbook", "Description after the edit",
                    "REVISED instructions: cite every claim with a chunk id.",
                    List.of("search_corpus"), false, "specialist");

            HttpRequest secondGet = HttpRequest.newBuilder()
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
                                  "name": "test-endpoints-it-live-edit"
                              },
                              "id": "7"
                            }
                            """))
                    .build();

            HttpResponse<String> secondResponse =
                    httpClient.send(secondGet, HttpResponse.BodyHandlers.ofString());
            System.out.println("DEBUG PROMPTS GET (AFTER EDIT) RESPONSE: STATUS="
                    + secondResponse.statusCode() + ", BODY=" + secondResponse.body());
            assertEquals(200, secondResponse.statusCode());

            String after = secondResponse.body();
            assertNotNull(after);
            assertTrue(after.contains("REVISED instructions"),
                    "the template is re-read at get-prompt time, so a live edit must apply without a "
                            + "restart — BODY=" + after);
            assertFalse(after.contains("ORIGINAL instructions"),
                    "the pre-edit template must be gone, not merely accompanied — BODY=" + after);
            assertTrue(after.contains("Description after the edit"),
                    "the description travels on prompts/get from the same re-read row — BODY=" + after);
        } finally {
            try {
                playbookService.deletePlaybook(name);
            } catch (Exception ignored) {}
        }
    }

    /**
     * A playbook is not just a template: the row also declares WHICH tools the run may use, whether
     * code execution is permitted, and which agent the playbook is written for. Those three fields
     * only reach an external MCP client through {@code _meta} on the {@code prompts/get} result —
     * there is nowhere else in the protocol for them — so a client that fetches a playbook and gets
     * no meta has to guess, and the guess it makes is "all tools, default agent". That inverts the
     * point of the field: a playbook deliberately restricted to {@code search_corpus} silently
     * becomes an unrestricted one, and an orchestrator- or reviewer-targeted playbook is run as a
     * specialist.
     *
     * <p>
     * {@code testDynamicPromptsListAndGetRpc} asserts only that the template text comes back, so
     * dropping {@code .meta(...)} from the {@code GetPromptResult} builder — the natural casualty of
     * a builder tidy-up, since the same map is also set on the {@code Prompt} spec a few lines
     * above and looks duplicated — leaves it green. The two maps are NOT redundant: the one on
     * {@code Prompt} travels with {@code prompts/list}, this one with {@code prompts/get}, and only
     * the latter is re-read from the database at call time so a live edit takes effect. The
     * template text below deliberately names no tool, so the tool name in the response can only
     * have come from the metadata.
     */
    @Test
    public void promptsGetCarriesTheToolAndTargetAgentMetadata() throws Exception {
        playbookService.savePlaybook("test-endpoints-it-prompt-meta", "Prompt Meta IT Playbook",
            "Carries tool metadata", "Answer {query} from the corpus.", List.of("search_corpus"),
            false);

        try (McpSession session = establishSession()) {
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
                                  "name": "test-endpoints-it-prompt-meta"
                              },
                              "id": "5"
                            }
                            """))
                    .build();

            HttpResponse<String> getResponse = session.getHttpClient().send(getRequest,
                    HttpResponse.BodyHandlers.ofString());
            System.out.println("DEBUG PROMPTS GET META RESPONSE: STATUS="
                    + getResponse.statusCode() + ", BODY=" + getResponse.body());
            assertEquals(200, getResponse.statusCode());

            String body = getResponse.body();
            assertNotNull(body);
            assertTrue(body.contains("Answer {query} from the corpus."),
                    "Should return the prompt template content");
            assertTrue(body.contains("\"search_corpus\""),
                    "The declared tool list must travel with the prompt, or the client assumes "
                            + "every tool is allowed — BODY=" + body);
            assertTrue(body.contains("\"codeExecution\""),
                    "codeExecution must be declared explicitly, never defaulted — BODY=" + body);
            assertTrue(body.contains("\"targetAgent\"") && body.contains("\"specialist\""),
                    "targetAgent defaults to 'specialist' and must be stated — BODY=" + body);
        } finally {
            try {
                playbookService.deletePlaybook("test-endpoints-it-prompt-meta");
            } catch (Exception ignored) {}
        }
    }

    @Test
    public void testDynamicPromptsListAndGetRpc() throws Exception {
        // Seed a dynamic prompt
        playbookService.savePlaybook("test-endpoints-it-playbook", "Test Endpoints IT Playbook", "Testing integration details", "Hello context: {query}", List.of(), false);

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
