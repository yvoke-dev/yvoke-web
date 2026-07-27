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
