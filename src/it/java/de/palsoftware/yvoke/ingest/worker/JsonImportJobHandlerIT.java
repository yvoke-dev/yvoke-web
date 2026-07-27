package de.palsoftware.yvoke.ingest.worker;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
import de.palsoftware.yvoke.ingest.core.model.IngestJobKind;
import de.palsoftware.yvoke.shared.jobengine.model.EnqueueRequest;
import de.palsoftware.yvoke.shared.jobengine.model.IngestionJob;
import de.palsoftware.yvoke.shared.jobengine.model.JobStatus;
import de.palsoftware.yvoke.shared.jobengine.repository.JobRepository;
import de.palsoftware.yvoke.shared.jobengine.service.JobService;

@SpringBootTest(properties = {"spring.flyway.enabled=true",
    "spring.flyway.locations=filesystem:docker/db/migration", "app.worker.enabled=true",
    "app.worker.concurrency=1", "app.worker.poll-interval=200ms",
    "app.upload-dir=${java.io.tmpdir}"})
@DirtiesContext
public class JsonImportJobHandlerIT {

    private static final String COLLECTION = "OIM-JSON-HANDLER-TEST";
    private static final String VERSION = "1.0";

    private static final String JSON_ARRAY = """
        [
          {"name": "Alice", "role": "admin"},
          {"name": "Bob", "role": "user"}
        ]
        """;

    @Autowired
    private JobService jobService;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @TempDir
    Path tempDir;

    private Path jsonPath;

    @BeforeEach
    public void setUp() throws Exception {
        cleanup();
        UUID collId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO collections (id, name, description, tags) VALUES (?, ?, ?, ?)", collId,
            COLLECTION, "Test collection", new String[] {VERSION});

        jsonPath = tempDir.resolve("users.json");
        Files.writeString(jsonPath, JSON_ARRAY, StandardCharsets.UTF_8);
    }

    @AfterEach
    public void tearDown() {
        cleanup();
    }

    private void cleanup() {
        jdbcTemplate.update("DELETE FROM ingestion_jobs");
        jdbcTemplate.update("DELETE FROM json_objects");
        jdbcTemplate.update("DELETE FROM collections WHERE name = ?", COLLECTION);
    }

    @Test
    public void jsonImportRunsToCompletion() {
        UUID id = jobService.enqueue(new EnqueueRequest(IngestJobKind.JSON_IMPORT.getValue(),
            jsonPath.toString(), VERSION, COLLECTION)).jobId();

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            IngestionJob job = jobRepository.findById(id).orElseThrow();
            if (job.status() == JobStatus.FAILED) {
                System.err.println("!!! JOB FAILED WITH ERROR: " + job.error());
            }
            assertThat(job.status()).isEqualTo(JobStatus.COMPLETED);
            assertThat(job.progress()).isEqualTo(100);
        });

        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM json_objects j JOIN collections c ON j.collection_id = c.id WHERE c.name = ?",
            Integer.class, COLLECTION);

        assertThat(count).isEqualTo(2);
    }
}
