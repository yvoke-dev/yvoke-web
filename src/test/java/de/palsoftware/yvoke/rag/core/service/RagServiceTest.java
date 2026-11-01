package de.palsoftware.yvoke.rag.core.service;

import de.palsoftware.yvoke.rag.core.model.AgenticRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.llm.core.model.LlmToolCall;
import de.palsoftware.yvoke.llm.core.service.LlmClient;
import de.palsoftware.yvoke.rag.core.model.AgenticChatContext;
import de.palsoftware.yvoke.rag.retrieval.HybridSearch;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

public class RagServiceTest {

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
            15, 4096, 0);
    }

    @Test
    public void testRagDisabledThrowsException() {
        ReflectionTestUtils.setField(ragService, "chatEnabled", false);

        Assertions.assertThrows(IllegalStateException.class, () -> {
            ragService.generateAgenticAnswer(
                AgenticRequest.builder().query("query").modelOverride("model").build(), token -> {
                });
        });
    }

    @Test
    void hasExactlyOneAutowiredConstructorAndAlwaysRegistersWebSearchInToolRegistry() {
        List<Constructor<?>> autowired = Arrays.stream(RagService.class.getDeclaredConstructors())
            .filter(c -> c.isAnnotationPresent(Autowired.class)).toList();

        assertThat(autowired).hasSize(1);
        assertThat(ragService.getToolRegistry()).containsKey("WebSearch");
    }

    @Test
    void guardToolExecutionFailsClosedWhenContextOrRepositoriesMissingForAreaRestrictedCall() {
        LlmToolCall searchCall =
            new LlmToolCall("call-1", "function", "search_corpus", "{\"collection\":\"OIM\"}");
        assertThat((String) ReflectionTestUtils.invokeMethod(ragService, "guardToolExecution",
            searchCall, null))
            .isEqualTo("Error: Tool execution requires an authorized area context.");

        AgenticChatContext restrictedCtx = new AgenticChatContext(Set.of("oim"));
        assertThat((String) ReflectionTestUtils.invokeMethod(ragService, "guardToolExecution",
            searchCall, restrictedCtx))
            .isEqualTo("Error: Tool execution blocked: area verification unavailable.");

        LlmToolCall tocCall = new LlmToolCall("call-2", "function", "get_toc",
            "{\"document_id\":\"11111111-1111-1111-1111-111111111111\"}");
        assertThat((String) ReflectionTestUtils.invokeMethod(ragService, "guardToolExecution",
            tocCall, restrictedCtx))
            .isEqualTo("Error: Document with ID '11111111-1111-1111-1111-111111111111' not found.");

        LlmToolCall sectionDocCall = new LlmToolCall("call-3", "function", "get_section",
            "{\"document_id\":\"11111111-1111-1111-1111-111111111111\"}");
        assertThat((String) ReflectionTestUtils.invokeMethod(ragService, "guardToolExecution",
            sectionDocCall, restrictedCtx))
            .isEqualTo("Error: Document not found: 11111111-1111-1111-1111-111111111111");

        LlmToolCall sectionChunkCall =
            new LlmToolCall("call-4", "function", "get_section", "{\"chunk_id\":\"a1b2c3d4\"}");
        assertThat((String) ReflectionTestUtils.invokeMethod(ragService, "guardToolExecution",
            sectionChunkCall, restrictedCtx)).isEqualTo("Error: Chunk not found: a1b2c3d4");
    }
}
