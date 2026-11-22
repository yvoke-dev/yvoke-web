package de.palsoftware.yvoke.llm.core.service;

import de.palsoftware.yvoke.llm.core.model.LlmCallFailedException;
import de.palsoftware.yvoke.llm.core.model.LlmRequest;
import de.palsoftware.yvoke.llm.core.model.LlmResponse;
import de.palsoftware.yvoke.llm.core.model.LlmResponseChunk;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/**
 * Provider-agnostic contract for synchronous and streaming LLM invocations.
 *
 * <p>All production callers go through {@link AccountingLlmClient} (the {@code @Primary} bean),
 * which wraps {@link ModelRoutingLlmClient} and the provider clients underneath. Provider
 * implementations share the following contract:
 *
 * <ul>
 *   <li><b>Retries:</b> Only non-streaming calls and initial stream <i>establishment</i> (before
 *       any chunk is emitted) are retried via {@link de.palsoftware.yvoke.llm.core.LlmRetry}.
 *       Mid-stream transport failures are never retried because partial output has already reached
 *       the consumer.
 *   <li><b>End-of-call accounting markers:</b> Every HTTP call made inside {@link #generateStream}
 *       (including an abandoned empty-turn attempt, a severed stream, or a fatally finished stream)
 *       emits one {@link LlmResponseChunk#endOfCall(de.palsoftware.yvoke.llm.core.model.LlmUsage,
 *       de.palsoftware.yvoke.llm.core.model.LlmGatewayInfo)} marker as soon as the stream drains
 *       and <i>before</i> any {@link LlmCallFailedException} is thrown. {@link AccountingLlmClient}
 *       consumes the marker to publish one {@code llm_call_logs} row per HTTP call and never
 *       forwards it to callers.
 *   <li><b>Billed failures:</b> A call that reached the provider and was blocked, errored, empty,
 *       or severed throws {@link LlmCallFailedException} carrying whatever {@link
 *       de.palsoftware.yvoke.llm.core.model.LlmUsage} the provider reported.
 *   <li><b>Cancellation:</b> Thread interruption during retry backoff or stream consumption raises
 *       {@link CancellationException} rather than a provider error.
 *   <li><b>Severed streams:</b> A stream that closes without any provider terminal signal (such as
 *       a finish reason or terminal response event) fails with {@link LlmCallFailedException} even
 *       if partial content was already delivered, so a cut-off answer is never returned as
 *       complete. Output-budget truncation ({@code MAX_TOKENS} / {@code incomplete} / {@code
 *       length}) with usable output stays benign.
 * </ul>
 */
public interface LlmClient {
    LlmResponse generate(LlmRequest request);

    /**
     * Streams a response, invoking {@code onChunk} for each chunk on the calling thread. Blocks
     * until the stream completes; throws {@link LlmCallFailedException} on provider/protocol
     * failures or {@link CancellationException} when cancelled. Designed for a blocking
     * (virtual-thread) caller — no Reactor involved.
     */
    void generateStream(LlmRequest request, Consumer<LlmResponseChunk> onChunk);
}
