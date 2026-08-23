package de.palsoftware.yvoke.llm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.palsoftware.yvoke.llm.core.EmptyTurnRetry.Turn;
import de.palsoftware.yvoke.llm.core.model.LlmCallFailedException;
import de.palsoftware.yvoke.llm.core.model.LlmUsage;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * "The model produced nothing" has three causes and only ONE of them is worth re-requesting. This
 * class is where that distinction lives, so the two clients cannot drift into answering it
 * differently — which is exactly what had happened: {@code GeminiLlmClient} refused to retry at all
 * while {@code AzureOpenAiResponsesLlmClient} retried once, both citing the same fact (that they
 * stream reasoning) as the reason.
 */
class EmptyTurnRetryTest {

    private static final LlmUsage USAGE = new LlmUsage(100, 200, 300, 0, 200);

    private static Turn empty(boolean cleanlyCompleted) {
        return new Turn(false, cleanlyCompleted, USAGE, "finishReason=whatever");
    }

    private static Turn produced() {
        return new Turn(true, true, USAGE, "finishReason=STOP");
    }

    @Test
    void aTurnThatProducedOutputIsRunOnce() {
        AtomicInteger attempts = new AtomicInteger();

        EmptyTurnRetry.run("Gemini", "gemini-3.6-flash", n -> {
            attempts.incrementAndGet();
            return produced();
        });

        assertThat(attempts.get()).isEqualTo(1);
    }

    /**
     * The one retryable shape: the provider said it finished, and said nothing. Nothing the caller
     * can use reached them, so a second request cannot duplicate an answer.
     */
    @Test
    void aCleanlyCompletedEmptyTurnIsRequestedOnceMore() {
        AtomicInteger attempts = new AtomicInteger();

        EmptyTurnRetry.run("Gemini", "gemini-3.6-flash", n -> {
            return attempts.incrementAndGet() == 1 ? empty(true) : produced();
        });

        assertThat(attempts.get()).isEqualTo(2);
    }

    /**
     * A turn that ran out of output budget will run out of it again. Re-requesting buys nothing and
     * pays for the whole budget twice.
     */
    @Test
    void aTruncatedEmptyTurnIsNotRequestedAgain() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> EmptyTurnRetry.run("Gemini", "gemini-3.6-flash", n -> {
            attempts.incrementAndGet();
            return empty(false);
        })).isInstanceOf(LlmCallFailedException.class);

        assertThat(attempts.get())
            .as("a truncated or severed empty turn must cost exactly one request").isEqualTo(1);
    }

    /** Two empty completions in a row is systematic, not a glitch: stop and report. */
    @Test
    void aSecondEmptyTurnFailsInsteadOfLoopingForever() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> EmptyTurnRetry.run("Azure OpenAI Responses", "gpt-5.6-luna", n -> {
            attempts.incrementAndGet();
            return empty(true);
        })).isInstanceOf(LlmCallFailedException.class)
            .hasMessageContaining("Azure OpenAI Responses").hasMessageContaining("2 attempt(s)")
            .hasMessageContaining("finishReason=whatever");

        assertThat(attempts.get()).isEqualTo(2);
    }

    /**
     * The tokens the abandoned attempts burned are already on their own {@code llm_call_logs} rows
     * via the end-of-call markers, but the exception still names what the last attempt cost so a
     * non-streaming caller has something to report.
     */
    @Test
    void theFailureCarriesTheUsageTheProviderBilled() {
        assertThatThrownBy(() -> EmptyTurnRetry.run("Gemini", "m", n -> empty(true)))
            .isInstanceOf(LlmCallFailedException.class)
            .extracting(e -> ((LlmCallFailedException) e).usage()).isEqualTo(USAGE);
    }

    /** The attempt number reaches the client, which needs it for logging and for its own guards. */
    @Test
    void theAttemptNumberIsOneBasedAndIncreases() {
        AtomicInteger seen = new AtomicInteger();

        EmptyTurnRetry.run("Gemini", "m", n -> {
            assertThat(n).isEqualTo(seen.incrementAndGet());
            return n == 1 ? empty(true) : produced();
        });

        assertThat(seen.get()).isEqualTo(2);
    }
}
