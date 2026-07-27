package de.palsoftware.yvoke.shared.jobengine.repository;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import de.palsoftware.yvoke.shared.jobengine.ItTestJobHandler;
import de.palsoftware.yvoke.shared.jobengine.model.EnqueueRequest;
import de.palsoftware.yvoke.shared.jobengine.model.EnqueueResult;
import de.palsoftware.yvoke.shared.jobengine.model.IngestionJob;
import de.palsoftware.yvoke.shared.jobengine.model.JobCounts;
import de.palsoftware.yvoke.shared.jobengine.model.JobStatus;
import de.palsoftware.yvoke.shared.jobengine.model.JobStep;
import de.palsoftware.yvoke.shared.jobengine.model.QueuedKindSummary;
import de.palsoftware.yvoke.shared.jobengine.service.JobService;


@SpringBootTest(properties = {"app.worker.enabled=false", "spring.flyway.enabled=true",
    "spring.flyway.locations=filesystem:docker/db/migration"})
public class JobRepositoryIT {

    private static final String COLLECTION = "OIM-JOBQ-TEST";
    private static final String OTHER_COLLECTION = "OIM-JOBQ-TEST-OTHER";
    /** A second kind, so kind-scoped operations can be shown NOT to touch their neighbours. */
    private static final String OTHER_KIND = ItTestJobHandler.KIND + "-other";

    @Autowired
    private JobRepository jobRepository;

    // claimNext's transaction now lives on JobService (Wave 3.5); exercise it through the same
    // transactional entry point the worker uses so the SKIP-LOCKED single-claim invariant holds.
    @Autowired
    private JobService jobService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    public void setUp() {
        cleanup();
        jdbcTemplate.update("DELETE FROM collections WHERE name = ?", OTHER_COLLECTION);
        // Collections are no longer auto-created by enqueue; the target must pre-exist.
        jdbcTemplate.update(
            "INSERT INTO collections (id, name) VALUES (?, ?) ON CONFLICT (name) DO NOTHING",
            UUID.randomUUID(), COLLECTION);
    }

    @AfterEach
    public void tearDown() {
        cleanup();
        jdbcTemplate.update("DELETE FROM collections WHERE name IN (?, ?)", COLLECTION,
            OTHER_COLLECTION);
    }

    private void cleanup() {
        jdbcTemplate.update("DELETE FROM ingestion_jobs");
    }

    private EnqueueRequest itTestRequest() {
        return new EnqueueRequest(ItTestJobHandler.KIND, "ref-" + UUID.randomUUID(), "1.0",
            COLLECTION);
    }

    @Test
    public void enqueueCreatesQueuedJob() {
        UUID id = jobRepository.enqueue(itTestRequest()).jobId();

        IngestionJob job = jobRepository.findById(id).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(job.progress()).isZero();
        assertThat(job.attempts()).isZero();
        assertThat(job.step()).isNull();
    }

    @Test
    public void claimNextTransitionsToRunningAndIncrementsAttempts() {
        UUID id = jobRepository.enqueue(itTestRequest()).jobId();

        IngestionJob claimed = jobService.claimNext().orElseThrow();

        assertThat(claimed.id()).isEqualTo(id);
        assertThat(claimed.status()).isEqualTo(JobStatus.RUNNING);
        assertThat(claimed.attempts()).isEqualTo(1);
        assertThat(claimed.startedAt()).isNotNull();
    }

    @Test
    public void claimNextReturnsEmptyWhenNoQueuedJobs() {
        // Enqueue one job and claim it (so it transitions to running)
        jobRepository.enqueue(itTestRequest()).jobId();
        jobService.claimNext().orElseThrow();

        // Only a running job present — nothing to claim.
        assertThat(jobService.claimNext()).isEmpty();
    }

    @Test
    public void singleClaimInvariantUnderConcurrency() throws Exception {
        int jobCount = 6;
        for (int i = 0; i < jobCount; i++) {
            jobRepository.enqueue(itTestRequest()).jobId();
        }

        int workers = 4;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(workers);
        List<java.util.concurrent.Future<List<UUID>>> futures = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            futures.add(pool.submit(() -> {
                List<UUID> claimed = new ArrayList<>();
                Optional<IngestionJob> job;
                while ((job = jobService.claimNext()).isPresent()) {
                    claimed.add(job.get().id());
                }
                return claimed;
            }));
        }

