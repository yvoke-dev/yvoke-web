package de.palsoftware.yvoke.ingest.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

import de.palsoftware.yvoke.collection.core.service.CollectionService;
import de.palsoftware.yvoke.document.core.repository.DocumentRepository;
import de.palsoftware.yvoke.shared.jobengine.model.EnqueueRequest;
import de.palsoftware.yvoke.shared.jobengine.model.EnqueueResult;
import de.palsoftware.yvoke.shared.jobengine.service.JobService;
import de.palsoftware.yvoke.tag.core.service.TagService;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import de.palsoftware.yvoke.collection.core.model.Collection;
import java.util.List;
import org.mockito.InOrder;
import java.nio.file.Files;
import java.util.stream.Stream;
import org.mockito.Mockito;

/**
 * Path-traversal confinement for the ingest staging paths (SEC-01 / SEC-16), moved here from the
 * controller test when the orchestration was extracted into {@link IngestService} (MNT-04). The
 * guards live in the service now, so this is where they are pinned.
 */
public class IngestServiceTest {

    @TempDir
    Path uploadDir;

    private JobService jobService;
    private CollectionService collectionService;
    private TagService tagService;
    private DocumentRepository documentRepository;
    private IngestService ingestService;

    @BeforeEach
    public void setUp() {
        jobService = mock(JobService.class);
        collectionService = mock(CollectionService.class);
        tagService = mock(TagService.class);
        documentRepository = mock(DocumentRepository.class);
        ingestService = new IngestService(jobService, collectionService, tagService,
            documentRepository, uploadDir.toString());
    }

    /**
     * {@code POST /api/ingest/v1/custom} deliberately does NOT pre-register its target, and the
     * rejection that follows must be a <b>400</b>.
     *
     * <p>
     * The two sibling entry points on this service ({@code uploadAndEnqueue} and {@code processKg})
     * both create the collection and declare the tag before enqueueing. {@code enqueueCustom} does
     * neither: it stages the zip and hands the request straight to {@code JobService.enqueue},
     * where {@code CollectionTagEnqueueValidator} refuses an unknown collection (or a tag the
     * collection does not declare) with an {@code IllegalArgumentException}. That asymmetry is
     * intentional — a custom corpus targets a curated collection whose tag vocabulary an operator
     * has already declared, and auto-creating one from a typo mints an empty collection that also
     * carries a real {@code chunks} partition. Nothing observes the asymmetry today, so "make it
     * consistent with the other two" is a one-line edit no test would notice.
     *
     * <p>
     * The status code is the other half. The validator's rejection is a routine operator typo, and
     * the {@code catch (IllegalArgumentException)} arm is the only thing that turns it into a 400
     * naming the offending collection. Remove it and the exception escapes as an unhandled
     * exception: an API client reads "500 Internal server error" — retry later — for a request that
     * can never succeed until it is corrected, and the message that names the bad collection is
     * replaced by the generic error text. Every existing test here stubs {@code jobService.enqueue}
     * to succeed, so that arm has never executed.
     */
    @Test
    public void anUnknownCollectionMakesCustomA400AndNothingIsPreRegistered() {
        MockMultipartFile zip =
            new MockMultipartFile("file", "corpus.zip", "application/zip", "PK-payload".getBytes());
        when(jobService.enqueue(any(EnqueueRequest.class))).thenThrow(
            new IllegalArgumentException("Collection 'GHOST-COLLECTION' does not exist."));

        assertThatThrownBy(() -> ingestService.enqueueCustom(zip, "GHOST-COLLECTION", "9.3",
            "**/*.md", "graph/entities.jsonl", "graph/relationships.jsonl"))
            .as("a validator rejection must reach the caller as a 400, not an unhandled 500")
            .isInstanceOf(ResponseStatusException.class).satisfies(thrown -> {
                ResponseStatusException rse = (ResponseStatusException) thrown;
                assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(rse.getReason())
                    .as("the operator has to be told WHICH collection was wrong")
                    .contains("GHOST-COLLECTION");
            });

        verify(collectionService, never()).createCollection(any(), any());
        verify(tagService, never()).addTagToCollection(any(), any());
    }

