package de.palsoftware.yvoke.llm.core.service;

import de.palsoftware.yvoke.llm.core.EmptyTurnRetry;
import de.palsoftware.yvoke.llm.core.LlmRetry;
import de.palsoftware.yvoke.llm.core.model.LlmCallFailedException;
import de.palsoftware.yvoke.llm.core.model.LlmGatewayInfo;
import de.palsoftware.yvoke.llm.core.model.LlmResponseChunk;
import de.palsoftware.yvoke.llm.core.model.LlmUsage;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single execution template that enforces the {@link LlmClient#generateStream} contract across all
 * provider clients.
 *
 * <p>Previously each provider client hand-copied the same six-step sequence: empty-turn retry,
 * establishment-only {@link LlmRetry}, stream iteration with interrupt checks, 4-way cancellation
 * detection, {@link LlmResponseChunk#endOfCall} emission before verdicts, and terminal/empty-turn
 * classification. Centralising the template here makes emitting {@code endOfCall} before a failure
 * throw structural (preventing lost {@code llm_call_logs} rows) and guarantees severed streams fail
 * consistently across every provider.
 */
final class StreamingLlmExecution {

    private static final Logger log = LoggerFactory.getLogger(StreamingLlmExecution.class);

    private StreamingLlmExecution() {}

    /** Provider-agnostic handle around a live blocking stream. */
    interface StreamSource<E> extends AutoCloseable {
        void forEach(Consumer<E> action);

        @Override
        void close();
    }

    /** Per-attempt adapter supplied by each provider client. */
    interface StreamAttempt<E> {
        StreamSource<E> establish();

        void onEvent(E event, StreamOutcome outcome, Consumer<LlmResponseChunk> emit);
    }

    static <E> void run(
            String provider,
            String operation,
            String model,
            String terminalLabel,
            Consumer<LlmResponseChunk> onChunk,
            IntFunction<StreamAttempt<E>> attemptFactory) {
        EmptyTurnRetry.run(
                provider,
                model,
                attemptNumber -> {
                    StreamAttempt<E> attempt = attemptFactory.apply(attemptNumber);
                    StreamOutcome outcome = new StreamOutcome(terminalLabel);

                    // Retry ONLY stream establishment (before any chunk reaches the caller);
                    // mid-stream failures cannot be safely resumed without duplicating output.
                    StreamSource<E> established =
                            LlmRetry.withRetry(operation, 3, attempt::establish);

                    try {
                        established.forEach(event -> {
                            if (Thread.currentThread().isInterrupted()) {
                                throw new CancellationException(provider + " stream interrupted");
                            }
                            outcome.recordEvent();
                            attempt.onEvent(
                                    event,
                                    outcome,
                                    chunk -> {
                                        if (chunk == null) {
                                            return;
                                        }
                                        if (chunk.usage() != null) {
                                            outcome.recordUsage(chunk.usage());
                                        }
                                        if (chunk.gateway() != null) {
                                            outcome.recordGateway(chunk.gateway());
                                        }
                                        onChunk.accept(chunk);
                                    });
                        });
                    } catch (RuntimeException e) {
                        if (isCancellation(e)) {
                            log.info("{} stream cancelled/interrupted", provider);
                            throw new CancellationException(provider + " stream interrupted");
                        }
                        log.error("{} streaming call failed for model={}", provider, model, e);
                        throw e;
                    } finally {
                        try {
                            established.close();
                        } catch (Exception e) {
                            log.warn("Failed to close {} response stream", provider, e);
                        }
                    }

                    // Close this HTTP call for accounting BEFORE any verdict throws or retries:
                    // every drained call (abandoned, blocked, severed, or completed) gets its own
                    // llm_call_logs row.
                    onChunk.accept(LlmResponseChunk.endOfCall(outcome.usage(), outcome.gateway()));

                    if (outcome.failure() != null) {
                        throw new LlmCallFailedException(outcome.failure(), null, outcome.usage());
                    }

                    // A stream that delivered partial content or tool calls and then closed without
                    // any terminal event was cut short mid-answer. Never return it as complete, and
                    // never retry it (partial output has already reached the caller).
                    if (outcome.producedOutput() && !outcome.sawTerminalEvent()) {
                        String description = outcome.describe();
                        log.warn(
                                "{} stream for model={} ended prematurely without a terminal event ({})",
                                provider,
                                model,
                                description);
                        throw new LlmCallFailedException(
                                provider
                                        + " stream ended prematurely without a terminal event ("
                                        + description
                                        + ")",
                                null,
                                outcome.usage());
                    }

                    return new EmptyTurnRetry.Turn(
                            outcome.producedOutput(),
                            outcome.cleanlyCompleted(),
                            outcome.usage(),
                            outcome.describe());
                });
    }

    static boolean isCancellation(Throwable e) {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof CancellationException || c instanceof InterruptedException) {
                return true;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return false;
    }

    /** Per-attempt stream observations accumulated while draining events. */
    static final class StreamOutcome {
        private final String terminalLabel;
        private boolean sawContent;
        private boolean sawToolCall;
        private String terminalValue;
        private boolean cleanlyCompleted;
        private String failure;
        private int events;
        private LlmUsage usage;
        private LlmGatewayInfo gateway;

        StreamOutcome(String terminalLabel) {
            this.terminalLabel = terminalLabel;
        }

        void recordEvent() {
            events++;
        }

        void markContent() {
            sawContent = true;
        }

        void markToolCall() {
            sawToolCall = true;
        }

        void recordUsage(LlmUsage usage) {
            if (usage != null) {
                this.usage = usage;
            }
        }

        void recordGateway(LlmGatewayInfo gateway) {
            if (gateway != null) {
                this.gateway = gateway;
            }
        }

        void recordTerminal(String value, boolean clean) {
            if (value != null && !value.isBlank()) {
                this.terminalValue = value;
                this.cleanlyCompleted = clean;
            }
        }

        void fail(String message) {
            this.failure = message;
        }

        boolean producedOutput() {
            return sawContent || sawToolCall;
        }

        boolean sawTerminalEvent() {
            return terminalValue != null;
        }

        boolean cleanlyCompleted() {
            return cleanlyCompleted;
        }

        String failure() {
            return failure;
        }

        LlmUsage usage() {
            return usage;
        }

        LlmGatewayInfo gateway() {
            return gateway;
        }

        String describe() {
            StringBuilder out = new StringBuilder();
            out.append(terminalLabel)
                    .append('=')
                    .append(terminalValue == null ? "none" : terminalValue);
            out.append(", events=").append(events);
            if (usage != null) {
                out.append(", completionTokens=")
                        .append(usage.completionTokens())
                        .append(" of which reasoning=")
                        .append(usage.thoughtTokens());
            }
            if (terminalValue == null) {
                out.append(
                        " — the stream ended without a terminal event, which means it was cut "
                                + "short rather than completed");
            }
            return out.toString();
        }
    }
}
