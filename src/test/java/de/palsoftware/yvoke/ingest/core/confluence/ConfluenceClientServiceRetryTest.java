package de.palsoftware.yvoke.ingest.core.confluence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import de.palsoftware.yvoke.shared.security.SecretCipher;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * A swallowed fetch failure used to be indistinguishable from an empty page: a throttled crawl of
 * ~616 pages produced an empty corpus while every job reported success.
 *
 * <p>
 * The SSRF guard in {@code getClient} refuses loopback addresses, so a local mock {@code
 * HttpServer} cannot be reached through this service — the fetch is stubbed at the
 * (package-private) {@code fetchPageBody} seam instead, which keeps the retry policy assertions
 * deterministic.
 */
class ConfluenceClientServiceRetryTest {

    private static final int MAX_ATTEMPTS = 3;

    /** Scripted marker: the response arrived, but carried no {@code body.<type>.value}. */
    private static final Object ABSENT = new Object();

    static final ConfluenceInstance INSTANCE =
        new ConfluenceInstance(UUID.randomUUID(), "Docs", "docs", "https://wiki.example.com",
            "e@x.com", "tok", null, "SPACE", "123", "", "", "coll", "v1", false, true, null, null);

    /** Records the requested body types and replays a scripted outcome for each call. */
    private static final class ScriptedClient extends ConfluenceClientService {

        private final Deque<Object> script = new ArrayDeque<>();
        private final AtomicInteger storageCalls = new AtomicInteger();
        private final AtomicInteger viewCalls = new AtomicInteger();
        private final AtomicInteger searchCalls = new AtomicInteger();
        private final AtomicInteger attachmentListCalls = new AtomicInteger();

        private ScriptedClient(Object... outcomes) {
            this(MAX_ATTEMPTS, outcomes);
        }

        private ScriptedClient(int maxAttempts, Object... outcomes) {
            super(mock(SecretCipher.class), RestClient.builder(), maxAttempts, 1, 5);
            script.addAll(List.of(outcomes));
        }

        private Object next() {
            Object next = script.isEmpty() ? "" : script.poll();
            if (next instanceof RuntimeException e) {
                throw e;
            }
            return next;
        }

        @Override
        Optional<String> fetchPageBody(ConfluenceInstance instance, String pageId,
            String bodyType) {
            if ("storage".equals(bodyType)) {
                storageCalls.incrementAndGet();
            } else {
                viewCalls.incrementAndGet();
            }
            Object outcome = next();
            // ABSENT models a 200 whose JSON carries no body.<type>.value at all — a page whose
            // body was not expanded. A String (including "") models the field being present.
            return outcome == ABSENT ? Optional.empty() : Optional.of((String) outcome);
        }

        @Override
        @SuppressWarnings("unchecked")
        Map<String, Object> fetchSearchPage(ConfluenceInstance instance, String baseUrl, URI uri) {
            searchCalls.incrementAndGet();
            return (Map<String, Object>) next();
        }

        @Override
        @SuppressWarnings("unchecked")
        Map<String, Object> fetchAttachmentList(ConfluenceInstance instance, String pageId) {
            attachmentListCalls.incrementAndGet();
            return (Map<String, Object>) next();
        }
    }

