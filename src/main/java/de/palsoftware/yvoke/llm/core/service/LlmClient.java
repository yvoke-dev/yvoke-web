package de.palsoftware.yvoke.llm.core.service;

import de.palsoftware.yvoke.llm.core.model.LlmRequest;
import de.palsoftware.yvoke.llm.core.model.LlmResponse;
import de.palsoftware.yvoke.llm.core.model.LlmResponseChunk;

import java.util.function.Consumer;

public interface LlmClient {
    LlmResponse generate(LlmRequest request);

    /**
     * Streams a response, invoking {@code onChunk} for each chunk on the calling thread. Blocks
     * until the stream completes; throws on failure. Designed for a blocking (virtual-thread)
     * caller — no Reactor involved.
     */
    void generateStream(LlmRequest request, Consumer<LlmResponseChunk> onChunk);
}