    /**
     * SEC-01: a crafted multipart filename must not let the staged zip escape the freshly created
     * {@code custom_*} temp directory (CWE-22 path traversal / arbitrary file write).
     */
    @Test
    public void enqueueCustomRejectsPathTraversalInFilename() throws Exception {
        ArgumentCaptor<EnqueueRequest> captor = ArgumentCaptor.forClass(EnqueueRequest.class);
        when(jobService.enqueue(captor.capture()))
            .thenReturn(EnqueueResult.created(UUID.randomUUID()));

        // Malicious multipart filename attempting to climb out of the staging temp dir.
        MockMultipartFile file = new MockMultipartFile("file", "../evil-pwn.zip", "application/zip",
            "zip bytes".getBytes());

        UUID jobId = ingestService.enqueueCustom(file, "OIM", "v1", "**/*.md",
            "graph/entities.jsonl", "graph/relationships.jsonl");

        assertThat(jobId).isNotNull();

        String stagedRef = captor.getValue().sourceRef();
        Path staged = Path.of(stagedRef);
        // The staged path must stay inside the freshly created custom_* temp dir.
        assertThat(stagedRef).doesNotContain("..");
        assertThat(staged).isEqualTo(staged.normalize());
        assertThat(staged.getParent().getFileName().toString()).startsWith("custom_");
        assertThat(staged.getFileName().toString()).isEqualTo("evil-pwn.zip");
    }

    /**
     * The custom_* staging dir must live INSIDE the configured upload dir — the worker-side
     * {@code UploadPathGuard} rejects any sourceRef outside {@code app.upload-dir}, so staging in
     * the system temp dir makes every custom ingest fail at execution time.
     */
    @Test
    public void enqueueCustomStagesZipInsideUploadDir() throws Exception {
        ArgumentCaptor<EnqueueRequest> captor = ArgumentCaptor.forClass(EnqueueRequest.class);
        when(jobService.enqueue(captor.capture()))
            .thenReturn(EnqueueResult.created(UUID.randomUUID()));

        MockMultipartFile file =
            new MockMultipartFile("file", "corpus.zip", "application/zip", "zip bytes".getBytes());

        ingestService.enqueueCustom(file, "OIM", "v1", "**/*.md", "graph/entities.jsonl",
            "graph/relationships.jsonl");

        Path staged = Path.of(captor.getValue().sourceRef()).normalize();
        Path root = uploadDir.toAbsolutePath().normalize();
        assertThat(staged.startsWith(root)).isTrue();
        assertThat(staged.getParent().getFileName().toString()).startsWith("custom_");
    }

    /**
     * {@code sourceTag} DEFAULTS to the target tag when the caller omits it (null) or sends it
     * blank.
     *
     * <p>
     * {@code findIdByFile} matches on collection AND tag AND source_file, and the tag it is given
     * is whatever this expression produced — so the default is what makes the common call shape
     * work at all: "extract the graph for install-kit.md in OIM-Install", named once, for the
     * version being processed. Drop it and the probe runs with a null tag, which matches no row in
     * a tag-scoped corpus (every document of both kit versions carries its version tag), so the
     * endpoint answers 404 "Document not found" for a document that is plainly there. The message
     * even names the tag it searched, which would then be {@code null} — an operator reading it has
     * no reason to suspect the API invented the scope rather than using theirs.
     *
     * <p>
     * Every existing test passes {@code sourceTag} explicitly
     * ({@code processKgWithoutDocumentIdDemandsBothSourceFieldsAndReportsAMissingDocument} sends
     * "9.3.1"), so the defaulting branch has never run. The assertion here is an outcome, not an
     * argument: the ONLY stubbed probe is the one carrying the trimmed target tag, and every other
     * combination returns {@code Optional.empty()} (Mockito's default for an Optional return), so a
     * lost default turns into the 404 and fails the test rather than being read back from a
     * matcher. Both spellings of "absent" are covered, because the guard is a two-part condition.
     */
    @Test
    public void processKgWithNoSourceTagLooksTheDocumentUpUnderTheTargetTag() {
        UUID colId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        when(collectionService.getCollection("OIM"))
            .thenReturn(Optional.of(new Collection(colId, "OIM", "d", List.of(), null)));
        when(documentRepository.findIdByFile("OIM-Install", "10.0", "install-kit.md"))
            .thenReturn(Optional.of(docId));
        ArgumentCaptor<EnqueueRequest> captor = ArgumentCaptor.forClass(EnqueueRequest.class);
        when(jobService.enqueue(captor.capture())).thenReturn(EnqueueResult.created(jobId));

        EnqueueResult omitted = ingestService.processKg(null, "  install-kit.md  ",
            "  OIM-Install  ", null, " OIM ", " 10.0 ");
        EnqueueResult blank =
            ingestService.processKg(null, "install-kit.md", "OIM-Install", "   ", "OIM", "10.0");

        assertThat(omitted.jobId()).as("a null sourceTag must resolve the document, not 404")
            .isEqualTo(jobId);
        assertThat(blank.jobId()).as("a blank sourceTag is the same case as an absent one")
            .isEqualTo(jobId);
        assertThat(captor.getAllValues()).extracting(EnqueueRequest::sourceRef)
            .as("the job must target the document the defaulted lookup found")
            .containsExactly(docId.toString(), docId.toString());
    }

