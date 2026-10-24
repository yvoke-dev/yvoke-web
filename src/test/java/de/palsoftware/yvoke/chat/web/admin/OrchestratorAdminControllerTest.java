package de.palsoftware.yvoke.chat.web.admin;

import de.palsoftware.yvoke.area.TestAreas;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.palsoftware.yvoke.chat.core.service.ChatConversationService;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProfile;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProfileService;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProperties;
import de.palsoftware.yvoke.rag.prompt.Playbook;
import de.palsoftware.yvoke.rag.prompt.PlaybookService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

public class OrchestratorAdminControllerTest {

    private OrchestratorProfileService profileService;
    private PlaybookService playbookService;
    private ChatConversationService chatConversationService;
    private OrchestratorProperties properties;
    private OrchestratorAdminController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext()
            .setAuthentication(new TestingAuthenticationToken("admin", "pass", "ROLE_ADMIN"));

        profileService = mock(OrchestratorProfileService.class);
        playbookService = mock(PlaybookService.class);
        chatConversationService = mock(ChatConversationService.class);
        // All-null: the record's own fallbacks, which application.yml is pinned to match.
        properties = new OrchestratorProperties(null, null, null, null);

        when(playbookService.getPlaybook(any())).thenAnswer(inv -> Optional
            .of(new Playbook(inv.getArgument(0), inv.getArgument(0), "", "", List.of(), false,
                "specialist", false, Instant.now(), Instant.now(), false, "OIM")));

        controller = new OrchestratorAdminController(profileService, playbookService,
            chatConversationService, properties, TestAreas.withAreas("OIM", "Finance"));
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /**
     * The review-round limit had FOUR independent definitions and Wave 1 raised only one of them
     * ({@code OrchestratorProperties}, to 3). The other three still said 2, so a profile created
     * through this form without touching the field silently got the old limit — invisible locally
     * only because every existing DB row happens to carry 3.
     *
     * <p>
     * The fix is that this controller now has no number of its own: an absent parameter falls back
     * to the configured value. So this asserts the fallback resolves through
     * {@code OrchestratorProperties}, not that it equals any particular literal — a test naming 3
     * here would just be a fifth copy of the number.
     */
    /** P1-12: the area chosen on the form reaches the saved profile. */
    @Test
    void thePostedAreaReachesTheSavedProfile() throws Exception {
        when(playbookService.getPlaybook(any())).thenAnswer(inv -> Optional
            .of(new Playbook(inv.getArgument(0), inv.getArgument(0), "", "", List.of(), false,
                "specialist", false, Instant.now(), Instant.now(), false, "PingID")));

        mockMvc
            .perform(post("/admin/orchestrators").param("area", "PingID").param("name", "Ping")
                .param("orchestratorPlaybook", "orch-pb").param("reviewerPlaybook", "rev-pb"))
            .andExpect(status().is3xxRedirection());

        ArgumentCaptor<OrchestratorProfile> saved =
            ArgumentCaptor.forClass(OrchestratorProfile.class);
        verify(profileService).saveProfile(saved.capture());
        assertThat(saved.getValue().area()).isEqualTo("PingID");
    }

    @Test
    void aProfileSavedWithoutAReviewRoundLimitGetsTheConfiguredDefaultNotAStaleLiteral()
        throws Exception {
        mockMvc
            .perform(post("/admin/orchestrators").param("area", "OIM").param("name", "TestProfile")
                .param("orchestratorPlaybook", "orch-pb").param("reviewerPlaybook", "rev-pb"))
            .andExpect(status().is3xxRedirection());

        ArgumentCaptor<OrchestratorProfile> saved =
            ArgumentCaptor.forClass(OrchestratorProfile.class);
        verify(profileService).saveProfile(saved.capture());
        assertThat(saved.getValue().maxReviewRounds())
            .as("an omitted limit must resolve through the configured default")
            .isEqualTo(properties.resolvedMaxReviewRounds());
        assertThat(saved.getValue().maxSpecialistCalls())
            .isEqualTo(properties.resolvedMaxSpecialistCalls());
    }

