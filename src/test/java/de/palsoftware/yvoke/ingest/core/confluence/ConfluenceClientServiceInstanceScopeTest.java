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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestClient;

/**
 * The connector is instance-scoped: nothing here may be shared between two connected Confluence
 * sites.
 *
 * <p>
 * The display-name cache is the sharp edge. It used to be keyed by {@code accountId} ALONE, and an
 * Atlassian account id is only unique WITHIN a site — so with two instances connected, the first
 * site to resolve an id decided the name every other site would render for it, silently attributing
 * one company's page to another company's employee.
 */
class ConfluenceClientServiceInstanceScopeTest {

    private static final String SECRET_KEY = "unit-test-secret-key";
    private static final String SALT = "deadbeefcafe1234";

    private HttpServer server;
    private final AtomicInteger userRequests = new AtomicInteger();

    private SecretCipher cipher;
    private CountingClientService service;

    /**
     * Counts client builds and routes every request to the local mock server. Overriding the
     * ad-hoc-credentials overload leaves the per-instance caching logic under test while keeping
     * the SSRF guard (which refuses loopback) out of the way.
     */
    private final class CountingClientService extends ConfluenceClientService {

        private final AtomicInteger builds = new AtomicInteger();

        private CountingClientService(SecretCipher secretCipher) {
            super(secretCipher, RestClient.builder(), 3, 1, 5);
        }

        @Override
        public RestClient getClient(String domain, String email, String apiToken) {
            builds.incrementAndGet();
            return RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .build();
        }
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rest/api/user", exchange -> {
            // Consume the request body before responding, or the socket closes early and the
            // client sees a flaky 400 (documented project pitfall).
            exchange.getRequestBody().readAllBytes();
            byte[] body = ("{\"displayName\":\"Person " + userRequests.incrementAndGet() + "\"}")
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();

        cipher = new SecretCipher(SECRET_KEY, SALT, mock(Environment.class));
        service = new CountingClientService(cipher);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private ConfluenceInstance instance(UUID id, String name, String slug, String tokenEnc) {
        return new ConfluenceInstance(id, name, slug, "https://" + slug + ".atlassian.net/wiki",
            "svc@example.com", tokenEnc, cipher.keyId(), "SPACE", "1", null, null, "coll", null,
            false, true, null, null);
    }

    @Test
    void twoInstancesDoNotShareADisplayNameForTheSameAccountId() {
        ConfluenceInstance acme = instance(UUID.randomUUID(), "Acme", "acme", cipher.encrypt("a"));
        ConfluenceInstance globex =
            instance(UUID.randomUUID(), "Globex", "globex", cipher.encrypt("b"));

        String onAcme = service.getUserDisplayName(acme, "557058:same-id");
        String onGlobex = service.getUserDisplayName(globex, "557058:same-id");

        assertThat(onAcme).isEqualTo("Person 1");
        assertThat(onGlobex).isEqualTo("Person 2");
        assertThat(userRequests.get()).isEqualTo(2);
    }

    @Test
    void aDisplayNameIsStillCachedPerInstance() {
        ConfluenceInstance acme = instance(UUID.randomUUID(), "Acme", "acme", cipher.encrypt("a"));

        assertThat(service.getUserDisplayName(acme, "557058:same-id")).isEqualTo("Person 1");
        assertThat(service.getUserDisplayName(acme, "557058:same-id")).isEqualTo("Person 1");

        assertThat(userRequests.get()).isEqualTo(1);
    }

    @Test
    void theClientIsBuiltOncePerInstanceAndNotSharedBetweenThem() {
        ConfluenceInstance acme = instance(UUID.randomUUID(), "Acme", "acme", cipher.encrypt("a"));
        ConfluenceInstance globex =
            instance(UUID.randomUUID(), "Globex", "globex", cipher.encrypt("b"));

        service.getClient(acme);
        service.getClient(acme);
        service.getClient(globex);
        service.getClient(globex);

        assertThat(service.builds.get()).isEqualTo(2);
    }

    @Test
    void aReEnteredTokenTakesEffectWithoutARestart() {
        UUID id = UUID.randomUUID();
        ConfluenceInstance before = instance(id, "Acme", "acme", cipher.encrypt("old"));
        service.getClient(before);

        ConfluenceInstance after = instance(id, "Acme", "acme", cipher.encrypt("new"));
        service.getClient(after);

        assertThat(service.builds.get()).isEqualTo(2);
    }

