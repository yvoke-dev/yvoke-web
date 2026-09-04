package de.palsoftware.yvoke.ingest.core.confluence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.sun.net.httpserver.HttpServer;
import de.palsoftware.yvoke.shared.security.SecretCipher;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class ConfluenceClientServicePageContentTest {

    private static final ConfluenceInstance INSTANCE =
        new ConfluenceInstance(UUID.randomUUID(), "Docs", "docs", "https://wiki.example.com",
            "e@x.com", "tok", null, "SPACE", "123", "", "", "coll", "v1", false, true, null, null);

    private static final class TestClient extends ConfluenceClientService implements AutoCloseable {
        private final HttpServer server;
        private final RestClient client;
        private final Deque<String> responseBodies = new ArrayDeque<>();
        private final Deque<Integer> statusCodes = new ArrayDeque<>();
        private final List<String> requestedUris = new CopyOnWriteArrayList<>();

        private TestClient() throws IOException {
            super(mock(SecretCipher.class), RestClient.builder(), 3, 1, 5);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/rest/api/content/", exchange -> {
                requestedUris.add(exchange.getRequestURI().toString());
                exchange.getRequestBody().readAllBytes();
                int status = statusCodes.isEmpty() ? 200 : statusCodes.poll();
                byte[] payload = (responseBodies.isEmpty() ? "{}" : responseBodies.poll())
                    .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, payload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                }
            });
            server.start();
            client = RestClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build();
        }

        private void enqueueResponse(String body) {
            responseBodies.add(body);
        }

        private void enqueueResponse(int status, String body) {
            statusCodes.add(status);
            responseBodies.add(body);
        }

        @Override
        public RestClient getClient(ConfluenceInstance instance) {
            return client;
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    @Test
    void getPageContentExtractsXhtmlAuthorLastUpdatedAndVersion() throws IOException {
        String json = """
            {
              "id": "12345",
              "body": {
                "storage": {
                  "value": "<p>Hello Confluence</p>"
                }
              },
              "version": {
                "by": {
                  "displayName": "Alice Admin",
                  "publicName": "Alice",
                  "username": "aadmin"
                },
                "when": "2024-03-15T12:00:00.000Z",
                "number": 5
              }
            }
            """;

        try (TestClient client = new TestClient()) {
            client.enqueueResponse(json);

            ConfluencePageContent content = client.getPageContent(INSTANCE, "12345");

            assertThat(content.xhtml()).isEqualTo("<p>Hello Confluence</p>");
            assertThat(content.author()).isEqualTo("Alice Admin");
            assertThat(content.lastUpdated()).isEqualTo("2024-03-15");
            assertThat(content.version()).isEqualTo(5);

            assertThat(client.requestedUris).hasSize(1);
            assertThat(client.requestedUris.get(0))
                .isEqualTo("/rest/api/content/12345?expand=body.storage,version");
        }
    }

    @Test
    void getPageContentFallsBackToPublicNameWhenDisplayNameMissing() throws IOException {
        String json = """
            {
              "id": "12345",
              "body": {
                "storage": {
                  "value": "<p>Content</p>"
                }
              },
              "version": {
                "by": {
                  "publicName": "Bob Public",
                  "username": "buser"
                },
                "when": "2024-03-15T10:00:00Z",
                "number": 2
              }
            }
            """;

        try (TestClient client = new TestClient()) {
            client.enqueueResponse(json);

            ConfluencePageContent content = client.getPageContent(INSTANCE, "12345");

            assertThat(content.author()).isEqualTo("Bob Public");
        }
    }

    @Test
    void getPageContentFallsBackToUsernameWhenDisplayNameAndPublicNameMissing() throws IOException {
        String json = """
            {
              "id": "12345",
              "body": {
                "storage": {
                  "value": "<p>Content</p>"
                }
              },
              "version": {
                "by": {
                  "username": "charlie_u"
                },
                "when": "2024-03-15T10:00:00Z",
                "number": 1
              }
            }
            """;

        try (TestClient client = new TestClient()) {
            client.enqueueResponse(json);

            ConfluencePageContent content = client.getPageContent(INSTANCE, "12345");

            assertThat(content.author()).isEqualTo("charlie_u");
        }
    }

    @Test
    void getPageContentHandlesMissingVersionGracefully() throws IOException {
        String json = """
            {
              "id": "12345",
              "body": {
                "storage": {
                  "value": "<p>No version info</p>"
                }
              }
            }
            """;

        try (TestClient client = new TestClient()) {
            client.enqueueResponse(json);

            ConfluencePageContent content = client.getPageContent(INSTANCE, "12345");

            assertThat(content.xhtml()).isEqualTo("<p>No version info</p>");
            assertThat(content.author()).isNull();
            assertThat(content.lastUpdated()).isNull();
            assertThat(content.version()).isNull();
        }
    }

    @Test
    void getPageContentHandlesMissingAuthorGracefully() throws IOException {
        String json = """
            {
              "id": "12345",
              "body": {
                "storage": {
                  "value": "<p>Anonymous edit</p>"
                }
              },
              "version": {
                "when": "2024-03-15T12:00:00.000Z",
                "number": 1
              }
            }
            """;

        try (TestClient client = new TestClient()) {
            client.enqueueResponse(json);

            ConfluencePageContent content = client.getPageContent(INSTANCE, "12345");

            assertThat(content.xhtml()).isEqualTo("<p>Anonymous edit</p>");
            assertThat(content.author()).isNull();
            assertThat(content.lastUpdated()).isEqualTo("2024-03-15");
            assertThat(content.version()).isEqualTo(1);
        }
    }

    @Test
    void getPageContentFormatsVariousIsoTimestampFormats() throws IOException {
        String jsonWithOffset = """
            {
              "id": "12345",
              "body": {
                "storage": {
                  "value": "<p>Timezones</p>"
                }
              },
              "version": {
                "when": "2024-03-15T18:30:00+05:30",
                "number": 1
              }
            }
            """;

        try (TestClient client = new TestClient()) {
            client.enqueueResponse(jsonWithOffset);

            ConfluencePageContent content = client.getPageContent(INSTANCE, "12345");
            assertThat(content.lastUpdated()).isEqualTo("2024-03-15");
        }
    }

    @Test
    void getPageContentFallsBackToBodyViewWhenStorageAbsent() throws IOException {
        String noStorageJson = """
            {
              "id": "12345",
              "body": {}
            }
            """;
        String viewJson = """
            {
              "id": "12345",
              "body": {
                "view": {
                  "value": "<p>Rendered view content</p>"
                }
              },
              "version": {
                "by": {
                  "displayName": "Editor Dave"
                },
                "when": "2024-03-15T12:00:00Z",
                "number": 4
              }
            }
            """;

        try (TestClient client = new TestClient()) {
            client.enqueueResponse(noStorageJson);
            client.enqueueResponse(viewJson);

            ConfluencePageContent content = client.getPageContent(INSTANCE, "12345");

            assertThat(content.xhtml()).isEqualTo("<p>Rendered view content</p>");
            assertThat(content.author()).isEqualTo("Editor Dave");
            assertThat(content.lastUpdated()).isEqualTo("2024-03-15");
            assertThat(content.version()).isEqualTo(4);

            assertThat(client.requestedUris).hasSize(2);
            assertThat(client.requestedUris.get(0))
                .isEqualTo("/rest/api/content/12345?expand=body.storage,version");
            assertThat(client.requestedUris.get(1))
                .isEqualTo("/rest/api/content/12345?expand=body.view,version");
        }
    }
}