    /**
     * The form and its two JS prefill paths carried the literal {@code 2} as well. They now render
     * from this attribute, so the number lives in {@code application.yml} alone.
     */
    @Test
    void theFormIsToldTheDefaultsSoItNeedNoLiteralsOfItsOwn() throws Exception {
        when(profileService.listAllProfiles()).thenReturn(List.of());
        when(playbookService.listAllPlaybooks()).thenReturn(List.of());
        when(chatConversationService.getAllowedModels()).thenReturn(List.of("m"));

        mockMvc.perform(get("/admin/orchestrators")).andExpect(status().isOk())
            .andExpect(
                model().attribute("defaultMaxReviewRounds", properties.resolvedMaxReviewRounds()))
            .andExpect(model().attribute("defaultMaxSpecialistCalls",
                properties.resolvedMaxSpecialistCalls()));
    }

    @Test
    void testViewOrchestrators() throws Exception {
        when(profileService.listAllProfiles()).thenReturn(List.of());
        when(playbookService.listAllPlaybooks()).thenReturn(List.of());
        when(chatConversationService.getAllowedModels())
            .thenReturn(List.of("gemini-3.1-pro-preview"));

        mockMvc.perform(get("/admin/orchestrators")).andExpect(status().isOk())
            .andExpect(view().name("admin/orchestrators"))
            .andExpect(model().attributeExists("profiles"))
            .andExpect(model().attributeExists("playbooks"))
            .andExpect(model().attributeExists("allowedModels"));
    }

