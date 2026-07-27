package de.palsoftware.yvoke.llm.core.context;

import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Attribution for the LLM call about to be made on this thread: who it is for
 * ({@code conversationId}/{@code messageId}/{@code agentRunId}/{@code userId}) and what kind of
 * work it is ({@code source}/{@code role}).
 *
 * <p>
 * Every entry point that calls {@code LlmClient} must set this before the call, because
 * {@code AccountingLlmClient} reads it to attribute the resulting {@code llm_call_logs} row. A call
 * made with no context still gets logged — under {@link #UNKNOWN_SOURCE}, so it is visibly
 * unattributed rather than silently missing.
 *
 * <p>
 * This is a {@link ThreadLocal} and is <b>not</b> propagated by
 * {@code DelegatingSecurityContextExecutorService}. Batch paths that fan work out over virtual
 * threads must set it <i>inside</i> each task, not around the executor.
 */
public final class LlmCallContextHolder {

    /** Source recorded when a call reaches the provider with no context set. */
    public static final String UNKNOWN_SOURCE = "unknown";

    public record Context(UUID conversationId, UUID messageId, UUID agentRunId, UUID userId,
        String source, String role) {}

    private static final ThreadLocal<Context> CONTEXT = new ThreadLocal<>();

    private LlmCallContextHolder() {}

    public static void set(UUID conversationId, UUID messageId, UUID agentRunId, UUID userId,
        String source, String role) {
        if (conversationId == null && messageId == null && agentRunId == null && userId == null
            && source == null && role == null) {
            CONTEXT.remove();
        } else {
            CONTEXT.set(new Context(conversationId, messageId, agentRunId, userId, source, role));
        }
    }

    public static Context get() {
        return CONTEXT.get();
    }

    public static void clear() {
        CONTEXT.remove();
    }

    public static void runWithContext(UUID conversationId, UUID messageId, UUID agentRunId,
        UUID userId, String source, String role, Runnable action) {
        Context previous = get();
        try {
            set(conversationId, messageId, agentRunId, userId, source, role);
            action.run();
        } finally {
            restore(previous);
        }
    }

    public static <T> T callWithContext(UUID conversationId, UUID messageId, UUID agentRunId,
        UUID userId, String source, String role, Callable<T> action) throws Exception {
        Context previous = get();
        try {
            set(conversationId, messageId, agentRunId, userId, source, role);
            return action.call();
        } finally {
            restore(previous);
        }
    }

    private static void restore(Context previous) {
        if (previous != null) {
            set(previous.conversationId(), previous.messageId(), previous.agentRunId(),
                previous.userId(), previous.source(), previous.role());
        } else {
            clear();
        }
    }
}
