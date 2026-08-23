package de.palsoftware.yvoke.ingest.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import de.palsoftware.yvoke.ingest.core.service.IngestPrompts;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import de.palsoftware.yvoke.ingest.core.model.IngestJobKind;
import de.palsoftware.yvoke.kg.core.model.KgExtractionResult;
import de.palsoftware.yvoke.kg.core.model.KgExtractionResult.ExtractedEntity;
import de.palsoftware.yvoke.kg.core.model.KgExtractionResult.ExtractedRelationship;
import de.palsoftware.yvoke.kg.core.service.DocumentKgExtractor;
import de.palsoftware.yvoke.rag.retrieval.EmbeddingService;
import de.palsoftware.yvoke.shared.jobengine.model.EnqueueRequest;
import de.palsoftware.yvoke.shared.jobengine.model.IngestionJob;
import de.palsoftware.yvoke.shared.jobengine.model.JobStatus;
import de.palsoftware.yvoke.shared.jobengine.repository.JobRepository;
import de.palsoftware.yvoke.shared.jobengine.service.JobService;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;


@SpringBootTest(properties = {"spring.flyway.enabled=true",
    "spring.flyway.locations=filesystem:docker/db/migration", "app.worker.enabled=true",
    "app.worker.concurrency=2", "app.worker.poll-interval=200ms",
    // UploadPathGuard confines file-backed sourceRefs to app.upload-dir; @TempDir lives under
    // tmpdir.
    "app.upload-dir=${java.io.tmpdir}"})
@DirtiesContext
public class StandardDocumentJobHandlerIT {

    private static final String COLLECTION = "OIM-MANUAL-HANDLER-TEST";
    private static final String VERSION = "9.3";

    private static final String MARKDOWN = """
        # OIM Authentication Guide

        ## Introduction
        This manual explains authentication in One Identity Manager.

        ## OAuth
        OAuth is a token-based authentication module.
        """;

    @Autowired
    private JobService jobService;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private EmbeddingService embeddingService;

    @MockitoBean
    private DocumentKgExtractor kgExtractor;

    @TempDir
    Path tempDir;

    private Path manualPath;

    @BeforeEach
    public void setUp() throws Exception {
        cleanup();
        UUID collId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO collections (id, name, description, tags) VALUES (?, ?, ?, ?)", collId,
            COLLECTION, "Test collection", new String[] {VERSION});
        // Prompts are required job settings now, so the rows the jobs name must exist -- exactly
        // as in a real deployment, where an operator picks them from a list of registered prompts.
        jdbcTemplate.update("INSERT INTO system_prompts (name, type, system_prompt)"
            + " VALUES (?, ?, ?) ON CONFLICT (name) DO NOTHING", "it-kg", "KG",
            "Return STRICT JSON only.");
        jdbcTemplate.update("INSERT INTO system_prompts (name, type, system_prompt)"
            + " VALUES (?, ?, ?) ON CONFLICT (name) DO NOTHING", "it-summarize", "SUMMARIZE",
            "Write a concise summary.");

        manualPath = tempDir.resolve("auth_guide.md");
        Files.writeString(manualPath, MARKDOWN, StandardCharsets.UTF_8);