    @Test
    void testCreateOrUpdateProfile() throws Exception {
        mockMvc
            .perform(post("/admin/orchestrators").param("area", "OIM").param("name", "TestProfile")
                .param("maxReviewRounds", "3").param("maxSpecialistCalls", "10")
                .param("orchestratorPlaybook", "orch-pb").param("reviewerPlaybook", "rev-pb")
                .param("specialistPlaybooks", "spec-pb1", "spec-pb2"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/admin/orchestrators"));

        verify(profileService).saveProfile(any(OrchestratorProfile.class));
    }

    /**
     * The prototype checkbox the form posts has to arrive on the saved profile.
     *
     * <p>
     * Spring binds what a handler declares and drops everything else without a warning, so a
     * checkbox with no matching {@code @RequestParam} is a silent no-op: the admin ticks it, the
     * page redirects with "saved successfully", and the profile stays visible to every user. That
     * exact three-way drift between template, controller and consumer already cost this project
     * once ({@code jsonUniqueField}), and nothing but an assertion on the SAVED profile catches it
     * — the redirect is identical either way.
     *
     * <p>
     * An unchecked box posts nothing at all, which is the second half of the contract: absent must
     * mean {@code false}, not a 400.
     */
    @Test
    void thePrototypeCheckboxOnTheFormReachesTheSavedProfile() throws Exception {
        mockMvc.perform(post("/admin/orchestrators").param("area", "OIM").param("name", "Browsing")
            .param("orchestratorPlaybook", "orch-pb").param("reviewerPlaybook", "rev-pb")
            .param("prototype", "true")).andExpect(status().is3xxRedirection());

        ArgumentCaptor<OrchestratorProfile> saved =
            ArgumentCaptor.forClass(OrchestratorProfile.class);
        verify(profileService).saveProfile(saved.capture());
        assertThat(saved.getValue().prototype()).isTrue();
    }

    @Test
    void anUncheckedPrototypeBoxSavesAsNotAPrototype() throws Exception {
        mockMvc
            .perform(post("/admin/orchestrators").param("area", "OIM").param("name", "OIM")
                .param("orchestratorPlaybook", "orch-pb").param("reviewerPlaybook", "rev-pb"))
            .andExpect(status().is3xxRedirection());

        ArgumentCaptor<OrchestratorProfile> saved =
            ArgumentCaptor.forClass(OrchestratorProfile.class);
        verify(profileService).saveProfile(saved.capture());
        assertThat(saved.getValue().prototype()).isFalse();
    }

    @Test
    void testExportProfile() throws Exception {
        when(profileService.getProfile("TestProfile"))
            .thenReturn(Optional.of(new OrchestratorProfile("TestProfile", 2, 8, "orch", "rev",
                List.of(), null, null, null, null, null, null, false, null, null, "OIM")));
        when(profileService.exportProfileToJson("TestProfile"))
            .thenReturn("{\"name\":\"TestProfile\"}");

        mockMvc.perform(get("/admin/orchestrators/export").param("name", "TestProfile"))
            .andExpect(status().isOk()).andExpect(
                header().string(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                    .filename("TestProfile.json", StandardCharsets.UTF_8).build().toString()));

        verify(profileService).exportProfileToJson("TestProfile");
    }

    @Test
    void testExportProfile_escapesFilenameAgainstHeaderInjection() throws Exception {
        String unsafeName = "malicious\"; dummy=\"";
        when(profileService.getProfile(unsafeName))
            .thenReturn(Optional.of(new OrchestratorProfile(unsafeName, 2, 8, "orch", "rev",
                List.of(), null, null, null, null, null, null, false, null, null, "OIM")));
        when(profileService.exportProfileToJson(unsafeName)).thenReturn("{\"name\":\"malicious\"}");

        mockMvc.perform(get("/admin/orchestrators/export").param("name", unsafeName))
            .andExpect(status().isOk()).andExpect(
                header().string(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                    .filename(unsafeName + ".json", StandardCharsets.UTF_8).build().toString()));
    }

    @Test
    void testImportProfile() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "profile.json", "application/json",
            "{\"name\":\"TestProfile\"}".getBytes());
        OrchestratorProfile profile = new OrchestratorProfile("TestProfile", 2, 8, "orch", "rev",
            List.of(), null, null, null, null, null, null, false, null, null, "OIM");
        when(profileService.importProfileFromJson("{\"name\":\"TestProfile\"}", "OIM"))
            .thenReturn(profile);

        mockMvc.perform(multipart("/admin/orchestrators/import").file(file).param("area", "OIM"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/admin/orchestrators"));

        verify(profileService).importProfileFromJson("{\"name\":\"TestProfile\"}", "OIM");
    }

    @Test
    void testDeleteProfile() throws Exception {
        when(profileService.getProfile("TestProfile"))
            .thenReturn(Optional.of(new OrchestratorProfile("TestProfile", 2, 8, "orch", "rev",
                List.of(), null, null, null, null, null, null, false, null, null, "OIM")));

        mockMvc.perform(post("/admin/orchestrators/delete").param("name", "TestProfile"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/admin/orchestrators"));

        verify(profileService).deleteProfile("TestProfile");
    }

    @Test
    void viewOrchestrators_filtersByAdministeredArea() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
            "areaAdmin", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_OIM"));

        OrchestratorProfile oimProfile = new OrchestratorProfile("OimProfile", 2, 8, "orch", "rev",
            List.of(), null, null, null, null, null, null, false, null, null, "OIM");
        OrchestratorProfile finProfile = new OrchestratorProfile("FinProfile", 2, 8, "orch", "rev",
            List.of(), null, null, null, null, null, null, false, null, null, "Finance");
        when(profileService.listAllProfiles()).thenReturn(List.of(oimProfile, finProfile));

        Playbook oimPb = new Playbook("pb1", "pb1", "", "", List.of(), false, "orchestrator", false,
            Instant.now(), Instant.now(), false, "OIM");
        Playbook finPb = new Playbook("pb2", "pb2", "", "", List.of(), false, "orchestrator", false,
            Instant.now(), Instant.now(), false, "Finance");
        when(playbookService.listAllPlaybooks()).thenReturn(List.of(oimPb, finPb));
        when(playbookService.listSpecializedPlaybooks()).thenReturn(List.of());
        when(chatConversationService.getAllowedModels()).thenReturn(List.of("model"));

        mockMvc.perform(get("/admin/orchestrators")).andExpect(status().isOk())
            .andExpect(model().attribute("profiles", List.of(oimProfile)))
            .andExpect(model().attribute("playbooks", List.of(oimPb)))
            .andExpect(model().attribute("areas", List.of("OIM")));
    }

    @Test
    void createProfile_unauthorizedArea_forbidden() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
            "areaAdmin", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_OIM"));

        mockMvc
            .perform(
                post("/admin/orchestrators").param("area", "Finance").param("name", "FinProfile")
                    .param("orchestratorPlaybook", "orch").param("reviewerPlaybook", "rev"))
            .andExpect(status().isForbidden());
    }

    @Test
    void createProfile_crossAreaHijack_forbidden() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
            "areaAdmin", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_OIM"));

        when(profileService.getProfile("FinProfile"))
            .thenReturn(Optional.of(new OrchestratorProfile("FinProfile", 2, 8, "orch", "rev",
                List.of(), null, null, null, null, null, null, false, null, null, "Finance")));

        mockMvc
            .perform(post("/admin/orchestrators").param("area", "OIM").param("name", "FinProfile")
                .param("orchestratorPlaybook", "orch").param("reviewerPlaybook", "rev"))
            .andExpect(status().isForbidden());
    }

    @Test
    void createProfile_changeArea_redirectsWithError() throws Exception {
        when(profileService.getProfile("Existing"))
            .thenReturn(Optional.of(new OrchestratorProfile("Existing", 2, 8, "orch", "rev",
                List.of(), null, null, null, null, null, null, false, null, null, "OIM")));

        mockMvc
            .perform(post("/admin/orchestrators").param("area", "Finance").param("name", "Existing")
                .param("orchestratorPlaybook", "orch").param("reviewerPlaybook", "rev"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/admin/orchestrators"));
    }

    @Test
    void createProfile_playbookFromDifferentArea_redirectsWithError() throws Exception {
        when(playbookService.getPlaybook("orch"))
            .thenReturn(Optional.of(new Playbook("orch", "orch", "", "", List.of(), false,
                "orchestrator", false, Instant.now(), Instant.now(), false, "OIM")));
        when(playbookService.getPlaybook("rev-finance"))
            .thenReturn(Optional.of(new Playbook("rev-finance", "rev-finance", "", "", List.of(),
                false, "reviewer", false, Instant.now(), Instant.now(), false, "Finance")));

        mockMvc
            .perform(post("/admin/orchestrators").param("area", "OIM").param("name", "NewProfile")
                .param("orchestratorPlaybook", "orch").param("reviewerPlaybook", "rev-finance"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/admin/orchestrators"));
    }

    @Test
    void exportProfile_unauthorizedArea_notFound() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
            "areaAdmin", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_OIM"));

        when(profileService.getProfile("FinProfile"))
            .thenReturn(Optional.of(new OrchestratorProfile("FinProfile", 2, 8, "orch", "rev",
                List.of(), null, null, null, null, null, null, false, null, null, "Finance")));

        mockMvc.perform(get("/admin/orchestrators/export").param("name", "FinProfile"))
            .andExpect(status().isNotFound());
    }

    @Test
    void deleteProfile_unauthorizedArea_notFound() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
            "areaAdmin", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_OIM"));

        when(profileService.getProfile("FinProfile"))
            .thenReturn(Optional.of(new OrchestratorProfile("FinProfile", 2, 8, "orch", "rev",
                List.of(), null, null, null, null, null, null, false, null, null, "Finance")));

        mockMvc.perform(post("/admin/orchestrators/delete").param("name", "FinProfile"))
            .andExpect(status().isNotFound());
    }
}