    private static HttpClientErrorException tooManyRequests(String retryAfter) {
        HttpHeaders headers = new HttpHeaders();
        if (retryAfter != null) {
            headers.set(HttpHeaders.RETRY_AFTER, retryAfter);
        }
        return HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests",
            headers, new byte[0], null);
    }

    @Test
    void rateLimitedFetchIsRetriedAndEventuallySucceeds() {
        ScriptedClient client =
            new ScriptedClient(tooManyRequests("1"), tooManyRequests(null), "<p>body</p>");

        String body = client.getPageBodyStorage(INSTANCE, "page-1");

        assertThat(body).isEqualTo("<p>body</p>");
        assertThat(client.storageCalls.get()).isEqualTo(3);
        assertThat(client.viewCalls.get()).isZero();
    }

    @Test
    void persistentRateLimitingFailsInsteadOfReturningAnEmptyBody() {
        ScriptedClient client = new ScriptedClient(tooManyRequests("1"), tooManyRequests("1"),
            tooManyRequests("1"), tooManyRequests("1"));

        assertThatThrownBy(() -> client.getPageBodyStorage(INSTANCE, "page-1"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("page-1")
            .hasMessageContaining("429");

        assertThat(client.storageCalls.get()).isEqualTo(MAX_ATTEMPTS);
    }

    @Test
    void serverErrorFallsBackToBodyViewAndSucceeds() {
        ScriptedClient client =
            new ScriptedClient(HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR,
                "boom", HttpHeaders.EMPTY, new byte[0], null), "<p>view body</p>");

        assertThat(client.getPageBodyStorage(INSTANCE, "page-1")).isEqualTo("<p>view body</p>");
        assertThat(client.viewCalls.get()).isEqualTo(1);
    }

    @Test
    void failingFallbackFailsTheJobInsteadOfReturningAnEmptyBody() {
        ScriptedClient client = new ScriptedClient(
            HttpClientErrorException.create(HttpStatus.NOT_FOUND, "nope", HttpHeaders.EMPTY,
                new byte[0], null),
            HttpClientErrorException.create(HttpStatus.NOT_FOUND, "nope", HttpHeaders.EMPTY,
                new byte[0], null));

        assertThatThrownBy(() -> client.getPageBodyStorage(INSTANCE, "page-1"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("page-1");
    }

    @Test
    void unexpandedStorageBodyFallsBackToBodyViewInsteadOfReportingAnEmptyPage() {
        // A 200 carrying no body.storage.value throws nothing, so the exception-driven body.view
        // fallback never fired: the page reached the ingest as "" and was recorded as a completed,
        // zero-chunk document — which the version-skip then never re-attempts. Exactly the legacy
        // storage format the fallback exists for.
        ScriptedClient client = new ScriptedClient(ABSENT, "<p>view body</p>");

        assertThat(client.getPageBodyStorage(INSTANCE, "page-1")).isEqualTo("<p>view body</p>");
        assertThat(client.storageCalls.get()).isEqualTo(1);
        assertThat(client.viewCalls.get()).isEqualTo(1);
    }

    @Test
    void unexpandedBodyInBothFormatsFailsInsteadOfSilentlyIngestingNothing() {
        ScriptedClient client = new ScriptedClient(ABSENT, ABSENT);

        assertThatThrownBy(() -> client.getPageBodyStorage(INSTANCE, "page-1"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("page-1");
    }

    @Test
    void genuinelyEmptyBodyStaysEmptyAndIsNotTreatedAsAFailure() {
        // The distinction the fix rests on: a page that really has no content (a hierarchy-only
        // parent, a page holding just a macro) reports an EMPTY value rather than an absent field.
        // That is a skip, not a failure, and must not burn a body.view round-trip either.
        ScriptedClient client = new ScriptedClient("");

        assertThat(client.getPageBodyStorage(INSTANCE, "page-1")).isEmpty();
        assertThat(client.storageCalls.get()).isEqualTo(1);
        assertThat(client.viewCalls.get()).isZero();
    }

    @Test
    void transportFailureIsNotSwallowed() {
        ScriptedClient client = new ScriptedClient(new ResourceAccessException("connection reset"));

        assertThatThrownBy(() -> client.getPageBodyStorage(INSTANCE, "page-1"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("page-1");
    }

    @Test
    void retryAfterHeaderIsHonouredWithinTheConfiguredCap() {
        // max-backoff is 5ms in this fixture, so a hostile "Retry-After: 600" must not stall the
        // worker for ten minutes.
        ScriptedClient client = new ScriptedClient(tooManyRequests("600"), "<p>body</p>");

        long start = System.nanoTime();
        assertThat(client.getPageBodyStorage(INSTANCE, "page-1")).isEqualTo("<p>body</p>");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isLessThan(2_000);
    }

    /**
     * {@code Retry-After} is defined as EITHER a delay in seconds OR an HTTP-date, and Atlassian —
     * plus any CDN or proxy fronting it — sends the date form. {@code backoff} parses it with
     * {@code Long.parseLong}, so the only thing keeping a date from becoming the failure is one
     * {@code catch (NumberFormatException)} whose body is a comment: it reads exactly like dead
     * code left over from a stricter parser, and deleting it is a tidy-up that compiles and keeps
     * every other test in this class green, because all of them script either a numeric value ("1",
     * "600") or no header at all.
     *
     * <p>
     * What breaks is not the parse — it is the RETRY. The exception is thrown from inside the
     * {@code catch} block of {@code withRateLimitRetry}, so it is not caught there; it propagates
     * into {@code getPageBodyStorage}'s generic {@code catch (RuntimeException)} and comes back out
     * as a hard failure of the page job. In other words the recovery path becomes the thing that
     * kills the crawl, and it does so on precisely the response the recovery exists for: a 429 the
     * server has told us is survivable. On a throttled site that is every in-flight page job at
     * once, and the reported error names a number-format problem rather than rate limiting.
     *
     * <p>
     * The assertion is that the fetch SUCCEEDS on the second attempt through the storage path — a
     * date must be ignored, not honoured, not fatal — and that no {@code body.view} round-trip was
     * burned, which is what distinguishes "the header was ignored" from "something threw and a
     * fallback rescued it".
     */
    @Test
    void anHttpDateRetryAfterFallsBackToTheExponentialDelayInsteadOfKillingTheRetry() {
        ScriptedClient client =
            new ScriptedClient(tooManyRequests("Wed, 21 Oct 2026 07:28:00 GMT"), "<p>body</p>");

        long start = System.nanoTime();
        assertThat(client.getPageBodyStorage(INSTANCE, "page-1"))
            .as("an HTTP-date Retry-After is ignored, so the ordinary backoff retries and succeeds")
            .isEqualTo("<p>body</p>");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(client.storageCalls.get()).as("one 429, then the retry").isEqualTo(2);
        assertThat(client.viewCalls.get())
            .as("a plain retry, not a rescue by the body.view fallback").isZero();
        // The exponential delay applies (1ms base, 5ms cap in this fixture): a date must never be
        // coerced into a sleep either.
        assertThat(elapsedMs).isLessThan(2_000);
    }

    /**
     * A large configured {@code max-attempts} used to overflow {@code 1L << (attempt - 1)} to a
     * negative delay, and {@code Thread.sleep} then threw {@code IllegalArgumentException} —
     * turning the retry itself into the failure.
     */
    @Test
    void aLargeMaxAttemptsDoesNotOverflowTheBackoffShift() {
        Object[] script = new Object[80];
        for (int i = 0; i < script.length - 1; i++) {
            script[i] = tooManyRequests(null);
        }
        script[script.length - 1] = "<p>body</p>";
        ScriptedClient client = new ScriptedClient(200, script);

        assertThat(client.getPageBodyStorage(INSTANCE, "page-1")).isEqualTo("<p>body</p>");
        assertThat(client.storageCalls.get()).isEqualTo(script.length);
    }

    // ---------------------------------------------------------------------
    // The retry must cover the whole crawl, not just the per-page body fetch: a 429 on one of the
    // ~13 CQL search pages failed the crawl AFTER it had enqueued hundreds of page jobs, with no
    // resume point.
    // ---------------------------------------------------------------------

    @Test
    void rateLimitedCrawlPagingIsRetriedInsteadOfFailingMidCrawl() {
        ScriptedClient client = new ScriptedClient(tooManyRequests("1"), tooManyRequests(null),
            Map.of("results", List.of(Map.of("id", "page-1"))));

        List<Map<String, Object>> seen = new ArrayList<>();
        client.crawlAllDescendantPages(INSTANCE, seen::addAll);

        assertThat(seen).hasSize(1);
        assertThat(client.searchCalls.get()).isEqualTo(3);
    }

    @Test
    void persistentlyRateLimitedCrawlFailsLoudly() {
        ScriptedClient client = new ScriptedClient(tooManyRequests("1"), tooManyRequests("1"),
            tooManyRequests("1"), tooManyRequests("1"));

        assertThatThrownBy(() -> client.crawlAllDescendantPages(INSTANCE, batch -> {
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("SPACE")
            .hasMessageContaining("429");
        assertThat(client.searchCalls.get()).isEqualTo(MAX_ATTEMPTS);
    }

    // ---------------------------------------------------------------------
    // Every test above replaces fetchPageBody with a script, so the parsing that DECIDES
    // absent-vs-empty — the single point the whole contract rests on — had no coverage at all. It
    // is driven here against a local mock Confluence instead.
    // ---------------------------------------------------------------------

    /**
     * Runs the real {@code fetchPageBody} against a local mock Confluence.
     *
     * <p>
     * Only {@code getClient(ConfluenceInstance)} is overridden: the SSRF guard on the credentials
     * overload refuses loopback, and replacing the client here leaves the response parsing — the
     * thing under test — untouched.
     */
    private static final class LocalServerClient extends ConfluenceClientService
        implements AutoCloseable {

        private final HttpServer server;
        private final RestClient client;

        private LocalServerClient(String... jsonBodies) throws IOException {
            super(mock(SecretCipher.class), RestClient.builder(), MAX_ATTEMPTS, 1, 5);
            Deque<String> bodies = new ArrayDeque<>(List.of(jsonBodies));
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/rest/api/content/", exchange -> {
                // Consume the request body before responding, or the socket closes early and the
                // client sees a flaky 400 (documented project pitfall).
                exchange.getRequestBody().readAllBytes();
                byte[] payload =
                    (bodies.isEmpty() ? "{}" : bodies.poll()).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, payload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                }
            });
            server.start();
            client = RestClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build();
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

    /**
     * A 200 that never expanded the body must be ABSENT, and a 200 carrying an explicitly empty
     * value must be PRESENT-and-empty. Collapsing the two into {@code ""} is silent, permanent
     * content loss: {@code getPageBodyStorage} only falls back from {@code storage} to {@code view}
     * when the storage fetch is absent, so a page whose body was simply not expanded would be
     * ingested as a completed zero-chunk document, its {@code confluence_page_version} would be
     * recorded, and the version-skip would stop any later crawl from ever retrying it — on a job
     * that reports success. Every other test in this class overrides {@code fetchPageBody} away, so
     * this is the only place the classification itself runs.
     */
    @Test
    void aResponseWithNoExpandedBodyFieldIsAbsentNotEmpty() throws IOException {
        try (LocalServerClient client = new LocalServerClient("{}", "{\"body\":{}}",
            "{\"body\":{\"storage\":{}}}", "{\"body\":{\"storage\":{\"value\":\"\"}}}")) {

            // No body at all, no body.storage, and a body.storage with no value: all "not
            // expanded", none of which threw, so only the Optional can tell the caller.
            assertThat(client.fetchPageBody(INSTANCE, "page-1", "storage")).isEmpty();
            assertThat(client.fetchPageBody(INSTANCE, "page-2", "storage")).isEmpty();
            assertThat(client.fetchPageBody(INSTANCE, "page-3", "storage")).isEmpty();

            // A page that genuinely has no content: present, and empty.
            assertThat(client.fetchPageBody(INSTANCE, "page-4", "storage")).contains("");
        }
    }

    // ---------------------------------------------------------------------
    // getPageAttachments used to swallow every failure and return an empty list, so with
    // processAttachments=true a throttling server produced pages that ingested green with all
    // attachment text missing.
    // ---------------------------------------------------------------------

    @Test
    void rateLimitedAttachmentListingIsRetriedAndEventuallySucceeds() {
        ScriptedClient client = new ScriptedClient(tooManyRequests("1"),
            Map.of("results", List.of(Map.of("title", "spec.pdf"))));

        assertThat(client.getPageAttachments(INSTANCE, "page-1")).hasSize(1);
        assertThat(client.attachmentListCalls.get()).isEqualTo(2);
    }

    @Test
    void failingAttachmentListingPropagatesInsteadOfReturningAnEmptyList() {
        ScriptedClient client = new ScriptedClient(HttpClientErrorException
            .create(HttpStatus.FORBIDDEN, "nope", HttpHeaders.EMPTY, new byte[0], null));

        assertThatThrownBy(() -> client.getPageAttachments(INSTANCE, "page-1"))
            .isInstanceOf(HttpClientErrorException.class);
    }

    @Test
    void persistentlyRateLimitedAttachmentListingFailsLoudly() {
        ScriptedClient client =
            new ScriptedClient(tooManyRequests("1"), tooManyRequests("1"), tooManyRequests("1"));

        assertThatThrownBy(() -> client.getPageAttachments(INSTANCE, "page-1"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("page-1")
            .hasMessageContaining("429");
        assertThat(client.attachmentListCalls.get()).isEqualTo(MAX_ATTEMPTS);
    }
}