        when(embeddingService.embedBatch(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            List<float[]> out = new ArrayList<>(texts.size());
            for (int i = 0; i < texts.size(); i++) {
                out.add(new float[1024]);
            }
            return out;
        });
    }

    @AfterEach
    public void tearDown() {
        cleanup();
    }

    private void cleanup() {
        jdbcTemplate.update("DELETE FROM ingestion_jobs");
        jdbcTemplate.update("DELETE FROM collections WHERE name = ?", COLLECTION);
    }


    @Test
    public void manualJobRunsToCompletionWithCounts() {
        when(kgExtractor.extract(anyList(), any(), any(), any())).thenReturn(
            new KgExtractionResult(List.of(new ExtractedEntity("OAuth", "module", "token auth")),
                List.of(new ExtractedRelationship("OAuth", "part_of", "OIM", "")), 0));

        UUID id = jobService.enqueue(new EnqueueRequest(IngestJobKind.STANDARD.getValue(),
            manualPath.toString(), VERSION, COLLECTION)).jobId();

        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            IngestionJob job = jobRepository.findById(id).orElseThrow();
            if (job.status() == JobStatus.FAILED) {
                System.err.println("!!! JOB FAILED WITH ERROR: " + job.error());
            }
            assertThat(job.status()).isEqualTo(JobStatus.COMPLETED);
            assertThat(job.progress()).isEqualTo(100);
            assertThat(job.counts()).isNotNull();
            assertThat(job.counts().docs()).isEqualTo(1);
            assertThat(job.counts().chunks()).isPositive();
            assertThat(job.counts().entities()).isZero();
        });

        UUID documentId = jdbcTemplate.queryForObject(
            "SELECT d.id FROM documents d JOIN collections c ON d.collection_id = c.id WHERE c.name = ?",
            UUID.class, COLLECTION);

        UUID kgId = jobService.enqueue(new EnqueueRequest(IngestJobKind.KG_EXTRACT.getValue(),
            documentId.toString(), VERSION, COLLECTION,
            Map.of(IngestPrompts.SETTING_KG_PROMPT, "it-kg"))).jobId();

        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            IngestionJob job = jobRepository.findById(kgId).orElseThrow();
            if (job.status() == JobStatus.FAILED) {
                System.err.println("!!! KG JOB FAILED WITH ERROR: " + job.error());
            }
            assertThat(job.status()).isEqualTo(JobStatus.COMPLETED);
            assertThat(job.progress()).isEqualTo(100);
            assertThat(job.counts()).isNotNull();
            assertThat(job.counts().entities()).isPositive();
        });
    }

    /**
     * A zip's {@code documentGlob} MUST be matched against each entry's path RELATIVE to the
     * extraction directory, never against its absolute path on disk.
     *
     * <p>
     * The extraction directory is a fresh {@code standard_ingest_*} temp dir, so an absolute path
     * carries a random prefix no operator-authored glob can ever anticipate. Matching against it
     * makes every anchored glob match nothing at all — and the failure looks exactly like success:
     * {@code processZipFile} walks an empty file list, returns {@code JobCounts(0, 0, 0, 0, 0)},
     * and because {@code StandardDocumentJobHandler.expectsEntities} is false,
     * {@code JobService.validateCounts} finds nothing wrong and marks the job COMPLETED at 100%.
     * The operator gets a green job that ingested zero documents, with no error, no warning and no
     * failed step to inspect; the omission only surfaces later as a corpus that cannot answer
     * questions about content everyone believes was loaded.
     *
     * <p>
     * No existing test would notice. The other jobs in this class point at a single {@code .md}
     * file and never enter the zip branch at all, and a test using the DEFAULT glob would pass
     * either way: that pattern begins with a directory-spanning wildcard, so it matches an absolute
     * path just as happily as a relative one. Only a glob anchored at the archive root — the kind
     * an operator actually writes to select one folder out of a kit export — can tell the two
     * apart, which is why this fixture uses {@code docs/*.md} and puts a second document outside
     * that folder.
     *
     * <p>
     * Note the wait is on the job reaching a TERMINAL state, not on the assertion itself: the
     * regression does not fail the job, it completes it with zero documents, so an
     * {@code untilAsserted} on the count would spend the whole 20s window re-reading a row that
     * settled in a second before finally timing out.
     */
    @Test
    public void documentGlobSelectsZipEntriesByTheirPathRelativeToTheExtractionDir()
        throws Exception {
        // Staged under @TempDir, i.e. under java.io.tmpdir, which this class pins as
        // app.upload-dir — UploadPathGuard rejects a sourceRef anywhere else at execution time.
        Path zip = tempDir.resolve("globbed.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("docs/a.md"));
            zos.write("""
                # Docs A

                ## Section A
                Body of the selected document.
                """.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("notes/b.md"));
            zos.write("""
                # Notes B

                ## Section B
                Body of the document the glob excludes.
                """.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        UUID id = jobService.enqueue(new EnqueueRequest(IngestJobKind.STANDARD.getValue(),
            zip.toString(), VERSION, COLLECTION, Map.of("documentGlob", "docs/*.md"))).jobId();

        Awaitility.await().atMost(Duration.ofSeconds(20))
            .until(() -> jobRepository.findById(id).orElseThrow().status().isTerminal());

        IngestionJob job = jobRepository.findById(id).orElseThrow();
        assertThat(job.status()).as("job error: %s", job.error()).isEqualTo(JobStatus.COMPLETED);
        assertThat(job.counts()).isNotNull();
        assertThat(job.counts().docs())
            .as("only docs/a.md matches 'docs/*.md' once relativized against the temp dir")
            .isEqualTo(1);
        assertThat(job.counts().chunks()).isPositive();

        List<String> sourceFiles = jdbcTemplate.queryForList("""
            SELECT d.metadata->>'source_file' FROM documents d
            JOIN collections c ON d.collection_id = c.id
            WHERE c.name = ?
            """, String.class, COLLECTION);
        assertThat(sourceFiles).containsExactly("docs/a.md");
    }

    @Test
    public void zeroEntityKgReprocessJobIsMarkedFailed() {
        UUID id = jobService.enqueue(new EnqueueRequest(IngestJobKind.STANDARD.getValue(),
            manualPath.toString(), VERSION, COLLECTION)).jobId();

        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            IngestionJob job = jobRepository.findById(id).orElseThrow();
            assertThat(job.status()).isEqualTo(JobStatus.COMPLETED);
        });

        UUID documentId = jdbcTemplate.queryForObject(
            "SELECT d.id FROM documents d JOIN collections c ON d.collection_id = c.id WHERE c.name = ?",
            UUID.class, COLLECTION);

        when(kgExtractor.extract(anyList(), any(), any(), any()))
            .thenReturn(new KgExtractionResult(List.of(), List.of(), 0));

        UUID kgId = jobService.enqueue(new EnqueueRequest(IngestJobKind.KG_EXTRACT.getValue(),
            documentId.toString(), VERSION, COLLECTION,
            Map.of(IngestPrompts.SETTING_KG_PROMPT, "it-kg"))).jobId();

        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            IngestionJob job = jobRepository.findById(kgId).orElseThrow();
            assertThat(job.status()).isEqualTo(JobStatus.FAILED);
        });
    }
}
