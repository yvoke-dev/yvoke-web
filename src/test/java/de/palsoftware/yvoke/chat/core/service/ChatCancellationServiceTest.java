package de.palsoftware.yvoke.chat.core.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /**
     * The CANCELLED sentinel has no lifetime, and this is where that stops being an implementation
     * detail.
     *
     * <p>
     * {@code stop()} uses {@code compute()} and writes the sentinel whenever the current value is
     * not a Thread — INCLUDING when there is no value at all. {@code deregister()} is
     * {@code activeTasks.remove(id, thread)}, identity-scoped, so it can never clear a sentinel;
     * nothing else removes one and nothing expires it. So a Stop click that lands even a moment
     * after the run it was aimed at has finished leaves a permanent mark on that conversation id,
     * and {@code register()} interrupts whatever thread arrives next for it — whenever that is,
     * minutes or hours later.
     *
     * <p>
     * That is reachable from the UI, not theoretical: {@code ChatController.stopGeneration} calls
     * {@code stop(id)} unconditionally once ownership checks out, and both async paths call
     * {@code register()} as the FIRST statement of the executor task
     * ({@code ChatMessageService}:159 and :243) — so the user's next question in that conversation
     * starts already interrupted, falls straight into the {@code CancellationException} branch, and
     * is persisted as status {@code cancelled} with "[Generation stopped by user]" against a
     * question the user never stopped. The sentinel is deliberate and correct for the intra-run
     * race it was written for (stop arriving before the worker registers,
     * {@code testStopBeforeRegister_EagerlyInterruptsThread}); what is undecided is whether it
     * should survive the run it belongs to.
     *
     * <p>
     * This is a CHARACTERIZATION test: it pins what the code does today so the cross-run
     * consequence is visible in the suite instead of being reasoned out from three files.
     * {@code testStopAfterDeregister_DoesNothing} looks like it covers this case and does not — it
     * asserts only that the already-finished thread is not interrupted, never inspects the map, and
     * stays green with the leftover sentinel sitting there. If the owner decides a stop with
     * nothing in flight should be a no-op, this test must be changed deliberately, and that is the
     * point.
     */
    @Test
    void aStopWithNothingRunningLeavesASentinelThatInterruptsTheNextGeneration() {
        UUID id = UUID.randomUUID();
        TestThread finished = new TestThread();

        // A generation that ran to completion and deregistered itself in its finally block.
        service.register(id, finished);
        service.deregister(id, finished);

        // The Stop click lands after that — nothing is in flight for this conversation.
        service.stop(id);
        assertFalse(finished.isInterrupted(), "the finished run's thread must not be touched");

        // Later: the user asks their next question in the SAME conversation.
        TestThread next = new TestThread();
        service.register(id, next);

        assertTrue(next.isInterrupted(),
            "current behaviour: the CANCELLED sentinel left by the no-op stop never expires, so the "
                + "next generation for this conversation begins interrupted and is persisted as "
                + "'cancelled' with '[Generation stopped by user]'");
    }

    @Test
    void testStaleDeregisterDoesNotClobberNewerRegistration() {
        UUID id = UUID.randomUUID();
        TestThread owner = new TestThread();
        TestThread stale = new TestThread();

        service.register(id, owner);
        // A stale finally-deregister from a finished generation (a DIFFERENT thread) must NOT
        // remove
        // the current owner's registration — otherwise stop() below would become a silent no-op
        // (ARC-12).
        service.deregister(id, stale);

        service.stop(id);
        assertTrue(owner.isInterrupted());
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
