package de.palsoftware.yvoke.ingest.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.palsoftware.yvoke.ingest.core.confluence.ConfluenceInstance;
import de.palsoftware.yvoke.ingest.core.confluence.ConfluenceInstanceRepository;
import de.palsoftware.yvoke.ingest.core.confluence.TokenHealth;
import de.palsoftware.yvoke.shared.security.SecretCipher;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * The connectors admin page as a BROWSER drives it: the form post goes through {@code WebDataBinder}
 * and the instance list is rendered with rows in it.
 *
 * <p>
 * Both of those were the coverage gap that let two page-breaking defects ship.
 * {@code ConfluenceConnectorControllerTest} calls the controller method directly with a
 * hand-constructed {@code BindingResult}, so no request ever reached the binder — and an unchecked
 * checkbox submits no parameter at all, which a primitive {@code boolean} record component cannot be
 * constructed from, so the very first "Save Instance" failed 100% of the time. Meanwhile
 * {@code AllPagesRenderSmokeIT} and the browser e2e all render {@code /admin/connectors} against an
 * EMPTY database, leaving the entire {@code th:each} row branch — the token-health badges, the 13
 * {@code data-*} attributes the Edit button copies into the form, the delete form, the target/label
 * summary grid, the per-row htmx expressions — unexecuted, so a typo in any of them shipped green.
 *
 * <p>
 * The class is {@code @Transactional}: this context runs a LIVE job worker which polls
 * {@code ingestion_jobs} for queued work every two seconds. Everything written here — instances,
 * the collection, and above all the queued jobs the delete path cancels and the crawl sync-all
 * enqueues — stays inside the test's uncommitted transaction, so the worker's separate connection
 * can never see it, let alone execute it or make a Confluence request for it.
 *
 * <p>
 * The context signature is deliberately identical to {@code CostExplorerFragmentIT} and the other
 * admin write ITs, so this class REUSES their cached TestContext instead of minting a new one (a
 * distinct properties/mock-bean combination tips the cache over and poisons the RANDOM_PORT MCP
 * context).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "app.security.mock=true")
@Transactional
public class ConnectorsAdminWriteIT {

    private static final String COLLECTION = "IT-CONNECTORS-WRITE";
    private static final String TAG = "10.0";
    private static final String OTHER_TAG = "9.3.1";
    private static final String SLUG = "it-connectors";
    private static final String NAME = "IT Connectors Wiki";
    private static final String CONNECTORS_URL = "http://localhost:8080/admin/connectors";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ConfluenceInstanceRepository instanceRepository;

    @Autowired
    private SecretCipher secretCipher;

    private MockMvc mockMvc;

