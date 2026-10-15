package de.palsoftware.yvoke.rag.core.service;

import de.palsoftware.yvoke.rag.core.model.AgenticRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.llm.core.service.LlmClient;
import de.palsoftware.yvoke.rag.retrieval.HybridSearch;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;
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
}
