package de.palsoftware.yvoke.rag.core.websearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import de.palsoftware.yvoke.llm.core.context.LlmCallContextHolder;
import de.palsoftware.yvoke.llm.core.event.LlmCallLoggedEvent;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

class WebSearchServiceTest {

    private static final String ALLOWED_DOMAINS =
        "support.oneidentity.com, www.oneidentity.com/community/";
    private static final String MODEL = "gemini-3.5-flash-lite";
    private static final String API_KEY = "test-gemini-key";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        LlmCallContextHolder.clear();
        Thread.interrupted();
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void hasExactlyOneAutowiredConstructor() {
        List<Constructor<?>> autowired =
            Arrays.stream(WebSearchService.class.getDeclaredConstructors())
                .filter(c -> c.isAnnotationPresent(Autowired.class)).toList();

        assertThat(autowired).hasSize(1);
    }

    @Test
    void isAvailableAndSearchFailClosedWhenDisabledMisconfiguredOrPlaceholderKey() {
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        List<WebSearchService> unavailableVariants = List.of(
            new WebSearchService(false, ALLOWED_DOMAINS, MODEL, 8, 15, baseUrl, API_KEY,
                objectMapper, null),
            new WebSearchService(true, "", MODEL, 8, 15, baseUrl, API_KEY, objectMapper, null),
            new WebSearchService(true, "localhost, 127.0.0.1", MODEL, 8, 15, baseUrl, API_KEY,
                objectMapper, null),
            new WebSearchService(true, ALLOWED_DOMAINS, MODEL, 8, 15, baseUrl, null, objectMapper,
                null),
            new WebSearchService(true, ALLOWED_DOMAINS, MODEL, 8, 15, baseUrl, "   ", objectMapper,
                null),
            new WebSearchService(true, ALLOWED_DOMAINS, MODEL, 8, 15, baseUrl,
                "placeholder-gemini-api-key", objectMapper, null));

        for (WebSearchService variant : unavailableVariants) {
            assertThat(variant.isAvailable()).isFalse();
            assertThatThrownBy(() -> variant.search("OIM 9.3"))
                .isInstanceOf(IllegalStateException.class);
        }
        assertThat(requests.get()).isZero();
    }

    @Test
    void searchSendsGoogleSearchToolRequestResolvesRedirectsFiltersDeduplicatesAndLogsUsage()
        throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> requestQuery = new AtomicReference<>();
        AtomicReference<String> apiKeyHeader = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();

