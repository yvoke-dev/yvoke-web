package de.palsoftware.yvoke.rag.web.admin;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import org.springframework.test.web.servlet.MockMvc;

import org.springframework.mock.web.MockMultipartFile;

import de.palsoftware.yvoke.rag.prompt.SystemPromptType;

import de.palsoftware.yvoke.rag.prompt.SystemPrompt;

import java.time.Instant;

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
import static org.mockito.ArgumentMatchers.isNull;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
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
        SecurityContextHolder.getContext()
            .setAuthentication(new TestingAuthenticationToken("admin", "pass", "ROLE_ADMIN"));

        hybridSearch = mock(HybridSearch.class);
        retrievalLogRepository = mock(RetrievalLogRepository.class);
        ragAdminViewService = mock(RagAdminViewService.class);
        collectionService = mock(CollectionService.class);
        playbookService = mock(PlaybookService.class);
        systemPromptService = mock(SystemPromptService.class);
        ragService = mock(RagService.class);
        objectMapper = new ObjectMapper();

        telemetryService = mock(RetrievalTelemetryService.class);

        controller =
            new RagAdminController(hybridSearch, retrievalLogRepository, ragAdminViewService,
                collectionService, playbookService, systemPromptService, ragService, objectMapper,
                telemetryService, 100, MAX_LIMIT, TestAreas.withAreas("OIM", "Finance"));

        model = mock(Model.class);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
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
    void testSearch_whenCollectionsEmpty_activeCollectionIsNullAndSearchNotExecuted() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
            "areaAdmin", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_FINANCE"));
        when(collectionService.listForAdminUser()).thenReturn(Collections.emptyList());

        String view = controller.testSearch("secret query", null, null, 8, true, true, model);

        assertThat(view).isEqualTo("admin/search");
        verify(model).addAttribute("collection", null);
        verify(hybridSearch, times(0)).searchWithId(any(), any());
    }

    @Test
    void testSearch_crossAreaCollectionSpecified_throwsNotFound() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
            "areaAdmin", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_FINANCE"));
        Collection oimCol = new Collection(UUID.randomUUID(), "OimCol", "Desc", List.of(),
            OffsetDateTime.now(), "OIM");
        when(collectionService.getCollection("OimCol")).thenReturn(Optional.of(oimCol));

        assertThatThrownBy(() -> controller.testSearch(null, "OimCol", null, 8, true, true, model))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void testSearch_nonExistentCollectionSpecifiedWhenCollectionsEmpty_setsCollectionNull() {
        when(collectionService.listCollections()).thenReturn(Collections.emptyList());
        when(collectionService.getCollection("NonExistent")).thenReturn(Optional.empty());

        controller.testSearch("query", "NonExistent", null, 8, true, true, model);

        verify(model).addAttribute("collection", null);
        verify(hybridSearch, times(0)).searchWithId(any(), any());
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
        when(systemPromptService.getPrompt("test-prompt"))
            .thenReturn(Optional.of(new SystemPrompt("test-prompt", SystemPromptType.CHAT, "Hello",
                "desc", Instant.now(), Instant.now(), false, "OIM")));
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
            anyBoolean(), any(), anyBoolean(), eq("PingID"), isNull(), eq(1));

        mvc.perform(post("/admin/prompts").param("name", "p").param("type", "CHAT")
            .param("systemPrompt", "text").param("area", "PingID"))
            .andExpect(status().is3xxRedirection());
        verify(systemPromptService).savePrompt(eq("p"), eq(SystemPromptType.CHAT), eq("text"),
            any(), eq("PingID"));
    }

    @Test
    void thePostedSystemPromptReachesTheSavedPlaybook() throws Exception {
        when(systemPromptService.getPrompt("custom-sys-prompt"))
            .thenReturn(Optional.of(new SystemPrompt("custom-sys-prompt", SystemPromptType.CHAT,
                "Hello", "desc", Instant.now(), Instant.now(), false, "OIM")));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/admin/playbooks").param("name", "pb").param("title", "PB")
            .param("templateText", "text").param("area", "OIM")
            .param("systemPrompt", "custom-sys-prompt").param("version", "3"))
            .andExpect(status().is3xxRedirection());
        verify(playbookService).savePlaybook(eq("pb"), eq("PB"), any(), eq("text"), any(),
            anyBoolean(), any(), anyBoolean(), eq("OIM"), eq("custom-sys-prompt"), eq(3));
    }

    @Test
    void viewPlaybooksAddsChatSystemPromptsToModel() {
        SystemPrompt prompt =
            new SystemPrompt("custom-prompt", SystemPromptType.CHAT, "body", "desc");
        when(systemPromptService.listPromptsByType(SystemPromptType.CHAT))
            .thenReturn(List.of(prompt));

        controller.viewPlaybooks(model);

        verify(model).addAttribute("systemPrompts", List.of(prompt));
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
        when(systemPromptService.getPrompt(unsafeName))
            .thenReturn(Optional.of(new SystemPrompt(unsafeName, SystemPromptType.CHAT, "Hello",
                "desc", Instant.now(), Instant.now(), false, "OIM")));
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
        when(playbookService.getPlaybook(unsafeName))
            .thenReturn(Optional.of(new Playbook(unsafeName, "title", "desc", "text", List.of(),
                false, "specialist", false, Instant.now(), Instant.now(), false, "OIM")));
        when(playbookService.exportPlaybookToMarkdown(unsafeName))
            .thenReturn("---\nname: malicious\n---\nHello");

        var response = controller.exportPlaybook(unsafeName);

        String disposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(disposition).doesNotContain("filename=\"malicious\"; dummy=\"");
        assertThat(disposition).isEqualTo(ContentDisposition.attachment()
            .filename(unsafeName + ".md", StandardCharsets.UTF_8).build().toString());
    }

    @Test
    void viewPlaybooks_filtersPlaybooksPromptsAreasToAdministeredArea() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
            "areaAdmin", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_FINANCE"));

        Playbook finPb = new Playbook("fin-pb", "Finance PB", "desc", "template", List.of(), false,
            "specialist", false, Instant.now(), Instant.now(), false, "Finance");
        Playbook oimPb = new Playbook("oim-pb", "OIM PB", "desc", "template", List.of(), false,
            "specialist", false, Instant.now(), Instant.now(), false, "OIM");
        when(playbookService.listAllPlaybooks()).thenReturn(List.of(finPb, oimPb));

        SystemPrompt finPrompt = new SystemPrompt("fin-prompt", SystemPromptType.CHAT, "body",
            "desc", Instant.now(), Instant.now(), false, "Finance");
        SystemPrompt oimPrompt = new SystemPrompt("oim-prompt", SystemPromptType.CHAT, "body",
            "desc", Instant.now(), Instant.now(), false, "OIM");
        when(systemPromptService.listPromptsByType(SystemPromptType.CHAT))
            .thenReturn(List.of(finPrompt, oimPrompt));

        String view = controller.viewPlaybooks(model);

        assertThat(view).isEqualTo("admin/playbooks");
        verify(model).addAttribute("playbooks", List.of(finPb));
        verify(model).addAttribute("areas", List.of("Finance"));
        verify(model).addAttribute("systemPrompts", List.of(finPrompt));
    }

    @Test
    void createPlaybook_unauthorizedArea_forbidden() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
            "areaAdmin", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_FINANCE"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/admin/playbooks").param("name", "pb").param("title", "PB")
            .param("templateText", "text").param("area", "OIM")).andExpect(status().isForbidden());
    }

    @Test
    void createPlaybook_crossAreaNameHijack_forbidden() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
            "areaAdmin", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_FINANCE"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        when(playbookService.getPlaybook("existing-oim-pb")).thenReturn(
            Optional.of(new Playbook("existing-oim-pb", "Title", "desc", "text", List.of(), false,
                "specialist", false, Instant.now(), Instant.now(), false, "OIM")));

        mvc.perform(post("/admin/playbooks").param("name", "existing-oim-pb").param("title", "PB")
            .param("templateText", "text").param("area", "Finance"))
            .andExpect(status().isForbidden());
    }

    @Test
    void createPlaybook_changeArea_redirectsWithError() throws Exception {
        SecurityContextHolder.getContext()
            .setAuthentication(new TestingAuthenticationToken("admin", "pass", "ROLE_ADMIN"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        when(playbookService.getPlaybook("pb"))
            .thenReturn(Optional.of(new Playbook("pb", "Title", "desc", "text", List.of(), false,
                "specialist", false, Instant.now(), Instant.now(), false, "OIM")));

        mvc.perform(post("/admin/playbooks").param("name", "pb").param("title", "PB")
            .param("templateText", "text").param("area", "Finance"))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/playbooks"));
    }

    @Test
    void createPlaybook_systemPromptFromDifferentArea_redirectsWithError() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
            "areaAdmin", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_FINANCE"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        when(systemPromptService.getPrompt("oim-sys-prompt"))
            .thenReturn(Optional.of(new SystemPrompt("oim-sys-prompt", SystemPromptType.CHAT,
                "Hello", "desc", Instant.now(), Instant.now(), false, "OIM")));

        mvc.perform(post("/admin/playbooks").param("name", "pb").param("title", "PB")
            .param("templateText", "text").param("area", "Finance")
            .param("systemPrompt", "oim-sys-prompt")).andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/admin/playbooks"));
    }

    @Test
    void exportPlaybook_unauthorizedArea_notFound() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
            "areaAdmin", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_FINANCE"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        when(playbookService.getPlaybook("oim-pb"))
            .thenReturn(Optional.of(new Playbook("oim-pb", "Title", "desc", "text", List.of(),
                false, "specialist", false, Instant.now(), Instant.now(), false, "OIM")));

        mvc.perform(get("/admin/playbooks/export").param("name", "oim-pb"))
            .andExpect(status().isNotFound());
    }

    @Test
    void deletePlaybook_unauthorizedArea_notFound() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
            "areaAdmin", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_FINANCE"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        when(playbookService.getPlaybook("oim-pb"))
            .thenReturn(Optional.of(new Playbook("oim-pb", "Title", "desc", "text", List.of(),
                false, "specialist", false, Instant.now(), Instant.now(), false, "OIM")));

        mvc.perform(post("/admin/playbooks/delete").param("name", "oim-pb"))
            .andExpect(status().isNotFound());
    }

    @Test
    void importPlaybook_unauthorizedArea_forbidden() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
            "areaAdmin", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_FINANCE"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        String md = "---\nname: imported-pb\narea: OIM\n---\nBody";
        MockMultipartFile file = new MockMultipartFile("file", "test.md", "text/markdown",
            md.getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/admin/playbooks/import").file(file).param("area", "Finance"))
            .andExpect(status().isForbidden());
    }

    @Test
    void importPlaybook_crossAreaNameHijack_forbidden() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
            "areaAdmin", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_FINANCE"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        when(playbookService.getPlaybook("existing-oim-pb")).thenReturn(
            Optional.of(new Playbook("existing-oim-pb", "Title", "desc", "text", List.of(), false,
                "specialist", false, Instant.now(), Instant.now(), false, "OIM")));

        String md = "---\nname: existing-oim-pb\narea: Finance\n---\nBody";
        MockMultipartFile file = new MockMultipartFile("file", "test.md", "text/markdown",
            md.getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/admin/playbooks/import").file(file).param("area", "Finance"))
            .andExpect(status().isForbidden());
    }

    @Test
    void viewPrompts_filtersPromptsToAdministeredArea() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
            "areaAdmin", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_FINANCE"));

        SystemPrompt finPrompt = new SystemPrompt("fin-prompt", SystemPromptType.CHAT, "body",
            "desc", Instant.now(), Instant.now(), false, "Finance");
        SystemPrompt oimPrompt = new SystemPrompt("oim-prompt", SystemPromptType.CHAT, "body",
            "desc", Instant.now(), Instant.now(), false, "OIM");
        when(systemPromptService.listAllPrompts()).thenReturn(List.of(finPrompt, oimPrompt));
        when(systemPromptService.listPromptsByType(SystemPromptType.CHAT))
            .thenReturn(List.of(finPrompt, oimPrompt));

        String view = controller.viewPrompts(model);

        assertThat(view).isEqualTo("admin/prompts");
        verify(model).addAttribute("prompts", List.of(finPrompt));
        verify(model).addAttribute("chatPrompts", List.of(finPrompt));
        verify(model).addAttribute("areas", List.of("Finance"));
    }

    @Test
    void getKgPromptOptions_filtersToAdministeredArea() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
            "areaAdmin", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_FINANCE"));

        SystemPrompt finKg = new SystemPrompt("fin-kg", SystemPromptType.KG, "body", "Fin KG",
            Instant.now(), Instant.now(), false, "Finance");
        SystemPrompt oimKg = new SystemPrompt("oim-kg", SystemPromptType.KG, "body", "OIM KG",
            Instant.now(), Instant.now(), false, "OIM");
        when(systemPromptService.listPromptsByType(SystemPromptType.KG))
            .thenReturn(List.of(finKg, oimKg));

        String html = controller.getKgPromptOptions();

        assertThat(html).contains("fin-kg");
        assertThat(html).doesNotContain("oim-kg");
    }

    @Test
    void createPrompt_unauthorizedArea_forbidden() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
            "areaAdmin", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_FINANCE"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/admin/prompts").param("name", "new-p").param("type", "CHAT")
            .param("systemPrompt", "text").param("area", "OIM")).andExpect(status().isForbidden());
    }

    @Test
    void createPrompt_crossAreaKeyHijack_forbidden() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
            "areaAdmin", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_FINANCE"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        when(systemPromptService.getPrompt("existing-oim-p"))
            .thenReturn(Optional.of(new SystemPrompt("existing-oim-p", SystemPromptType.CHAT,
                "text", "desc", Instant.now(), Instant.now(), false, "OIM")));

        mvc.perform(post("/admin/prompts").param("name", "existing-oim-p").param("type", "CHAT")
            .param("systemPrompt", "text").param("area", "Finance"))
            .andExpect(status().isForbidden());
    }

    @Test
    void createPrompt_changeArea_redirectsWithError() throws Exception {
        SecurityContextHolder.getContext()
            .setAuthentication(new TestingAuthenticationToken("admin", "pass", "ROLE_ADMIN"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        when(systemPromptService.getPrompt("p")).thenReturn(Optional.of(new SystemPrompt("p",
            SystemPromptType.CHAT, "text", "desc", Instant.now(), Instant.now(), false, "OIM")));

        mvc.perform(post("/admin/prompts").param("name", "p").param("type", "CHAT")
            .param("systemPrompt", "text").param("area", "Finance"))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/prompts"));
    }

    @Test
    void exportPrompt_unauthorizedArea_notFound() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
            "areaAdmin", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_FINANCE"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        when(systemPromptService.getPrompt("oim-p"))
            .thenReturn(Optional.of(new SystemPrompt("oim-p", SystemPromptType.CHAT, "text", "desc",
                Instant.now(), Instant.now(), false, "OIM")));

        mvc.perform(get("/admin/prompts/export").param("name", "oim-p"))
            .andExpect(status().isNotFound());
    }

    @Test
    void deletePrompt_unauthorizedArea_notFound() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
            "areaAdmin", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_FINANCE"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        when(systemPromptService.getPrompt("oim-p"))
            .thenReturn(Optional.of(new SystemPrompt("oim-p", SystemPromptType.CHAT, "text", "desc",
                Instant.now(), Instant.now(), false, "OIM")));

        mvc.perform(post("/admin/prompts/delete").param("name", "oim-p"))
            .andExpect(status().isNotFound());
    }

    @Test
    void importPrompt_unauthorizedArea_forbidden() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
            "areaAdmin", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_FINANCE"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        String md = "---\nname: imported-p\narea: OIM\n---\nBody";
        MockMultipartFile file = new MockMultipartFile("file", "test.md", "text/markdown",
            md.getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/admin/prompts/import").file(file).param("area", "Finance"))
            .andExpect(status().isForbidden());
    }

    @Test
    void importPrompt_crossAreaKeyHijack_forbidden() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
            "areaAdmin", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_FINANCE"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        when(systemPromptService.getPrompt("existing-oim-p"))
            .thenReturn(Optional.of(new SystemPrompt("existing-oim-p", SystemPromptType.CHAT,
                "text", "desc", Instant.now(), Instant.now(), false, "OIM")));

        String md = "---\nname: existing-oim-p\narea: Finance\n---\nBody";
        MockMultipartFile file = new MockMultipartFile("file", "test.md", "text/markdown",
            md.getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/admin/prompts/import").file(file).param("area", "Finance"))
            .andExpect(status().isForbidden());
    }

    @Test
    void updateActiveChatPrompt_areaAdmin_forbidden() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
            "areaAdmin", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_FINANCE"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/admin/prompts/active").param("activeChatPrompt", "custom-prompt"))
            .andExpect(status().isForbidden());

        verify(systemPromptService, times(0)).setDefaultChatPromptName(any());
    }

    @Test
    void updateActiveChatPrompt_superAdmin_success() throws Exception {
        SecurityContextHolder.getContext()
            .setAuthentication(new TestingAuthenticationToken("admin", "pass", "ROLE_ADMIN"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/admin/prompts/active").param("activeChatPrompt", "custom-prompt"))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/prompts"));

        verify(systemPromptService).setDefaultChatPromptName("custom-prompt");
    }
}
