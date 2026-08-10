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
import com.sun.net.httpserver.HttpExchange;
import java.util.ArrayList;
import java.util.Collections;

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

    /**
     * The one path in this application that AUTHENTICATES with a stored secret must use
     * {@code decryptOrThrow}, never {@code decrypt}.
     *
     * <p>
     * {@code SecretCipherTest} pins what each method does in isolation — {@code decrypt} degrades
     * an unreadable secret to {@code ""}, {@code decryptOrThrow} raises, {@code canDecrypt} never
     * throws — but nothing checks which one each caller picks, and the three are interchangeable at
     * the call site: same signature, same return type, no compiler help. Swapping this one for
     * {@code decrypt} (the shorter name, and the obvious "stop throwing on a list page" edit if the
     * two call sites are ever unified) makes {@code resolveApiToken} hand back an empty string, so
     * the connector builds a client with {@code Basic base64(email:)} and sync fails against
     * Atlassian with 401s. The operator then debugs a permissions problem — re-inviting the service
     * account, checking space access — when the actual cause is a rotated {@code APP_SECRET_KEY} on
     * this side, which nothing in the failure mentions.
     *
     * <p>
     * The rotated-key state is reached the way production reaches it: a ciphertext produced by a
     * key that is no longer configured, with the fingerprint of that retired key still on the row.
     * The {@code UNDECRYPTABLE} pre-assertion is the non-vacuity guard — it proves the token is
     * present (so the earlier {@code MISSING} short-circuit is not what threw) and that decryption
     * is really attempted. The final assertion is SEC-17: an error about a credential must never
     * carry the credential.
     */
    @Test
    void aTokenEncryptedUnderARetiredKeyFailsLoudlyInsteadOfAuthenticatingWithAnEmptyString() {
        SecretCipher retiredKey =
            new SecretCipher("retired-" + SECRET_KEY, SALT, mock(Environment.class));
        String ciphertextFromTheRetiredKey = retiredKey.encrypt("s3cr3t-atlassian-token");

        ConfluenceInstance rotated = new ConfluenceInstance(UUID.randomUUID(), "Acme", "acme",
            "https://acme.atlassian.net/wiki", "svc@example.com", ciphertextFromTheRetiredKey,
            retiredKey.keyId(), "SPACE", "1", null, null, "coll", null, false, true, null, null);

        assertThat(rotated.tokenHealth(cipher.keyId()))
            .as("a token IS stored, so resolveApiToken must get past its MISSING short-circuit and"
                + " actually attempt the decryption")
            .isEqualTo(TokenHealth.UNDECRYPTABLE);

        assertThatThrownBy(() -> service.resolveApiToken(rotated)).as(
            "an unreadable credential must raise here, not authenticate with \"\" and surface as"
                + " a 401 from Atlassian that reads as a permissions problem")
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("Acme")
            .hasMessageNotContaining("s3cr3t-atlassian-token");
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
    // testConnection renders sample page titles into HTML that the controller then injects
    // WITHOUT escaping (ConfluenceConnectorController says so in a comment right above the
    // interpolation), so the htmlEscape here is the only thing standing between a Confluence
    // author and an admin's browser.
    // ---------------------------------------------------------------------

    /**
     * A Confluence page title is chosen by whoever can create a page in the connected space, and
     * {@code samplePagesHtml} is the ONE field {@code ConfluenceConnectorController} interpolates
     * into its htmx response unescaped: it escapes {@code message}, {@code spaceName} and
     * {@code parentPageTitle} and then explicitly trusts this one ("already escaped per-item at the
     * source"). If the escaping here regresses, a page titled {@code <script>...</script>} executes
     * in the browser of an authenticated admin sitting on /admin/connectors - the highest-privilege
     * page in the app, the one that can read and rewrite every connector's stored credentials. That
     * is stored XSS authored on a remote wiki and delivered through an htmx swap. Nothing else can
     * catch the regression: the controller test mocks this service so it would keep passing, and
     * the controller is documented as trusting the field. The assertion is therefore on the
     * RENDERED string - no {@code <script>} / {@code <img} may survive, and the escaped forms must
     * be present - never on a call to htmlEscape.
     */
    @Test
    void anAttackerAuthoredPageTitleIsEscapedBeforeItReachesTheAdminPage() {
        serveConnectionTest(200, "{\"totalSize\":1,\"results\":[{\"id\":\"42<img src=x>\","
            + "\"title\":\"<script>alert(1)</script>\"}]}");

        ConfluenceClientService.ConnectionTestResult result = service.testConnection(
            "acme.atlassian.net", "svc@example.com", "tok", "SPACE", "12345", null, null);

        assertThat(result.ok()).isTrue();
        assertThat(result.samplePagesHtml()).doesNotContain("<script>").doesNotContain("<img")
            .contains("&lt;script&gt;alert(1)&lt;/script&gt;").contains("42&lt;img src=x&gt;");
    }

    /**
     * Registers the three endpoints {@code testConnection} walks in order - space info, parent page
     * info, CQL search. Only the search response varies between the cases here. The longest
     * matching context path wins in {@link HttpServer}, so {@code /rest/api/content/search} does
     * not fall through to the parent-page handler.
     */
    private void serveConnectionTest(int searchStatus, String searchBody) {
        server.createContext("/rest/api/space",
            exchange -> respondJson(exchange, 200, "{\"name\":\"Engineering\"}"));
        server.createContext("/rest/api/content", exchange -> respondJson(exchange, 200,
            "{\"title\":\"Root Page\",\"version\":{\"number\":7}}"));
        server.createContext("/rest/api/content/search",
            exchange -> respondJson(exchange, searchStatus, searchBody));
    }

    private static void respondJson(HttpExchange exchange, int status, String json)
        throws IOException {
        // Consume the request body before responding, or the socket closes early and the client
        // sees a flaky 400 (documented project pitfall).
        exchange.getRequestBody().readAllBytes();
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /**
     * "Test Connection" is the only feedback an admin gets BEFORE a crawl runs, and its green card
     * is read as "this whole configuration works". It only means that if all three probes actually
     * ran, because each one validates a DIFFERENT field of the form:
     * {@code /rest/api/space/{space}} the space key,
     * {@code /rest/api/content/{rootPageId}?expand=version} the root page id, and
     * {@code /rest/api/content/search?cql&limit=5} the query the crawl will really issue.
     *
     * <p>
     * The root-page probe is the fragile one and the one that matters most. A mistyped or
     * since-deleted root page id is invisible to the other two — the space still resolves and the
     * CQL still parses — so if that probe is dropped, weakened, or loses its
     * {@code expand=version}, the admin is told "Connection OK" and the crawl that follows finds
     * nothing under the root: an empty collection, a green job, and no signal anywhere on the
     * connectors page. Nothing currently notices. {@code serveConnectionTest} merely registers
     * three contexts, so a {@code testConnection} that called only one of them would satisfy every
     * existing test in this class, and the two tests that do drive it assert only the escaping and
     * the failure branch — never the success payload, so {@code spaceName}, {@code parentPageTitle}
     * and {@code parentPageVersion} have shipped unasserted as well, and those three ARE the card.
     *
     * <p>
     * The assertion is therefore on the recorded request URIs, in order, with an exact count: this
     * is a contract about which remote calls happen, and an extra or missing round-trip is as much
     * a regression as a wrong one.
     */
    @Test
    void theConnectionTestProbesTheSpaceTheRootPageAndTheCqlSearchInThatOrder() {
        List<String> requested = Collections.synchronizedList(new ArrayList<>());
        server.createContext("/rest/api/space", exchange -> {
            requested.add(exchange.getRequestURI().toString());
            respondJson(exchange, 200, "{\"name\":\"Engineering\"}");
        });
        server.createContext("/rest/api/content", exchange -> {
            requested.add(exchange.getRequestURI().toString());
            respondJson(exchange, 200, "{\"title\":\"Root Page\",\"version\":{\"number\":7}}");
        });
        server.createContext("/rest/api/content/search", exchange -> {
            requested.add(exchange.getRequestURI().toString());
            respondJson(exchange, 200,
                "{\"totalSize\":42,\"results\":[{\"id\":\"9\",\"title\":\"Child\"}]}");
        });

        ConfluenceClientService.ConnectionTestResult result = service.testConnection(
            "acme.atlassian.net", "svc@example.com", "tok", "SPACE", "12345", null, null);

        // The success card, field by field — every one of them comes from a different probe.
        assertThat(result.ok()).isTrue();
        assertThat(result.spaceName()).isEqualTo("Engineering");
        assertThat(result.parentPageTitle()).isEqualTo("Root Page");
        assertThat(result.parentPageVersion()).isEqualTo(7);
        assertThat(result.pagesCount()).isEqualTo(42);

        assertThat(requested).as("exactly three probes, no more and no fewer").hasSize(3);
        assertThat(requested.get(0)).as("1. the space key").isEqualTo("/rest/api/space/SPACE");
        assertThat(requested.get(1)).as("2. the ROOT PAGE id — the field nothing else validates")
            .startsWith("/rest/api/content/12345").contains("expand=version");
        assertThat(requested.get(2)).as("3. the CQL the crawl will actually run")
            .startsWith("/rest/api/content/search").contains("cql=").contains("limit=5");
    }

    /**
     * The connection test is the admin's only feedback loop when a token is revoked, an e-mail is
     * wrong or a space key is a typo, and it runs through htmx: the fragment this result produces
     * IS what the page shows. The {@code RestClientResponseException} branch is what turns
     * Atlassian's rejection into that fragment - if it stops degrading to
     * {@code ConnectionTestResult.failure}, the exception escapes into the MVC error path and the
     * admin gets a generic card (or an empty swap) naming neither the status nor Atlassian's own
     * explanation, leaving "it does not work" with no way to tell a 401 (bad token) from a 404 (bad
     * space) from a 429 (throttled) - and the operator's next move is different in all three cases.
     * The status code and the API's message are therefore both asserted; they are the entire
     * diagnostic value of this branch. Note the failure result must also stay empty of connection
     * details ({@code spaceName} null, {@code samplePagesHtml} empty), because the controller
     * renders the success card purely on {@code ok()}.
     */
    @Test
    void aRejectedCredentialIsReportedAsAFailureNamingTheStatusAndTheApiExplanation() {
        serveConnectionTest(401,
            "{\"message\":\"Basic authentication with passwords is deprecated.\"}");

        ConfluenceClientService.ConnectionTestResult result = service.testConnection(
            "acme.atlassian.net", "svc@example.com", "revoked", "SPACE", "12345", null, null);

        assertThat(result.ok()).isFalse();
        assertThat(result.message()).contains("HTTP 401")
            .contains("Basic authentication with passwords is deprecated.");
        assertThat(result.spaceName()).isNull();
        assertThat(result.samplePagesHtml()).isEmpty();
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