    /**
     * SEC-16: the upload path must also confine a traversal filename. A UUID prefix alone does NOT
     * neutralize {@code ../../..} — the staged file must stay inside the upload directory.
     */
    @Test
    public void uploadDeclaresTheTagOnTheCollectionBeforeEnqueueing() throws Exception {
        // The ordering is load-bearing, not incidental. CollectionTagEnqueueValidator only
        // validates and propagates a request's tag when the target collection ALREADY declares a
        // non-empty tag set; against a collection with tags='{}' it silently returns an EMPTY tag
        // list and the requested tag is dropped with no error. The job then runs untagged, its
        // documents lose version scoping, and a second version's ingest collides on the same
        // source_file identity and REPLACES the first. So the tag must be registered on the
        // collection before the job row is written — enqueueing first would drop it.
        UUID colId = UUID.randomUUID();
        when(collectionService.getCollection("OIM"))
            .thenReturn(Optional.of(new Collection(colId, "OIM", "d", List.of(), null)));
        when(jobService.enqueue(any(EnqueueRequest.class)))
            .thenReturn(EnqueueResult.created(UUID.randomUUID()));

        MockMultipartFile file =
            new MockMultipartFile("file", "m.md", "text/markdown", "data".getBytes());

        ingestService.uploadAndEnqueue(file, "OIM", " 10.0 ", "standard", null, null);

        InOrder inOrder = Mockito.inOrder(tagService, jobService);
        inOrder.verify(tagService).addTagToCollection(colId, "10.0");
        inOrder.verify(jobService).enqueue(any(EnqueueRequest.class));
    }

