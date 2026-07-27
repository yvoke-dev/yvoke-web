package de.palsoftware.yvoke.rag.core.service;

import de.palsoftware.yvoke.rag.core.model.AgenticRequest;
import de.palsoftware.yvoke.rag.core.model.RagResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.chat.core.tool.AskClarifyingQuestionToolCallback;
import de.palsoftware.yvoke.llm.core.service.LlmClient;
import de.palsoftware.yvoke.llm.core.model.LlmRequest;
import de.palsoftware.yvoke.llm.core.model.LlmResponseChunk;
import de.palsoftware.yvoke.llm.core.model.LlmToolCallDelta;
import de.palsoftware.yvoke.llm.core.model.LlmUsage;
import de.palsoftware.yvoke.rag.retrieval.HybridSearch;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import java.util.ArrayList;
import java.util.function.Consumer;

public class RagServiceAgenticTest {

    private HybridSearch hybridSearch;
    private LlmClient llmClient;
    private CitationVerifier citationVerifier;
    private RagService ragService;

    @BeforeEach
    public void setUp() {
        hybridSearch = mock(HybridSearch.class);
        llmClient = mock(LlmClient.class);
        citationVerifier = mock(CitationVerifier.class);

        ragService = new RagService(hybridSearch, llmClient, citationVerifier, new ObjectMapper(),
            6, 4096, 0);
    }

    @Test
    public void testAgenticToolCallingLoopSuccess() {
        // 1. Mock a ToolCallback for oim_search
        ToolCallback mockSearchTool = mock(ToolCallback.class);
        ToolDefinition toolDef = mock(ToolDefinition.class);
        when(toolDef.name()).thenReturn("oim_search");
        when(mockSearchTool.getToolDefinition()).thenReturn(toolDef);
        when(mockSearchTool.call(anyString())).thenReturn("mock search chunk response text");

        ragService.getToolRegistry().put("oim_search", mockSearchTool);

        // 2. Mock LlmClient stream responses for the two turns
        doAnswer(new org.mockito.stubbing.Answer<Void>() {
            private int callCount = 0;

            @Override
            public Void answer(org.mockito.invocation.InvocationOnMock inv) {
                Consumer<LlmResponseChunk> cb = inv.getArgument(1);
                if (callCount == 0) {
                    cb.accept(new LlmResponseChunk(null, null, List.of(new LlmToolCallDelta(0,
                        "call_123", "oim_search", "{\"query\":\"Person\"}")),
                        new LlmUsage(10, 20, 30, 0, 0)));
                } else if (callCount == 1) {
                    cb.accept(new LlmResponseChunk("Final stream output.", null, null,
                        new LlmUsage(40, 50, 90, 0, 0)));
                }
                callCount++;
                return null;
            }
        }).when(llmClient).generateStream(any(LlmRequest.class), any());

        // 4. Run the method
        List<String> tokens = new ArrayList<>();
        RagResult result =
            ragService
                .generateAgenticAnswer(
                    AgenticRequest.builder().query("Find Person table")
                        .modelOverride("model-override").history(Collections.emptyList()).build(),
                    tokens::add);

        String answer = String.join("", tokens);

        // Verify that the tool execution progress was printed
        assertThat(answer).contains("🔧 *Calling tool:* oim_search");
        // Verify that the final text streamed is present
        assertThat(answer).contains("Final stream output.");

        // Verify that the tool was called exactly once with expected arguments
        verify(mockSearchTool, times(1)).call("{\"query\":\"Person\"}");
    }

    @Test
    public void testAgenticToolCallingLoopHitsCap() {
        // 1. Mock a ToolCallback that gets called repeatedly
        ToolCallback mockSearchTool = mock(ToolCallback.class);
        ToolDefinition toolDef = mock(ToolDefinition.class);
        when(toolDef.name()).thenReturn("oim_search");
        when(mockSearchTool.getToolDefinition()).thenReturn(toolDef);
        when(mockSearchTool.call(anyString())).thenReturn("mock response");

        ragService.getToolRegistry().put("oim_search", mockSearchTool);

        // 2. Mock LlmClient stream responses for hits cap (max iterations = 3)
        doAnswer(new org.mockito.stubbing.Answer<Void>() {
            private int callCount = 0;

            @Override
            public Void answer(org.mockito.invocation.InvocationOnMock inv) {
                Consumer<LlmResponseChunk> cb = inv.getArgument(1);
                if (callCount < 3) {
                    cb.accept(new LlmResponseChunk(null, null,
                        List.of(new LlmToolCallDelta(0, "call_1", "oim_search", "{}")),
                        new LlmUsage(10, 20, 30, 0, 0)));
                } else if (callCount == 3) {
                    cb.accept(new LlmResponseChunk("Forced answer due to cap.", null, null,
                        new LlmUsage(40, 50, 90, 0, 0)));
                }
                callCount++;
                return null;
            }
        }).when(llmClient).generateStream(any(LlmRequest.class), any());

        // Create a custom RagService with maxIterations = 3 to test the cap
        RagService customRagService = new RagService(hybridSearch, llmClient, citationVerifier,
            new ObjectMapper(), 3, 4096, 0);
        customRagService.getToolRegistry().put("oim_search", mockSearchTool);

        // 4. Run the method
        List<String> tokens = new ArrayList<>();
        RagResult result =
            customRagService
                .generateAgenticAnswer(
                    AgenticRequest.builder().query("Find Person table")
                        .modelOverride("model-override").history(Collections.emptyList()).build(),
                    tokens::add);

        String answer = String.join("", tokens);

        // It should call the tool exactly 3 times before breaking out
        verify(mockSearchTool, times(3)).call("{}");
        assertThat(answer).contains("Forced answer due to cap.");
        assertThat(answer).contains(
            "⚠️ *[System: The maximum loop count of 3 iterations was hit. The response was cut off to prevent an infinite loop.]*");
    }

