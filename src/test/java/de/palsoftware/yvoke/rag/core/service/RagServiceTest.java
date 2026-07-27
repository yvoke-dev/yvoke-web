package de.palsoftware.yvoke.rag.core.service;

import de.palsoftware.yvoke.rag.core.model.AgenticRequest;

import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.llm.core.service.LlmClient;
import de.palsoftware.yvoke.rag.retrieval.HybridSearch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
        org.springframework.test.util.ReflectionTestUtils.setField(ragService, "chatEnabled",
            false);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> {
            ragService.generateAgenticAnswer(
                AgenticRequest.builder().query("query").modelOverride("model").build(), token -> {
                });
        });
    }
}
