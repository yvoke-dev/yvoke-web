package de.palsoftware.yvoke.shared.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import de.palsoftware.yvoke.shared.jobengine.repository.JobRepository;
import org.hamcrest.Matchers;

/**
 * The destructive admin writes: clearing a knowledge-graph scope and stopping a running job. Neither
 * had a test at any tier, which is an uncomfortable place for the two endpoints that delete data and
 * kill work in flight.
 *
 * <p>Both guards are covered as well as both happy paths, because the guards are the whole safety
 * story: {@code /admin/kg/clear} refuses to run without an explicit tag (a missing tag must not be
 * read as "all versions"), and {@code /admin/jobs/{id}/stop} must report honestly when it changed
 * nothing rather than claiming success.
 *
 * <p>{@code POST /admin/documents/{id}/process-kg} is deliberately not driven here: it enqueues an
 * LLM extraction job, and this context has a live worker and a real {@code LlmClient}, so the happy
 * path would make network calls. Only its authorization is asserted.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "app.security.mock=true")
public class AdminDestructiveWriteIT {

    private static final String COLLECTION = "IT-DESTRUCTIVE-WRITE";
    private static final String TAG = "9.3";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JobRepository jobRepository;

    private MockMvc mockMvc;

    private UUID collectionId;

    @BeforeEach
    public void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        cleanup();
        collectionId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO collections (id, name, tags) VALUES (?, ?, ARRAY['9.3'])",
            collectionId, COLLECTION);
    }

    @AfterEach
    public void tearDown() {
        cleanup();
    }

    private void cleanup() {
        jdbcTemplate.update("DELETE FROM collections WHERE name = ?", COLLECTION);
    }

    private static OidcLoginRequestPostProcessor admin() {
        return oidcLogin().idToken(token -> token.claim("oid", "it-destructive-admin")).authorities(
            new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("ROLE_USER"));
    }

    private static OidcLoginRequestPostProcessor plainUser() {
        return oidcLogin().idToken(token -> token.claim("oid", "it-destructive-user"))
            .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    private void seedEntity(String name) {
        jdbcTemplate.update(
            "INSERT INTO entities (id, collection_id, name, kind, description, tags) "
                + "VALUES (?, ?, ?, 'module', 'seeded', ARRAY['9.3'])",
            UUID.randomUUID(), collectionId, name);
    }

    private int entityCount() {
        Integer n = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM entities WHERE collection_id = ?", Integer.class, collectionId);
        return n == null ? 0 : n;
    }

    // ---------- KG clear ----------

    @Test
    public void clearingAScopeDeletesThatScopesGraph() throws Exception {
        seedEntity("ADS");
        seedEntity("DBQueue");
        assertThat(entityCount()).isEqualTo(2);

        mockMvc
            .perform(post("/admin/kg/clear").with(csrf()).with(admin())
                .param("collection", COLLECTION).param("tag", TAG))
            .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("success"));

        assertThat(entityCount()).isZero();
    }

    /**
     * A blank tag must be refused, not treated as "every version". This is the difference between
     * clearing one release's graph and clearing the whole collection's.
     */
    @Test
    public void clearingWithoutATagIsRefusedAndDeletesNothing() throws Exception {
        seedEntity("ADS");

        mockMvc
            .perform(post("/admin/kg/clear").with(csrf()).with(admin())
                .param("collection", COLLECTION).param("tag", "   "))
            .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("error"));

        assertThat(entityCount()).isEqualTo(1);
    }

    @Test
    public void clearingTheGraphRequiresAdmin() throws Exception {
        seedEntity("ADS");

        mockMvc.perform(post("/admin/kg/clear").with(csrf()).with(plainUser())
            .param("collection", COLLECTION).param("tag", TAG)).andExpect(status().isForbidden());

        assertThat(entityCount()).isEqualTo(1);
    }

    // ---------- job stop ----------

    private UUID seedJob(String status) {
        UUID jobId = UUID.randomUUID();
        // Seeded as 'running' rather than 'queued' so the live background worker in this context
        // cannot claim it and race the assertions.
        //
        // source_ref is unique per call: ux_ingestion_jobs_active_work (V3) allows only ONE
        // queued/running job per (kind, source_ref, collection_id, tags), so a shared literal would
        // make a second active seed a duplicate-key failure rather than a fixture.
        jdbcTemplate.update(
            "INSERT INTO ingestion_jobs (id, kind, source_ref, tags, collection_id, status, "
                + "progress) VALUES (?, 'kg-extract', ?, ARRAY['9.3'], ?, ?, 10)",
            jobId, "it/destructive/" + jobId, collectionId, status);
        return jobId;
    }

    private String jobStatus(UUID jobId) {
        return jdbcTemplate.queryForObject("SELECT status FROM ingestion_jobs WHERE id = ?",
            String.class, jobId);
    }

    /**
     * Wave 3b: a stop records CANCELLED, not FAILED. An operator's stop is not an ingest failure,
     * and after bulk-cancelling a repointed connector's queue a jobs list of hundreds of red rows
     * hides the genuine failures completely.
     */
    @Test
    public void stoppingARunningJobMarksItCancelledWithAReason() throws Exception {
        UUID jobId = seedJob("running");

        mockMvc.perform(post("/admin/jobs/" + jobId + "/stop").with(csrf()).with(admin()))
            .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("success"));

        assertThat(jobStatus(jobId)).isEqualTo("cancelled");
        assertThat(jdbcTemplate.queryForObject("SELECT error FROM ingestion_jobs WHERE id = ?",
            String.class, jobId)).isEqualTo("Stopped by administrator");
    }

    // ---------- bulk cancel of queued work ----------
    //
    // Only the guards and the honest-reporting path are driven here. This context runs a LIVE
    // worker on a 2s poll, so a seeded 'queued' job could be claimed between the INSERT and the
    // cancel; the actual cancellation semantics (queued cancelled, running untouched, per-instance
    // scoping) are pinned in JobRepositoryIT, whose context has the worker disabled.

    @Test
    public void cancellingQueuedWorkOfAKindWithNothingQueuedSaysSoAndLeavesRunningJobsAlone()
        throws Exception {
        UUID running = seedJob("running");

        mockMvc
            .perform(post("/admin/jobs/cancel-queued").with(csrf()).with(admin()).param("kind",
                "it-bulk-cancel-no-such-kind"))
            .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("error"));

        assertThat(jobStatus(running)).isEqualTo("running");
    }

    @Test
    public void cancellingQueuedWorkRequiresAdmin() throws Exception {
        UUID running = seedJob("running");

        mockMvc.perform(post("/admin/jobs/cancel-queued").with(csrf()).with(plainUser())
            .param("kind", "kg-extract")).andExpect(status().isForbidden());

        assertThat(jobStatus(running)).isEqualTo("running");
    }

    /** Stopping something already finished must say so instead of reporting a success it didn't do. */
    @Test
    public void stoppingAFinishedJobReportsThatNothingChanged() throws Exception {
        UUID jobId = seedJob("succeeded");

        mockMvc.perform(post("/admin/jobs/" + jobId + "/stop").with(csrf()).with(admin()))
            .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("error"));

        assertThat(jobStatus(jobId)).isEqualTo("succeeded");
    }

    @Test
    public void stoppingAJobRequiresAdmin() throws Exception {
        UUID jobId = seedJob("running");

        mockMvc.perform(post("/admin/jobs/" + jobId + "/stop").with(csrf()).with(plainUser()))
            .andExpect(status().isForbidden());

        assertThat(jobStatus(jobId)).isEqualTo("running");
    }

    @Test
    public void enqueuingKgExtractionForADocumentRequiresAdmin() throws Exception {
        mockMvc
            .perform(post("/admin/documents/" + UUID.randomUUID() + "/process-kg").with(csrf())
                .with(plainUser()))
            .andExpect(status().isForbidden());
    }

    /**
     * The job-detail page's outcome card is styled by STATUS, not by the presence of `error`.
     * Keying off `error` alone rendered a deliberate stop under a red "Failure details" heading
     * while the badge beside it read a muted "Cancelled" — the page contradicting itself, and an
     * operator sent to investigate a fault that never happened.
     *
     * <p>
     * Rendered here rather than in the e2e tier: the it-tests profile excludes browser tests, so
     * without this nothing in the fast loop ever renders this template and a broken Thymeleaf
     * expression would reach production.
     */
    @Test
    public void aCancelledJobRendersItsOutcomeAsCancellationNotFailure() throws Exception {
        UUID jobId = seedJob("running");
        mockMvc.perform(post("/admin/jobs/" + jobId + "/stop").with(csrf()).with(admin()))
            .andExpect(status().is3xxRedirection());

        // Assert on the RENDERED element: both headings and both class names also appear as
        // string literals in the page's inline script, which drives the same swap over SSE.
        mockMvc.perform(get("/admin/jobs/" + jobId).with(admin())).andExpect(status().isOk())
            .andExpect(content()
                .string(Matchers.containsString(">Cancellation details</h3>")))
            .andExpect(content()
                .string(Matchers.containsString("card job-outcome-cancelled")))
            .andExpect(content().string(Matchers
                .not(Matchers.containsString(">Failure details</h3>"))));
    }

    @Test
    public void aFailedJobStillRendersItsOutcomeAsFailure() throws Exception {
        UUID jobId = seedJob("failed");
        jdbcTemplate.update("UPDATE ingestion_jobs SET error = ? WHERE id = ?", "boom", jobId);

        mockMvc.perform(get("/admin/jobs/" + jobId).with(admin())).andExpect(status().isOk())
            .andExpect(
                content().string(Matchers.containsString(">Failure details</h3>")))
            .andExpect(
                content().string(Matchers.containsString("card job-outcome-failed")));
    }

    /**
     * The crawl summary used to live only on a progress event: no replay buffer, and the terminal
     * snapshot blanks it, so it survived milliseconds and only for someone already watching —
     * while connectors.html tells the operator to read it here.
     */
    @Test
    public void aJobSummarySurvivesTheRunAndIsRenderedOnTheDetailPage() throws Exception {
        UUID jobId = seedJob("completed");
        jobRepository.updateSummary(jobId,
            "queued 3 page(s), 41 already queued, 0 could not be queued");

        mockMvc.perform(get("/admin/jobs/" + jobId).with(admin())).andExpect(status().isOk())
            .andExpect(content().string(Matchers.containsString(">Run summary</h3>")))
            .andExpect(content()
                .string(Matchers.containsString("41 already queued")));
    }

    /**
     * S7 Data: {@code error} and {@code summary} are different columns with different meanings, and
     * a NON-EMPTY {@code error} is what marks a job failed. A summary must therefore never be
     * written to it — not even additionally.
     *
     * <p>
     * A dual write is the plausible regression ("put it in error too, so it shows up on the detail
     * page without a new card") and it passes every test that exists. The sibling
     * {@code aJobSummarySurvivesTheRunAndIsRenderedOnTheDetailPage} would catch the summary being
     * routed INSTEAD of {@code error} — the Run summary card would vanish — but nothing asserts
     * {@code error} stays untouched, so a write to both keeps that test green while the completed
     * job starts rendering the red "Failure details" / {@code job-outcome-failed} card. A
     * successful Confluence crawl then looks exactly like a failed one, which destroys the single
     * signal an operator scans the jobs list for; after a bulk re-crawl every green row goes red at
     * once and the genuine failures become unfindable.
     *
     * <p>
     * Both halves are asserted because they fail differently: the column check is the contract, and
     * the render check is what an operator actually sees. The outcome card is always present in the
     * DOM and hidden by an inline style when {@code error} is null, so the visible/hidden state —
     * not the heading text — is the thing to assert.
     */
    @Test
    public void aRunSummaryNeverLandsInTheErrorColumnThatMarksAJobFailed() throws Exception {
        UUID jobId = seedJob("completed");

        jobRepository.updateSummary(jobId,
            "queued 3 page(s), 41 already queued, 0 could not be queued");

        assertThat(jdbcTemplate.queryForObject("SELECT error FROM ingestion_jobs WHERE id = ?",
            String.class, jobId))
            .as("a run summary is not a failure: writing it to `error` marks the job failed")
            .isNull();

        mockMvc.perform(get("/admin/jobs/" + jobId).with(admin())).andExpect(status().isOk())
            .andExpect(content().string(Matchers.containsString(">Run summary</h3>")))
            .andExpect(content().string(Matchers
                .containsString("display: none; margin-bottom: 0;")));
    }

    /** No summary, no card — an empty heading would just be noise on most jobs. */
    @Test
    public void aJobWithoutASummaryRendersNoSummaryCard() throws Exception {
        UUID jobId = seedJob("completed");

        mockMvc.perform(get("/admin/jobs/" + jobId).with(admin())).andExpect(status().isOk())
            .andExpect(content().string(
                Matchers.not(Matchers.containsString(">Run summary</h3>"))));
    }
}
