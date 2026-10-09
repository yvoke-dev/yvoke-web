package de.palsoftware.yvoke.chat.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import de.palsoftware.yvoke.chat.core.model.ToolCallRecord;
import de.palsoftware.yvoke.llm.core.model.LlmMessage;
import de.palsoftware.yvoke.llm.core.model.LlmToolCall;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ToolCallTraceExtractorTest {

    private ToolCallTraceExtractor extractor;
    private UUID messageId;

    @BeforeEach
    void setUp() {
        extractor = new ToolCallTraceExtractor();
        messageId = UUID.randomUUID();
    }

    @Test
    void testNullOrEmptyMessages_ReturnsEmptyList() {
        assertThat(extractor.extract(messageId, null)).isEmpty();
        assertThat(extractor.extract(messageId, Collections.emptyList())).isEmpty();
    }

    @Test
    void testExtractToolCalls_HappyPath() {
        LlmToolCall tc1 =
            new LlmToolCall("call_1", "function", "search_corpus", "{\"query\":\"test\"}");
        LlmToolCall tc2 = new LlmToolCall("call_2", "function", "calculate", "{\"expr\":\"2+2\"}");

        LlmMessage assistantMsg =
            new LlmMessage("assistant", "", null, List.of(tc1, tc2), null, null);
        LlmMessage toolMsg1 =
            new LlmMessage("tool", "search results", null, null, "call_1", "search_corpus");
        LlmMessage toolMsg2 = new LlmMessage("tool", "4", null, null, "call_2", "calculate");
        LlmMessage finalAssistantMsg =
            new LlmMessage("assistant", "Here is the final answer.", null, null, null, null);

        List<LlmMessage> messages = List.of(new LlmMessage("user", "Hello"), assistantMsg, toolMsg1,
            toolMsg2, finalAssistantMsg);

        List<ToolCallRecord> records = extractor.extract(messageId, messages);

        assertThat(records).hasSize(2);

        ToolCallRecord r0 = records.get(0);
        assertThat(r0.messageId()).isEqualTo(messageId);
        assertThat(r0.seq()).isEqualTo(0);
        assertThat(r0.toolCallId()).isEqualTo("call_1");
        assertThat(r0.toolName()).isEqualTo("search_corpus");
        assertThat(r0.arguments()).isEqualTo("{\"query\":\"test\"}");
        assertThat(r0.result()).isEqualTo("search results");
        assertThat(r0.isError()).isFalse();
        assertThat(r0.id()).isNotNull();
        assertThat(r0.createdAt()).isNotNull();

        ToolCallRecord r1 = records.get(1);
        assertThat(r1.messageId()).isEqualTo(messageId);
        assertThat(r1.seq()).isEqualTo(1);
        assertThat(r1.toolCallId()).isEqualTo("call_2");
        assertThat(r1.toolName()).isEqualTo("calculate");
        assertThat(r1.arguments()).isEqualTo("{\"expr\":\"2+2\"}");
        assertThat(r1.result()).isEqualTo("4");
        assertThat(r1.isError()).isFalse();
        assertThat(r1.id()).isNotNull();
    }

    @Test
    void testDuplicateCallIdAcrossIterations_MaintainsMonotonicSeq() {
        // Turn 1
        LlmToolCall tcTurn1 =
            new LlmToolCall("call_repeat", "function", "search_corpus", "{\"q\":\"a\"}");
        LlmMessage assistantTurn1 =
            new LlmMessage("assistant", "", null, List.of(tcTurn1), null, null);
        LlmMessage toolTurn1 =
            new LlmMessage("tool", "result_a", null, null, "call_repeat", "search_corpus");

        // Turn 2 re-uses the exact same call ID "call_repeat"
        LlmToolCall tcTurn2 =
            new LlmToolCall("call_repeat", "function", "search_corpus", "{\"q\":\"b\"}");
        LlmMessage assistantTurn2 =
            new LlmMessage("assistant", "", null, List.of(tcTurn2), null, null);
        LlmMessage toolTurn2 =
            new LlmMessage("tool", "result_b", null, null, "call_repeat", "search_corpus");

        LlmMessage finalTurn = new LlmMessage("assistant", "Final done");

        List<LlmMessage> messages = List.of(new LlmMessage("user", "Start"), assistantTurn1,
            toolTurn1, assistantTurn2, toolTurn2, finalTurn);

        List<ToolCallRecord> records = extractor.extract(messageId, messages);

        assertThat(records).hasSize(2);
        assertThat(records.get(0).seq()).isEqualTo(0);
        assertThat(records.get(0).toolCallId()).isEqualTo("call_repeat");
        assertThat(records.get(0).arguments()).isEqualTo("{\"q\":\"a\"}");
        assertThat(records.get(0).result()).isEqualTo("result_a");

        assertThat(records.get(1).seq()).isEqualTo(1);
        assertThat(records.get(1).toolCallId()).isEqualTo("call_repeat");
        assertThat(records.get(1).arguments()).isEqualTo("{\"q\":\"b\"}");
        assertThat(records.get(1).result()).isEqualTo("result_b");
    }

    @Test
    void testMissingToolResponse_FlagsErrorAndNullResult() {
        // LLM emitted 2 tool calls, but stream interrupted after executing only the first
        LlmToolCall tc1 = new LlmToolCall("call_1", "function", "tool_ok", "{}");
        LlmToolCall tc2 =
            new LlmToolCall("call_2", "function", "tool_abandoned", "{\"param\":\"val\"}");

        LlmMessage assistantMsg =
            new LlmMessage("assistant", "", null, List.of(tc1, tc2), null, null);
        LlmMessage toolMsg1 = new LlmMessage("tool", "ok", null, null, "call_1", "tool_ok");
        // No tool response for call_2!

        List<LlmMessage> messages = List.of(assistantMsg, toolMsg1);

        List<ToolCallRecord> records = extractor.extract(messageId, messages);

        assertThat(records).hasSize(2);
        assertThat(records.get(0).seq()).isEqualTo(0);
        assertThat(records.get(0).result()).isEqualTo("ok");
        assertThat(records.get(0).isError()).isFalse();

        assertThat(records.get(1).seq()).isEqualTo(1);
        assertThat(records.get(1).toolCallId()).isEqualTo("call_2");
        assertThat(records.get(1).result()).isNull();
        assertThat(records.get(1).isError()).isTrue();
    }

    @Test
    void testSanitizeNullBytesAndErrorResult() {
        LlmToolCall tc = new LlmToolCall("call_err\u0000", "function", "tool\u0000name",
            "{\"arg\":\"val\u0000\"}");
        LlmMessage assistantMsg = new LlmMessage("assistant", "", null, List.of(tc), null, null);
        LlmMessage toolMsg = new LlmMessage("tool", "Error: failed\u0000 to fetch", null, null,
            "call_err\u0000", "tool\u0000name");

        List<ToolCallRecord> records = extractor.extract(messageId, List.of(assistantMsg, toolMsg));

        assertThat(records).hasSize(1);
        ToolCallRecord r = records.get(0);
        assertThat(r.toolCallId()).isEqualTo("call_err");
        assertThat(r.toolName()).isEqualTo("toolname");
        assertThat(r.arguments()).isEqualTo("{\"arg\":\"val\"}");
        assertThat(r.result()).isEqualTo("Error: failed to fetch");
        assertThat(r.isError()).isTrue();
    }

    @Test
    void testNullOrBlankCallIdOrToolName_ReceivesDeterministicFallbacks() {
        LlmToolCall tcNull = new LlmToolCall(null, "function", null, "{}");
        LlmToolCall tcBlank = new LlmToolCall("   ", "function", "   ", "{}");

        LlmMessage assistantMsg =
            new LlmMessage("assistant", "", null, List.of(tcNull, tcBlank), null, null);
        LlmMessage toolMsg1 = new LlmMessage("tool", "res1", null, null, null, null);
        LlmMessage toolMsg2 = new LlmMessage("tool", "res2", null, null, null, null);

        List<LlmMessage> messages = List.of(assistantMsg, toolMsg1, toolMsg2);

        List<ToolCallRecord> records = extractor.extract(messageId, messages);

        assertThat(records).hasSize(2);
        assertThat(records.get(0).seq()).isEqualTo(0);
        assertThat(records.get(0).toolCallId()).isEqualTo("call_0");
        assertThat(records.get(0).toolName()).isEqualTo("unknown");
        assertThat(records.get(0).result()).isEqualTo("res1");

        assertThat(records.get(1).seq()).isEqualTo(1);
        assertThat(records.get(1).toolCallId()).isEqualTo("call_1");
        assertThat(records.get(1).toolName()).isEqualTo("unknown");
        assertThat(records.get(1).result()).isEqualTo("res2");
    }
}