    /**
     * {@code /process-kg} must register the tag on the target collection BEFORE the job row is
     * written, for the same reason the upload path must — and it is a second, independent copy of
     * that ordering.
     *
     * <p>
     * {@code CollectionTagEnqueueValidator} only validates and propagates a request's tag when the
     * target collection ALREADY declares a non-empty tag set. Against a collection whose
     * {@code tags} is {@code '{}'} it returns an EMPTY tag list and the requested tag is dropped —
     * no exception, no warning. The kg-extract job then runs untagged, so its entities are written
     * into the tag-blind scope: {@code KgWriteRepository} resolves identity by
     * {@code (collection, kind, lower(name), tag set)}, and an untagged row is a different scope
     * from every tagged one, so the extracted graph is invisible to every read path (they all
     * filter {@code :tag = ANY(tags)}) while the job reports a perfectly normal entity count.
     *
     * <p>
     * This endpoint is the one that auto-creates its target collection when it does not exist —
     * i.e. it routinely runs against a collection with an EMPTY tag set, which is precisely the
     * case the validator silently drops. Reordering the two calls is the natural-looking cleanup
     * ("enqueue first, then bookkeeping"), and nothing about it fails: the API still answers 202
     * with a job id.
     *
     * <p>
     * {@code uploadDeclaresTheTagOnTheCollectionBeforeEnqueueing} pins the same rule on the upload
     * path only; these are two separate methods with two separate call sequences, so that test
     * cannot see a regression here.
     */
    @Test
    public void processKgDeclaresTheTagOnTheCollectionBeforeEnqueueing() {
        UUID colId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        when(collectionService.getCollection("OIM"))
            .thenReturn(Optional.of(new Collection(colId, "OIM", "d", List.of(), null)));
        ArgumentCaptor<EnqueueRequest> captor = ArgumentCaptor.forClass(EnqueueRequest.class);
        when(jobService.enqueue(captor.capture()))
            .thenReturn(EnqueueResult.created(UUID.randomUUID()));

        ingestService.processKg(docId, null, null, null, " OIM ", " 10.0 ");

        InOrder inOrder = inOrder(tagService, jobService);
        inOrder.verify(tagService).addTagToCollection(colId, "10.0");
        inOrder.verify(jobService).enqueue(any(EnqueueRequest.class));
        // The declared tag and the enqueued tag must be the same string, or declaring it first
        // protects nothing.
        assertThat(captor.getValue().collection()).isEqualTo("OIM");
        assertThat(captor.getValue().tags()).containsExactly("10.0");
        verify(collectionService, never()).createCollection(any(), any());
    }

    @Test
    public void uploadAndEnqueueConfinesTraversalFilenameToUploadDir() throws Exception {
        ArgumentCaptor<EnqueueRequest> captor = ArgumentCaptor.forClass(EnqueueRequest.class);
        when(collectionService.getCollection("OIM")).thenReturn(Optional.empty());
        when(jobService.enqueue(captor.capture()))
            .thenReturn(EnqueueResult.created(UUID.randomUUID()));

        MockMultipartFile file =
            new MockMultipartFile("file", "../../../evil.md", "text/markdown", "data".getBytes());

        UUID jobId = ingestService.uploadAndEnqueue(file, "OIM", null, "standard", null, null);

        assertThat(jobId).isNotNull();

        String stagedRef = captor.getValue().sourceRef();
        Path staged = Path.of(stagedRef).normalize();
        Path root = uploadDir.toAbsolutePath().normalize();
        assertThat(stagedRef).doesNotContain("..");
        assertThat(staged.startsWith(root)).isTrue();
        assertThat(staged.getParent()).isEqualTo(root);
        assertThat(staged.getFileName().toString()).endsWith("-evil.md");
    }