    @Test
    void editingAnInstancesDomainAlsoDropsItsCachedDisplayNames() {
        UUID id = UUID.randomUUID();
        ConfluenceInstance before = instance(id, "Acme", "acme", cipher.encrypt("tok"));
        assertThat(service.getUserDisplayName(before, "557058:same-id")).isEqualTo("Person 1");

        // Repointed at a different site: the account ids cached for the old one mean nothing there.
        ConfluenceInstance moved = new ConfluenceInstance(id, "Acme", "acme",
            "https://acme-eu.atlassian.net/wiki", "svc@example.com", cipher.encrypt("tok"),
            cipher.keyId(), "SPACE", "1", null, null, "coll", null, false, true, null, null);

        assertThat(service.getUserDisplayName(moved, "557058:same-id")).isEqualTo("Person 2");
    }

    // ---------------------------------------------------------------------
    // Deleting an instance, or clearing its token, left the built RestClient in the cache — and
    // that client carries `Basic base64(email:plaintextToken)` as a DEFAULT HEADER, so a revoked
    // credential stayed reachable in the heap until the next restart. The display-name cache only
    // ever shrank when a client happened to be rebuilt.
    // ---------------------------------------------------------------------

    @Test
    void evictingAnInstanceDropsItsClientAndItsCachedDisplayNames() {
        UUID id = UUID.randomUUID();
        ConfluenceInstance acme = instance(id, "Acme", "acme", cipher.encrypt("tok"));
        assertThat(service.getUserDisplayName(acme, "557058:same-id")).isEqualTo("Person 1");
        assertThat(service.builds.get()).isEqualTo(1);

        service.evict(id);

        assertThat(service.getUserDisplayName(acme, "557058:same-id")).isEqualTo("Person 2");
        assertThat(service.builds.get()).isEqualTo(2);
    }

    @Test
    void evictingAnInstanceLeavesEveryOtherInstanceAlone() {
        UUID acmeId = UUID.randomUUID();
        ConfluenceInstance acme = instance(acmeId, "Acme", "acme", cipher.encrypt("a"));
        ConfluenceInstance globex =
            instance(UUID.randomUUID(), "Globex", "globex", cipher.encrypt("b"));
        assertThat(service.getUserDisplayName(acme, "557058:same-id")).isEqualTo("Person 1");
        assertThat(service.getUserDisplayName(globex, "557058:same-id")).isEqualTo("Person 2");

        service.evict(acmeId);

        assertThat(service.getUserDisplayName(globex, "557058:same-id")).isEqualTo("Person 2");
        assertThat(userRequests.get()).isEqualTo(2);
    }

    /** The repository announces a delete / cleared token; the listener is what evicts. */
    @Test
    void theCredentialsChangedEventEvictsTheInstance() {
        UUID id = UUID.randomUUID();
        ConfluenceInstance acme = instance(id, "Acme", "acme", cipher.encrypt("tok"));
        assertThat(service.getUserDisplayName(acme, "557058:same-id")).isEqualTo("Person 1");

        service.onInstanceCredentialsChanged(new ConfluenceInstanceCredentialsChangedEvent(id));

        assertThat(service.getUserDisplayName(acme, "557058:same-id")).isEqualTo("Person 2");
    }

    // ---------------------------------------------------------------------
    // Credential failures must name the instance and its token health. "Confluence connector is
    // not configured" said nothing about WHICH of several sites was broken, or what to do.
    // ---------------------------------------------------------------------

    @Test
    void aMissingTokenFailsNamingTheInstanceAndItsHealth() {
        ConfluenceInstance noToken = new ConfluenceInstance(UUID.randomUUID(), "iCC Wiki", "icc",
            "https://icc.atlassian.net/wiki", "svc@example.com", null, null, "SPACE", "1", null,
            null, "coll", null, false, true, null, null);

        assertThatThrownBy(() -> service.resolveApiToken(noToken))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("iCC Wiki")
            .hasMessageContaining(TokenHealth.MISSING.name());
    }

    @Test
    void anUndecryptableTokenFailsNamingTheInstanceWithoutLeakingTheCiphertext() {
        String ciphertext = cipher.encrypt("the-real-token");
        // Same ciphertext, a key that is gone: exactly what an APP_SECRET_KEY rotation leaves.
        SecretCipher rotated =
            new SecretCipher("a-different-secret-key", SALT, mock(Environment.class));
        CountingClientService rotatedService = new CountingClientService(rotated);
        ConfluenceInstance stale = new ConfluenceInstance(UUID.randomUUID(), "iCC Wiki", "icc",
            "https://icc.atlassian.net/wiki", "svc@example.com", ciphertext, "a-retired-key",
            "SPACE", "1", null, null, "coll", null, false, true, null, null);

        assertThatThrownBy(() -> rotatedService.resolveApiToken(stale))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("iCC Wiki")
            .hasMessageContaining(TokenHealth.UNDECRYPTABLE.name())
            .hasMessageContaining("re-enter the token")
            .satisfies(e -> assertThat(List.of(String.valueOf(e.getMessage()))).noneMatch(
                message -> message.contains(ciphertext) || message.contains("the-real-token")));
    }
}
