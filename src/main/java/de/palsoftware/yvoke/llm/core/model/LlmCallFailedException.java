package de.palsoftware.yvoke.llm.core.model;

/**
 * A call that reached the provider, consumed tokens, and then produced no usable answer — a safety
 * block, a recitation block, or empty candidates.
 *
 * <p>
 * The provider billed for those tokens. Throwing a plain exception discarded the usage the response
 * carried, so the call left no row in {@code llm_call_logs} at all and its cost was invisible in
 * every view. Carrying the usage on the exception lets {@code AccountingLlmClient} record it before
 * the failure propagates, which is what the streaming path already does by observing chunks.
 *
 * @param usage what the provider reported for the failed call; {@code null} when it reported
 *        nothing, in which case there is genuinely nothing to bill
 */
public class LlmCallFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient LlmUsage usage;

    public LlmCallFailedException(String message, Throwable cause, LlmUsage usage) {
        super(message, cause);
        this.usage = usage;
    }

    public LlmUsage usage() {
        return usage;
    }
}
