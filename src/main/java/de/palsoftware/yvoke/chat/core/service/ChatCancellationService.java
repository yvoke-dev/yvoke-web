package de.palsoftware.yvoke.chat.core.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@Service
public class ChatCancellationService {
    private static final Logger log = LoggerFactory.getLogger(ChatCancellationService.class);
    static final Duration DEFAULT_SENTINEL_TTL = Duration.ofSeconds(10);

    // Values can be either a Thread or a CancelledSentinel
    private final ConcurrentHashMap<UUID, Object> activeTasks = new ConcurrentHashMap<>();
    private final Duration sentinelTtl;
    private final Clock clock;
    private final Executor delayedExecutor;

    record CancelledSentinel(Instant createdAt) {}

    @Autowired
    public ChatCancellationService() {
        this(DEFAULT_SENTINEL_TTL, Clock.systemUTC());
    }

    /** Test seam constructor for deterministic TTL and clock. */
    ChatCancellationService(Duration sentinelTtl, Clock clock) {
        this(sentinelTtl, clock, CompletableFuture
            .delayedExecutor(Math.max(1, sentinelTtl.toMillis()), TimeUnit.MILLISECONDS));
    }

    ChatCancellationService(Duration sentinelTtl, Clock clock, Executor delayedExecutor) {
        this.sentinelTtl = sentinelTtl;
        this.clock = clock;
        this.delayedExecutor = delayedExecutor;
    }

    /**
     * Registers a worker thread for the given conversation ID. If a non-expired stop() sentinel is
     * present, this will immediately interrupt the thread. Stale/expired sentinels are ignored.
     */
    public void register(UUID conversationId, Thread thread) {
        Object previous = activeTasks.put(conversationId, thread);
        if (previous instanceof CancelledSentinel sentinel) {
            if (!isExpired(sentinel)) {
                log.info(
                    "Conversation {} was cancelled before registration. Interrupting thread immediately.",
                    conversationId);
                thread.interrupt();
            } else {
                log.info(
                    "Stale cancellation sentinel for conversation {} has expired. Not interrupting thread.",
                    conversationId);
            }
        }
    }

    /**
     * Deregisters the given worker thread for the conversation ID. Identity-scoped (ARC-12): only
     * removes the entry if {@code thread} is still the registered one, so a stale
     * finally-deregister from a finished generation cannot clobber a newer generation's
     * registration (which would make a subsequent {@link #stop} a silent no-op) or wipe a pending
     * CANCELLED sentinel. Must be called in a finally block, on the worker thread, when generation
     * completes or aborts.
     */
    public void deregister(UUID conversationId, Thread thread) {
        activeTasks.remove(conversationId, thread);
    }

    /**
     * Signals the worker thread for the given conversation ID to stop. If the thread is registered,
     * it will be interrupted. If not registered yet, a timestamped CancelledSentinel is placed and
     * scheduled for eviction after {@code sentinelTtl}.
     */
    public void stop(UUID conversationId) {
        activeTasks.compute(conversationId, (key, current) -> {
            if (current instanceof Thread thread) {
                log.info("Interrupting active generation thread for conversation: {}",
                    conversationId);
                thread.interrupt();
                return current; // keep the thread reference
            } else {
                log.info("Marking conversation {} as cancelled (thread not yet registered)",
                    conversationId);
                CancelledSentinel sentinel = new CancelledSentinel(clock.instant());
                scheduleEviction(conversationId, sentinel);
                return sentinel;
            }
        });
    }

    /**
     * Clears any pending cancellation sentinel for the given conversation without affecting an
     * active running thread. Callers invoke this at turn start so residual sentinels from prior
     * idle stops cannot abort the new generation.
     */
    public void reset(UUID conversationId) {
        activeTasks.computeIfPresent(conversationId, (key, current) -> {
            if (current instanceof CancelledSentinel) {
                log.debug("Cleared cancellation sentinel on reset for conversation {}",
                    conversationId);
                return null;
            }
            return current;
        });
    }

    private void scheduleEviction(UUID conversationId, CancelledSentinel sentinel) {
        if (delayedExecutor != null) {
            delayedExecutor.execute(() -> {
                if (activeTasks.remove(conversationId, sentinel)) {
                    log.debug("Evicted expired cancellation sentinel for conversation {}",
                        conversationId);
                }
            });
        }
    }

    private boolean isExpired(CancelledSentinel sentinel) {
        return !clock.instant().isBefore(sentinel.createdAt().plus(sentinelTtl));
    }

    /** Package-private inspection helper for unit tests. */
    boolean isSentinelPresent(UUID conversationId) {
        return activeTasks.get(conversationId) instanceof CancelledSentinel;
    }

    /** Package-private inspection helper for unit tests. */
    boolean hasActiveTask(UUID conversationId) {
        return activeTasks.containsKey(conversationId);
    }
}
