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

    @Test
    void disabledLimiterAlwaysAllows() {
        AtomicLong now = new AtomicLong(0);
        GenerationRateLimiter limiter = new GenerationRateLimiter(false, 1, 60, now::get);

        for (int i = 0; i < 100; i++) {
            assertThat(limiter.tryAcquire("user:alice")).isTrue();
        }
    }
}
