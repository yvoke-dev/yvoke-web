package de.palsoftware.yvoke.chat.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChatCancellationServiceTest {

    private ChatCancellationService service;

    @BeforeEach
    void setUp() {
        service = new ChatCancellationService();
    }

    @Test
    void testRegisterAndStop_InterruptsThread() {
        UUID id = UUID.randomUUID();
        TestThread thread = new TestThread();

        service.register(id, thread);
        assertFalse(thread.isInterrupted());

        service.stop(id);
        assertTrue(thread.isInterrupted());

        service.deregister(id, thread);
    }

    @Test
    void testStopBeforeRegister_EagerlyInterruptsThread() {
        UUID id = UUID.randomUUID();
        TestThread thread = new TestThread();

        // Stop called before the worker thread registers itself
        service.stop(id);
        assertFalse(thread.isInterrupted());

        // Now the worker thread registers
        service.register(id, thread);

        // It should be immediately interrupted
        assertTrue(thread.isInterrupted());

        service.deregister(id, thread);
    }

    @Test
    void testStopAfterDeregister_DoesNothing() {
        UUID id = UUID.randomUUID();
        TestThread thread = new TestThread();

        service.register(id, thread);
        service.deregister(id, thread);

        service.stop(id);
        assertFalse(thread.isInterrupted());
    }

    @Test
    void aStopWithNothingRunningDoesNotInterruptSubsequentGenerationAfterTtl() {
        MutableTestClock clock = new MutableTestClock(Instant.now());
        ChatCancellationService timedService =
            new ChatCancellationService(Duration.ofSeconds(10), clock);

        UUID id = UUID.randomUUID();
        TestThread finished = new TestThread();

        timedService.register(id, finished);
        timedService.deregister(id, finished);

        // The Stop click lands after that — nothing is in flight for this conversation.
        timedService.stop(id);
        assertFalse(finished.isInterrupted(), "the finished run's thread must not be touched");

        // Later: 15 seconds pass (past the 10-second TTL)
        clock.advance(Duration.ofSeconds(15));

        // User asks their next question in the SAME conversation
        TestThread next = new TestThread();
        timedService.register(id, next);

        assertFalse(next.isInterrupted(),
            "The subsequent generation must NOT be interrupted by an expired cancellation sentinel");
    }

    @Test
    void defaultSentinelTtlIsSixtySeconds() {
        assertThat(ChatCancellationService.DEFAULT_SENTINEL_TTL).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void sentinelWithDefaultTtlSurvivesFiftySecondsAndExpiresAfterSixty() {
        MutableTestClock clock = new MutableTestClock(Instant.now());
        ChatCancellationService timedService =
            new ChatCancellationService(ChatCancellationService.DEFAULT_SENTINEL_TTL, clock);

        UUID id = UUID.randomUUID();
        timedService.stop(id);

        // 50 seconds in: within 60s TTL, registration must still be interrupted
        clock.advance(Duration.ofSeconds(50));
        TestThread threadWithinTtl = new TestThread();
        timedService.register(id, threadWithinTtl);
        assertTrue(threadWithinTtl.isInterrupted(), "Registration within 60s must be interrupted");

        // Finish the first generation by deregistering the thread
        timedService.deregister(id, threadWithinTtl);

        // Stop again with nothing running — creates a new CancelledSentinel
        timedService.stop(id);
        clock.advance(Duration.ofSeconds(61));
        TestThread threadAfterTtl = new TestThread();
        timedService.register(id, threadAfterTtl);
        assertFalse(threadAfterTtl.isInterrupted(),
            "Registration after 60s must NOT be interrupted");
    }

    @Test
    void testOrphanedSentinelIsEvictedAfterTtl() throws InterruptedException {
        ChatCancellationService timedService =
            new ChatCancellationService(Duration.ofMillis(50), Clock.systemUTC());
        UUID id = UUID.randomUUID();

        timedService.stop(id);
        assertTrue(timedService.hasActiveTask(id));

        // Wait for delayed eviction
        Thread.sleep(120);

        assertFalse(timedService.hasActiveTask(id),
            "Orphaned sentinel must be evicted from activeTasks after TTL to prevent memory leak");
    }

    @Test
    void testResetClearsSentinelImmediately() {
        UUID id = UUID.randomUUID();
        service.stop(id);
        assertTrue(service.isSentinelPresent(id));

        service.reset(id);
        assertFalse(service.isSentinelPresent(id));

        TestThread next = new TestThread();
        service.register(id, next);
        assertFalse(next.isInterrupted(), "Next generation must not be interrupted after reset");
    }

    @Test
    void testResetDoesNotRemoveActiveRunningThread() {
        UUID id = UUID.randomUUID();
        TestThread thread = new TestThread();
        service.register(id, thread);

        service.reset(id);

        service.stop(id);
        assertTrue(thread.isInterrupted(),
            "Active running thread must remain registered and stoppable");
        service.deregister(id, thread);
    }

    @Test
    void testStaleDeregisterDoesNotClobberNewerRegistration() {
        UUID id = UUID.randomUUID();
        TestThread owner = new TestThread();
        TestThread stale = new TestThread();

        service.register(id, owner);
        // A stale finally-deregister from a finished generation (a DIFFERENT thread) must NOT
        // remove the current owner's registration — otherwise stop() below would become a silent
        // no-op
        // (ARC-12).
        service.deregister(id, stale);

        service.stop(id);
        assertTrue(owner.isInterrupted());
    }

    private static class MutableTestClock extends Clock {
        private Instant instant;
        private final ZoneId zone = ZoneOffset.UTC;

        MutableTestClock(Instant initial) {
            this.instant = initial;
        }

        void advance(Duration duration) {
            this.instant = this.instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private static class TestThread extends Thread {
        private boolean interrupted = false;

        @Override
        public void interrupt() {
            interrupted = true;
        }

        @Override
        public boolean isInterrupted() {
            return interrupted;
        }
    }
}
