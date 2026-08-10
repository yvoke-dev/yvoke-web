package de.palsoftware.yvoke.chat.web.admin;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;

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
import java.util.List;
import java.time.LocalDate;
import org.hamcrest.Matchers;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

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
     * S15.31: the preset is what the date range is actually built from, and the range is what every
     * figure on the cost page is computed over.
     *
     * <p>
     * The sibling {@code everyTimePresetResolvesADateRange} walks all four spellings but asserts
     * only HTTP 200, and no test anywhere reads the resolved {@code startDate} / {@code endDate}
     * model attributes — so a broken boundary is invisible: the page renders perfectly and shows
     * the wrong month's spend. Three distinct regressions hide behind that 200. A {@code this_month
     * } / {@code last_month} boundary off by a month (or by a day, on the
     * {@code lengthOfMonth()} end) silently reports someone else's period as this one's. Losing the
     * {@code all} branch's {@code {null, null}} turns an intended full-history query into a bounded
     * one — or, in the other direction, sending nulls where explicit dates were meant to apply
     * unbounds a scan of {@code llm_call_logs} against the 60s statement timeout. And losing the
     * case-insensitive match makes the preset fall through to the explicit-date branch, which
     * quietly honours whatever stale {@code startDate}/{@code endDate} the form still carried.
     *
     * <p>
     * Explicit dates are therefore supplied on EVERY request below — deliberately far in the past,
     * so a preset that stopped resolving cannot accidentally land on the right answer, and so the
     * fall-through branch is proved to still work for {@code custom}.
     */
    @Test
    public void eachTimePresetResolvesTheDateRangeItNamesAndAllMeansUnbounded() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate thisMonthStart = today.withDayOfMonth(1);
        LocalDate thisMonthEnd = today.withDayOfMonth(today.lengthOfMonth());
        LocalDate lastMonth = today.minusMonths(1);
        LocalDate lastMonthStart = lastMonth.withDayOfMonth(1);
        LocalDate lastMonthEnd = lastMonth.withDayOfMonth(lastMonth.lengthOfMonth());

        // Mixed case on purpose: the resolver matches with equalsIgnoreCase, and the fall-through
        // it would otherwise hit silently applies the explicit dates instead.
        mockMvc
            .perform(get("/admin/costs/fragment/explorer").param("timePreset", "THIS_MONTH")
                .param("startDate", "2019-01-01").param("endDate", "2019-01-02")
                .header("HX-Request", "true").with(admin()))
            .andExpect(status().isOk())
            .andExpect(model().attribute("startDate", thisMonthStart))
            .andExpect(model().attribute("endDate", thisMonthEnd));

        mockMvc
            .perform(get("/admin/costs/fragment/explorer").param("timePreset", "Last_Month")
                .param("startDate", "2019-01-01").param("endDate", "2019-01-02")
                .header("HX-Request", "true").with(admin()))
            .andExpect(status().isOk())
            .andExpect(model().attribute("startDate", lastMonthStart))
            .andExpect(model().attribute("endDate", lastMonthEnd));

        // 'all' means BOTH bounds null — an explicit date must not survive it.
        mockMvc
            .perform(get("/admin/costs/fragment/explorer").param("timePreset", "ALL")
                .param("startDate", "2019-01-01").param("endDate", "2019-01-02")
                .header("HX-Request", "true").with(admin()))
            .andExpect(status().isOk())
            .andExpect(model().attribute("startDate", Matchers.nullValue()))
            .andExpect(model().attribute("endDate", Matchers.nullValue()));

        // Anything else keeps the explicit dates — this is the branch the presets must NOT reach.
        mockMvc
            .perform(get("/admin/costs/fragment/explorer").param("timePreset", "custom")
                .param("startDate", "2019-01-01").param("endDate", "2019-01-02")
                .header("HX-Request", "true").with(admin()))
            .andExpect(status().isOk())
            .andExpect(model().attribute("startDate", LocalDate.parse("2019-01-01")))
            .andExpect(model().attribute("endDate", LocalDate.parse("2019-01-02")));
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

    /**
     * {@code getCostMonitoringPage} and {@code getExplorerFragment} carry the same two blanking
     * rules — clear {@code selectedSources} unless the view is RAW/CALL, clear
     * {@code selectedPlaybooks} unless it is MESSAGE — as two verbatim copies with nothing tying
     * them together. They exist because the template <em>disables</em> those selects for the wrong
     * view level ({@code th:disabled="${viewLevel != 'RAW'}"}) but a disabled select still carries
     * whatever the browser last submitted in the URL, and htmx replays the whole query string on
     * every control change. Drop the blanking from one handler and a source filter chosen while on
     * the RAW tab keeps narrowing the query after the admin switches to CONVERSATION, while the
     * dropdown that would explain it renders greyed out and unselected: the numbers shrink with no
     * visible cause, which on a spend dashboard is read as "we spent less", not "a filter is stuck".
     * Because the page only ever renders its unfiltered initial state in practice, the fragment is
     * where this actually bites — and it is the copy that had no test at all. Asserting on both
     * handlers is the point: they must not drift.
     */
    @Test
    public void filterBlankingRulesApplyOnBothThePageAndTheFragment() throws Exception {
        for (String url : new String[] {"/admin/costs", "/admin/costs/fragment/explorer"}) {
            // CONVERSATION: neither filter applies, so neither may survive into the query.
            mockMvc
                .perform(get(url).param("viewLevel", "CONVERSATION")
                    .param("selectedSources", "specialist").param("selectedPlaybooks", "oim-ask")
                    .header("HX-Request", "true").with(admin()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("selectedSources", List.of()))
                .andExpect(model().attribute("selectedPlaybooks", List.of()));

            // RAW is the one view whose source filter is real; playbooks still must not leak.
            mockMvc
                .perform(get(url).param("viewLevel", "RAW").param("selectedSources", "specialist")
                    .param("selectedPlaybooks", "oim-ask").header("HX-Request", "true")
                    .with(admin()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("selectedSources", List.of("specialist")))
                .andExpect(model().attribute("selectedPlaybooks", List.of()));

            // MESSAGE is the mirror image: playbooks survive, sources do not.
            mockMvc
                .perform(get(url).param("viewLevel", "MESSAGE")
                    .param("selectedSources", "specialist").param("selectedPlaybooks", "oim-ask")
                    .header("HX-Request", "true").with(admin()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("selectedSources", List.of()))
                .andExpect(model().attribute("selectedPlaybooks", List.of("oim-ask")));
        }
    }

    @Test
    public void theExplorerFragmentRequiresAdmin() throws Exception {
        mockMvc.perform(get("/admin/costs/fragment/explorer").with(plainUser()))
            .andExpect(status().isForbidden());
    }

    /**
     * These three endpoints write the prices every figure on the cost dashboard is derived from,
     * and none of them carries a method-level guard — deliberately, because
     * {@code @PreAuthorize}/{@code @Secured} are silently inert in this application (there is no
     * {@code @EnableMethodSecurity} anywhere, and {@code ArchitectureTest} fails the build on
     * them). Their entire authorization is the single
     * {@code .requestMatchers("/admin/**").hasRole("ADMIN")} line in {@code SecurityConfig}'s
     * browser chain.
     *
     * <p>
     * That leaves the whole pricing surface one ordering mistake away from being open. Spring
     * Security applies the FIRST matching rule, so a {@code permitAll()} matcher added above it for
     * some sibling path — a fragment someone wants to embed, an export someone wants to link —
     * takes every method underneath it with it, and there is nothing at the controller to catch
     * that. It fails open rather than closed: a plain {@code ROLE_USER} could rewrite the
     * {@code model_pricing} rows, and because {@code CostCalculationService} re-prices every
     * dashboard view from the current rates rather than reading the persisted
     * {@code llm_call_logs.total_cost}, the historical spend reported for every user and every
     * conversation changes with them. Nothing records who set a price, so the corruption is silent
     * and not reversible from inside the app.
     *
     * <p>
     * Both {@code /admin/pricing} endpoints are asserted, not just the save: they call different
     * service methods ({@code updateModelPricing}/{@code deleteModelPricing}) and a rule change
     * scoped to one would otherwise leave the other wide open with this test still green. The CSRF
     * token is supplied on purpose: without it the response would be 403 for the wrong reason, and
     * the assertion would pass even with the authorization rule deleted outright.
     *
     * <p>
     * A third endpoint, {@code POST /admin/costs/pricing/save}, used to be asserted here. It was a
     * second, duplicate money-writing route on {@code CostMonitoringAdminController} that wrote the
     * price and then rendered a fragment it never populated — so it persisted the change and
     * answered 500. Nothing linked to it; it has been deleted, and {@code /admin/pricing} is now
     * the only way in.
     */
    @Test
    public void theMoneyWritingPricingEndpointsRejectAPlainUser() throws Exception {
        mockMvc
            .perform(post("/admin/pricing/save").with(csrf()).with(plainUser())
                .param("modelName", "gemini-3.6-flash").param("prompt", "0.10")
                .param("completion", "0.40").param("cached", "0.01").param("thought", "0.40"))
            .andExpect(status().isForbidden());

        mockMvc
            .perform(post("/admin/pricing/delete").with(csrf()).with(plainUser())
                .param("modelName", "gemini-3.6-flash"))
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
            .andExpect(MockMvcResultMatchers.content()
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
