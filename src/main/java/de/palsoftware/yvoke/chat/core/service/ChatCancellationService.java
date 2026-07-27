package de.palsoftware.yvoke.chat.core.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ChatCancellationService {
    private static final Logger log = LoggerFactory.getLogger(ChatCancellationService.class);

    // Values can be either a Thread or the CANCELLED sentinel
    private final ConcurrentHashMap<UUID, Object> activeTasks = new ConcurrentHashMap<>();
    private static final Object CANCELLED = new Object();

    /**
     * Registers a worker thread for the given conversation ID. If stop() was already called, this
     * will immediately interrupt the thread.
     */
    public void register(UUID conversationId, Thread thread) {
        Object previous = activeTasks.put(conversationId, thread);
        if (previous == CANCELLED) {
            log.info(
                "Conversation {} was cancelled before registration. Interrupting thread immediately.",
                conversationId);
            thread.interrupt();
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
     * it will be interrupted. If not registered yet, a CANCELLED sentinel is placed to eagerly
     * interrupt upon registration.
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
                return CANCELLED;
            }
        });
    }
}
