package de.palsoftware.yvoke.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GenerationConcurrencyLimiterTest {

    @Test
    void admitsUpToTheCapThenRejectsUntilAReleaseFreesASlot() {
        GenerationConcurrencyLimiter limiter = new GenerationConcurrencyLimiter(2);

        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse(); // at capacity

        limiter.release();
        assertThat(limiter.tryAcquire()).isTrue(); // freed slot reused
    }

    @Test
    void releaseRestoresAvailablePermits() {
        GenerationConcurrencyLimiter limiter = new GenerationConcurrencyLimiter(3);
        limiter.tryAcquire();
        limiter.tryAcquire();
        assertThat(limiter.availablePermits()).isEqualTo(1);
        limiter.release();
        assertThat(limiter.availablePermits()).isEqualTo(2);
    }

    @Test
    void capOfZeroDisablesLimitingAndNeverRejects() {
        GenerationConcurrencyLimiter limiter = new GenerationConcurrencyLimiter(0);
        for (int i = 0; i < 100; i++) {
            assertThat(limiter.tryAcquire()).isTrue();
        }
        // release is a harmless no-op when disabled.
        limiter.release();
    }
}
