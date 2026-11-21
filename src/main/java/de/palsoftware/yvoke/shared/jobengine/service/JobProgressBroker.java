package de.palsoftware.yvoke.shared.jobengine.service;

import de.palsoftware.yvoke.shared.jobengine.model.ProgressEvent;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class JobProgressBroker {

    private static final Logger log = LoggerFactory.getLogger(JobProgressBroker.class);

    private static final long EMITTER_TIMEOUT_MS = 30 * 60 * 1000L;

    private final Map<UUID, List<Subscriber>> subscribers = new ConcurrentHashMap<>();

    public SseEmitter subscribe(UUID jobId) {
        return registerSubscriber(jobId).emitter;
    }

    /**
     * Subscribes to live progress events <em>before</em> loading and emitting the initial DB
     * snapshot. Serializing all sends per subscriber prevents concurrent {@code SseEmitter.send}
     * calls and guarantees no terminal transition occurring between the DB read and subscription is
     * lost.
     */
    public SseEmitter subscribeWithInitial(UUID jobId,
        Supplier<Optional<ProgressEvent>> snapshotLoader) {
        Subscriber sub = registerSubscriber(jobId);
        Optional<ProgressEvent> initial = snapshotLoader.get();
        if (initial.isPresent()) {
            boolean keepOpen = sub.send(initial.get());
            if (!keepOpen) {
                remove(jobId, sub);
            }
        }
        return sub.emitter;
    }

    private Subscriber registerSubscriber(UUID jobId) {
        SseEmitter emitter = createEmitter();
        Subscriber sub = new Subscriber(emitter);
        subscribers.compute(jobId, (k, list) -> {
            List<Subscriber> target = (list != null) ? list : new CopyOnWriteArrayList<>();
            target.add(sub);
            return target;
        });
        emitter.onCompletion(() -> {
            sub.markClosed();
            remove(jobId, sub);
        });
        emitter.onTimeout(() -> {
            sub.markClosed();
            remove(jobId, sub);
        });
        emitter.onError(e -> {
            sub.markClosed();
            remove(jobId, sub);
        });
        return sub;
    }

    protected SseEmitter createEmitter() {
        return new SseEmitter(EMITTER_TIMEOUT_MS);
    }

    public void publish(ProgressEvent event) {
        List<Subscriber> list = subscribers.get(event.jobId());
        if (list == null) {
            return;
        }
        for (Subscriber sub : list) {
            boolean keepOpen = sub.send(event);
            if (!keepOpen) {
                remove(event.jobId(), sub);
            }
        }
    }

    private void remove(UUID jobId, Subscriber sub) {
        subscribers.computeIfPresent(jobId, (k, list) -> {
            list.remove(sub);
            return list.isEmpty() ? null : list;
        });
    }

    private static final class Subscriber {
        private final SseEmitter emitter;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private Subscriber(SseEmitter emitter) {
            this.emitter = emitter;
        }

        private synchronized boolean send(ProgressEvent event) {
            if (closed.get()) {
                return false;
            }
            try {
                emitter.send(SseEmitter.event().name("progress").data(event));
                if (event.isTerminal()) {
                    closed.set(true);
                    emitter.complete();
                    return false;
                }
                return true;
            } catch (IOException | IllegalStateException e) {
                closed.set(true);
                log.debug("Dropping SSE subscriber for job {}: {}", event.jobId(), e.getMessage());
                return false;
            }
        }

        private void markClosed() {
            closed.set(true);
        }
    }
}
