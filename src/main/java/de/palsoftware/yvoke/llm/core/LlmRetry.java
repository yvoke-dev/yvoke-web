package de.palsoftware.yvoke.llm.core;

import com.google.genai.errors.ApiException;
import com.google.genai.errors.GenAiIOException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LlmRetry {

    private static final Logger log = LoggerFactory.getLogger(LlmRetry.class);
    private static final long BASE_BACKOFF_MS = 500L;
    private static final long MAX_BACKOFF_MS = 8_000L;

    /**
     * A 429 is a quota window, not a transient blip, so it gets its own far longer schedule.
     * Retrying a rate limit after half a second simply re-spends the quota that produced it — in
     * the incident this class was hardened after, each attempt re-uploaded a ~600k-token prompt
     * into an already exhausted limit and the window never got a chance to reset.
     */
    private static final long RATE_LIMIT_BASE_BACKOFF_MS = 15_000L;
    private static final long RATE_LIMIT_MAX_BACKOFF_MS = 60_000L;

    private LlmRetry() {}

    public static <T> T withRetry(String operation, int maxAttempts, Supplier<T> action) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.get();
            } catch (RuntimeException e) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new CancellationException("Thread interrupted during LLM call");
                }
                last = e;
                boolean transientFailure = isTransient(e);
                if (attempt >= maxAttempts || !transientFailure) {
                    // Attached, not wrapped: GeminiLlmClient inspects a single level of getCause()
                    // to spot cancellation, and the caller catches on exception type. Suppression
                    // carries the count without changing either.
                    e.addSuppressed(
                        new LlmRetryExhausted(operation, attempt, maxAttempts, transientFailure));
                    throw e;
                }
                long backoffMs = backoffMs(attempt, e);
                log.warn("{} failed (attempt {}/{}), retrying in {} ms: {}", operation, attempt,
                    maxAttempts, backoffMs, e.toString());
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    // Interrupted while waiting means the user pressed Stop. Rethrowing the
                    // provider exception would caption a deliberate cancellation as a system
                    // error — the same conflation the loop-top guard above already avoids, and
                    // now far more likely to be hit because the 429 backoff waits tens of seconds.
                    Thread.currentThread().interrupt();
                    throw new CancellationException("Thread interrupted during LLM retry backoff");
                }
            }
        }
        throw last; // unreachable: the loop either returns or throws
    }

    /**
     * Backoff before the next attempt. Pure and package-private so the quota-scale wait for a 429
     * can be asserted without a test actually sleeping through it.
     */
    static long backoffMs(int attempt, Throwable t) {
        long base = isRateLimit(t) ? RATE_LIMIT_BASE_BACKOFF_MS : BASE_BACKOFF_MS;
        long cap = isRateLimit(t) ? RATE_LIMIT_MAX_BACKOFF_MS : MAX_BACKOFF_MS;
        int shift = Math.min(Math.max(attempt - 1, 0), 20);
        long scaled = base << shift;
        return scaled <= 0 ? cap : Math.min(cap, scaled);
    }

    private static boolean isRateLimit(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof ApiException apiException) {
                return apiException.code() == 429;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return false;
    }

    static boolean isTransient(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            // Prefer the SDK's typed HTTP status over brittle message-substring matching:
            // ApiException.getMessage() is "500 Internal Server Error. ..." (no leading space), so
            // a substring check for " 500" would miss genuine 5xx errors.
            if (c instanceof ApiException apiException) {
                int code = apiException.code();
                return code == 408 || code == 429 || code == 500 || code == 502 || code == 503
                    || code == 504;
            }
            if (c instanceof GenAiIOException || c instanceof SocketTimeoutException
                || c instanceof IOException) {
                return true;
            }
            String msg = c.getMessage();
            if (msg != null) {
                String m = msg.toLowerCase();
                if (m.contains("429") || m.contains("rate limit") || m.contains("rate-limit")
                    || m.contains("timeout") || m.contains("timed out") || m.contains("overload")
                    || m.contains("unavailable") || m.contains(" 500") || m.contains(" 502")
                    || m.contains(" 503") || m.contains(" 504")) {
                    return true;
                }
            }
        }
        return false;
    }
}
