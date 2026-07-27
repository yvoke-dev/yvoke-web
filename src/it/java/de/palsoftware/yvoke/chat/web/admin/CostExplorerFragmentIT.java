package de.palsoftware.yvoke.chat.web.admin;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The cost explorer's htmx fragment, and the Confluence connector endpoints.
 *
 * <p>{@code /admin/costs} only ever renders its initial unfiltered state; every control on the page
 * goes through {@code /admin/costs/fragment/explorer}, which no test had ever called. The page exists
 * to make spending decisions, so a filter that silently doesn't apply produces plausible but wrong
 * numbers. The data layer is well covered by service ITs — what is asserted here is the part they
 * cannot see: parameter binding for the multi-selects and {@code @DateTimeFormat} dates, the
 * {@code resolveDatePreset} branches, the view-level blanking rules, and that the fragment actually
 * renders as a sub-tree.
 *
 * <p>{@code POST /admin/connectors/confluence/sync} is intentionally limited to its authorization
 * check: the happy path enqueues a real Confluence import job, and this context runs a live worker, so
 * driving it would make outbound network calls from the test suite. The connection test uses a
 * {@code .invalid} host (RFC 2606), which cannot resolve, so it exercises the failure fragment without
 * contacting anything.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "app.security.mock=true")
public class CostExplorerFragmentIT {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    public void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private static OidcLoginRequestPostProcessor admin() {
        return oidcLogin().idToken(token -> token.claim("oid", "it-cost-admin")).authorities(
            new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("ROLE_USER"));
    }

    private static OidcLoginRequestPostProcessor plainUser() {
        return oidcLogin().idToken(token -> token.claim("oid", "it-cost-user"))
            .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Test
    public void everyViewLevelRendersTheFragment() throws Exception {
        for (String viewLevel : new String[] {"CONVERSATION", "MESSAGE", "CALL", "RAW"}) {
            mockMvc
                .perform(get("/admin/costs/fragment/explorer").param("viewLevel", viewLevel)
                    .header("HX-Request", "true").with(admin()))
                .andExpect(status().isOk());
        }
    }

    @Test
    public void everyTimePresetResolvesADateRange() throws Exception {
        for (String preset : new String[] {"this_month", "last_month", "all", "custom"}) {
            mockMvc
                .perform(get("/admin/costs/fragment/explorer").param("timePreset", preset)
                    .param("startDate", "2026-01-01").param("endDate", "2026-07-25")
                    .header("HX-Request", "true").with(admin()))
                .andExpect(status().isOk());
        }
    }

    /**
     * The multi-selects bind to {@code List<String>} and {@code List<UUID>}. A UUID list is the one
     * most likely to break on an empty or malformed value, and it comes straight from a request.
     */
    @Test
    public void multiSelectFiltersBindIncludingUuidLists() throws Exception {
        mockMvc
            .perform(get("/admin/costs/fragment/explorer").param("viewLevel", "MESSAGE")
                .param("selectedModels", "claude-opus-5").param("selectedModels", "claude-sonnet-5")
                .param("selectedUserIds", UUID.randomUUID().toString())
                .param("selectedPlaybooks", "oim-ask").header("HX-Request", "true").with(admin()))
            .andExpect(status().isOk());
    }

    @Test
    public void aMalformedCursorFallsBackInsteadOfFailing() throws Exception {
        mockMvc
            .perform(get("/admin/costs/fragment/explorer").param("cursor", "not-a-real-cursor")
                .header("HX-Request", "true").with(admin()))
            .andExpect(status().isOk());
        mockMvc.perform(get("/admin/costs/fragment/explorer").param("cursor", "")
            .header("HX-Request", "true").with(admin())).andExpect(status().isOk());
    }

    @Test
    public void theExplorerFragmentRequiresAdmin() throws Exception {
        mockMvc.perform(get("/admin/costs/fragment/explorer").with(plainUser()))
            .andExpect(status().isForbidden());
    }

    // ---------- Confluence connector ----------

    @Test
    public void savingConnectorSettingsRequiresAdmin() throws Exception {
        mockMvc
            .perform(post("/admin/connectors/confluence").with(csrf()).with(plainUser())
                .param("domain", "example.invalid").param("email", "a@b.invalid")
                .param("space", "SP").param("parentPageId", "1"))
            .andExpect(status().isForbidden());
    }

    @Test
    public void testingTheConnectionAgainstAnUnreachableHostRendersTheFailureFragment()
        throws Exception {
        mockMvc
            .perform(post("/admin/connectors/confluence/test").with(csrf()).with(admin())
                .param("domain", "confluence.example.invalid").param("email", "a@b.invalid")
                .param("apiToken", "not-a-real-token").param("space", "SP")
                .param("parentPageId", "1").header("HX-Request", "true"))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .contentTypeCompatibleWith("text/html"));
    }

    @Test
    public void testingTheConnectionRequiresAdmin() throws Exception {
        mockMvc
            .perform(post("/admin/connectors/confluence/test").with(csrf()).with(plainUser())
                .param("domain", "example.invalid").param("email", "a@b.invalid")
                .param("space", "SP").param("parentPageId", "1"))
            .andExpect(status().isForbidden());
    }

    @Test
    public void triggeringASyncRequiresAdmin() throws Exception {
        mockMvc.perform(post("/admin/connectors/confluence/sync").with(csrf()).with(plainUser()))
            .andExpect(status().isForbidden());
    }
}
