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
