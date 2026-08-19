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

    // ------------------------------------------------------------------------
    // index = -1: the shape BOTH Azure clients emit, and the only shape in production
    // that exercises the id-lookup / last-builder fallback. It had no coverage at all.
    // ------------------------------------------------------------------------

    /**
     * The exact delta stream {@code AzureOpenAiLlmClient} produces for two parallel tool calls.
     *
     * <p>
     * {@code ChatCompletionsFunctionToolCall}'s deserializer discards the wire-level {@code index},
     * so the client emits {@code index = -1} and reassembly falls back to: an id-bearing fragment
     * opens (or finds) a call, an id-less fragment appends to the MOST RECENTLY OPENED one. That is
     * correct only while the service keeps each call's fragments contiguous.
     *
     * <p>
     * The counterpart in {@code AzureOpenAiLlmClientTest} asserts that the client really emits this
     * sequence; this test owns what the sequence reassembles into. Splitting it that way is
     * deliberate — the two classes are in different packages, and the delta stream IS the contract
     * between them, so each side pins its own half rather than one side re-implementing the other.
     */
    @Test
    void theAzureParallelShapeReassemblesIntoTwoDistinctCalls() {
        ToolCallAccumulator acc = new ToolCallAccumulator();
        acc.accept(new LlmToolCallDelta(-1, "call_a", "search", ""));
        acc.accept(new LlmToolCallDelta(-1, null, null, "{\"q\":1}"));
        acc.accept(new LlmToolCallDelta(-1, "call_b", "fetch", ""));
        acc.accept(new LlmToolCallDelta(-1, null, null, "{\"id\":2}"));

        List<LlmToolCall> calls = acc.assemble();

        assertThat(calls).as("both calls must survive as distinct entries").hasSize(2);
        assertThat(calls.get(0).id()).isEqualTo("call_a");
        assertThat(calls.get(0).name()).isEqualTo("search");
        assertThat(calls.get(0).arguments()).isEqualTo("{\"q\":1}");
        assertThat(calls.get(1).id()).isEqualTo("call_b");
        assertThat(calls.get(1).name()).isEqualTo("fetch");
        assertThat(calls.get(1).arguments()).isEqualTo("{\"id\":2}");
    }

    /**
     * A redelivered id must find its existing call rather than mint a second one — the id lookup is
     * what makes the {@code index = -1} path tolerant of the service repeating an id.
     */
    @Test
    void aRedeliveredIdAppendsToTheSameCallRatherThanOpeningAnother() {
        ToolCallAccumulator acc = new ToolCallAccumulator();
        acc.accept(new LlmToolCallDelta(-1, "call_a", "search", "{\"q\":"));
        acc.accept(new LlmToolCallDelta(-1, "call_a", null, "1}"));

        List<LlmToolCall> calls = acc.assemble();

        assertThat(calls).as("the same id is the same call").hasSize(1);
        assertThat(calls.get(0).arguments()).isEqualTo("{\"q\":1}");
    }

    /** An id-less fragment arriving before any call is opened must not throw. */
    @Test
    void anIdLessFragmentWithNoOpenCallIsToleratedRatherThanThrowing() {
        ToolCallAccumulator acc = new ToolCallAccumulator();
        acc.accept(new LlmToolCallDelta(-1, null, null, "{\"orphan\":true}"));

        List<LlmToolCall> calls = acc.assemble();

        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).arguments()).isEqualTo("{\"orphan\":true}");
    }

    /**
     * The limitation made visible.
     *
     * <p>
     * With the index gone, an argument fragment belonging to an EARLIER call — which the wire
     * format permits and the {@code index} field exists precisely to express — is appended to the
     * most recently opened one instead. Both calls then carry structurally broken JSON, and every
     * layer downstream sees only "the model sent bad arguments": the tool fails, the model is told
     * to try again, and nothing anywhere names the real cause.
     *
     * <p>
     * This cannot be fixed from here — the information was destroyed by the SDK's deserializer
     * before the delta was built — so the accumulator's job is to make it LOUD rather than silent.
     * The balance check is what turns an invisible mis-split into a diagnosable one; it is
     * deliberately structural rather than a JSON parse, so the accumulator stays dependency-free.
     */
    @Test
    void anInterleavedFragmentIsMisattributedAndReportedAsUnbalanced() {
        ToolCallAccumulator acc = new ToolCallAccumulator();
        acc.accept(new LlmToolCallDelta(-1, "call_a", "search", "{\"q\":"));
        acc.accept(new LlmToolCallDelta(-1, "call_b", "fetch", "{\"id\":2}"));
        // Belongs to call_a; the service would have said index:0. Without the index it lands on b.
        acc.accept(new LlmToolCallDelta(-1, null, null, "1}"));

        List<LlmToolCall> calls = acc.assemble();

        assertThat(calls.get(0).arguments())
            .as("call_a is left truncated — the fragment that completes it went elsewhere")
            .isEqualTo("{\"q\":");
        assertThat(ToolCallAccumulator.looksBalanced(calls.get(0).arguments()))
            .as("a truncated fragment must be detectable, not silently handed on").isFalse();
        assertThat(ToolCallAccumulator.looksBalanced("{\"q\":1}")).as("complete object").isTrue();
        assertThat(ToolCallAccumulator.looksBalanced("")).as("no arguments is not a mis-split")
            .isTrue();
        assertThat(ToolCallAccumulator.looksBalanced("{\"s\":\"a}b\"}"))
            .as("a delimiter inside a string is not a delimiter").isTrue();
    }
}
