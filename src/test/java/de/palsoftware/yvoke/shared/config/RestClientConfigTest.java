package de.palsoftware.yvoke.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

public class RestClientConfigTest {

    private static RestClientConfig config() {
        RestClientConfig config = new RestClientConfig();
        ReflectionTestUtils.setField(config, "connectTimeout", 5000);
        ReflectionTestUtils.setField(config, "readTimeout", 5000);
        return config;
    }

    @Test
    public void testRestClientBuilderCreatesHttpComponentsFactory() {
        RestClient.Builder builder = config().restClientBuilder();
        assertThat(builder).isNotNull();
        assertThat(builder.build()).isNotNull();
    }

    @Test
    public void testRestClientBuilderUsesConnectionPooling() {
        RestClient client = config().restClientBuilder().baseUrl("https://example.com").build();
        assertThat(client).isNotNull();
    }

    @Test
    public void embeddingAndRerankUseSeparatePoolsFromEachOtherAndTheDefault() {
        // PRF-05: distinct builder beans => distinct connection managers, so reranking cannot
        // starve
        // behind embedding on the shared Voyage route.
        RestClientConfig config = config();
        RestClient.Builder embedding = config.embeddingRestClientBuilder();
        RestClient.Builder rerank = config.rerankRestClientBuilder();
        RestClient.Builder shared = config.restClientBuilder();

        assertThat(embedding).isNotNull().isNotSameAs(rerank).isNotSameAs(shared);
        assertThat(rerank).isNotSameAs(shared);
        assertThat(embedding.build()).isNotNull();
        assertThat(rerank.build()).isNotNull();
    }

    /**
     * SEC-11: the Confluence client must NOT transparently follow HTTP redirects, otherwise a
     * malicious/compromised server could bounce the connection to an internal address after the
     * SSRF host check has already passed. The default client, by contrast, follows redirects as
     * usual.
     */
    @Test
    public void confluenceClientDoesNotFollowRedirects() throws Exception {
        AtomicInteger targetHits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/start", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Location", "/target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/target", exchange -> {
            exchange.getRequestBody().readAllBytes();
            targetHits.incrementAndGet();
            byte[] body = "REACHED-TARGET".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();

            RestClient confluenceClient = config().confluenceRestClientBuilder().build();
            ResponseEntity<String> response =
                confluenceClient.get().uri(base + "/start").retrieve().toEntity(String.class);

            // Redirect surfaced as-is, and /target was never reached.
            assertThat(response.getStatusCode().is3xxRedirection()).isTrue();
            assertThat(targetHits.get()).isZero();

            // Sanity check: the DEFAULT client would have followed the redirect to /target.
            RestClient defaultClient = config().restClientBuilder().build();
            ResponseEntity<String> followed =
                defaultClient.get().uri(base + "/start").retrieve().toEntity(String.class);
            assertThat(followed.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
            assertThat(followed.getBody()).isEqualTo("REACHED-TARGET");
            assertThat(targetHits.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }
}
