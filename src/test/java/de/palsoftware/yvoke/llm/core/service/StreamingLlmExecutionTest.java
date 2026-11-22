package de.palsoftware.yvoke.llm.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.palsoftware.yvoke.llm.core.model.GatewayCacheStatus;
import de.palsoftware.yvoke.llm.core.model.LlmCallFailedException;
import de.palsoftware.yvoke.llm.core.model.LlmGatewayInfo;
import de.palsoftware.yvoke.llm.core.model.LlmResponseChunk;
import de.palsoftware.yvoke.llm.core.model.LlmUsage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Pins the shared streaming contract owned by {@link StreamingLlmExecution}: end-of-call emission
 * before verdicts, fatal-failure billing, severed partial stream detection, empty-turn retry,
 * establishment-only retry, and cancellation classification.
 */
class StreamingLlmExecutionTest {

    private static final LlmUsage USAGE = new LlmUsage(100, 20, 120, 10, 5);
    private static final LlmGatewayInfo GATEWAY =
            new LlmGatewayInfo(GatewayCacheStatus.REPLAYED, "gw-1");

    @Test
    void fatalFailureEmitsEndOfCallWithUsageAndGatewayBeforeThrowing() {
        List<LlmResponseChunk> chunks = new ArrayList<>();

        assertThatThrownBy(() ->
                        StreamingLlmExecution.<String>run(
                                "Gemini",
                                "Gemini.generateStream",
                                "gemini-2.5-flash",
                                "finishReason",
                                chunks::add,
                                attempt ->
                                        new StreamingLlmExecution.StreamAttempt<>() {
                                            @Override
                                            public StreamingLlmExecution.StreamSource<String>
                                                    establish() {
                                                return sourceOf(List.of("SAFETY"));
                                            }

                                            @Override
                                            public void onEvent(
                                                    String event,
                                                    StreamingLlmExecution.StreamOutcome outcome,
                                                    Consumer<LlmResponseChunk> emit) {
                                                outcome.recordUsage(USAGE);
                                                outcome.recordGateway(GATEWAY);
                                                outcome.recordTerminal(event, false);
                                                outcome.fail("blocked: finishReason=" + event);
                                            }
                                        }))
                .isInstanceOf(LlmCallFailedException.class)
                .hasMessageContaining("SAFETY")
                .extracting(e -> ((LlmCallFailedException) e).usage())
                .isEqualTo(USAGE);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).endOfCall()).isTrue();
        assertThat(chunks.get(0).usage()).isEqualTo(USAGE);
        assertThat(chunks.get(0).gateway()).isEqualTo(GATEWAY);
    }

    @Test
    void severedStreamWithPartialContentFailsWithoutRetryAfterEmittingEndOfCall() {
        List<LlmResponseChunk> chunks = new ArrayList<>();
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() ->
                        StreamingLlmExecution.<String>run(
                                "OpenRouter",
                                "OpenRouter.generateStream",
                                "deepseek/deepseek-chat",
                                "finishReason",
                                chunks::add,
                                attempt -> {
                                    attempts.incrementAndGet();
                                    return new StreamingLlmExecution.StreamAttempt<>() {
                                        @Override
                                        public StreamingLlmExecution.StreamSource<String>
                                                establish() {
                                            return sourceOf(List.of("Step 1: open the "));
                                        }

                                        @Override
                                        public void onEvent(
                                                String event,
                                                StreamingLlmExecution.StreamOutcome outcome,
                                                Consumer<LlmResponseChunk> emit) {
                                            outcome.markContent();
                                            emit.accept(
                                                    new LlmResponseChunk(event, null, null, USAGE));
                                        }
                                    };
                                }))
                .isInstanceOf(LlmCallFailedException.class)
                .hasMessageContaining("prematurely without a terminal event")
                .hasMessageContaining("finishReason=none")
                .extracting(e -> ((LlmCallFailedException) e).usage())
                .isEqualTo(USAGE);

        assertThat(attempts.get())
                .as("a severed stream that already emitted partial content must never be retried")
                .isEqualTo(1);
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).content()).isEqualTo("Step 1: open the ");
        assertThat(chunks.get(1).endOfCall()).isTrue();
        assertThat(chunks.get(1).usage()).isEqualTo(USAGE);
    }

    @Test
    void truncatedStreamWithContentSucceedsAsBenign() {
        List<LlmResponseChunk> chunks = new ArrayList<>();

        StreamingLlmExecution.<String>run(
                "Azure OpenAI Responses",
                "AzureOpenAiResponses.generateStream",
                "gpt-5.6-luna",
                "status",
                chunks::add,
                attempt ->
                        new StreamingLlmExecution.StreamAttempt<>() {
                            @Override
                            public StreamingLlmExecution.StreamSource<String> establish() {
                                return sourceOf(List.of("partial answer"));
                            }

                            @Override
                            public void onEvent(
                                    String event,
                                    StreamingLlmExecution.StreamOutcome outcome,
                                    Consumer<LlmResponseChunk> emit) {
                                outcome.markContent();
                                outcome.recordTerminal("incomplete", false);
                                emit.accept(new LlmResponseChunk(event, null, null, USAGE));
                            }
                        });

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).content()).isEqualTo("partial answer");
        assertThat(chunks.get(1).endOfCall()).isTrue();
    }

    @Test
    void wrappedInterruptedExceptionIsClassifiedAsCancellation() {
        assertThatThrownBy(() ->
                        StreamingLlmExecution.<String>run(
                                "Gemini",
                                "Gemini.generateStream",
                                "gemini-2.5-flash",
                                "finishReason",
                                c -> {},
                                attempt ->
                                        new StreamingLlmExecution.StreamAttempt<>() {
                                            @Override
                                            public StreamingLlmExecution.StreamSource<String>
                                                    establish() {
                                                return new StreamingLlmExecution.StreamSource<>() {
                                                    @Override
                                                    public void forEach(Consumer<String> action) {
                                                        throw new IllegalStateException(
                                                                "wrapped",
                                                                new InterruptedException("stop"));
                                                    }

                                                    @Override
                                                    public void close() {}
                                                };
                                            }

                                            @Override
                                            public void onEvent(
                                                    String event,
                                                    StreamingLlmExecution.StreamOutcome outcome,
                                                    Consumer<LlmResponseChunk> emit) {}
                                        }))
                .isInstanceOf(CancellationException.class);
    }

    @Test
    void establishmentIsRetriedWhileMidStreamIoFailureIsNot() {
        AtomicInteger establishCalls = new AtomicInteger();
        List<LlmResponseChunk> chunks = new ArrayList<>();

        assertThatThrownBy(() ->
                        StreamingLlmExecution.<String>run(
                                "OpenRouter",
                                "OpenRouter.generateStream",
                                "deepseek/deepseek-chat",
                                "finishReason",
                                chunks::add,
                                attempt ->
                                        new StreamingLlmExecution.StreamAttempt<>() {
                                            @Override
                                            public StreamingLlmExecution.StreamSource<String>
                                                    establish() {
                                                if (establishCalls.incrementAndGet() == 1) {
                                                    throw new UncheckedIOException(
                                                            new IOException("transient connect"));
                                                }
                                                return new StreamingLlmExecution.StreamSource<>() {
                                                    @Override
                                                    public void forEach(Consumer<String> action) {
                                                        action.accept("chunk-1");
                                                        throw new UncheckedIOException(
                                                                new IOException("mid-stream drop"));
                                                    }

                                                    @Override
                                                    public void close() {}
                                                };
                                            }

                                            @Override
                                            public void onEvent(
                                                    String event,
                                                    StreamingLlmExecution.StreamOutcome outcome,
                                                    Consumer<LlmResponseChunk> emit) {
                                                outcome.markContent();
                                                emit.accept(
                                                        new LlmResponseChunk(
                                                                event, null, null, null));
                                            }
                                        }))
                .isInstanceOf(UncheckedIOException.class);

        assertThat(establishCalls.get()).isEqualTo(2);
        assertThat(chunks).extracting(LlmResponseChunk::content).containsExactly("chunk-1");
    }

    private static <E> StreamingLlmExecution.StreamSource<E> sourceOf(List<E> events) {
        return new StreamingLlmExecution.StreamSource<>() {
            @Override
            public void forEach(Consumer<E> action) {
                events.forEach(action);
            }

            @Override
            public void close() {}
        };
    }
}
