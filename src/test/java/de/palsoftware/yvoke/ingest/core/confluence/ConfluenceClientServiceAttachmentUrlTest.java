package de.palsoftware.yvoke.ingest.core.confluence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.sun.net.httpserver.HttpServer;
import de.palsoftware.yvoke.shared.security.SecretCipher;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.web.client.RestClient;

/**
 * An attachment's download URL comes from the Confluence response body — i.e. from a server that
 * may be hostile or compromised — and the request that follows it carries the instance's
 * {@code Authorization} header.
 *
 * <p>
 * The guard used to decide "is this absolute?" with
 * {@code startsWith("http://") || startsWith("https://")}, which is CASE-SENSITIVE, while every
 * downstream parser is not: {@code UriComponentsBuilder} reads the scheme case-insensitively and
 * Apache HttpClient lower-cases it in {@code HttpHost}. So {@code HTTPS://169.254.169.254/…} looked
 * relative to the guard, skipped BOTH the SSRF check and the same-host check, and was still issued
 * as an absolute, authenticated request to the cloud metadata endpoint — whose response Tika then
 * appended into the ingested page.
 */
class ConfluenceClientServiceAttachmentUrlTest {

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private GuardedClientService service;

    /** Routes every request to the local mock server, leaving only the URL guards under test. */
    private final class GuardedClientService extends ConfluenceClientService {

        private GuardedClientService() {
            super(mock(SecretCipher.class), RestClient.builder(), 1, 1, 5);
        }

        @Override
        public RestClient getClient(String domain, String email, String apiToken) {
            return RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .build();
        }
    }

    private ConfluenceInstance instance() {
        return new ConfluenceInstance(UUID.randomUUID(), "Acme", "acme",
            "https://acme.atlassian.net/wiki", "svc@example.com", "tok", null, "SPACE", "1", null,
            null, "coll", null, false, true, null, null);
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            // Consume the request body before responding, or the socket closes early and the
            // client sees a flaky 400 (documented project pitfall).
            exchange.getRequestBody().readAllBytes();
            requests.incrementAndGet();
            byte[] body = "attachment-bytes".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        service = new GuardedClientService();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    @Timeout(30)
    void anUppercaseSchemeToTheCloudMetadataEndpointIsRejected() {
        assertThatThrownBy(() -> service.downloadAttachment(instance(),
            "HTTPS://169.254.169.254/latest/meta-data/iam/security-credentials/"))
            .isInstanceOf(IllegalArgumentException.class);

        assertThat(requests.get()).isZero();
    }

    @Test
    @Timeout(30)
    void aMixedCaseSchemeToAPrivateAddressIsRejected() {
        assertThatThrownBy(
            () -> service.downloadAttachment(instance(), "HtTpS://10.0.0.5/admin/secrets"))
            .isInstanceOf(IllegalArgumentException.class);

        assertThat(requests.get()).isZero();
    }

    /** Protocol-relative: no scheme at all, but an authority — the client would still leave. */
    @Test
    @Timeout(30)
    void aProtocolRelativeUrlIsRejected() {
        assertThatThrownBy(
            () -> service.downloadAttachment(instance(), "//169.254.169.254/latest/meta-data/"))
            .isInstanceOf(IllegalArgumentException.class);

        assertThat(requests.get()).isZero();
    }

    /** The same-host guard must run for an uppercase scheme too, not just the SSRF guard. */
    @Test
    @Timeout(30)
    void anUppercaseSchemeToAPublicButForeignHostIsRejected() {
        assertThatThrownBy(() -> service.downloadAttachment(instance(), "HTTPS://8.8.8.8/evil.bin"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("different host");

        assertThat(requests.get()).isZero();
    }

    @Test
    @Timeout(30)
    void aNormalRelativeDownloadUrlStillWorks() {
        byte[] bytes = service.downloadAttachment(instance(),
            "/download/attachments/12345/spec.pdf?version=1&api=v2");

        assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("attachment-bytes");
        assertThat(requests.get()).isEqualTo(1);
    }
}
