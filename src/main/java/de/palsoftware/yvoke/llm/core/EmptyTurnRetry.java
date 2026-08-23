package de.palsoftware.yvoke.llm.core;

import de.palsoftware.yvoke.llm.core.model.LlmCallFailedException;
import de.palsoftware.yvoke.llm.core.model.LlmUsage;
import java.util.function.IntFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one place that decides whether a turn which produced nothing is worth re-requesting.
 *
 * <p>
 * Distinct from {@link LlmRetry}, which retries a failed <i>transport</i>. This retries a
 * <i>successful</i> call that came back with nothing in it — an outcome reasoning models make
 * ordinary rather than exotic, because reasoning tokens spend the same output budget as the answer.
 * It cannot be expressed as an {@code LlmRetry} classification: the verdict is only available after
 * the stream has been consumed and parsed, which happens outside the retry that wraps
 * establishment.
 *
 * <p>
 * "Produced nothing" has three causes and only ONE is worth re-requesting:
 * <ul>
 * <li><b>Completed and silent</b> — the provider says it finished, and said nothing. Retry. Nothing
 * the caller can use reached them, so a second attempt cannot duplicate an answer.</li>
 * <li><b>Truncated</b> — the turn exhausted its output budget. A re-request exhausts it again, at
 * full price.</li>
 * <li><b>Severed</b> — the stream ended with no terminal event at all. That is a transport fault,
 * and retrying it here is a guess about the network rather than about the model.</li>
 * </ul>
 *
 * <p>
 * Centralised because the two clients had already drifted into answering it differently while
 * citing the same fact: both stream reasoning, and both noted that a re-request replays it —
 * {@code GeminiLlmClient} called that disqualifying and refused to retry at all, while
 * {@code AzureOpenAiResponsesLlmClient} called it an acceptable price. It is a price: the reasoning
 * summary IS re-emitted. The alternative is handing the user a blank answer, which is worse, and
 * the UI hides {@code <think>} in any case. What must never be duplicated is answer content, and by
 * construction of the retryable case there was none.
 *
 * <p>
 * Bounded at two. An empty completion that repeats is systematic rather than a glitch, and
 * re-sending a large prompt a third time to discover the same nothing costs more than the round the
 * retry exists to save.
 */
public final class EmptyTurnRetry {

    private static final Logger log = LoggerFactory.getLogger(EmptyTurnRetry.class);

    static final int MAX_ATTEMPTS = 2;

    private EmptyTurnRetry() {}

    /**
     * What one attempt produced.
     *
     * @param producedOutput text or a tool call — anything the caller can act on. Reasoning alone
     *        is deliberately not output: a turn that only thought is precisely the case being
     *        detected, so counting it here would stop the check ever firing.
     * @param cleanlyCompleted the provider reported a normal end of turn (Gemini
     *        {@code finishReason=STOP}, Responses {@code status=completed}) rather than truncation,
     *        a block, or no terminal event at all
     * @param usage what this attempt cost, for the failure to report
     * @param describe human-readable diagnosis, named in the exception so a failure says why it
     *        looked like a success
     */
    public record Turn(boolean producedOutput, boolean cleanlyCompleted, LlmUsage usage,
        String describe) {}

    /**
     * Runs {@code attempt} until it produces output, re-requesting at most once and only for a
     * cleanly-completed empty turn.
     *
     * <p>
     * Each attempt is one HTTP call and is expected to have emitted its own
     * {@link de.palsoftware.yvoke.llm.core.model.LlmResponseChunk#endOfCall} marker before
     * returning, so an abandoned attempt still appears in {@code llm_call_logs} on its own terms.
     *
     * @param attempt receives the one-based attempt number
     * @throws LlmCallFailedException when no attempt produced anything
     */
    public static void run(String provider, String model, IntFunction<Turn> attempt) {
        for (int n = 1;; n++) {
            Turn turn = attempt.apply(n);
            if (turn.producedOutput()) {
                return;
            }
            if (turn.cleanlyCompleted() && n < MAX_ATTEMPTS) {
                log.warn("{} produced an empty turn for model={} ({}); nothing the caller can use "
                    + "reached them, so re-requesting once", provider, model, turn.describe());
                continue;
            }
            log.warn("{} produced an empty turn for model={} ({}) after {} attempt(s)", provider,
                model, turn.describe(), n);
            throw new LlmCallFailedException(provider + " produced no content and no tool calls "
                + "after " + n + " attempt(s) (" + turn.describe() + ")", null, turn.usage());
        }
    }
}
