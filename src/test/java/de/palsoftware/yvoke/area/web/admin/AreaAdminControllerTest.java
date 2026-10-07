package de.palsoftware.yvoke.area.web.admin;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.palsoftware.yvoke.area.TestAreas;
import de.palsoftware.yvoke.area.core.AreaMembers;
import de.palsoftware.yvoke.area.core.AreaService;
import de.palsoftware.yvoke.area.core.AreaWithMembers;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The Areas admin page. Every field goes through MockMvc with its real parameter name: a form field
 * the controller never binds is a silent no-op.
 */
class AreaAdminControllerTest {

    private AreaService areaService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        areaService = mock(AreaService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AreaAdminController(areaService)).build();
    }

    @Test
    void thePageListsEveryAreaWithItsMembers() throws Exception {
        List<AreaWithMembers> areas =
            List.of(new AreaWithMembers(TestAreas.area("OIM"), AreaMembers.NONE));
        when(areaService.listAreasWithMembers()).thenReturn(areas);

        mvc.perform(get("/admin/areas")).andExpect(view().name("admin/areas"))
            .andExpect(model().attribute("areas", areas));
    }

    @Test
    void everyPostedFieldReachesTheSave() throws Exception {
        mvc.perform(post("/admin/areas").param("originalName", "OIM").param("name", "Oracle IAM")
            .param("title", "Oracle Identity").param("description", "Docs")
            .param("prototype", "true").param("defaultSystemPrompt", "oim-chat")
            .param("defaultPlaybook", "oim-full").param("defaultProfile", "OIM"))
            .andExpect(redirectedUrl("/admin/areas")).andExpect(flash().attributeExists("success"));

        verify(areaService).saveArea("OIM", "Oracle IAM", "Oracle Identity", "Docs", true,
            "oim-chat", "oim-full", "OIM");
    }

    @Test
    void aNewAreaNeedsOnlyItsName() throws Exception {
        mvc.perform(post("/admin/areas").param("name", "PingID"))
            .andExpect(redirectedUrl("/admin/areas"));

        verify(areaService).saveArea(null, "PingID", null, null, false, null, null, null);
    }

    @Test
    void deletePassesTheName() throws Exception {
        mvc.perform(post("/admin/areas/delete").param("name", "PingID"))
            .andExpect(redirectedUrl("/admin/areas")).andExpect(flash().attributeExists("success"));

        verify(areaService).deleteArea("PingID");
    }

    /** The message names the area as stored, not as typed. */
    @Test
    void theDeleteMessageNamesTheStoredArea() throws Exception {
        when(areaService.deleteArea(" pingid ")).thenReturn("PingID");

        mvc.perform(post("/admin/areas/delete").param("name", " pingid "))
            .andExpect(flash().attribute("success", "Area 'PingID' deleted."));
    }
}
