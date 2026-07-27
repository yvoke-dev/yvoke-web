package de.palsoftware.yvoke.kg.web.admin;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.palsoftware.yvoke.kg.core.repository.KgWriteRepository;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Full Thymeleaf render coverage for the KG admin pages after the DTO-at-boundary refactor
 * (Wave 3.3): the overview (scopes), browse/search (entity list with the derived {@code displayTag})
 * and the active-entity panel (relationships + neighborhood + metadata). A missing accessor on a
 * per-view DTO would throw during rendering and surface as a 500.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "app.security.mock=true")
public class KgAdminRenderingIT {

    private static final String COLLECTION = "OIM-KGVIEW-TEST";
    private static final String TAG = "9.3";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private KgWriteRepository kgRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    private static OidcLoginRequestPostProcessor admin() {
        return oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"),
            new SimpleGrantedAuthority("ROLE_USER"));
    }

    @BeforeEach
    public void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        jdbcTemplate.update(
            "INSERT INTO collections (id, name) VALUES (?, ?) ON CONFLICT (name) DO NOTHING",
            UUID.randomUUID(), COLLECTION);

        UUID subjectId = kgRepository.upsertEntity(COLLECTION, TAG, "OAuth Module", "module",
            "Handles OAuth flows.");
        UUID objectId = kgRepository.upsertEntity(COLLECTION, TAG, "OIM", "product", "The product.");
        kgRepository.upsertRelationship(COLLECTION, TAG, "OAuth Module", "depends_on", "OIM",
            subjectId, objectId, "OAuth relies on OIM.");
    }

    @AfterEach
    public void tearDown() {
        kgRepository.deleteTagGraph(COLLECTION, TAG);
        jdbcTemplate.update("DELETE FROM collections WHERE name = ?", COLLECTION);
    }

    @Test
    public void overviewRendersScopeDtos() throws Exception {
        mockMvc.perform(get("/admin/kg").with(admin())).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString(COLLECTION)));
    }

    @Test
    public void browseRendersEntityDtosWithDisplayTag() throws Exception {
        mockMvc.perform(get("/admin/kg/view").param("collection", COLLECTION).param("tag", TAG)
                .with(admin())).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("OAuth Module")));
    }

    @Test
    public void activeEntityPanelRendersRelationshipAndEntityDtos() throws Exception {
        mockMvc.perform(get("/admin/kg/view").param("collection", COLLECTION).param("tag", TAG)
                .param("selectedEntity", "OAuth Module").with(admin())).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Handles OAuth flows.")));
    }
}
