package de.palsoftware.yvoke.ingest.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

import org.mockito.ArgumentCaptor;

import de.palsoftware.yvoke.collection.core.service.CollectionService;
import de.palsoftware.yvoke.ingest.core.model.IngestJobKind;
import de.palsoftware.yvoke.rag.prompt.SystemPromptService;
import de.palsoftware.yvoke.shared.audit.repository.AuditLogRepository;
import de.palsoftware.yvoke.shared.jobengine.model.EnqueueRequest;
import de.palsoftware.yvoke.shared.jobengine.model.EnqueueResult;
import de.palsoftware.yvoke.shared.jobengine.service.JobService;
import de.palsoftware.yvoke.shared.user.service.UserService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

public class IngestAdminControllerTest {

    @TempDir
    Path tempDir;

    private CollectionService collectionService;
    private SystemPromptService systemPromptService;
    private JobService jobService;
    private AuditLogRepository auditLogRepository;
    private UserService userService;

    private IngestAdminController controller;
    private Model model;
    private RedirectAttributes redirectAttributes;

    @BeforeEach
    public void setUp() {
        collectionService = mock(CollectionService.class);
        systemPromptService = mock(SystemPromptService.class);
        jobService = mock(JobService.class);
        auditLogRepository = mock(AuditLogRepository.class);
        userService = mock(UserService.class);

        when(userService.getCurrentUser()).thenReturn(Optional.empty());

        controller = new IngestAdminController(collectionService, systemPromptService, jobService,
            auditLogRepository, userService, tempDir.toString());

        model = new ConcurrentModel();
        redirectAttributes = mock(RedirectAttributes.class);
    }

    @Test
    public void testIngestView() {
        when(collectionService.listCollections()).thenReturn(Collections.emptyList());
        String view = controller.ingestView(model, null);
        assertThat(view).isEqualTo("admin/ingest");
        assertThat(model.getAttribute("collections")).isNotNull();
    }

    @Test
    public void testUploadIngestEmptyFile() throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(true);

        String view = controller.uploadIngest(file, "OIM", "9.3", IngestJobKind.STANDARD.getValue(),
            null, null, null, null, null, true, null, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/ingest");
        verify(redirectAttributes).addFlashAttribute(eq("error"), anyString());
    }

    @Test
    public void testUploadIngestSuccess() throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn("manual.md");
        when(file.getInputStream())
            .thenReturn(new ByteArrayInputStream("Markdown content".getBytes()));

        UUID jobId = UUID.randomUUID();
        when(jobService.enqueue(any(EnqueueRequest.class)))
            .thenReturn(EnqueueResult.created(jobId));

        String view = controller.uploadIngest(file, "OIM", "9.3", IngestJobKind.STANDARD.getValue(),
            null, null, null, null, null, true, null, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/jobs/" + jobId);
        verify(jobService).enqueue(any(EnqueueRequest.class));
        verify(auditLogRepository).log(eq("anonymous_admin"), eq("INGEST_DATA"),
            eq(jobId.toString()), anyMap());
    }

    /**
     * When the enqueue ADOPTS a job already in flight, the form must warn — never report success.
     *
     * <p>
     * Enqueue is idempotent per {@code (kind, source_ref, collection_id, tags)} while a job for
     * that key is queued or running, so a duplicate returns {@code EnqueueResult.adopted(id)}: the
     * pre-existing job's id, and that job runs with the settings IT was enqueued with. Every option
     * on this form — the KG prompt, the summarize prompt, the document glob, the graph flag, the
     * JSON unique field — is carried in {@code ingestion_jobs.settings} and was therefore NOT
     * applied. Saying "Job enqueued successfully!" and redirecting to a job id the operator did not
     * create is doubly wrong: it claims work was created that was not, and it attributes settings
     * to a run that never received them. The operator then watches a job complete, sees a result
     * that does not match what they selected, and has no way to tell that their submission was
     * discarded — the only remedy (cancel that job and submit again) is precisely what the warning
     * text spells out.
     *
     * <p>
     * The distinction is one boolean deep — {@code result.created()} — and both branches redirect
     * to the same place and write the same audit row, so the flash key is the ONLY observable
     * difference. Every existing upload test stubs {@code EnqueueResult.created(...)}, so the
     * adopted branch has no witness at all and could flash success without failing anything.
     */
    @Test
    public void anAdoptedUploadWarnsThatTheChosenSettingsWereNotApplied() throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn("manual.md");
        when(file.getInputStream())
            .thenReturn(new ByteArrayInputStream("Markdown content".getBytes()));

