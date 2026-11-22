package de.palsoftware.yvoke.shared.web;

import java.util.concurrent.atomic.AtomicInteger;
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
 * <p>Backed by a CAS-bounded counter so extraneous or duplicate {@link #release()} calls can never
 * inflate available permits above {@code maxConcurrent}.
 *
 * <p>Set {@code app.generation.max-concurrent} to 0 (or less) to disable the cap.
 */
@Component
public class GenerationConcurrencyLimiter {

    private final boolean enabled;
    private final int maxConcurrent;
    private final AtomicInteger available;

    public GenerationConcurrencyLimiter(
            @Value("${app.generation.max-concurrent}") int maxConcurrent) {
        this.enabled = maxConcurrent > 0;
        this.maxConcurrent = Math.max(1, maxConcurrent);
        this.available = new AtomicInteger(this.maxConcurrent);
    }

    /**
     * @return true if a generation slot was acquired (caller MUST later call {@link #release()}),
     *     or false if the app is at capacity and the caller should reject the request (HTTP 429).
     */
    public boolean tryAcquire() {
        if (!enabled) {
            return true;
        }
        while (true) {
            int cur = available.get();
            if (cur <= 0) {
                return false;
            }
            if (available.compareAndSet(cur, cur - 1)) {
                return true;
            }
        }
    }

    /**
     * Returns a previously acquired slot, clamped at {@code maxConcurrent} so double-release cannot
     * inflate capacity. No-op when the cap is disabled.
     */
    public void release() {
        if (!enabled) {
            return;
        }
        while (true) {
            int cur = available.get();
            if (cur >= maxConcurrent) {
                return;
            }
            if (available.compareAndSet(cur, cur + 1)) {
                return;
            }
        }
    }

    /** Visible for tests. */
    public int availablePermits() {
        return available.get();
    }
}
