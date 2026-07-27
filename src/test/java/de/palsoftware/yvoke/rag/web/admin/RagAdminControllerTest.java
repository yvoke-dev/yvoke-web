package de.palsoftware.yvoke.rag.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.service.CollectionService;
import de.palsoftware.yvoke.rag.core.service.RagService;
import de.palsoftware.yvoke.rag.prompt.PlaybookService;
import de.palsoftware.yvoke.rag.prompt.SystemPromptService;
import de.palsoftware.yvoke.rag.retrieval.HybridSearch;
import de.palsoftware.yvoke.rag.retrieval.RagAdminViewService;
import de.palsoftware.yvoke.rag.retrieval.RetrievalLogRepository;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.Model;

class RagAdminControllerTest {

    private HybridSearch hybridSearch;
    private RetrievalLogRepository retrievalLogRepository;
    private RagAdminViewService ragAdminViewService;
    private CollectionService collectionService;
    private PlaybookService playbookService;
    private SystemPromptService systemPromptService;
    private RagService ragService;
    private ObjectMapper objectMapper;

    private RagAdminController controller;
    private Model model;

    @BeforeEach
    void setUp() {
        hybridSearch = mock(HybridSearch.class);
        retrievalLogRepository = mock(RetrievalLogRepository.class);
        ragAdminViewService = mock(RagAdminViewService.class);
        collectionService = mock(CollectionService.class);
        playbookService = mock(PlaybookService.class);
        systemPromptService = mock(SystemPromptService.class);
        ragService = mock(RagService.class);
        objectMapper = new ObjectMapper();

        controller = new RagAdminController(hybridSearch, retrievalLogRepository,
            ragAdminViewService, collectionService, playbookService, systemPromptService,
            ragService, objectMapper, 100);

        model = mock(Model.class);
    }

    @Test
    void testSearchDefaultsToFirstCollectionWhenNoneSpecified() {
        Collection col1 = new Collection(UUID.randomUUID(), "Col1", "Desc1", List.of("1.0"),
            OffsetDateTime.now());
        Collection col2 = new Collection(UUID.randomUUID(), "Col2", "Desc2", List.of("2.0"),
            OffsetDateTime.now());
        when(collectionService.listCollections()).thenReturn(List.of(col1, col2));
        when(collectionService.getCollection("Col1")).thenReturn(Optional.of(col1));

        String view = controller.testSearch(null, null, null, 8, true, true, model);

        assertThat(view).isEqualTo("admin/search");
        verify(model).addAttribute("collections", List.of(col1, col2));
        verify(model).addAttribute("collection", "Col1");
        verify(model).addAttribute("tag", "1.0");
    }

    @Test
    void testSearchFallsBackToFirstCollectionWhenInvalidSpecified() {
        Collection col1 = new Collection(UUID.randomUUID(), "Col1", "Desc1", List.of("1.0"),
            OffsetDateTime.now());
        when(collectionService.listCollections()).thenReturn(List.of(col1));
        when(collectionService.getCollection("Col1")).thenReturn(Optional.of(col1));

        controller.testSearch(null, "NonExistentCol", null, 8, true, true, model);

        verify(model).addAttribute("collection", "Col1");
    }

    @Test
    void testSearchKeepsRawDefaultWhenCollectionsEmpty() {
        when(collectionService.listCollections()).thenReturn(Collections.emptyList());
        when(collectionService.getCollection("OIM")).thenReturn(Optional.empty());

        controller.testSearch(null, null, null, 8, true, true, model);

        verify(model).addAttribute("collection", "OIM");
    }

    @Test
    void testExportPrompt() {
        when(systemPromptService.exportPromptToMarkdown("test-prompt"))
            .thenReturn("---\nname: test-prompt\n---\nHello");

        var response = controller.exportPrompt("test-prompt");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getFirst("Content-Disposition"))
            .contains("filename=\"test-prompt.md\"");
    }
}
