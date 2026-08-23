package de.palsoftware.yvoke.llm.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The end-of-call marker exists so
 * {@link de.palsoftware.yvoke.llm.core.service.AccountingLlmClient} can write one
 * {@code llm_call_logs} row per HTTP call rather than one per {@code generateStream}.
 *
 * <p>
 * It cannot be inferred from "this chunk carried usage": Gemini reports an absolute whole-request
 * snapshot on <i>every</i> event that carries usage (see {@code GeminiLlmClient#parseChunk}), so
 * inferring a boundary there would mint a row per chunk. Hence an explicit flag.
 */
class LlmResponseChunkTest {

    @Test
    void anEndOfCallMarkerCarriesUsageAndNothingElse() {
        LlmUsage usage = new LlmUsage(10, 20, 30, 0, 5);
        LlmGatewayInfo gateway = new LlmGatewayInfo(GatewayCacheStatus.FORWARDED, "log-1");

        LlmResponseChunk marker = LlmResponseChunk.endOfCall(usage, gateway);

        assertThat(marker.endOfCall()).isTrue();
        assertThat(marker.usage()).isSameAs(usage);
        assertThat(marker.gateway()).isSameAs(gateway);
        assertThat(marker.content()).isNull();
        assertThat(marker.reasoning()).isNull();
        assertThat(marker.toolCallDeltas()).isNull();
        assertThat(marker.parts()).isNull();
    }

    /**
     * The accounting decorator consumes the marker instead of forwarding it, so a marker that also
     * carried answer text would silently delete that text from the user's answer. Rejecting it in
     * the canonical constructor is what makes that unrepresentable rather than merely documented.
     */
    @Test
    void anEndOfCallMarkerCannotCarryContent() {
        assertThatThrownBy(
            () -> new LlmResponseChunk("answer text", null, null, null, null, null, true))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("end-of-call");

        assertThatThrownBy(
            () -> new LlmResponseChunk(null, "reasoning", null, null, null, null, true))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new LlmResponseChunk(null, null,
            List.of(new LlmToolCallDelta(0, "id", "name", "{}")), null, null, null, true))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new LlmResponseChunk(null, null, null, null,
            List.of(new LlmPart("text", "hi", null, null)), null, true))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /** Every existing construction site must keep producing an ordinary, forwardable chunk. */
    @Test
    void anOrdinaryChunkIsNotAnEndOfCallMarker() {
        assertThat(new LlmResponseChunk("hi", null, null, null).endOfCall()).isFalse();
        assertThat(new LlmResponseChunk("hi", null, null, null, null).endOfCall()).isFalse();
        assertThat(new LlmResponseChunk("hi", null, null, null, null, null).endOfCall()).isFalse();
    }
}
