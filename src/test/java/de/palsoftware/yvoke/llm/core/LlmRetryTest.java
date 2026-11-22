package de.palsoftware.yvoke.llm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.genai.errors.ApiException;
import com.google.genai.errors.GenAiIOException;
import com.openai.core.JsonValue;
import com.openai.core.http.Headers;
import com.openai.errors.OpenAIServiceException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class LlmRetryTest {

    @Test
    void testIsTransientWithApiException() {
        // Test various HTTP response codes
        assertTrue(LlmRetry.isTransient(new ApiException(408, "TIMEOUT", "Request timed out")));
        assertTrue(
                LlmRetry.isTransient(
                        new ApiException(429, "RESOURCE_EXHAUSTED", "Rate limit exceeded")));
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
    void anOpenAiSdkStatusIsClassifiedByCodeRatherThanByItsMessage() {
        // openai-java backs OpenRouterLlmClient and AzureOpenAiResponsesLlmClient, and its
        // exceptions were understood by neither branch of providerStatus: the message reads
        // "503: ..." with no leading space, so the " 503" substring fallback missed it too and
        // every retryable failure on those two clients was classified permanent.
        assertTrue(LlmRetry.isTransient(openAiError(429)));
        assertTrue(LlmRetry.isTransient(openAiError(500)));
        assertTrue(LlmRetry.isTransient(openAiError(503)));

        assertFalse(LlmRetry.isTransient(openAiError(400)));
        assertFalse(LlmRetry.isTransient(openAiError(404)));
    }

    private static OpenAIServiceException openAiError(int status) {
        // The concrete subclasses are final with private constructors; the abstract status contract
        // is what providerStatus reads.
        return new OpenAIServiceException("boom", null) {
            @Override
            public int statusCode() {
                return status;
            }

            @Override
            public Headers headers() {
                return Headers.builder().build();
            }

            @Override
            public JsonValue body() {
                return JsonValue.from(null);
            }

            @Override
            public Optional<String> code() {
                return Optional.empty();
            }

            @Override
            public Optional<String> param() {
                return Optional.empty();
            }

            @Override
            public Optional<String> type() {
                return Optional.empty();
            }
        };
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
            assertThrows(
                    CancellationException.class,
                    () ->
                            LlmRetry.withRetry(
                                    "Gemini.generate",
                                    3,
                                    () -> {
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

        Thread worker =
                new Thread(() -> {
                    try {
                        // A transient failure sends the loop into Thread.sleep(backoff); the
                        // interrupt
                        // below lands there rather than at the loop top.
                        LlmRetry.withRetry(
                                "Gemini.generate",
                                3,
                                () -> {
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

        assertTrue(
                thrown.get() instanceof CancellationException,
                "a Stop during backoff must surface as cancellation, got: " + thrown.get());
        assertTrue(
                flagStillSet.get(),
                "the interrupt flag must be restored so callers up the stack still see the cancellation");
    }

    @Test
    void retryExhaustion_attachesTheAttemptCountToTheFailure() {
        IOException boom = new IOException("connection reset");

        UncheckedIOException thrown =
                assertThrows(
                        UncheckedIOException.class,
                        () ->
                                LlmRetry.withRetry(
                                        "Gemini.generateStream",
                                        3,
                                        () -> {
                                            throw new UncheckedIOException(boom);
                                        }));

        LlmRetryExhausted marker = markerOf(thrown);
        assertNotNull(marker, "the exhausted failure must carry its attempt count");
        assertEquals(3, marker.attempt());
        assertEquals(3, marker.maxAttempts());
    }

    @Test
    void nonTransientFailure_recordsThatItWasNeverRetried() {
        IllegalArgumentException thrown =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                LlmRetry.withRetry(
                                        "Gemini.generate",
                                        3,
                                        () -> {
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

    // --- OpenAI SDK rate-limit & Retry-After header handling. ---

    @Test
    void anOpenAiRateLimitGetsTheQuotaScaleBackoff() {
        long openAiBackoff = LlmRetry.backoffMs(1, openAiError(429));

        assertTrue(
                openAiBackoff >= 10_000L,
                "429 backoff must be quota-scale, was " + openAiBackoff + "ms");
        assertEquals(
                LlmRetry.backoffMs(1, new ApiException(429, "", "")),
                openAiBackoff,
                "the provider must not change how long a rate limit is waited out");
        assertTrue(
                LlmRetry.backoffMs(1, openAiError(503)) <= 1_000L,
                "a 503 is an ordinary blip and must not be waited out for a quota window");
    }

    @Test
    void aRetryAfterHeaderBeatsTheGuessedBackoff() {
        assertEquals(
                42_000L,
                LlmRetry.backoffMs(1, openAiError(429, "42")),
                "the server's own interval must win over the quota-scale guess");
        assertEquals(
                42_000L,
                LlmRetry.backoffMs(3, openAiError(429, "42")),
                "and it does not compound with the attempt number");
    }

    @Test
    void anAbsurdOrUnparseableRetryAfterFallsBackToTheSchedule() {
        assertEquals(
                ProviderRateLimit.MAX_RETRY_AFTER_SECONDS * 1000L,
                LlmRetry.backoffMs(1, openAiError(429, "3600")),
                "clamped, not obeyed");
        assertEquals(
                15_000L,
                LlmRetry.backoffMs(1, openAiError(429, "Wed, 21 Oct 2026 07:28:00 GMT")),
                "an HTTP-date Retry-After is not honoured; the quota schedule applies");
        assertEquals(
                15_000L,
                LlmRetry.backoffMs(1, openAiError(429, (String) null)),
                "no header at all keeps the existing quota-scale backoff");
        assertEquals(
                500L,
                LlmRetry.backoffMs(1, openAiError(503, "42")),
                "Retry-After only governs rate limits; an ordinary blip keeps the fast schedule");
    }

    @Test
    void aHeaderIsFoundWhateverCaseTheProviderSentItIn() {
        Headers mixedCase =
                Headers.builder()
                        .put("Retry-After", "9")
                        .put("X-RateLimit-Remaining-Tokens", "0")
                        .build();
        OpenAIServiceException failure = openAiError(429, mixedCase);

        ProviderRateLimit info = ProviderRateLimit.from(failure);

        assertEquals(9L, info.retryAfterSeconds(), "Retry-After must be found in any casing");
        assertEquals("0", info.remainingTokens());
        assertEquals(9_000L, LlmRetry.backoffMs(1, failure));
    }

    @Test
    void theRateLimitHeadersAreReadOffTheResponse() {
        ProviderRateLimit info = ProviderRateLimit.from(openAiError(429, "17"));

        assertEquals(17L, info.retryAfterSeconds());
        assertEquals("0", info.remainingTokens());
        assertEquals("249", info.remainingRequests());
        assertEquals("req-abc-123", info.requestId());
        assertTrue(info.describe().contains("retry-after=17s"), info.describe());
        assertTrue(info.describe().contains("remaining-tokens=0"), info.describe());
        assertTrue(
                ProviderRateLimit.from(openAiError(429, (String) null)).isEmpty()
                        || ProviderRateLimit.from(openAiError(429, (String) null))
                                        .retryAfterSeconds()
                                == null);
    }

    private static OpenAIServiceException openAiError(int statusCode, String retryAfter) {
        Headers.Builder hb = Headers.builder();
        if (retryAfter != null) {
            hb.put("retry-after", retryAfter)
                    .put("x-ratelimit-remaining-tokens", "0")
                    .put("x-ratelimit-remaining-requests", "249")
                    .put("apim-request-id", "req-abc-123");
        }
        return openAiError(statusCode, hb.build());
    }

    private static OpenAIServiceException openAiError(int statusCode, Headers headers) {
        return new OpenAIServiceException("Status code " + statusCode, null) {
            @Override
            public int statusCode() {
                return statusCode;
            }

            @Override
            public Headers headers() {
                return headers;
            }

            @Override
            public JsonValue body() {
                return JsonValue.from(null);
            }

            @Override
            public Optional<String> code() {
                return Optional.empty();
            }

            @Override
            public Optional<String> param() {
                return Optional.empty();
            }

            @Override
            public Optional<String> type() {
                return Optional.empty();
            }
        };
    }

    /**
     * A typed provider status anywhere in the chain outranks any wrapper's prose.
     *
     * <p>The old classifier interleaved the two: it examined message text at each depth BEFORE
     * looking for a typed signal deeper down, so an outer wrapper whose message happened to contain
     * a needle decided the answer first. Here the wrapper says "service unavailable" while the
     * provider actually returned 400 — a permanent, unretryable rejection. Classifying it transient
     * re-sends a rejected prompt three times, with the quota-scale backoff, for a request that can
     * never succeed. Splitting the passes makes the typed rule authoritative and leaves the
     * substring list as the fallback it was always meant to be.
     */
    @Test
    void aTypedProviderStatusOutranksAWrapperMessageThatLooksTransient() {
        Throwable wrapped =
                new IllegalStateException(
                        "service unavailable, giving up",
                        new ApiException(400, "INVALID_ARGUMENT", "Bad Request"));

        assertFalse(
                LlmRetry.isTransient(wrapped),
                "a provider 400 is permanent no matter what an outer wrapper's message says");
    }

    /**
     * A self-referencing cause must not spin forever. {@code isRateLimit} has always guarded this;
     * {@code isTransient} walked the same chains with no guard at all, so one malformed throwable
     * from any SDK would hang the calling thread inside the classifier itself. Run on a separate
     * thread so a regression is reported as a failing test rather than as a hung build — an
     * infinite loop cannot be interrupted, so the default same-thread timeout would never fire.
     */
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aSelfReferencingCauseTerminates() {
        Throwable loop =
                new IllegalStateException("round and round") {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public synchronized Throwable getCause() {
                        return this;
                    }
                };

        assertFalse(LlmRetry.isTransient(loop), "no needle, no typed signal — and it must return");
    }

    /**
     * {@code TimeoutException} extends {@code Exception}, not {@code IOException}, and reactor's
     * default message contains none of the substring needles — so it was classified PERMANENT and a
     * timeout raised anywhere in the reactive plumbing retried nothing at all.
     */
    @Test
    void aBareTimeoutExceptionIsRetryable() {
        assertTrue(
                LlmRetry.isTransient(new TimeoutException("gave up")),
                "a reactive timeout is a transient failure, not a permanent one");
    }
}