    @Test
    public void testAgenticToolCallingLoopWithReasoningOnly() {
        // 1. Mock a ToolCallback for oim_search
        ToolCallback mockSearchTool = mock(ToolCallback.class);
        ToolDefinition toolDef = mock(ToolDefinition.class);
        when(toolDef.name()).thenReturn("oim_search");
        when(mockSearchTool.getToolDefinition()).thenReturn(toolDef);
        when(mockSearchTool.call(anyString())).thenReturn("mock search chunk response text");

        ragService.getToolRegistry().put("oim_search", mockSearchTool);

        // 2. Mock LlmClient stream responses for the two turns:
        // Turn 1 has reasoning and tool call, but no content.
        // Turn 2 has reasoning and final content.
        doAnswer(new org.mockito.stubbing.Answer<Void>() {
            private int callCount = 0;

            @Override
            public Void answer(org.mockito.invocation.InvocationOnMock inv) {
                Consumer<LlmResponseChunk> cb = inv.getArgument(1);
                if (callCount == 0) {
                    cb.accept(new LlmResponseChunk(null, "Reasoning in turn 1.",
                        List.of(new LlmToolCallDelta(0, "call_123", "oim_search", "{}")),
                        new LlmUsage(10, 20, 30, 0, 0)));
                } else if (callCount == 1) {
                    cb.accept(new LlmResponseChunk("Final content in turn 2.",
                        "Reasoning in turn 2.", null, new LlmUsage(40, 50, 90, 0, 0)));
                }
                callCount++;
                return null;
            }
        }).when(llmClient).generateStream(any(LlmRequest.class), any());

        // 3. Run the method
        List<String> tokens = new ArrayList<>();
        RagResult result = ragService.generateAgenticAnswer(
            AgenticRequest.builder().query("Find Person").modelOverride("model-override")
                .history(Collections.emptyList()).build(),
            tokens::add);

        String answer = String.join("", tokens);

        // Verify that thinking tags are properly opened and closed in each turn
        // Turn 1 should be wrapped: <think>\nReasoning in turn 1.\n</think>\n\n
        // Turn 2 should have its own thinking: <think>\nReasoning in turn
        // 2.\n</think>\n\nFinal content in turn 2.
        assertThat(answer).contains("<think>\nReasoning in turn 1.\n</think>\n\n");
        assertThat(answer)
            .contains("<think>\nReasoning in turn 2.\n</think>\n\nFinal content in turn 2.");
    }

    @Test
    public void testAgenticToolCallingLoopHaltsOnClarifyingQuestion() {
        // Register the real AskClarifyingQuestionToolCallback
        AskClarifyingQuestionToolCallback tool =
            new AskClarifyingQuestionToolCallback(new ObjectMapper());
        ragService.getToolRegistry().put("ask_clarifying_question", tool);

        // Mock LLM response to call ask_clarifying_question
        doAnswer(new org.mockito.stubbing.Answer<Void>() {
            private int callCount = 0;

            @Override
            public Void answer(org.mockito.invocation.InvocationOnMock inv) {
                Consumer<LlmResponseChunk> cb = inv.getArgument(1);
                if (callCount == 0) {
                    cb.accept(new LlmResponseChunk(null, null,
                        List.of(new LlmToolCallDelta(0, "call_clarify", "ask_clarifying_question",
                            "{\"question\":\"Which collection?\",\"options\":[\"a\",\"b\"]}")),
                        new LlmUsage(10, 20, 30, 0, 0)));
                } else {
                    // This second turn should NOT be executed because the loop halts
                    cb.accept(new LlmResponseChunk("Should not generate this.", null, null,
                        new LlmUsage(40, 50, 90, 0, 0)));
                }
                callCount++;
                return null;
            }
        }).when(llmClient).generateStream(any(LlmRequest.class), any());

        List<String> tokens = new ArrayList<>();
        RagResult result = ragService.generateAgenticAnswer(
            AgenticRequest.builder().query("Search docs").modelOverride("model-override")
                .history(Collections.emptyList()).build(),
            tokens::add);

        String answer = String.join("", tokens);

        // Verify that it streamed the structured XML representation instead of standard tool text
        assertThat(answer).contains("<clarifying-question>");
        assertThat(answer).contains("<question>Which collection?</question>");
        assertThat(answer).contains("<option>a</option>");
        assertThat(answer).contains("<option>b</option>");
        assertThat(answer).contains("</clarifying-question>");

        // Verify that the loop stopped and didn't generate turn 2 content
        assertThat(answer).doesNotContain("Should not generate this.");
    }
}
