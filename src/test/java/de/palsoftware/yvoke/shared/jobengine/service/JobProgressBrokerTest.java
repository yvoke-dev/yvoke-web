package de.palsoftware.yvoke.shared.jobengine.service;

import de.palsoftware.yvoke.shared.jobengine.model.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class JobProgressBrokerTest {

    private static JobProgressBroker brokerReturning(SseEmitter emitter) {
        return new JobProgressBroker() {
            @Override
            protected SseEmitter createEmitter() {
                return emitter;
            }
        };
    }

    @Test
    void publishToUnknownJobIsNoOp() {
        JobProgressBroker broker = new JobProgressBroker();
        // No subscribers registered — must not throw.
        broker.publish(new ProgressEvent(UUID.randomUUID(), "running", "chunk", 10, null, null));
    }

    @Test
    void nonTerminalEventSendsWithoutCompleting() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        JobProgressBroker broker = brokerReturning(emitter);
        UUID jobId = UUID.randomUUID();
        broker.subscribe(jobId);

        broker.publish(new ProgressEvent(jobId, "running", "embed", 40, null, null));

        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter, never()).complete();
    }

    @Test
    void terminalEventSendsAndCompletes() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        JobProgressBroker broker = brokerReturning(emitter);
        UUID jobId = UUID.randomUUID();
        broker.subscribe(jobId);

        broker.publish(new ProgressEvent(jobId, "completed", null, 100, null,
            new JobCounts(10, 50, 100, 200, 0)));

        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
    }

    @Test
    void terminalDetectionMatchesStatus() {
        assertThat(
            new ProgressEvent(UUID.randomUUID(), "completed", null, 100, null, null).isTerminal())
            .isTrue();
        assertThat(new ProgressEvent(UUID.randomUUID(), "failed", null, 50, "x", null).isTerminal())
            .isTrue();
        assertThat(
            new ProgressEvent(UUID.randomUUID(), "cancelled", null, 0, null, null).isTerminal())
            .isTrue();
        assertThat(
            new ProgressEvent(UUID.randomUUID(), "running", "chunk", 10, null, null).isTerminal())
            .isFalse();
        assertThat(new ProgressEvent(UUID.randomUUID(), "queued", null, 0, null, null).isTerminal())
            .isFalse();
    }
}
