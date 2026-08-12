package de.palsoftware.yvoke.llm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.azure.core.exception.HttpResponseException;
import com.azure.core.http.HttpHeaderName;
import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpMethod;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.HttpResponse;
import com.google.genai.errors.ApiException;
import com.google.genai.errors.GenAiIOException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

class LlmRetryTest {

    @Test
    void testIsTransientWithApiException() {
        // Test various HTTP response codes
        assertTrue(LlmRetry.isTransient(new ApiException(408, "TIMEOUT", "Request timed out")));
        assertTrue(LlmRetry
            .isTransient(new ApiException(429, "RESOURCE_EXHAUSTED", "Rate limit exceeded")));
        assertTrue(LlmRetry.isTransient(new ApiException(500, "INTERNAL", "Internal error")));
        assertTrue(LlmRetry.isTransient(new ApiException(502, "BAD_GATEWAY", "Bad Gateway")));
        assertTrue(
            LlmRetry.isTransient(new ApiException(503, "UNAVAILABLE", "Service Unavailable")));
        assertTrue(
            LlmRetry.isTransient(new ApiException(504, "GATEWAY_TIMEOUT", "Gateway Timeout")));

        // Test non-transient HTTP codes
        assertFalse(LlmRetry.isTransient(new ApiException(400, "INVALID_ARGUMENT", "Bad Request")));
        assertFalse(LlmRetry.isTransient(new ApiException(401, "UNAUTHENTICATED", "Unauthorized")));
        assertFalse(LlmRetry.isTransient(new ApiException(403, "PERMISSION_DENIED", "Forbidden")));
        assertFalse(LlmRetry.isTransient(new ApiException(404, "NOT_FOUND", "Not Found")));
        assertFalse(LlmRetry.isTransient(new ApiException(409, "ALREADY_EXISTS", "Conflict")));
    }

    @Test
    void testIsTransientWithIoExceptions() {
        assertTrue(LlmRetry.isTransient(new GenAiIOException("network error", null)));
        assertTrue(LlmRetry.isTransient(new SocketTimeoutException("read timed out")));
        assertTrue(LlmRetry.isTransient(new IOException("generic io exception")));
    }

    @Test
    void testIsTransientWithMessageMatching() {
        // String/Message matching checks
        assertTrue(LlmRetry.isTransient(new RuntimeException("Rate limit exceeded")));
        assertTrue(LlmRetry.isTransient(new RuntimeException("model overload error")));
        assertTrue(LlmRetry.isTransient(new RuntimeException("Connection timed out")));
        assertTrue(LlmRetry.isTransient(new RuntimeException("Internal 500 error")));

        // Non-matching messages
        assertFalse(LlmRetry.isTransient(new RuntimeException("Some other random error")));
        assertFalse(LlmRetry.isTransient(new IllegalArgumentException("invalid argument")));
    }

    // --- Attempt accounting. The count only ever reached an slf4j line, so a run that died after
    // exhausting its retries recorded no trace of having retried at all. ---