        List<UUID> allClaimed = new ArrayList<>();
        for (var f : futures) {
            allClaimed.addAll(f.get());
        }
        pool.shutdown();

        // Every queued job claimed exactly once (no duplicates, none lost).
        assertThat(allClaimed).hasSize(jobCount);
        assertThat(allClaimed).doesNotHaveDuplicates();
    }

    @Test
    public void markCompletedPersistsCountsAndTerminalState() {
        UUID id = jobRepository.enqueue(itTestRequest()).jobId();
        jobService.claimNext();

        jobRepository.markCompleted(id, new JobCounts(1, 8, 5, 7, 10));

        IngestionJob job = jobRepository.findById(id).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.COMPLETED);
        assertThat(job.progress()).isEqualTo(100);
        assertThat(job.counts()).isEqualTo(new JobCounts(1, 8, 5, 7, 10));
        assertThat(job.finishedAt()).isNotNull();
    }

    /**
     * Graph output a handler could not persist is part of the terminal result (V4), so a lossy run
     * is visible on the job instead of only in a WARN.
     */
    @Test
    public void markCompletedPersistsSkippedGraphCounts() {
        UUID id = jobRepository.enqueue(itTestRequest()).jobId();
        jobService.claimNext();

        jobRepository.markCompleted(id, new JobCounts(1, 8, 5, 7, 0, 3, 4));

        JobCounts counts = jobRepository.findById(id).orElseThrow().counts();
        assertThat(counts.skippedEntities()).isEqualTo(3);
        assertThat(counts.skippedEdges()).isEqualTo(4);
    }

    @Test
    public void markFailedRecordsError() {
        UUID id = jobRepository.enqueue(itTestRequest()).jobId();
        jobService.claimNext();

        jobRepository.markFailed(id, "boom");

        IngestionJob job = jobRepository.findById(id).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.FAILED);
        assertThat(job.error()).isEqualTo("boom");
        assertThat(job.finishedAt()).isNotNull();
    }

    @Test
    public void updateProgressPersistsStepAndProgress() {
        UUID id = jobRepository.enqueue(itTestRequest()).jobId();
        jobService.claimNext();

        jobRepository.updateProgress(id, JobStep.EMBED, 40);

        IngestionJob job = jobRepository.findById(id).orElseThrow();
        assertThat(job.step()).isEqualTo(JobStep.EMBED);
        assertThat(job.progress()).isEqualTo(40);
    }

    @Test
    public void requeueOrphansReturnsRunningJobsToQueued() {
        UUID id = jobRepository.enqueue(itTestRequest()).jobId();
        IngestionJob claimed = jobService.claimNext().orElseThrow();
        assertThat(claimed.status()).isEqualTo(JobStatus.RUNNING);

        int requeued = jobRepository.requeueOrphans();
        assertThat(requeued).isGreaterThanOrEqualTo(1);

        IngestionJob job = jobRepository.findById(id).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(job.step()).isNull();
        assertThat(job.startedAt()).isNull();
        // attempts is preserved so a retried job is distinguishable from a first attempt.
        assertThat(job.attempts()).isEqualTo(1);
    }

    // ---------------------------------------------------------------------
    // Wave 3b: admission control (ux_ingestion_jobs_active_work) and cancellation.
    // ---------------------------------------------------------------------

    /**
     * One ACTIVE job per unit of work. The duplicate adopts the queued job rather than throwing —
     * throwing would abort the Confluence crawl that enqueues inside its batch consumer.
     */
    @Test
    public void enqueueOfWorkAlreadyQueuedAdoptsTheActiveJob() {
        EnqueueRequest req = new EnqueueRequest(ItTestJobHandler.KIND, "same-ref", "1.0",
            COLLECTION);

        EnqueueResult first = jobRepository.enqueue(req);
        EnqueueResult second = jobRepository.enqueue(req);

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.jobId()).isEqualTo(first.jobId());
        assertThat(countJobs()).isEqualTo(1);
    }

    /** The slot is held while the job RUNS too, not only while it waits. */
    @Test
    public void enqueueOfWorkAlreadyRunningAdoptsTheRunningJob() {
        EnqueueRequest req = new EnqueueRequest(ItTestJobHandler.KIND, "same-ref", "1.0",
            COLLECTION);
        UUID first = jobRepository.enqueue(req).jobId();
        jobService.claimNext().orElseThrow();

        EnqueueResult second = jobRepository.enqueue(req);

        assertThat(second).isEqualTo(new EnqueueResult(first, false));
        assertThat(countJobs()).isEqualTo(1);
    }

    /** History must never block new work: the index is partial on the active statuses. */
    @Test
    public void enqueueAfterTheEarlierJobFinishedCreatesANewJob() {
        EnqueueRequest req = new EnqueueRequest(ItTestJobHandler.KIND, "same-ref", "1.0",
            COLLECTION);
        UUID first = jobRepository.enqueue(req).jobId();
        jobService.claimNext();
        jobRepository.markCompleted(first, new JobCounts(1, 1, 0, 0, 0));

        EnqueueResult second = jobRepository.enqueue(req);

        assertThat(second.created()).isTrue();
        assertThat(second.jobId()).isNotEqualTo(first);
    }

    /**
     * The key is all FOUR columns. A confluence-page-import's source_ref carries neither collection
     * nor tag, so keying on (kind, source_ref) alone would make two connector instances importing
     * one space into different collections block each other.
     */
    @Test
    public void sameKindAndSourceRefIntoADifferentTargetIsSeparateWork() {
        UUID otherCollection = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO collections (id, name) VALUES (?, ?)", otherCollection,
            OTHER_COLLECTION);

        EnqueueResult intoFirst = jobRepository
            .enqueue(new EnqueueRequest(ItTestJobHandler.KIND, "shared-ref", "1.0", COLLECTION));
        EnqueueResult intoSecond = jobRepository.enqueue(
            new EnqueueRequest(ItTestJobHandler.KIND, "shared-ref", "1.0", OTHER_COLLECTION));
        EnqueueResult otherTag = jobRepository
            .enqueue(new EnqueueRequest(ItTestJobHandler.KIND, "shared-ref", "2.0", COLLECTION));

        assertThat(intoFirst.created()).isTrue();
        assertThat(intoSecond.created()).isTrue();
        assertThat(otherTag.created()).isTrue();
        assertThat(countJobs()).isEqualTo(3);
    }

    @Test
    public void stopJobRecordsCancelledRatherThanFailed() {
        UUID id = jobRepository.enqueue(itTestRequest()).jobId();
        jobService.claimNext();

        assertThat(jobRepository.stopJob(id)).isEqualTo(1);

        IngestionJob job = jobRepository.findById(id).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.CANCELLED);
        assertThat(job.status().isTerminal()).isTrue();
        assertThat(job.error()).isEqualTo("Stopped by administrator");
        assertThat(job.finishedAt()).isNotNull();
    }

    /**
     * Cancelling a running job is cooperative: the handler notices and throws, and JobService turns
     * that into markFailed. If that overwrote 'cancelled', every operator stop would read as a
     * failure and the distinction would exist only in the error text.
     */
    @Test
    public void markFailedDoesNotOverwriteAnOperatorCancellation() {
        UUID id = jobRepository.enqueue(itTestRequest()).jobId();
        jobService.claimNext();
        jobRepository.stopJob(id);

        jobRepository.markFailed(id, "Job was cancelled by administrator");

        IngestionJob job = jobRepository.findById(id).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.CANCELLED);
        assertThat(job.error()).isEqualTo("Stopped by administrator");
    }

    /**
     * The SUCCESS side of the same guard. A stopped Confluence crawl keeps running until its next
     * checkpoint; if it reaches the end anyway, markCompleted must not resurrect it — the row would
     * flip from 'cancelled' to 'completed' after the UI had already closed its SSE stream on the
     * cancellation, and the operator would be told the work they stopped had succeeded.
     */
    @Test
    public void markCompletedDoesNotResurrectACancelledJob() {
        UUID id = jobRepository.enqueue(itTestRequest()).jobId();
        jobService.claimNext();
        jobRepository.stopJob(id);

        jobRepository.markCompleted(id, new JobCounts(3, 9, 0, 0, 0));

        IngestionJob job = jobRepository.findById(id).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.CANCELLED);
        assertThat(job.error()).isEqualTo("Stopped by administrator");
        // Nothing from the completion was written: no counts (doc_count stays NULL), no 100%.
        assertThat(job.counts()).isNull();
        assertThat(job.progress()).isNotEqualTo(100);
    }

    /**
     * The PROGRESS side of the same guard. The handler keeps running to its next checkpoint after
     * the stop, so it reports once more; without the guard that tick writes progress=100 and a
     * final step onto the cancelled row, and the job detail page shows a full green bar and "insert"
     * next to a CANCELLED status.
     */
    @Test
    public void updateProgressDoesNotWriteOntoACancelledJob() {
        UUID id = jobRepository.enqueue(itTestRequest()).jobId();
        jobService.claimNext();
        jobRepository.updateProgress(id, JobStep.CHUNK, 30);
        jobRepository.stopJob(id);

        jobRepository.updateProgress(id, JobStep.INSERT, 100);

        IngestionJob job = jobRepository.findById(id).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.CANCELLED);
        // Frozen at the last tick before the stop: neither the step nor the bar moved.
        assertThat(job.step()).isEqualTo(JobStep.CHUNK);
        assertThat(job.progress()).isEqualTo(30);
    }

    /** A cancelled job frees the admission slot, so the same work can be re-enqueued at once. */
    @Test
    public void cancellingAJobReleasesItsAdmissionSlot() {
        EnqueueRequest req = new EnqueueRequest(ItTestJobHandler.KIND, "same-ref", "1.0",
            COLLECTION);
        UUID first = jobRepository.enqueue(req).jobId();
        jobRepository.stopJob(first);

        EnqueueResult second = jobRepository.enqueue(req);

        assertThat(second.created()).isTrue();
        assertThat(second.jobId()).isNotEqualTo(first);
    }

    @Test
    public void cancelQueuedCancelsEveryQueuedJobOfAKindAndLeavesRunningOnesAlone() {
        UUID running = jobRepository
            .enqueue(new EnqueueRequest(ItTestJobHandler.KIND, "ref-running", "1.0", COLLECTION))
            .jobId();
        jobService.claimNext().orElseThrow();
        UUID queuedA = jobRepository
            .enqueue(new EnqueueRequest(ItTestJobHandler.KIND, "ref-a", "1.0", COLLECTION)).jobId();
        UUID queuedB = jobRepository
            .enqueue(new EnqueueRequest(ItTestJobHandler.KIND, "ref-b", "1.0", COLLECTION)).jobId();
        UUID otherKind = jobRepository
            .enqueue(new EnqueueRequest(OTHER_KIND, "ref-c", "1.0", COLLECTION)).jobId();

        assertThat(jobRepository.cancelQueued(ItTestJobHandler.KIND)).hasSize(2);

        assertThat(statusOf(queuedA)).isEqualTo(JobStatus.CANCELLED);
        assertThat(statusOf(queuedB)).isEqualTo(JobStatus.CANCELLED);
        assertThat(jobRepository.findById(queuedA).orElseThrow().error())
            .isEqualTo("Cancelled by administrator");
        assertThat(statusOf(running)).isEqualTo(JobStatus.RUNNING);
        assertThat(statusOf(otherKind)).isEqualTo(JobStatus.QUEUED);
    }

    /**
     * A bare kind sweeps every instance; a qualified one sweeps exactly that instance, which is what
     * makes "cancel this connector's backlog" possible without touching the other connector's.
     */
    @Test
    public void cancelQueuedIsInstanceScopedWhenTheKindIsQualified() {
        UUID wiki = jobRepository
            .enqueue(new EnqueueRequest(ItTestJobHandler.KIND + ":wiki", "ref-a", "1.0", COLLECTION))
            .jobId();
        UUID docs = jobRepository
            .enqueue(new EnqueueRequest(ItTestJobHandler.KIND + ":docs", "ref-b", "1.0", COLLECTION))
            .jobId();

        assertThat(jobRepository.cancelQueued(ItTestJobHandler.KIND + ":wiki")).hasSize(1);
        assertThat(statusOf(wiki)).isEqualTo(JobStatus.CANCELLED);
        assertThat(statusOf(docs)).isEqualTo(JobStatus.QUEUED);

        // The bare kind then covers the remaining instance.
        assertThat(jobRepository.cancelQueued(ItTestJobHandler.KIND)).hasSize(1);
        assertThat(statusOf(docs)).isEqualTo(JobStatus.CANCELLED);
    }

    /**
     * The sweep a kind cannot express: jobs enqueued under an instance's OLD slug still carry its
     * id in their settings, and that is what deleting the instance has to cancel by. Exercised
     * against a real Postgres because the whole thing hinges on the JSONB {@code ->>} operator
     * taking a BOUND key, which no mock can verify.
     */
    @Test
    public void cancelQueuedBySettingSweepsJobsWhoseKindNoLongerNamesTheInstance() {
        String instanceId = UUID.randomUUID().toString();
        // Queued under the old slug, but carrying the instance id.
        UUID renamed = jobRepository.enqueue(new EnqueueRequest(ItTestJobHandler.KIND + ":old-slug",
            "ref-a", "1.0", COLLECTION, Map.of("instanceId", instanceId))).jobId();
        // Same instance, current slug.
        UUID current = jobRepository.enqueue(new EnqueueRequest(ItTestJobHandler.KIND + ":new-slug",
            "ref-b", "1.0", COLLECTION, Map.of("instanceId", instanceId))).jobId();
        // A different instance, and a job with no settings at all — neither may be touched.
        UUID otherInstance = jobRepository.enqueue(new EnqueueRequest(
            ItTestJobHandler.KIND + ":other", "ref-c", "1.0", COLLECTION,
            Map.of("instanceId", UUID.randomUUID().toString()))).jobId();
        UUID noSettings = jobRepository
            .enqueue(new EnqueueRequest(ItTestJobHandler.KIND, "ref-d", "1.0", COLLECTION)).jobId();

        assertThat(jobRepository.cancelQueuedBySetting("instanceId", instanceId)).hasSize(2);

        assertThat(statusOf(renamed)).isEqualTo(JobStatus.CANCELLED);
        assertThat(statusOf(current)).isEqualTo(JobStatus.CANCELLED);
        assertThat(statusOf(otherInstance)).isEqualTo(JobStatus.QUEUED);
        assertThat(statusOf(noSettings)).isEqualTo(JobStatus.QUEUED);
    }

    /** Running jobs are mid-write and stop cooperatively, so a settings sweep must not touch them. */
    @Test
    public void cancelQueuedBySettingLeavesRunningJobsAlone() {
        String instanceId = UUID.randomUUID().toString();
        UUID running = jobRepository.enqueue(new EnqueueRequest(ItTestJobHandler.KIND,
            "ref-running", "1.0", COLLECTION, Map.of("instanceId", instanceId))).jobId();
        jobService.claimNext().orElseThrow();

        assertThat(jobRepository.cancelQueuedBySetting("instanceId", instanceId)).isEmpty();
        assertThat(statusOf(running)).isEqualTo(JobStatus.RUNNING);
    }

    @Test
    public void listQueuedKindsCountsOnlyQueuedWork() {
        // Enqueued and claimed FIRST, so claimNext (oldest queued wins) takes this one and not one
        // of the jobs the assertion counts.
        UUID running = jobRepository
            .enqueue(new EnqueueRequest(OTHER_KIND, "ref-c", "1.0", COLLECTION)).jobId();
        jobService.claimNext().orElseThrow();
        jobRepository.enqueue(new EnqueueRequest(ItTestJobHandler.KIND, "ref-a", "1.0", COLLECTION));
        jobRepository.enqueue(new EnqueueRequest(ItTestJobHandler.KIND, "ref-b", "1.0", COLLECTION));

        assertThat(jobRepository.listQueuedKinds())
            .containsExactly(new QueuedKindSummary(ItTestJobHandler.KIND, 2L));
        assertThat(statusOf(running)).isEqualTo(JobStatus.RUNNING);
    }

    @Test
    public void findStatusByIdSeesTheLiveStatusAndEmptyForAMissingRow() {
        UUID id = jobRepository.enqueue(itTestRequest()).jobId();
        assertThat(jobRepository.findStatusById(id)).contains(JobStatus.QUEUED);

        jobRepository.stopJob(id);
        assertThat(jobRepository.findStatusById(id)).contains(JobStatus.CANCELLED);
        assertThat(jobRepository.findStatusById(UUID.randomUUID())).isEmpty();
    }

    private JobStatus statusOf(UUID id) {
        return jobRepository.findById(id).orElseThrow().status();
    }

    private int countJobs() {
        Integer n = jdbcTemplate.queryForObject("SELECT count(*) FROM ingestion_jobs",
            Integer.class);
        return n == null ? 0 : n;
    }

    @Test
    public void enqueueAllowsParameterizedJobKinds() {
        EnqueueRequest req = new EnqueueRequest(ItTestJobHandler.KIND + ":no-kg",
            "ref-" + UUID.randomUUID(), "1.0", COLLECTION);
        UUID id = jobRepository.enqueue(req).jobId();

        IngestionJob job = jobRepository.findById(id).orElseThrow();
        assertThat(job.kind()).isEqualTo(ItTestJobHandler.KIND + ":no-kg");
    }
}
