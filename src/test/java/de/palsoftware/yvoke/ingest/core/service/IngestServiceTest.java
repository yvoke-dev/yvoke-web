package de.palsoftware.yvoke.ingest.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
     * SEC-16: the upload path must also confine a traversal filename. A UUID prefix alone does NOT
     * neutralize {@code ../../..} — the staged file must stay inside the upload directory.
     */
    @Test
    public void uploadAndEnqueueConfinesTraversalFilenameToUploadDir() throws Exception {
        ArgumentCaptor<EnqueueRequest> captor = ArgumentCaptor.forClass(EnqueueRequest.class);
        when(collectionService.getCollection("OIM")).thenReturn(Optional.empty());
        when(jobService.enqueue(captor.capture()))
            .thenReturn(EnqueueResult.created(UUID.randomUUID()));

        MockMultipartFile file =
            new MockMultipartFile("file", "../../../evil.md", "text/markdown", "data".getBytes());

        UUID jobId = ingestService.uploadAndEnqueue(file, "OIM", null, "standard", null);

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
            assertThatThrownBy(() -> ingestService.uploadAndEnqueue(file, "OIM", null, kind, null))
                .as("kind=%s", kind).isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
        }
        verify(jobService, never()).enqueue(any());
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
            assertThat(ingestService.uploadAndEnqueue(file, "OIM", null, kind, null)).isNotNull();
        }

        assertThat(captor.getAllValues()).extracting(EnqueueRequest::kind)
            .containsExactly("standard", "hierarchical", "json-import");
    }
}