        UUID activeJob = UUID.randomUUID();
        when(jobService.enqueue(any(EnqueueRequest.class)))
            .thenReturn(EnqueueResult.adopted(activeJob));

        String view = controller.uploadIngest(file, "OIM", "9.3", IngestJobKind.STANDARD.getValue(),
            "kg-prompt", "summarize-prompt", null, null, null, true, null, redirectAttributes);

        // The redirect goes to the job that already exists — "showing that job".
        assertThat(view).isEqualTo("redirect:/admin/jobs/" + activeJob);

        ArgumentCaptor<String> warning = ArgumentCaptor.forClass(String.class);
        verify(redirectAttributes).addFlashAttribute(eq("warning"), warning.capture());
        assertThat(warning.getValue()).contains("already queued or running").contains("OIM")
            .contains("were not applied");
        verify(redirectAttributes, never()).addFlashAttribute(eq("success"), any());
    }

    /**
     * The admin upload form does NOT allowlist its {@code kind}: whatever string is posted is
     * staged to disk first and then enqueued verbatim. This test pins that as the real contract,
     * because two downstream behaviours are built on top of it and both break silently if it
     * changes.
     *
     * <p>
     * First, the kind is the operator's ONLY diagnostic. {@code JobService.execute} fails an
     * unroutable job with the literal message {@code "no handler for kind=" + job.kind()}, which is
     * what the job-detail page shows; a controller that trimmed, lower-cased or defaulted the value
     * would make that message describe something the operator never typed, and a typo would become
     * unfindable. Second, {@code sourceRef} must be the staged path under {@code app.upload-dir}:
     * the worker-side {@code UploadPathGuard} rejects anything outside that root, and it does so at
     * EXECUTION time — the enqueue still reports success, so staging somewhere else (a system temp
     * dir, or "just use the original filename") produces a job that dies minutes later with a path
     * error that has nothing to do with what the operator did.
     *
     * <p>
     * The absence of the allowlist is deliberate to record here rather than to "fix" in passing.
     * {@code POST /api/ingest/v1/upload} goes through {@code IngestService.uploadAndEnqueue}, which
     * checks {@code UPLOAD_KINDS} (standard, hierarchical, json-import) and answers 400; this path
     * calls {@code jobService.enqueue} directly and has no such check, so the same mistake surfaces
     * as a failed job an operator must interpret instead of a rejected form. That is a usability
     * gap, NOT a privilege gap: {@code /admin/**} is already {@code hasRole("ADMIN")}, so
     * {@code PrivilegedJobKindGuard.requireAdminForPrivilegedKind} — which only rejects a caller
     * lacking ROLE_ADMIN — would be a strict no-op here, and CSRF stays enabled on the browser
     * chain so the form cannot be driven cross-site. Nothing else in the suite posts a kind this
     * endpoint does not serve.
     */
    @Test
    public void anUnrecognisedKindIsStagedAndEnqueuedVerbatimRatherThanRejected()
        throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn("space-export.zip");
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream("zip bytes".getBytes()));

        UUID jobId = UUID.randomUUID();
        ArgumentCaptor<EnqueueRequest> requestCaptor =
            ArgumentCaptor.forClass(EnqueueRequest.class);
        when(jobService.enqueue(requestCaptor.capture())).thenReturn(EnqueueResult.created(jobId));

        // A kind this form's upload path cannot serve: confluence-import's handler crawls a space
        // through a stored connector token and would be handed a staged FILE as its sourceRef.
        String view = controller.uploadIngest(file, "OIM", "9.3", "confluence-import", null, null,
            null, null, null, false, null, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/jobs/" + jobId);
        EnqueueRequest enqueued = requestCaptor.getValue();
        assertThat(enqueued.kind())
            .as("the kind reaches the job row unchanged — 'no handler for kind=…' can name nothing "
                + "else, so a normalized value would describe something the operator never typed")
            .isEqualTo("confluence-import");
        assertThat(enqueued.sourceRef())
            .as("a staged upload must live under app.upload-dir, or UploadPathGuard kills the job "
                + "at execution time long after the enqueue reported success")
            .startsWith(tempDir.toAbsolutePath().toString());
        // No allowlist on this path: nothing is rejected at submit time.
        verify(redirectAttributes, never()).addFlashAttribute(eq("error"), any());
    }

    @Test
    public void testUploadIngestCustomWithGraphDisabled() throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn("export.zip");
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream("zip bytes".getBytes()));

        UUID jobId = UUID.randomUUID();
        ArgumentCaptor<EnqueueRequest> requestCaptor =
            ArgumentCaptor.forClass(EnqueueRequest.class);
        when(jobService.enqueue(requestCaptor.capture())).thenReturn(EnqueueResult.created(jobId));

        String view = controller.uploadIngest(file, "OIM", "9.3", IngestJobKind.CUSTOM.getValue(),
            null, null, null, null, null, false, null, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/jobs/" + jobId);
        EnqueueRequest captured = requestCaptor.getValue();
        assertThat(captured.settings()).containsEntry("enableGraph", false);
    }

    /**
     * An UNCHECKED graph checkbox must be written into the job settings as an explicit
     * {@code enableGraph=false}, not left out.
     *
     * <p>
     * An unchecked HTML checkbox posts no value at all, so the binder hands the controller
     * {@code null} — and {@code CustomIngestService.isGraphEnabled} defaults to TRUE when the key
     * is ABSENT. The {@code else} branch that writes the false is therefore the only thing standing
     * between "the operator unticked graph injection" and "graph injection ran anyway": it inverts
     * the operator's explicit choice. That branch reads like redundant bookkeeping — writing a
     * value that is already the field's default — which is exactly the shape a cleanup deletes.
     *
     * <p>
     * The failure is not cosmetic and not visible on this page. Against a corpus zip that ships
     * {@code graph/entities.jsonl}, injection runs and writes entities and relationships into a
     * collection the operator deliberately kept graph-free; against one whose entities do not all
     * resolve to a document it aborts the job with a message about the knowledge graph the operator
     * never asked for. Either way the settings row records what was actually run, so the
     * disagreement is only discoverable by reading {@code ingestion_jobs.settings} and knowing the
     * default.
     *
     * <p>
     * No existing test passes {@code null} here: {@code testUploadIngestCustomWithGraphDisabled}
     * passes {@code Boolean.FALSE} (the ticked-then-unticked case, which takes the OTHER branch)
     * and {@code theJsonUniqueFieldChosenOnTheFormReachesTheJobSettings} passes {@code false}. The
     * unchecked-checkbox case — the ordinary one — has never been exercised.
     */
    @Test
    void anUncheckedGraphCheckboxIsRecordedAsAnExplicitFalse() throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn("export.zip");
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream("zip bytes".getBytes()));

        ArgumentCaptor<EnqueueRequest> requestCaptor =
            ArgumentCaptor.forClass(EnqueueRequest.class);
        when(jobService.enqueue(requestCaptor.capture()))
            .thenReturn(EnqueueResult.created(UUID.randomUUID()));

        // enableGraph = null: an unchecked checkbox posts no value at all.
        controller.uploadIngest(file, "OIM", "9.3", IngestJobKind.CUSTOM.getValue(), null, null,
            null, null, null, null, null, redirectAttributes);

        assertThat(requestCaptor.getValue().settings())
            .as("an absent key means isGraphEnabled defaults to TRUE — graph injection would run on"
                + " a corpus the operator opted out of")
            .containsEntry("enableGraph", false);
    }

    /**
     * The form has always posted {@code jsonUniqueField} (ingest.html) and
     * {@code JsonImportJobHandler} has always read it from {@code ingestion_jobs.settings} — but
     * the controller never bound it, so an admin-initiated json-import silently ignored the
     * operator's choice and INSERTED every object instead of upserting. Re-importing a corpus
     * through the admin page therefore duplicated every row, with the job reporting a normal count.
     * The only other producer of that settings key is POST /api/ingest/v1/upload.
     */
    @Test
    void theJsonUniqueFieldChosenOnTheFormReachesTheJobSettings() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn("objects.jsonl");
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream("{}".getBytes()));

        ArgumentCaptor<EnqueueRequest> requestCaptor =
            ArgumentCaptor.forClass(EnqueueRequest.class);
        when(jobService.enqueue(requestCaptor.capture()))
            .thenReturn(EnqueueResult.created(UUID.randomUUID()));

        controller.uploadIngest(file, "OIM", "9.3", IngestJobKind.JSON_IMPORT.getValue(), null,
            null, null, null, null, false, "  customer.id  ", redirectAttributes);

        assertThat(requestCaptor.getValue().settings())
            .as("without this the operator's unique field never reaches the handler")
            .containsEntry("jsonUniqueField", "customer.id");
    }
}
