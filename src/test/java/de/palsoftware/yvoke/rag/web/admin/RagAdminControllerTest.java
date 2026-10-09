package de.palsoftware.yvoke.rag.web.admin;

import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import org.springframework.test.web.servlet.MockMvc;

import org.springframework.mock.web.MockMultipartFile;

import de.palsoftware.yvoke.rag.prompt.SystemPromptType;

import de.palsoftware.yvoke.rag.prompt.SystemPrompt;

import de.palsoftware.yvoke.rag.prompt.Playbook;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import static org.mockito.ArgumentMatchers.anyBoolean;

import de.palsoftware.yvoke.area.TestAreas;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.service.CollectionService;
import de.palsoftware.yvoke.rag.core.service.RagService;
import de.palsoftware.yvoke.rag.prompt.PlaybookService;
import de.palsoftware.yvoke.rag.prompt.SystemPromptService;
import de.palsoftware.yvoke.rag.retrieval.HybridSearch;
import de.palsoftware.yvoke.rag.retrieval.RagAdminViewService;
import de.palsoftware.yvoke.rag.retrieval.RetrievalLogRepository;
import de.palsoftware.yvoke.rag.retrieval.RetrievalTelemetryService;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.ui.Model;
import org.mockito.InOrder;
import de.palsoftware.yvoke.rag.retrieval.SearchOptions;
import de.palsoftware.yvoke.rag.retrieval.SearchWithId;

class RagAdminControllerTest {

    /** Mirrors app.retrieval.max-limit in application.yml. */
    private static final int MAX_LIMIT = 20;

    private HybridSearch hybridSearch;
    private RetrievalLogRepository retrievalLogRepository;
    private RagAdminViewService ragAdminViewService;
    private CollectionService collectionService;
    private PlaybookService playbookService;
    private SystemPromptService systemPromptService;
    private RagService ragService;
    private ObjectMapper objectMapper;
    private RetrievalTelemetryService telemetryService;

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

        telemetryService = mock(RetrievalTelemetryService.class);

        controller = new RagAdminController(hybridSearch, retrievalLogRepository,
            ragAdminViewService, collectionService, playbookService, systemPromptService,
            ragService, objectMapper, telemetryService, 100, MAX_LIMIT, TestAreas.withAreas("OIM"));

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

    /**
     * The Top-K input's {@code max} is bound from {@code app.retrieval.max-limit} rather than
     * hardcoded in the template. A form that accepts a Top-K above the ceiling
     * {@link de.palsoftware.yvoke.rag.retrieval.HybridSearch} applies returns a clamped page with
     * nothing on screen explaining the shortfall — and a second hardcoded ceiling in the template
     * is one only a human keeps in sync with config.
     */
    @Test
    void theSearchFormsTopKCeilingComesFromConfig() {
        Collection col1 = new Collection(UUID.randomUUID(), "Col1", "Desc1", List.of("1.0"),
            OffsetDateTime.now());
        when(collectionService.listCollections()).thenReturn(List.of(col1));
        when(collectionService.getCollection("Col1")).thenReturn(Optional.of(col1));

        controller.testSearch(null, null, null, 8, true, true, model);

        verify(model).addAttribute("maxLimit", MAX_LIMIT);
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

    /**
     * S4.15. Retrieval telemetry is written off the search hot path by a single-threaded executor,
     * so the console's read is racing its own write. Two things make it correct: {@code flush()}
     * waits for that write to land, and the read is by {@code searchId} — the row belonging to the
     * search THIS request just ran.
     *
     * <p>
     * Drop the barrier and the read usually loses the race and finds nothing, so the telemetry
     * panel renders empty beside a result list that is full — which an operator reads as "this
     * search produced no lane data" and goes hunting for a retrieval bug that does not exist. Read
     * "the newest row for this collection" instead and it is worse than empty: the panel shows the
     * PREVIOUS search's pools, promotions and lane trace next to the current results, and every
     * number on screen is plausible, internally consistent, and about a different query. This
     * console exists to diagnose retrieval; a console that can attribute one search's telemetry to
     * another is actively misleading, and nothing on the page would ever say so.
     *
     * <p>
     * No existing controller test issues a query at all — they all pass {@code query == null} and
     * return before the search block — so neither the barrier nor the id-scoped read is covered
     * anywhere, and the ordering between them is covered nowhere at all.
     */
    @Test
    void theSearchConsoleFlushesThenReadsTheTelemetryRowOfTheSearchItJustRan() {
        Collection col1 = new Collection(UUID.randomUUID(), "Col1", "Desc1", List.of("1.0"),
            OffsetDateTime.now());
        when(collectionService.listCollections()).thenReturn(List.of(col1));
        when(collectionService.getCollection("Col1")).thenReturn(Optional.of(col1));

        UUID searchId = UUID.randomUUID();
        when(hybridSearch.searchWithId(eq("person"), any(SearchOptions.class)))
            .thenReturn(new SearchWithId(Collections.emptyList(), searchId));
        when(retrievalLogRepository.findTelemetryById(searchId)).thenReturn(Optional.empty());

        controller.testSearch("person", "Col1", null, 8, true, true, model);

        InOrder inOrder = inOrder(telemetryService, retrievalLogRepository);
        inOrder.verify(telemetryService).flush();
        inOrder.verify(retrievalLogRepository).findTelemetryById(searchId);
        verify(retrievalLogRepository, times(1)).findTelemetryById(any(UUID.class));
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

    /**
     * P1-12: the area chosen on each form reaches the save. A form field no controller binds is a
     * silent no-op, so this goes through MockMvc and the real parameter names.
     */
    @Test
    void thePostedAreaReachesTheSavedPlaybookAndPrompt() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/admin/playbooks").param("name", "pb").param("title", "PB")
            .param("templateText", "text").param("area", "PingID"))
            .andExpect(status().is3xxRedirection());
        verify(playbookService).savePlaybook(eq("pb"), eq("PB"), any(), eq("text"), any(),
            anyBoolean(), any(), anyBoolean(), eq("PingID"));

        mvc.perform(post("/admin/prompts").param("name", "p").param("type", "CHAT")
            .param("systemPrompt", "text").param("area", "PingID"))
            .andExpect(status().is3xxRedirection());
        verify(systemPromptService).savePrompt(eq("p"), eq(SystemPromptType.CHAT), eq("text"),
            any(), eq("PingID"));
    }