        server.createContext("/grounding-api-redirect/kb1", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Location",
                "https://support.oneidentity.com/kb/4380945");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/grounding-api-redirect/comm1", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Location",
                "https://www.oneidentity.com/community/threads/99");
            exchange.sendResponseHeaders(301, -1);
            exchange.close();
        });

        server.createContext("/v1beta/models/" + MODEL + ":generateContent", exchange -> {
            byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
            requestPath.set(exchange.getRequestURI().getPath());
            requestQuery.set(exchange.getRequestURI().getRawQuery());
            apiKeyHeader.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            requestBody.set(new String(bodyBytes, StandardCharsets.UTF_8));

            String responseJson = """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          { "text": "Fallback synthesis text for unindexed chunks." }
                        ]
                      },
                      "groundingMetadata": {
                        "groundingChunks": [
                          {
                            "web": {
                              "uri": "%s/grounding-api-redirect/kb1",
                              "title": "KB 4380945"
                            }
                          },
                          {
                            "web": {
                              "uri": "https://support.oneidentity.com/kb/4380945",
                              "title": "Duplicate KB 4380945"
                            }
                          },
                          {
                            "web": {
                              "uri": "%s/grounding-api-redirect/comm1",
                              "title": "Community Thread 99"
                            }
                          }
                        ],
                        "groundingSupports": [
                          {
                            "segment": { "text": "First support segment." },
                            "groundingChunkIndices": [0]
                          },
                          {
                            "segment": { "text": "Second support segment." },
                            "groundingChunkIndices": [0]
                          }
                        ]
                      }
                    }
                  ],
                  "usageMetadata": {
                    "promptTokenCount": 30,
                    "toolUsePromptTokenCount": 70,
                    "candidatesTokenCount": 40,
                    "cachedContentTokenCount": 10,
                    "thoughtsTokenCount": 15,
                    "totalTokenCount": 155
                  }
                }
                """.formatted(baseUrl, baseUrl);
            byte[] resp = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });

        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        UUID convId = UUID.randomUUID();
        UUID msgId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        LlmCallContextHolder.set(convId, msgId, runId, userId, null, "specialist");

        WebSearchService service = new WebSearchService(true, ALLOWED_DOMAINS, MODEL, 8, 15,
            baseUrl, API_KEY, objectMapper, publisher);

        assertThat(service.isAvailable()).isTrue();
        List<WebSearchResult> results = service.search("  OIM 9.3.1 hotfix 4380945  ");

        assertThat(requestPath.get()).isEqualTo("/v1beta/models/" + MODEL + ":generateContent");
        assertThat(requestQuery.get()).isNull();
        assertThat(apiKeyHeader.get()).isEqualTo(API_KEY);

        JsonNode sentJson = objectMapper.readTree(requestBody.get());
        assertThat(sentJson.at("/tools/0/google_search")).isNotNull();
        assertThat(sentJson.at("/tools/0/google_search").isObject()).isTrue();
        assertThat(sentJson.at("/contents/0/parts/0/text").asText()).isEqualTo(
            "OIM 9.3.1 hotfix 4380945 (site:support.oneidentity.com OR site:www.oneidentity.com/community)");

        assertThat(results).containsExactly(
            new WebSearchResult("KB 4380945", "https://support.oneidentity.com/kb/4380945",
                "First support segment. Second support segment."),
            new WebSearchResult("Community Thread 99",
                "https://www.oneidentity.com/community/threads/99",
                "Fallback synthesis text for unindexed chunks."));

        ArgumentCaptor<LlmCallLoggedEvent> eventCaptor =
            ArgumentCaptor.forClass(LlmCallLoggedEvent.class);
        verify(publisher).publishEvent(eventCaptor.capture());
        LlmCallLoggedEvent event = eventCaptor.getValue();
        assertThat(event.conversationId()).isEqualTo(convId);
        assertThat(event.messageId()).isEqualTo(msgId);
        assertThat(event.agentRunId()).isEqualTo(runId);
        assertThat(event.userId()).isEqualTo(userId);
        assertThat(event.source()).isEqualTo("web_search");
        assertThat(event.role()).isEqualTo("web_search");
        assertThat(event.model()).isEqualTo(MODEL);
        assertThat(event.promptTokens()).isEqualTo(100);
        assertThat(event.completionTokens()).isEqualTo(40);
        assertThat(event.cachedTokens()).isEqualTo(10);
        assertThat(event.thoughtTokens()).isEqualTo(15);
        assertThat(event.totalTokens()).isEqualTo(155);
    }

    @Test
    void searchStripsOffDomainUrlsAndHandlesRedirectHazardsAndPartialRedirectFailures() {
        server.createContext("/grounding-api-redirect/loop", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Location",
                "https://vertexaisearch.cloud.google.com/grounding-api-redirect/loop2");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/grounding-api-redirect/relative", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Location", "/kb/1");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/grounding-api-redirect/malformed", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Location", "http://[::1");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/grounding-api-redirect/nonredirect", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] ok = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, ok.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(ok);
            }
        });
        server.createContext("/grounding-api-redirect/nolocation", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/grounding-api-redirect/error500", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        AtomicInteger ssrfRequests = new AtomicInteger();
        server.createContext("/grounding-api-redirect/ssrf", exchange -> {
            exchange.getRequestBody().readAllBytes();
            ssrfRequests.incrementAndGet();
            exchange.getResponseHeaders().add("Location",
                "https://support.oneidentity.com/kb/ssrf-leaked");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/grounding-api-redirect/valid", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Location",
                "https://support.oneidentity.com/kb/4380945");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        String mismatchedLoopbackOrigin = "http://localhost:" + server.getAddress().getPort();
        server.createContext("/v1beta/models/" + MODEL + ":generateContent", exchange -> {
            exchange.getRequestBody().readAllBytes();
            String responseJson =
                """
                    {
                      "candidates": [
                        {
                          "content": { "parts": [{ "text": "Snippet" }] },
                          "groundingMetadata": {
                            "groundingChunks": [
                              { "web": { "uri": "https://stackoverflow.com/q/1", "title": "Off domain" } },
                              { "web": { "uri": "https://www.oneidentity.com/products/oim", "title": "Disallowed path" } },
                              { "web": { "uri": "%s/grounding-api-redirect/ssrf", "title": "Loopback SSRF" } },
                              { "web": { "uri": "%s/grounding-api-redirect/loop", "title": "Loop" } },
                              { "web": { "uri": "%s/grounding-api-redirect/relative", "title": "Relative" } },
                              { "web": { "uri": "%s/grounding-api-redirect/malformed", "title": "Malformed" } },
                              { "web": { "uri": "%s/grounding-api-redirect/nonredirect", "title": "200 OK" } },
                              { "web": { "uri": "%s/grounding-api-redirect/nolocation", "title": "No Location" } },
                              { "web": { "uri": "%s/grounding-api-redirect/error500", "title": "500 Error" } },
                              { "web": { "uri": "%s/grounding-api-redirect/valid", "title": "Valid KB" } }
                            ]
                          }
                        }
                      ]
                    }
                    """
                    .formatted(mismatchedLoopbackOrigin, baseUrl, baseUrl, baseUrl, baseUrl,
                        baseUrl, baseUrl, baseUrl);
            byte[] resp = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });

        WebSearchService service = new WebSearchService(true, ALLOWED_DOMAINS, MODEL, 8, 15,
            baseUrl, API_KEY, objectMapper, null);

        List<WebSearchResult> results = service.search("OIM hotfix");

        assertThat(ssrfRequests.get()).as(
            "redirect URL on an origin different from geminiBaseUrl and vertexaisearch must never be fetched")
            .isZero();
        assertThat(results).containsExactly(new WebSearchResult("Valid KB",
            "https://support.oneidentity.com/kb/4380945", "Snippet"));
    }

    @Test
    void searchClampsMaxResultsAndSurvivesAccountingException() {
        List<String> chunkItems = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            chunkItems.add("""
                { "web": { "uri": "https://support.oneidentity.com/kb/%d", "title": "KB %d" } }
                """.formatted(i, i));
        }

        server.createContext("/v1beta/models/" + MODEL + ":generateContent", exchange -> {
            exchange.getRequestBody().readAllBytes();
            String responseJson = """
                {
                  "candidates": [
                    {
                      "content": { "parts": [{ "text": "Snippet" }] },
                      "groundingMetadata": {
                        "groundingChunks": [%s]
                      }
                    }
                  ],
                  "usageMetadata": {
                    "promptTokenCount": 10,
                    "candidatesTokenCount": 20,
                    "totalTokenCount": 30
                  }
                }
                """.formatted(String.join(",", chunkItems));
            byte[] resp = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });

        ApplicationEventPublisher failingPublisher = mock(ApplicationEventPublisher.class);
        doThrow(new RuntimeException("DB down")).when(failingPublisher).publishEvent(any());

        // maxResults = 0 clamps to 1
        WebSearchService clampedLow = new WebSearchService(true, ALLOWED_DOMAINS, MODEL, 0, 15,
            baseUrl, API_KEY, objectMapper, failingPublisher);

        List<WebSearchResult> results = clampedLow.search("OIM");
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().url()).isEqualTo("https://support.oneidentity.com/kb/1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchRestoresInterruptFlagWhenMainRequestIsInterrupted() throws Exception {
        HttpClient mockHttp = mock(HttpClient.class);
        when(mockHttp.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenThrow(new InterruptedException("simulated interrupt"));

        WebSearchService service = new WebSearchService(true, ALLOWED_DOMAINS, MODEL, 8, 15,
            baseUrl, API_KEY, objectMapper, null, mockHttp);

        assertThatThrownBy(() -> service.search("OIM")).isInstanceOf(RuntimeException.class);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchRestoresInterruptFlagWhenRedirectResolutionIsInterrupted() throws Exception {
        HttpClient mockHttp = mock(HttpClient.class);
        HttpResponse<String> mainResponse = mock(HttpResponse.class);
        when(mainResponse.statusCode()).thenReturn(200);
        when(mainResponse.body()).thenReturn("""
            {
              "candidates": [
                {
                  "content": { "parts": [{ "text": "Snippet" }] },
                  "groundingMetadata": {
                    "groundingChunks": [
                      {
                        "web": {
                          "uri": "https://vertexaisearch.cloud.google.com/grounding-api-redirect/1",
                          "title": "KB 1"
                        }
                      }
                    ]
                  }
                }
              ]
            }
            """);

        when(mockHttp.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenReturn(mainResponse).thenThrow(new InterruptedException("redirect interrupt"));

        WebSearchService service = new WebSearchService(true, ALLOWED_DOMAINS, MODEL, 8, 15,
            baseUrl, API_KEY, objectMapper, null, mockHttp);

        assertThatThrownBy(() -> service.search("OIM")).isInstanceOf(RuntimeException.class);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchIsolatesRedirectIoExceptionAndClearsAndRestoresInterruptFlagDuringAccounting()
        throws Exception {
        HttpClient mockHttp = mock(HttpClient.class);
        HttpResponse<String> mainResponse = mock(HttpResponse.class);
        when(mainResponse.statusCode()).thenReturn(200);
        when(mainResponse.body()).thenReturn(
            """
                {
                  "candidates": [
                    {
                      "content": { "parts": [{ "text": "Fallback" }] },
                      "groundingMetadata": {
                        "groundingChunks": [
                          {
                            "web": {
                              "uri": "https://vertexaisearch.cloud.google.com/grounding-api-redirect/iofail",
                              "title": "Failing Redirect"
                            }
                          },
                          {
                            "web": {
                              "uri": "https://support.oneidentity.com/kb/4380945",
                              "title": "Surviving KB"
                            }
                          }
                        ]
                      }
                    }
                  ],
                  "usageMetadata": {
                    "promptTokenCount": 12,
                    "candidatesTokenCount": 8
                  }
                }
                """);

        when(mockHttp.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenAnswer(inv -> {
                // Simulate thread already marked interrupted when response arrives before
                // accounting
                Thread.currentThread().interrupt();
                return mainResponse;
            }).thenThrow(new IOException("Connection reset by peer"));

        AtomicReference<Boolean> interruptedDuringPublish = new AtomicReference<>();
        AtomicReference<LlmCallLoggedEvent> publishedEvent = new AtomicReference<>();
        ApplicationEventPublisher publisher = event -> {
            if (event instanceof LlmCallLoggedEvent llmEvent) {
                interruptedDuringPublish.set(Thread.currentThread().isInterrupted());
                publishedEvent.set(llmEvent);
            }
        };

        LlmCallContextHolder.set(null, null, null, null, "chat", "assistant");

        WebSearchService service = new WebSearchService(true, ALLOWED_DOMAINS, MODEL, 50, 15,
            baseUrl, API_KEY, objectMapper, publisher, mockHttp);

        // Clear interrupt from mainResponse answer when redirect runs, by verifying accounting
        // behavior:
        // Wait: if Thread.currentThread().interrupt() was set before publishUsageIfPresent,
        // publishUsageIfPresent clears it during publishEvent and restores it in finally!
        List<WebSearchResult> results = service.search("OIM");

        assertThat(interruptedDuringPublish.get()).isFalse();
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        assertThat(publishedEvent.get()).isNotNull();
        assertThat(publishedEvent.get().source()).isEqualTo("chat");
        assertThat(publishedEvent.get().role()).isEqualTo("web_search");
        assertThat(publishedEvent.get().totalTokens()).isEqualTo(20);
        assertThat(results).containsExactly(new WebSearchResult("Surviving KB",
            "https://support.oneidentity.com/kb/4380945", "Fallback"));
    }
}
