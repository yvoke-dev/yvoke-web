package de.palsoftware.yvoke.ingest.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
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
        when(kgExtractor.extract(anyList(), any(), any())).thenReturn(
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
            documentId.toString(), VERSION, COLLECTION)).jobId();

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

        when(kgExtractor.extract(anyList(), any(), any()))
            .thenReturn(new KgExtractionResult(List.of(), List.of(), 0));

        UUID kgId = jobService.enqueue(new EnqueueRequest(IngestJobKind.KG_EXTRACT.getValue(),
            documentId.toString(), VERSION, COLLECTION)).jobId();

        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            IngestionJob job = jobRepository.findById(kgId).orElseThrow();
            assertThat(job.status()).isEqualTo(JobStatus.FAILED);
        });
    }
}
