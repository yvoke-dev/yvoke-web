package de.palsoftware.yvoke.shared.web;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Per-principal token-bucket rate limiter for the expensive generation / ingest endpoints (SEC-03).
 * Each principal gets a bucket of {@code capacity} tokens that refills linearly over
 * {@code refill-period-seconds}; one token is consumed per admitted request. Buckets live in a
 * Caffeine cache keyed by principal and expire after inactivity, so the map cannot grow unbounded.
 *
 * <p>
 * This bounds runaway LLM spend / resource exhaustion from a single principal. A global cap on the
 * number of concurrently-running generations (PRF-15) is complementary and tracked separately,
 * because enforcing it correctly requires hooking the async SSE generation lifecycle rather than
 * request admission.
 */
@Component
public class GenerationRateLimiter {

    private final boolean enabled;
    private final int capacity;
    private final long refillPeriodMillis;
    private final LongSupplier clockMillis;
    private final Cache<String, Bucket> buckets;

    @Autowired
    public GenerationRateLimiter(@Value("${app.rate-limit.enabled}") boolean enabled,
        @Value("${app.rate-limit.capacity}") int capacity,
        @Value("${app.rate-limit.refill-period-seconds}") long refillPeriodSeconds) {
        this(enabled, capacity, refillPeriodSeconds, System::currentTimeMillis);
    }

    /** Test seam: allows a deterministic (virtual) clock. */
    GenerationRateLimiter(boolean enabled, int capacity, long refillPeriodSeconds,
        LongSupplier clockMillis) {
        this.enabled = enabled;
        this.capacity = Math.max(1, capacity);
        this.refillPeriodMillis = Math.max(1L, refillPeriodSeconds) * 1000L;
        this.clockMillis = clockMillis;
        this.buckets = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMillis(Math.max(this.refillPeriodMillis * 2, 60_000L)))
            .maximumSize(100_000).build();
    }

    /**
     * @return true if a token was available (request admitted); false if the principal is over its
     *         limit and the caller should reject the request (HTTP 429).
     */
    public boolean tryAcquire(String principal) {
        if (!enabled) {
            return true;
        }
        long now = clockMillis.getAsLong();
        Bucket bucket = buckets.get(principal, key -> new Bucket(capacity, now));
        return bucket.tryConsume(now, capacity, refillPeriodMillis);
    }

    private static final class Bucket {
        private double tokens;
        private long lastRefillMillis;

        Bucket(int capacity, long now) {
            this.tokens = capacity;
            this.lastRefillMillis = now;
        }

        synchronized boolean tryConsume(long now, int capacity, long refillPeriodMillis) {
            long elapsed = now - lastRefillMillis;
            if (elapsed > 0) {
                double refilled = (double) capacity * elapsed / refillPeriodMillis;
                tokens = Math.min(capacity, tokens + refilled);
                lastRefillMillis = now;
            }
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }
}