    @Test
    void theImportFormsPassTheirAreaAsTheFallback() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(playbookService.importPlaybookFromMarkdown(any(), any(), any())).thenReturn(
            new Playbook("pb", "PB", "", "text", List.of(), false, null, false, null, null));
        when(systemPromptService.importPromptFromMarkdown(any(), any(), any()))
            .thenReturn(new SystemPrompt("p", SystemPromptType.CHAT, "text", ""));

        mvc.perform(multipart("/admin/playbooks/import")
            .file(new MockMultipartFile("file", "pb.md", "text/markdown", "text".getBytes()))
            .param("area", "PingID")).andExpect(status().is3xxRedirection());
        verify(playbookService).importPlaybookFromMarkdown("text", "pb", "PingID");

        mvc.perform(multipart("/admin/prompts/import")
            .file(new MockMultipartFile("file", "p.md", "text/markdown", "text".getBytes()))
            .param("area", "PingID")).andExpect(status().is3xxRedirection());
        verify(systemPromptService).importPromptFromMarkdown("text", "p", "PingID");
    }

    @Test
    void testExportPrompt_escapesFilenameAgainstHeaderInjection() {
        String unsafeName = "malicious\"; dummy=\"";
        when(systemPromptService.exportPromptToMarkdown(unsafeName))
            .thenReturn("---\nname: malicious\n---\nHello");

        var response = controller.exportPrompt(unsafeName);

        String disposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(disposition).doesNotContain("filename=\"malicious\"; dummy=\"");
        assertThat(disposition).isEqualTo(ContentDisposition.attachment()
            .filename(unsafeName + ".md", StandardCharsets.UTF_8).build().toString());
    }

    @Test
    void testExportPlaybook_escapesFilenameAgainstHeaderInjection() {
        String unsafeName = "malicious\"; dummy=\"";
        when(playbookService.exportPlaybookToMarkdown(unsafeName))
            .thenReturn("---\nname: malicious\n---\nHello");

        var response = controller.exportPlaybook(unsafeName);

        String disposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(disposition).doesNotContain("filename=\"malicious\"; dummy=\"");
        assertThat(disposition).isEqualTo(ContentDisposition.attachment()
            .filename(unsafeName + ".md", StandardCharsets.UTF_8).build().toString());
    }
}
