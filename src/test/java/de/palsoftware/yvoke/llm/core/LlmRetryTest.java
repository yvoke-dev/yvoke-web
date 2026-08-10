package de.palsoftware.yvoke.llm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.genai.errors.ApiException;
import com.google.genai.errors.GenAiIOException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.Test;
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
}
