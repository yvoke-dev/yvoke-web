package de.palsoftware.yvoke.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class GenerationRateLimiterTest {

    @Test
    void allowsUpToCapacityThenRejects() {
        AtomicLong now = new AtomicLong(0);
        GenerationRateLimiter limiter = new GenerationRateLimiter(true, 2, 60, now::get);

        assertThat(limiter.tryAcquire("user:alice")).isTrue(); // 2 -> 1
        assertThat(limiter.tryAcquire("user:alice")).isTrue(); // 1 -> 0
        assertThat(limiter.tryAcquire("user:alice")).isFalse(); // empty -> 429
    }

    @Test
    void bucketsAreIsolatedPerPrincipal() {
        AtomicLong now = new AtomicLong(0);
        GenerationRateLimiter limiter = new GenerationRateLimiter(true, 1, 60, now::get);

        assertThat(limiter.tryAcquire("user:alice")).isTrue();
        assertThat(limiter.tryAcquire("user:alice")).isFalse();
        // A different principal has its own full bucket.
        assertThat(limiter.tryAcquire("user:bob")).isTrue();
    }

    @Test
    void refillsOverTime() {
        AtomicLong now = new AtomicLong(0);
        GenerationRateLimiter limiter = new GenerationRateLimiter(true, 2, 60, now::get);

        assertThat(limiter.tryAcquire("user:alice")).isTrue();
        assertThat(limiter.tryAcquire("user:alice")).isTrue();
        assertThat(limiter.tryAcquire("user:alice")).isFalse();

        // Advance a full refill period — the bucket is replenished to capacity.
        now.addAndGet(60_000);
        assertThat(limiter.tryAcquire("user:alice")).isTrue();
    }

    /**
     * Both clamps in the constructor exist for a misconfiguration nobody would notice, and every
     * other test in this class passes valid values (capacity 1-2, period 60), so neither branch is
     * ever entered.
     *
     * <p>
     * {@code Math.max(1, capacity)}: with {@code app.rate-limit.capacity: 0} an unclamped bucket is
     * created empty and refills {@code 0 * elapsed / period == 0} forever, so EVERY generation for
     * EVERY principal is 429ed permanently — the chat simply stops working, with the only clue a
     * value in a yml file that looks like "no limit configured".
     *
     * <p>
     * {@code Math.max(1L, refillPeriodSeconds)}: with {@code refill-period-seconds: 0} the refill
     * arithmetic divides by zero. It does not throw — this is double division, so it yields
     * {@code Infinity} and every bucket refills to capacity on any elapsed millisecond, i.e. the
     * limiter silently becomes a no-op and SEC-03 is off while the config still says
     * {@code enabled: true}. That is the failure direction that matters: an unlimited principal can
     * drive unbounded LLM spend and nothing anywhere reports it.
     *
     * <p>
     * Both stanzas assert the behaviour after the clamp rather than the field, because the field is
     * private and the clamp's whole value is what the bucket then does.
     */
    @Test
    void aMisconfiguredCapacityOrRefillPeriodIsClampedInsteadOfBreakingEveryGeneration() {
        AtomicLong now = new AtomicLong(0);

        // capacity: 0 must behave as 1, not as "nobody may ever generate".
        GenerationRateLimiter zeroCapacity = new GenerationRateLimiter(true, 0, 60, now::get);
        assertThat(zeroCapacity.tryAcquire("user:alice"))
            .as("an unclamped capacity of 0 starts every bucket empty and refills nothing, so every"
                + " principal is 429ed forever")
            .isTrue();
        assertThat(zeroCapacity.tryAcquire("user:alice"))
            .as("and the clamp is exactly 1 — it must not silently become 'unlimited'").isFalse();

        // refill-period-seconds: 0 must behave as 1s, not as an infinite refill rate.
        AtomicLong clock = new AtomicLong(0);
        GenerationRateLimiter zeroPeriod = new GenerationRateLimiter(true, 1, 0, clock::get);
        assertThat(zeroPeriod.tryAcquire("user:bob")).isTrue();
        clock.addAndGet(1);
        assertThat(zeroPeriod.tryAcquire("user:bob"))
            .as("unclamped, capacity * elapsed / 0 is Infinity: one millisecond would refill the"
                + " bucket to full and the limiter would admit everything")
            .isFalse();
    }

    @Test
    void disabledLimiterAlwaysAllows() {
        AtomicLong now = new AtomicLong(0);
        GenerationRateLimiter limiter = new GenerationRateLimiter(false, 1, 60, now::get);

        for (int i = 0; i < 100; i++) {
            assertThat(limiter.tryAcquire("user:alice")).isTrue();
        }
    }
}
