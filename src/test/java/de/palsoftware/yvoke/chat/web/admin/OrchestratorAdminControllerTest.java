package de.palsoftware.yvoke.chat.web.admin;

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
import de.palsoftware.yvoke.rag.prompt.PlaybookService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

public class OrchestratorAdminControllerTest {

    private OrchestratorProfileService profileService;
    private PlaybookService playbookService;
    private ChatConversationService chatConversationService;
    private OrchestratorAdminController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        profileService = mock(OrchestratorProfileService.class);
        playbookService = mock(PlaybookService.class);
        chatConversationService = mock(ChatConversationService.class);

        controller = new OrchestratorAdminController(profileService, playbookService,
            chatConversationService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
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
            .perform(post("/admin/orchestrators").param("name", "TestProfile")
                .param("maxReviewRounds", "3").param("maxSpecialistCalls", "10")
                .param("orchestratorPlaybook", "orch-pb").param("reviewerPlaybook", "rev-pb")
                .param("specialistPlaybooks", "spec-pb1", "spec-pb2"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/admin/orchestrators"));

        verify(profileService).saveProfile(any(OrchestratorProfile.class));
    }

    @Test
    void testExportProfile() throws Exception {
        when(profileService.exportProfileToJson("TestProfile"))
            .thenReturn("{\"name\":\"TestProfile\"}");

        mockMvc.perform(get("/admin/orchestrators/export").param("name", "TestProfile"))
            .andExpect(status().isOk()).andExpect(header().string("Content-Disposition",
                "attachment; filename=\"TestProfile.json\""));

        verify(profileService).exportProfileToJson("TestProfile");
    }

    @Test
    void testImportProfile() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "profile.json", "application/json",
            "{\"name\":\"TestProfile\"}".getBytes());
        OrchestratorProfile profile = new OrchestratorProfile("TestProfile", 2, 8, "orch", "rev",
            List.of(), null, null, null, null, null, null, null, null);
        when(profileService.importProfileFromJson("{\"name\":\"TestProfile\"}"))
            .thenReturn(profile);

        mockMvc.perform(multipart("/admin/orchestrators/import").file(file))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/admin/orchestrators"));

        verify(profileService).importProfileFromJson("{\"name\":\"TestProfile\"}");
    }

    @Test
    void testDeleteProfile() throws Exception {
        mockMvc.perform(post("/admin/orchestrators/delete").param("name", "TestProfile"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/admin/orchestrators"));

        verify(profileService).deleteProfile("TestProfile");
    }
}
