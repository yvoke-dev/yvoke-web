package de.palsoftware.yvoke.shared.jobengine.service;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.annotation.DirtiesContext;
import de.palsoftware.yvoke.shared.jobengine.ItTestJobHandler;
import de.palsoftware.yvoke.shared.jobengine.WorkerConfig;
import de.palsoftware.yvoke.shared.jobengine.model.EnqueueRequest;
import de.palsoftware.yvoke.shared.jobengine.model.IngestionJob;
import de.palsoftware.yvoke.shared.jobengine.model.JobStatus;
import de.palsoftware.yvoke.shared.jobengine.repository.JobRepository;


@SpringBootTest(properties = {"app.worker.enabled=true", "app.worker.concurrency=4",
    "app.worker.poll-interval=200ms", "spring.flyway.enabled=true",
    "spring.flyway.locations=filesystem:docker/db/migration"})
@DirtiesContext
public class JobWorkerIT {

    private static final String COLLECTION = "OIM-WORKER-TEST";
    private UUID collectionId;

    @Autowired
    private JobService jobService;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JobWorker jobWorker;

    @Autowired
    @Qualifier(WorkerConfig.JOB_EXECUTOR_BEAN)
    private ThreadPoolTaskExecutor jobExecutor;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    public void setUp() {
        cleanup();
        collectionId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO collections (id, name, description, tags) VALUES (?, ?, ?, ?)",
            collectionId, COLLECTION, "Test collection", new String[] {"1.0"});
    }

    @AfterEach
    public void tearDown() {
        cleanup();
    }

    private void cleanup() {
        jdbcTemplate.update("DELETE FROM ingestion_jobs");
        jdbcTemplate.update("DELETE FROM collections WHERE name = ?", COLLECTION);
    }

    private UUID enqueueItTest() {
        return jobService.enqueue(new EnqueueRequest(ItTestJobHandler.KIND,
            "ref-" + UUID.randomUUID(), "1.0", COLLECTION)).jobId();
    }

    @Test
    public void executorIsBoundedToConfiguredConcurrency() {
        // Property 2: the worker pool can never run more than the configured number of jobs at
        // once.
        assertThat(jobExecutor.getMaxPoolSize()).isEqualTo(4);
        assertThat(jobExecutor.getCorePoolSize()).isEqualTo(4);
    }

    @Test
    public void itTestJobsRunToCompletion() {
        int n = 6; // > concurrency, so the queue must drain over several ticks
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ids.add(enqueueItTest());
        }

        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            for (UUID id : ids) {
                IngestionJob job = jobRepository.findById(id).orElseThrow();
                assertThat(job.status()).isEqualTo(JobStatus.COMPLETED);
                assertThat(job.progress()).isEqualTo(100);
                assertThat(job.counts()).isNotNull();
                assertThat(job.counts().entities()).isPositive();
            }
        });
    }

    @Test
    public void recoverySweepRequeuesOrphanThenCompletes() {
        // Simulate a job left running by a crashed process.
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            """
                INSERT INTO ingestion_jobs
                    (id, kind, source_ref, tags, collection_id, status, progress, attempts, step, started_at, created_at)
                VALUES (?, 'it_test', ?, ARRAY['1.0']::text[], ?, 'running', 50, 1, 'embed', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
            id, "ref-" + id, collectionId);

        jobWorker.recoverOrphanedJobs();

        // After recovery the job is retriable and the poll loop eventually completes it.
        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            IngestionJob job = jobRepository.findById(id).orElseThrow();
            assertThat(job.status()).isEqualTo(JobStatus.COMPLETED);
            // attempts preserved across recovery (1 from the orphan + 1 from the retry claim).
            assertThat(job.attempts()).isGreaterThanOrEqualTo(2);
        });
    }
}
