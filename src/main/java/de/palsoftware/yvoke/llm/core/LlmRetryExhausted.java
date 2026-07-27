package de.palsoftware.yvoke.llm.core;

/**
 * Marker attached (as a suppressed exception) to the failure {@link LlmRetry} finally gives up on,
 * recording how many attempts were spent.
 *
 * <p>
 * It is attached rather than wrapped on purpose: {@code GeminiLlmClient} inspects only one level of
 * {@code getCause()} when deciding whether a failure is a cancellation, so wrapping would hide that
 * and could launder a {@link java.util.concurrent.CancellationException} into the generic-failure
 * path. Suppression carries the same information without changing the exception the caller catches.
 *
 * <p>
 * Stackless and non-suppressing — it is a data carrier, never a thing to throw.
 */
public final class LlmRetryExhausted extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String operation;
    private final int attempt;
    private final int maxAttempts;

    public LlmRetryExhausted(String operation, int attempt, int maxAttempts,
        boolean transientFailure) {
        super(buildMessage(operation, attempt, maxAttempts, transientFailure), null, false, false);
        this.operation = operation;
        this.attempt = attempt;
        this.maxAttempts = maxAttempts;
    }

    private static String buildMessage(String operation, int attempt, int maxAttempts,
        boolean transientFailure) {
        return operation + " failed on attempt " + attempt + "/" + maxAttempts
            + (transientFailure ? " (classified transient; retries exhausted)"
                : " (classified non-transient; not retried)");
    }

    public String operation() {
        return operation;
    }

    public int attempt() {
        return attempt;
    }

    public int maxAttempts() {
        return maxAttempts;
    }
}
