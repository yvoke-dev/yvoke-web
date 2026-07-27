package de.palsoftware.yvoke.shared.jobengine.service;

import de.palsoftware.yvoke.shared.jobengine.model.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class JobProgressBroker {

    private static final Logger log = LoggerFactory.getLogger(JobProgressBroker.class);

    private static final long EMITTER_TIMEOUT_MS = 30 * 60 * 1000L;

    private final Map<UUID, List<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    public SseEmitter subscribe(UUID jobId) {
        SseEmitter emitter = createEmitter();
        List<SseEmitter> list =
            subscribers.computeIfAbsent(jobId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        emitter.onCompletion(() -> remove(jobId, emitter));
        emitter.onTimeout(() -> remove(jobId, emitter));
        emitter.onError(e -> remove(jobId, emitter));
        return emitter;
    }

    protected SseEmitter createEmitter() {
        return new SseEmitter(EMITTER_TIMEOUT_MS);
    }

    public void publish(ProgressEvent event) {
        List<SseEmitter> list = subscribers.get(event.jobId());
        if (list == null) {
            return;
        }
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name("progress").data(event));
                if (event.isTerminal()) {
                    emitter.complete();
                }
            } catch (IOException | IllegalStateException e) {
                log.debug("Dropping SSE subscriber for job {}: {}", event.jobId(), e.getMessage());
                remove(event.jobId(), emitter);
            }
        }
        if (event.isTerminal()) {
            subscribers.remove(event.jobId());
        }
    }

    private void remove(UUID jobId, SseEmitter emitter) {
        List<SseEmitter> list = subscribers.get(jobId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) {
                subscribers.remove(jobId);
            }
        }
    }
}