    /**
     * Interruption always wins over the provider error. A user pressing Stop interrupts the worker
     * thread; if the provider exception were rethrown instead, a deliberate cancellation would be
     * captioned as a system failure — the exact conflation that once rendered a failed run as
     * "[Generation stopped by user]" and a stopped one as an error. Two distinct guards exist (the
     * loop top, and the backoff sleep) and both must hold: the backoff one matters most, because a
     * 429 waits tens of seconds and is overwhelmingly the window a Stop lands in.
     */
    @Test
    void interruptedThreadIsReportedAsCancellationNotAsTheProviderFailure() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class,
                () -> LlmRetry.withRetry("Gemini.generate", 3, () -> {
                    throw new IllegalStateException("provider blew up");
                }));
        } finally {
            Thread.interrupted(); // clear, so the flag cannot leak into sibling tests
        }
    }

    @Test
    void interruptDuringBackoffSleepIsReportedAsCancellationAndRestoresTheFlag() throws Exception {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean flagStillSet = new AtomicBoolean();
        CountDownLatch inAction = new CountDownLatch(1);

        Thread worker = new Thread(() -> {
            try {
                // A transient failure sends the loop into Thread.sleep(backoff); the interrupt
                // below lands there rather than at the loop top.
                LlmRetry.withRetry("Gemini.generate", 3, () -> {
                    inAction.countDown();
                    throw new UncheckedIOException(new IOException("timeout"));
                });
            } catch (Throwable t) {
                thrown.set(t);
                flagStillSet.set(Thread.currentThread().isInterrupted());
            }
        });
        worker.start();
        assertTrue(inAction.await(5, TimeUnit.SECONDS));
        Thread.sleep(50); // let it enter the backoff sleep
        worker.interrupt();
        worker.join(5000);

        assertTrue(thrown.get() instanceof CancellationException,
            "a Stop during backoff must surface as cancellation, got: " + thrown.get());
        assertTrue(flagStillSet.get(),
            "the interrupt flag must be restored so callers up the stack still see the cancellation");
    }

    @Test
    void retryExhaustion_attachesTheAttemptCountToTheFailure() {
        IOException boom = new IOException("connection reset");

        UncheckedIOException thrown = assertThrows(UncheckedIOException.class,
            () -> LlmRetry.withRetry("Gemini.generateStream", 3, () -> {
                throw new UncheckedIOException(boom);
            }));

        LlmRetryExhausted marker = markerOf(thrown);
        assertNotNull(marker, "the exhausted failure must carry its attempt count");
        assertEquals(3, marker.attempt());
        assertEquals(3, marker.maxAttempts());
    }

    @Test
    void nonTransientFailure_recordsThatItWasNeverRetried() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
            () -> LlmRetry.withRetry("Gemini.generate", 3, () -> {
                throw new IllegalArgumentException("invalid argument");
            }));

        LlmRetryExhausted marker = markerOf(thrown);
        assertNotNull(marker);
        assertEquals(1, marker.attempt());
        assertTrue(marker.getMessage().contains("non-transient"));
    }

    private static LlmRetryExhausted markerOf(Throwable t) {
        for (Throwable suppressed : t.getSuppressed()) {
            if (suppressed instanceof LlmRetryExhausted marker) {
                return marker;
            }
        }
        return null;
    }

    // --- Backoff. Pure so the quota-scale wait can be asserted without the test sleeping for it.
    // ---

    /**
     * A 429 is a quota window, not a blip. Retrying it after half a second re-spends the very quota
     * that produced it — which is exactly what happened in the incident, where each attempt
     * re-uploaded a ~600k-token prompt into an exhausted limit.
     */
    @Test
    void backoff_forRateLimitIsQuotaScaled() {
        long first = LlmRetry.backoffMs(1, new ApiException(429, "", ""));

        assertTrue(first >= 10_000L, "429 backoff must be quota-scale, was " + first + "ms");
        assertTrue(LlmRetry.backoffMs(2, new ApiException(429, "", "")) > first, "must grow");
    }

    @Test
    void backoff_forRateLimitIsCapped() {
        assertTrue(LlmRetry.backoffMs(9, new ApiException(429, "", "")) <= 60_000L);
    }

    @Test
    void backoff_forOrdinaryTransientFaultsStaysShort() {
        assertTrue(LlmRetry.backoffMs(1, new SocketTimeoutException("read timed out")) <= 1_000L);
        assertTrue(LlmRetry.backoffMs(9, new IOException("reset")) <= 8_000L);
    }

    // --- Azure OpenAI. A different SDK, a different exception type, the same two decisions. ---

    /**
     * Azure reports the status on {@code HttpResponseException.getResponse()} rather than on a
     * {@code code()} accessor. Without reading it, an Azure failure falls through to the
     * message-substring branch, where the outcome depends on wording rather than on the status —
     * and the wording azure-core produces is {@code "Status code 503, \"…\""}, which the branch's
     * {@code " 503"} check happens to match while a 429 body that never spells the number does not.
     */
    @Test
    void azureFailuresAreClassifiedFromTheirStatusNotTheirWording() {
        assertTrue(LlmRetry.isTransient(azure(408)));
        assertTrue(LlmRetry.isTransient(azure(429)));
        assertTrue(LlmRetry.isTransient(azure(500)));
        assertTrue(LlmRetry.isTransient(azure(502)));
        assertTrue(LlmRetry.isTransient(azure(503)));
        assertTrue(LlmRetry.isTransient(azure(504)));

        assertFalse(LlmRetry.isTransient(azure(400)));
        assertFalse(LlmRetry.isTransient(azure(401)));
        assertFalse(LlmRetry.isTransient(azure(403)));
        assertFalse(LlmRetry.isTransient(azure(404)));
    }

    /**
     * An Azure 429 must take the same quota-scale wait a Gemini 429 does, not the blip schedule.
     */
    @Test
    void anAzureRateLimitGetsTheQuotaScaleBackoff() {
        long azureBackoff = LlmRetry.backoffMs(1, azure(429));

        assertTrue(azureBackoff >= 10_000L,
            "429 backoff must be quota-scale, was " + azureBackoff + "ms");
        assertEquals(LlmRetry.backoffMs(1, new ApiException(429, "", "")), azureBackoff,
            "the provider must not change how long a rate limit is waited out");
        assertTrue(LlmRetry.backoffMs(1, azure(503)) <= 1_000L,
            "a 503 is an ordinary blip and must not be waited out for a quota window");
    }

    /**
     * A failure whose HTTP response never arrived carries no status. It must fall through to the
     * message and cause checks rather than be read as a status of zero, which would classify every
     * connection failure as permanent and stop the retry that would have recovered it.
     */
    @Test
    void anAzureExceptionWithNoResponseFallsBackToTheCauseChain() {
        assertTrue(LlmRetry
            .isTransient(new HttpResponseException("connection reset", null, new IOException())));
    }

    /**
     * A 429 says when to come back, and guessing over the top of it is how a shared-capacity
     * rejection turns into a retry storm. Measured live: a deployment running at 7 requests/minute
     * against a 250 RPM quota took repeated 429s while its own portal reported no rate limiting at
     * all — the server's {@code Retry-After} was the only number in the exchange that meant
     * anything, and we were ignoring it in favour of a fixed 15s/30s schedule.
     * {@code ConfluenceClientService} has honoured it on its own 429s all along; this brings the
     * LLM path to the same rule.
     */
    @Test
    void aRetryAfterHeaderBeatsTheGuessedBackoff() {
        assertEquals(42_000L, LlmRetry.backoffMs(1, azure(429, "42")),
            "the server's own interval must win over the quota-scale guess");
        assertEquals(42_000L, LlmRetry.backoffMs(3, azure(429, "42")),
            "and it does not compound with the attempt number");
    }

    /**
     * A provider is not allowed to park the run indefinitely: an hour-long {@code Retry-After} is
     * clamped, and anything unparseable — notably the HTTP-date form, which the spec permits —
     * falls back to the schedule rather than to a nonsensical wait or a crash.
     */
    @Test
    void anAbsurdOrUnparseableRetryAfterFallsBackToTheSchedule() {
        assertEquals(ProviderRateLimit.MAX_RETRY_AFTER_SECONDS * 1000L,
            LlmRetry.backoffMs(1, azure(429, "3600")), "clamped, not obeyed");
        assertEquals(15_000L, LlmRetry.backoffMs(1, azure(429, "Wed, 21 Oct 2026 07:28:00 GMT")),
            "an HTTP-date Retry-After is not honoured; the quota schedule applies");
        assertEquals(15_000L, LlmRetry.backoffMs(1, azure(429, null)),
            "no header at all keeps the existing quota-scale backoff");
        assertEquals(500L, LlmRetry.backoffMs(1, azure(503, "42")),
            "Retry-After only governs rate limits; an ordinary blip keeps the fast schedule");
    }

    /**
     * The sender's capitalisation must not decide whether the header is found. HTTP/2 sends header
     * names lowercase; {@code com.sun.net.httpserver} — the mock server every client test here uses
     * — rewrites them to {@code Retry-After}. A reader that compares with {@code String.equals} is
     * therefore green in one and red in the other, which is how the same class of bug has bitten
     * this codebase before with the {@code cf-aig-*} gateway headers.
     *
     * <p>
     * This assertion earns its place: the sibling test below sets its headers lowercase, so it
     * passes with an exact-case reader and proves nothing about the rule.
     */
    @Test
    void aHeaderIsFoundWhateverCaseTheProviderSentItIn() {
        HttpRequest request = new HttpRequest(HttpMethod.POST, "https://example.openai.azure.com");
        HttpHeaders mixedCase = new HttpHeaders();
        mixedCase.set(HttpHeaderName.fromString("Retry-After"), "9");
        mixedCase.set(HttpHeaderName.fromString("X-RateLimit-Remaining-Tokens"), "0");
        HttpResponseException failure = new HttpResponseException("Status code 429, \"\"",
            new MockHttpResponse(request, 429, mixedCase));

        ProviderRateLimit info = ProviderRateLimit.from(failure);

        assertEquals(9L, info.retryAfterSeconds(), "Retry-After must be found in any casing");
        assertEquals("0", info.remainingTokens());
        assertEquals(9_000L, LlmRetry.backoffMs(1, failure));
    }

    /** The headers an admin needs, read off the response rather than guessed from the body. */
    @Test
    void theRateLimitHeadersAreReadOffTheResponse() {
        ProviderRateLimit info = ProviderRateLimit.from(azure(429, "17"));

        assertEquals(17L, info.retryAfterSeconds());
        assertEquals("0", info.remainingTokens());
        assertEquals("249", info.remainingRequests());
        assertEquals("req-abc-123", info.requestId());
        assertTrue(info.describe().contains("retry-after=17s"), info.describe());
        assertTrue(info.describe().contains("remaining-tokens=0"), info.describe());
        assertTrue(ProviderRateLimit.from(azure(429, null)).isEmpty()
            || ProviderRateLimit.from(azure(429, null)).retryAfterSeconds() == null);
    }

    private static HttpResponseException azure(int statusCode) {
        return azure(statusCode, null);
    }

    private static HttpResponseException azure(int statusCode, String retryAfter) {
        HttpRequest request = new HttpRequest(HttpMethod.POST, "https://example.openai.azure.com");
        HttpHeaders headers = new HttpHeaders();
        if (retryAfter != null) {
            // Lowercase on purpose: HTTP/2 sends lowercase names, so the reader must not depend on
            // the capitalisation a mock server happens to produce.
            headers.set(HttpHeaderName.fromString("retry-after"), retryAfter);
            headers.set(HttpHeaderName.fromString("x-ratelimit-remaining-tokens"), "0");
            headers.set(HttpHeaderName.fromString("x-ratelimit-remaining-requests"), "249");
            headers.set(HttpHeaderName.fromString("apim-request-id"), "req-abc-123");
        }
        HttpResponse response = new MockHttpResponse(request, statusCode, headers);
        return new HttpResponseException("Status code " + statusCode + ", \"\"", response);
    }

    /** Minimal stand-in: the status code, plus the rate-limit headers a 429 carries. */
    private static final class MockHttpResponse extends HttpResponse {
        private final int statusCode;
        private final HttpHeaders headers;

        private MockHttpResponse(HttpRequest request, int statusCode, HttpHeaders headers) {
            super(request);
            this.statusCode = statusCode;
            this.headers = headers;
        }

        @Override
        public int getStatusCode() {
            return statusCode;
        }

        @Override
        @SuppressWarnings("deprecation")
        public String getHeaderValue(String name) {
            return headers.getValue(HttpHeaderName.fromString(name));
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }

        @Override
        public Flux<ByteBuffer> getBody() {
            return Flux.empty();
        }

        @Override
        public Mono<byte[]> getBodyAsByteArray() {
            return Mono.empty();
        }

        @Override
        public Mono<String> getBodyAsString() {
            return Mono.empty();
        }

        @Override
        public Mono<String> getBodyAsString(Charset charset) {
            return Mono.empty();
        }
    }
}