    /**
     * The two halves of the lookup key have to arrive together, and the guard is the only thing
     * that says so. Written as {@code &&} it passes whenever EITHER field is present, and the very
     * next lines dereference the one that is missing — {@code sourceCollection.trim()} throws NPE,
     * which the API advice turns into a 500 "Internal server error" instead of telling the caller
     * which field they forgot. A 500 also reads as "the server is broken", so the caller retries
     * the same malformed request rather than fixing it.
     *
     * <p>
     * The 404 text matters for a reason specific to this corpus: {@code findIdByFile} matches on
     * collection AND tag AND source_file, and the source tag DEFAULTS to the target tag when none
     * is given — so "document not found" here is most often a tag-scoping mistake, the same file
     * existing under 9.3.1 while the request named 10.0. A bare "not found" sends the operator
     * hunting for a document that is in fact present under a different tag, so all three RESOLVED
     * (trimmed, defaulted) values are named in the message. Nothing else covers {@code processKg}
     * at all — every test in this class exercises the upload paths.
     */
    @Test
    public void processKgWithoutDocumentIdDemandsBothSourceFieldsAndReportsAMissingDocument() {
        // Half a key is not a key: neither field alone may be accepted.
        assertThatThrownBy(
            () -> ingestService.processKg(null, "install-kit.md", null, null, "OIM", "10.0"))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(
            () -> ingestService.processKg(null, null, "OIM-Install", null, "OIM", "10.0"))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST));

        when(documentRepository.findIdByFile("OIM-Install", "9.3.1", "install-kit.md"))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> ingestService.processKg(null, "  install-kit.md  ",
            "  OIM-Install  ", "  9.3.1  ", "OIM", "10.0"))
            .isInstanceOf(ResponseStatusException.class).satisfies(e -> {
                ResponseStatusException ex = (ResponseStatusException) e;
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                assertThat(ex.getReason()).contains("install-kit.md").contains("OIM-Install")
                    .contains("9.3.1");
            });

        verify(jobService, never()).enqueue(any());
    }

    /**
     * SEC: {@code /api/ingest/v1/upload} takes the job kind straight from a request param, so it
     * was a second door onto the connector kinds — a 1-byte file with
     * {@code kind=confluence-import} enqueued a full space crawl running with the stored admin
     * token. An allowlist of the kinds this endpoint actually serves is stronger than a denylist:
     * any future privileged kind is rejected by default rather than by remembering to name it.
     */
    @Test
    public void uploadAndEnqueueRejectsKindsOutsideItsAllowlist() {
        when(collectionService.getCollection("OIM")).thenReturn(Optional.empty());
        MockMultipartFile file =
            new MockMultipartFile("file", "x.md", "text/markdown", "d".getBytes());

        for (String kind : new String[] {"confluence-import", "confluence-page-import", "custom",
            "kg-extract", "manual", ""}) {
            assertThatThrownBy(
                () -> ingestService.uploadAndEnqueue(file, "OIM", null, kind, null, null))
                .as("kind=%s", kind).isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
        }
        verify(jobService, never()).enqueue(any());
    }

    /**
     * An empty upload must be rejected at the boundary with a 400, before anything is staged and
     * before a job row exists. Nothing submits an empty file today — every test in this class and
     * in {@code IngestApiControllerTest} posts real bytes — so the guard on BOTH upload entry
     * points ({@code /api/ingest/v1/upload} and {@code /api/ingest/v1/custom}) has never executed.
     *
     * <p>
     * Without it a zero-byte file is copied into {@code app.upload-dir} and enqueued, and the
     * failure moves to the worker: the job runs, the parser finds no documents (or throws on an
     * empty zip), and the operator is left with a job-detail page carrying an opaque handler error
     * and no hint that the upload itself was the problem — while the staged file stays on disk and
     * the admission-control index holds a slot for a unit of work that can never succeed. The
     * status code is the contract for an API client: it has to be able to tell "your file was
     * empty" (fix the request) from a 500 (retry later), and both come out of the same handler.
     */
    @Test
    public void anEmptyUploadIs400AndNothingIsStagedOrEnqueued() throws Exception {
        MockMultipartFile empty =
            new MockMultipartFile("file", "corpus.zip", "application/zip", new byte[0]);

        assertThatThrownBy(
            () -> ingestService.uploadAndEnqueue(empty, "OIM", "9.3", "standard", null, null))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> ingestService.enqueueCustom(empty, "OIM", "9.3", "**/*.md",
            "graph/entities.jsonl", "graph/relationships.jsonl"))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST));

        verify(jobService, never()).enqueue(any(EnqueueRequest.class));
        try (Stream<Path> staged = Files.list(uploadDir)) {
            assertThat(staged)
                .as("rejected before the copy: no staged file and no custom_* staging directory")
                .isEmpty();
        }
    }

    @Test
    public void uploadAndEnqueueAcceptsTheKindsItServes() {
        ArgumentCaptor<EnqueueRequest> captor = ArgumentCaptor.forClass(EnqueueRequest.class);
        when(collectionService.getCollection("OIM")).thenReturn(Optional.empty());
        when(jobService.enqueue(captor.capture()))
            .thenReturn(EnqueueResult.created(UUID.randomUUID()));

        for (String kind : new String[] {"standard", "hierarchical", "json-import"}) {
            MockMultipartFile file =
                new MockMultipartFile("file", "x.md", "text/markdown", "d".getBytes());
            assertThat(ingestService.uploadAndEnqueue(file, "OIM", null, kind, null, null))
                .isNotNull();
        }

        assertThat(captor.getAllValues()).extracting(EnqueueRequest::kind)
            .containsExactly("standard", "hierarchical", "json-import");
    }
}
