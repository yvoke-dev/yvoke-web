package de.palsoftware.yvoke.shared.web;

import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * App-level cap on the number of generations running concurrently (PRF-15). Unlike the
 * per-principal {@link GenerationRateLimiter} (which bounds request <em>rate</em> at admission),
 * this bounds the number of in-flight LLM generations at any instant across all users, protecting
 * the process from resource exhaustion under a burst. A permit is taken when a generation starts
 * and returned when it finishes (success, error, or cancellation) — so it must be paired with a
 * guaranteed release.
 *
 * <p>
 * Set {@code app.generation.max-concurrent} to 0 (or less) to disable the cap.
 */
@Component
public class GenerationConcurrencyLimiter {

    private final boolean enabled;
    private final Semaphore permits;

    public GenerationConcurrencyLimiter(
        @Value("${app.generation.max-concurrent}") int maxConcurrent) {
        this.enabled = maxConcurrent > 0;
        this.permits = new Semaphore(Math.max(1, maxConcurrent));
    }

    /**
     * @return true if a generation slot was acquired (caller MUST later call {@link #release()}),
     *         or false if the app is at capacity and the caller should reject the request (HTTP
     *         429).
     */
    public boolean tryAcquire() {
        return !enabled || permits.tryAcquire();
    }

    /** Returns a previously acquired slot. No-op when the cap is disabled. */
    public void release() {
        if (enabled) {
            permits.release();
        }
    }

    /** Visible for tests. */
    int availablePermits() {
        return permits.availablePermits();
    }
}