    @BeforeEach
    public void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbcTemplate.update("INSERT INTO collections (id, name, description, tags) "
            + "VALUES (?, ?, ?, string_to_array(?, ','))", UUID.randomUUID(), COLLECTION,
            "connector write IT", OTHER_TAG + "," + TAG);
    }

    private static OidcLoginRequestPostProcessor admin() {
        return oidcLogin().idToken(token -> token.claim("oid", "it-connectors-admin")).authorities(
            new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("ROLE_USER"));
    }

    private static OidcLoginRequestPostProcessor plainUser() {
        return oidcLogin().idToken(token -> token.claim("oid", "it-connectors-user"))
            .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    /**
     * Exactly the parameters the rendered form submits — including Spring's two hidden checkbox
     * field markers, and NOT including {@code processAttachments}, because that box is unchecked
     * when the form opens. The {@code Accept}/{@code Referer} headers make it a classic navigational
     * form post, which is what {@code MvcExceptionHandler} keys its redirect-with-flash on.
     */
    private static Map<String, String> formFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("id", "");
        fields.put("name", NAME);
        fields.put("slug", SLUG);
        fields.put("domain", "https://it-connectors.atlassian.net");
        fields.put("email", "svc@example.com");
        fields.put("apiToken", "it-token");
        fields.put("space", "DOCS");
        fields.put("rootPageId", "12345");
        fields.put("includeLabels", "kb-public");
        fields.put("excludeLabels", "draft");
        fields.put("targetCollection", COLLECTION);
        fields.put("targetTag", TAG);
        fields.put("_processAttachments", "on");
        fields.put("_enabled", "on");
        fields.put("enabled", "true");
        return fields;
    }

    private MockHttpServletRequestBuilder browserPost(String url, Map<String, String> fields) {
        MockHttpServletRequestBuilder request = post(url).with(csrf()).with(admin())
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Referer", CONNECTORS_URL);
        fields.forEach(request::param);
        return request;
    }

    private MockHttpServletRequestBuilder browserSave(Map<String, String> fields) {
        return browserPost("/admin/connectors/confluence", fields);
    }

    private void saveDefaultInstance() throws Exception {
        mockMvc.perform(browserSave(formFields())).andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("success", Matchers.notNullValue()));
    }

    private ConfluenceInstance saved() {
        return instanceRepository.findBySlug(SLUG).orElseThrow();
    }

    private UUID collectionId() {
        return jdbcTemplate.queryForObject("SELECT id FROM collections WHERE name = ?", UUID.class,
            COLLECTION);
    }

    // ---------------------------------------------------------------------
    // Save — the path that never bound a real request before
    // ---------------------------------------------------------------------

    @Test
    public void savingWithTheAttachmentsCheckboxUncheckedPersistsTheInstance() throws Exception {
        mockMvc.perform(browserSave(formFields())).andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/admin/connectors"))
            .andExpect(flash().attribute("success", Matchers.containsString(NAME)))
            .andExpect(flash().attributeCount(1));

        ConfluenceInstance instance = saved();
        assertThat(instance.processAttachments()).isFalse();
        assertThat(instance.enabled()).isTrue();
        assertThat(instance.targetCollection()).isEqualTo(COLLECTION);
        assertThat(instance.targetTag()).isEqualTo(TAG);
        assertThat(instance.includeLabels()).isEqualTo("kb-public");
        // The credential columns are one value and move together, so the derived health cannot lie.
        assertThat(instance.apiTokenEnc()).isNotNull();
        assertThat(instance.tokenHealth(secretCipher.keyId())).isEqualTo(TokenHealth.OK);
    }

    /**
     * The same post with the two hidden markers stripped as well. The DTO defaults a missing
     * checkbox itself, so no caller can be broken by a box that simply was not ticked — and,
     * crucially, {@code @Valid} still runs. A binding error would make
     * {@code ModelAttributeMethodProcessor} skip validation entirely, turning every constraint on
     * the form into dead code.
     */
    @Test
    public void savingWithoutTheHiddenMarkersBindsBothCheckboxesToFalse() throws Exception {
        Map<String, String> fields = formFields();
        fields.remove("_processAttachments");
        fields.remove("_enabled");
        fields.remove("enabled");

        mockMvc.perform(browserSave(fields)).andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("success", Matchers.notNullValue()));

        ConfluenceInstance instance = saved();
        assertThat(instance.processAttachments()).isFalse();
        assertThat(instance.enabled()).isFalse();
    }

    @Test
    public void tickingTheAttachmentsBoxStoresItAsTrue() throws Exception {
        Map<String, String> fields = formFields();
        fields.put("processAttachments", "true");

        mockMvc.perform(browserSave(fields)).andExpect(status().is3xxRedirection());

        assertThat(saved().processAttachments()).isTrue();
    }

    /** Bean Validation still runs on the real request, and still rejects — nothing is written. */
    @Test
    public void aNonNumericRootPageIdIsRejectedByBeanValidation() throws Exception {
        Map<String, String> fields = formFields();
        fields.put("rootPageId", "DOCS-12345");

        mockMvc.perform(browserSave(fields)).andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("error", Matchers.containsString("numeric")));

        assertThat(instanceRepository.findBySlug(SLUG)).isEmpty();
    }

    /**
     * A target collection that does not exist is refused at save, where the message can name it —
     * not at the first sync, from an enqueue validator, on a page that says nothing about
     * connectors.
     */
    @Test
    public void savingAgainstAnUnknownCollectionIsRefusedAtSaveTime() throws Exception {
        Map<String, String> fields = formFields();
        fields.put("targetCollection", "IT-NO-SUCH-COLLECTION");

        mockMvc.perform(browserSave(fields)).andExpect(status().is3xxRedirection())
            .andExpect(
                flash().attribute("error", Matchers.containsString("IT-NO-SUCH-COLLECTION")));

        assertThat(instanceRepository.findBySlug(SLUG)).isEmpty();
    }

    /**
     * The documented 401 trap: an edit that changes only a label filter leaves the token box blank,
     * and a blank token must mean "keep the stored credential", never "destroy it".
     */
    @Test
    public void editingWithABlankTokenKeepsTheStoredCredential() throws Exception {
        saveDefaultInstance();
        ConfluenceInstance created = saved();

        Map<String, String> edit = formFields();
        edit.put("id", created.id().toString());
        edit.put("apiToken", "");
        edit.put("includeLabels", "kb-public, kb-internal");

        mockMvc.perform(browserSave(edit)).andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("success", Matchers.notNullValue()));

        ConfluenceInstance edited = saved();
        assertThat(edited.id()).isEqualTo(created.id());
        assertThat(edited.includeLabels()).isEqualTo("kb-public, kb-internal");
        assertThat(edited.apiTokenEnc()).isEqualTo(created.apiTokenEnc());
        assertThat(edited.tokenKeyId()).isEqualTo(created.tokenKeyId());
    }

    @Test
    public void savingRequiresAdmin() throws Exception {
        mockMvc.perform(post("/admin/connectors/confluence").with(csrf()).with(plainUser())
            .param("name", "nope").param("slug", "nope")).andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------------
    // Render — the row branch nothing had ever executed
    // ---------------------------------------------------------------------

    @Test
    public void theInstanceRowBranchRendersEveryPerRowControl() throws Exception {
        saveDefaultInstance();
        UUID id = saved().id();

        String html = mockMvc.perform(get("/admin/connectors").with(admin()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(html).contains(NAME).contains(SLUG)
            .contains("https://it-connectors.atlassian.net")
            // Token health badge: a stored, readable credential.
            .contains("Token OK")
            // The Edit button's data-* payload, which the form JavaScript copies field by field.
            .contains("data-id=\"" + id + "\"").contains("data-slug=\"" + SLUG + "\"")
            .contains("data-collection=\"" + COLLECTION + "\"").contains("data-tag=\"" + TAG + "\"")
            .contains("data-attachments=\"false\"").contains("data-enabled=\"true\"")
            .contains("data-root-page-id=\"12345\"")
            // Delete and sync forms, each carrying the row's id.
            .contains("/admin/connectors/confluence/delete")
            .contains("/admin/connectors/confluence/sync").contains("value=\"" + id + "\"")
            // Target and label summary grid.
            .contains(COLLECTION).contains("Attachments: skipped").contains("kb-public")
            .contains("draft")
            // Per-row htmx test expressions.
            .contains("test-results-" + id).contains("test-indicator-" + id)
            // ...and the empty-state card is gone.
            .doesNotContain("No Confluence Instances Configured")
            // The page must never be able to publish the credential or its key fingerprint.
            .doesNotContain("it-token").doesNotContain("apiTokenEnc").doesNotContain("tokenKeyId");
    }

    /** The other token-health state, which only exists on a rendered row. */
    @Test
    public void aRowWithNoStoredTokenRendersTheMissingBadge() throws Exception {
        jdbcTemplate.update(
            "INSERT INTO confluence_instances (id, name, slug, domain, email, space, root_page_id, "
                + "target_collection, target_tag, enabled) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, false)",
            UUID.randomUUID(), "IT Connectors Tokenless", "it-connectors-tokenless",
            "https://it-tokenless.atlassian.net", "svc@example.com", "DOCS", "999", COLLECTION, TAG);

        mockMvc.perform(get("/admin/connectors").with(admin())).andExpect(status().isOk())
            .andExpect(content().string(Matchers.containsString("Token Missing")))
            .andExpect(content().string(Matchers.containsString("Disabled")));
    }

    /** The hidden field markers the form binding depends on must actually be in the HTML. */
    @Test
    public void theFormRendersSpringsHiddenCheckboxMarkers() throws Exception {
        mockMvc.perform(get("/admin/connectors").with(admin())).andExpect(status().isOk())
            .andExpect(content().string(Matchers.containsString("name=\"_processAttachments\"")))
            .andExpect(content().string(Matchers.containsString("name=\"_enabled\"")));
    }

    // ---------------------------------------------------------------------
    // Tag options — the fragment the connectors select fetches
    // ---------------------------------------------------------------------

    /**
     * htmx sends the triggering element's own name on a GET, and the connectors select MUST be named
     * {@code targetCollection} to bind to the form. Reading only {@code collection} left that page's
     * tag container empty and hidden forever: every sync of a tagged collection then failed with
     * "Field 'tag' … must not be blank", and editing an instance applied its stored tag to a select
     * with no matching option, silently blanking it — which costs the next ingest its version
     * scoping.
     */
    @Test
    public void theTagContainerPopulatesForTheParameterNameTheConnectorsSelectSends()
        throws Exception {
        String fragment = mockMvc
            .perform(get("/admin/collections/tag-options").param("targetCollection", COLLECTION)
                .header("HX-Request", "true").header("HX-Current-URL", CONNECTORS_URL).with(admin()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(fragment).contains("id=\"tag-container\"").contains("name=\"targetTag\"")
            .contains(">" + TAG + "<").contains(">" + OTHER_TAG + "<")
            // Populated means visible: an empty container renders with display:none.
            .doesNotContain("display: none");
    }

    /** The ingest/search pages' parameter name still works — one endpoint, three callers. */
    @Test
    public void theTagContainerStillPopulatesForTheIngestPagesParameterName() throws Exception {
        mockMvc
            .perform(get("/admin/collections/tag-options").param("collection", COLLECTION)
                .header("HX-Request", "true")
                .header("HX-Current-URL", "http://localhost:8080/admin/ingest").with(admin()))
            .andExpect(status().isOk())
            .andExpect(content().string(Matchers.containsString(">" + TAG + "<")));
    }

    // ---------------------------------------------------------------------
    // Delete — no test at all before, not even authorization
    // ---------------------------------------------------------------------

    /**
     * Deleting must CANCEL the instance's queued work in the database. Once the row is gone every
     * queued {@code confluence-page-import:<slug>} can only fail with "instance no longer exists",
     * so a deleted connector would otherwise become a wall of red an operator sweeps up by hand. The
     * existing unit test mocks {@code JobRepository}, so nothing had ever executed the SQL.
     */
    @Test
    public void deletingCancelsOnlyItsOwnQueuedJobsInTheDatabase() throws Exception {
        saveDefaultInstance();
        UUID id = saved().id();

        UUID queuedCrawl = insertJob("confluence-import:" + SLUG, "confluence/DOCS/12345", "queued");
        UUID queuedPage =
            insertJob("confluence-page-import:" + SLUG, "confluence/DOCS/777", "queued");
        UUID runningPage =
            insertJob("confluence-page-import:" + SLUG, "confluence/DOCS/888", "running");
        UUID otherInstancePage =
            insertJob("confluence-page-import:other-wiki", "confluence/DOCS/999", "queued");

        mockMvc
            .perform(browserPost("/admin/connectors/confluence/delete",
                Map.of("id", id.toString())))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/connectors"))
            .andExpect(flash().attribute("success", Matchers.containsString("2 queued job(s)")));

        assertThat(statusOf(queuedCrawl)).isEqualTo("cancelled");
        assertThat(statusOf(queuedPage)).isEqualTo("cancelled");
        // A RUNNING job is mid-write and stops cooperatively; it is deliberately left alone.
        assertThat(statusOf(runningPage)).isEqualTo("running");
        // Another instance's work is never touched — that is what the slug-qualified kind is for.
        assertThat(statusOf(otherInstancePage)).isEqualTo("queued");

        assertThat(instanceRepository.findBySlug(SLUG)).isEmpty();
    }

    @Test
    public void deletingAnInstanceThatIsAlreadyGoneIsAReadableError() throws Exception {
        mockMvc
            .perform(browserPost("/admin/connectors/confluence/delete",
                Map.of("id", UUID.randomUUID().toString())))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("error", Matchers.containsString("no longer exists")));
    }

    @Test
    public void deletingRequiresAdmin() throws Exception {
        mockMvc.perform(post("/admin/connectors/confluence/delete").with(csrf()).with(plainUser())
            .param("id", UUID.randomUUID().toString())).andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------------
    // Sync all — no test at all before, not even authorization
    // ---------------------------------------------------------------------

    /**
     * The enqueue is real and the queued row is asserted in the database. That is safe only because
     * the whole test is one uncommitted transaction: the live worker never sees the job, so no
     * Confluence request is ever made.
     */
    @Test
    public void syncAllEnqueuesACrawlForEveryEnabledInstance() throws Exception {
        saveDefaultInstance();

        mockMvc.perform(browserPost("/admin/connectors/confluence/sync-all", Map.of()))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/connectors"))
            .andExpect(flash().attribute("success", Matchers.containsString("instance(s)")));

        Map<String, Object> job = jdbcTemplate.queryForMap(
            "SELECT kind, source_ref, status FROM ingestion_jobs WHERE kind = ?",
            "confluence-import:" + SLUG);
        assertThat(job).containsEntry("source_ref", "confluence/DOCS/12345")
            .containsEntry("status", "queued");
    }

    /**
     * A disabled instance is skipped rather than started — disabling is the operator's only stop
     * lever, and "Sync All Enabled" has to honour it.
     */
    @Test
    public void syncAllSkipsDisabledInstances() throws Exception {
        Map<String, String> fields = formFields();
        fields.remove("enabled");

        mockMvc.perform(browserSave(fields)).andExpect(status().is3xxRedirection());
        assertThat(saved().enabled()).isFalse();

        mockMvc.perform(browserPost("/admin/connectors/confluence/sync-all", Map.of()))
            .andExpect(status().is3xxRedirection());

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM ingestion_jobs WHERE kind = ?",
            Integer.class, "confluence-import:" + SLUG)).isZero();
    }

    @Test
    public void syncAllRequiresAdmin() throws Exception {
        mockMvc
            .perform(post("/admin/connectors/confluence/sync-all").with(csrf()).with(plainUser()))
            .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private UUID insertJob(String kind, String sourceRef, String status) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO ingestion_jobs (id, kind, source_ref, tags, collection_id, status) "
                + "VALUES (?, ?, ?, string_to_array(?, ','), ?, ?)",
            id, kind, sourceRef, TAG, collectionId(), status);
        return id;
    }

    private String statusOf(UUID jobId) {
        return jdbcTemplate.queryForObject("SELECT status FROM ingestion_jobs WHERE id = ?",
            String.class, jobId);
    }
}
