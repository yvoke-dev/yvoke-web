package de.palsoftware.yvoke.rag.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import de.palsoftware.yvoke.llm.core.model.LlmToolCall;
import de.palsoftware.yvoke.llm.core.model.LlmToolCallDelta;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the tool-call reconciliation extracted from RagService (MNT-13 / Wave 3.7): both provider
 * delta shapes, plus id-keyed merging and the thought-signature -> extraContent projection.
 */
class ToolCallAccumulatorTest {

    @Test
    void emptyByDefault() {
        assertThat(new ToolCallAccumulator().isEmpty()).isTrue();
    }

    @Test
    void geminiStyleCompleteDeltaProducesOneFinalisedCall() {
        ToolCallAccumulator acc = new ToolCallAccumulator();
        // complete=true: whole call in one delta, identified by id, arguments final.
        acc.accept(new LlmToolCallDelta(0, "call-1", "search", "{\"q\":\"x\"}", null, true));

        assertThat(acc.isEmpty()).isFalse();
        List<LlmToolCall> calls = acc.assemble();
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).id()).isEqualTo("call-1");
        assertThat(calls.get(0).name()).isEqualTo("search");
        assertThat(calls.get(0).arguments()).isEqualTo("{\"q\":\"x\"}");
        assertThat(calls.get(0).type()).isEqualTo("function");
    }

    @Test
    void completeDeltaReplacesArgumentsOnRedeliveryBySameId() {
        ToolCallAccumulator acc = new ToolCallAccumulator();
        acc.accept(new LlmToolCallDelta(0, "call-1", "search", "{\"q\":\"old\"}", null, true));
        acc.accept(new LlmToolCallDelta(0, "call-1", "search", "{\"q\":\"new\"}", null, true));

        List<LlmToolCall> calls = acc.assemble();
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).arguments()).isEqualTo("{\"q\":\"new\"}");
    }

    @Test
    void openAiStyleIncrementalDeltasAppendArgumentsByIndex() {
        ToolCallAccumulator acc = new ToolCallAccumulator();
        // Incremental fragments keyed by positional index; name arrives first, args stream in.
        acc.accept(new LlmToolCallDelta(0, "call-9", "lookup", null));
        acc.accept(new LlmToolCallDelta(0, null, null, "{\"a\":"));
        acc.accept(new LlmToolCallDelta(0, null, null, "1}"));

        List<LlmToolCall> calls = acc.assemble();
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).id()).isEqualTo("call-9");
        assertThat(calls.get(0).name()).isEqualTo("lookup");
        assertThat(calls.get(0).arguments()).isEqualTo("{\"a\":1}");
    }

    @Test
    void distinctIndicesProduceDistinctCalls() {
        ToolCallAccumulator acc = new ToolCallAccumulator();
        acc.accept(new LlmToolCallDelta(0, "c0", "first", "{}"));
        acc.accept(new LlmToolCallDelta(1, "c1", "second", "{}"));

        List<LlmToolCall> calls = acc.assemble();
        assertThat(calls).hasSize(2);
        assertThat(calls).extracting(LlmToolCall::name).containsExactly("first", "second");
    }

    @Test
    void thoughtSignatureIsProjectedIntoExtraContent() {
        ToolCallAccumulator acc = new ToolCallAccumulator();
        acc.accept(new LlmToolCallDelta(0, "call-1", "search", "{}", "sig-abc", true));

        LlmToolCall call = acc.assemble().get(0);
        assertThat(call.extraContent()).containsEntry("thoughtSignature", "sig-abc");
    }
}
