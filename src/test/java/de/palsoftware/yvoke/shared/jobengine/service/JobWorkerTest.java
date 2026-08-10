package de.palsoftware.yvoke.shared.jobengine.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;

import de.palsoftware.yvoke.shared.jobengine.WorkerProperties;
import de.palsoftware.yvoke.shared.jobengine.model.IngestionJob;
import de.palsoftware.yvoke.shared.jobengine.model.JobStatus;
import de.palsoftware.yvoke.shared.jobengine.repository.JobRepository;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

class JobWorkerTest {

    private static IngestionJob job(UUID id) {
        return new IngestionJob(id, "standard", "ref", "1.0", UUID.randomUUID(), "COL",
            JobStatus.RUNNING, null, 0, 0, null, null, null, null, null);
    }

    /**
     * S7.3: {@code app.worker.concurrency} is enforced ONLY by {@code poll()}'s in-memory
     * {@code activeCount < concurrency()} gate — the executor behind it is built with
     * {@code queueCapacity=0}, so it cannot absorb a surplus, it can only reject one. Lose or
     * loosen the gate (an off-by-one to {@code <=}, or "the executor will queue it anyway") and a
     * single tick claims every queued row: the pool takes {@code concurrency} of them and throws
     * {@code RejectedExecutionException} for the rest, {@code dispatch} re-queues those, and the
     * next tick claims them again — a churn loop that rewrites {@code ingestion_jobs} rows
     * continuously while the throughput protecting the embedding provider silently stops meaning
     * anything.
     *
     * <p>
     * The mock executor deliberately never runs the submitted task, which is precisely the state a
     * real worker is in while jobs are executing: {@code activeCount} is only decremented by the
     * task itself. The second {@code poll()} is the other half of the rule — the counter is a
     * field, not per-tick state, so a tick arriving while the pool is full must claim nothing at
     * all. {@code claimNext} is stubbed with more jobs than the cap so an ungated loop terminates
     * and FAILS rather than hanging the suite.
     *
     * <p>
     * Nothing covers this today: {@code JobWorkerIT} asserts only the pool's core/max size, and
     * every other test in this file supplies exactly one claimable job, so the loop condition is
     * evaluated once and its bound is never reached.
     */
    @Test
    void onePollTickNeverClaimsMoreJobsThanTheConfiguredConcurrency() {
        JobRepository jobRepository = mock(JobRepository.class);
        JobService jobService = mock(JobService.class);
        TaskExecutor jobExecutor = mock(TaskExecutor.class);
        when(jobService.claimNext()).thenReturn(Optional.of(job(UUID.randomUUID())),
            Optional.of(job(UUID.randomUUID())), Optional.of(job(UUID.randomUUID())),
            Optional.of(job(UUID.randomUUID())), Optional.empty());

        JobWorker worker = new JobWorker(jobRepository, jobService, jobExecutor,
            new WorkerProperties(true, 2, Duration.ofSeconds(2)));
        worker.poll();
        worker.poll();

        verify(jobService, times(2)).claimNext();
        verify(jobExecutor, times(2)).execute(any(Runnable.class));
        verify(jobRepository, never()).requeueJob(any());
    }

    private static WorkerProperties enabled() {
        return new WorkerProperties(true, 4, Duration.ofSeconds(2));
    }

    /**
     * A rejected executor submission must undo only ITS OWN reservation. The obvious-looking
     * {@code requeueOrphans()} has no id predicate — it is {@code WHERE status = 'running'} — so
     * calling it here flips every job currently executing on another worker thread back to
     * {@code queued} while those threads are still running, and the very next poll tick claims
     * those rows again. That is two workers on one job, which the whole claim protocol
     * ({@code FOR UPDATE SKIP LOCKED}) exists to prevent, and it corrupts the other jobs rather
     * than the one that actually failed to start.
     */
    @Test
    void aRejectedSubmissionRequeuesOnlyTheRejectedJobAndLeavesOtherRunningJobsAlone() {
        JobRepository jobRepository = mock(JobRepository.class);
        JobService jobService = mock(JobService.class);
        TaskExecutor jobExecutor = mock(TaskExecutor.class);
        UUID rejectedId = UUID.randomUUID();

        when(jobService.claimNext()).thenReturn(Optional.of(job(rejectedId)), Optional.empty());
        doThrow(new RejectedExecutionException("executor is shutting down")).when(jobExecutor)
            .execute(any(Runnable.class));

        new JobWorker(jobRepository, jobService, jobExecutor, enabled()).poll();

        verify(jobRepository, times(1)).requeueJob(rejectedId);
        verify(jobRepository, never()).requeueOrphans();
    }

    /**
     * The startup recovery sweep is the one place a blanket re-queue IS correct: nothing is running
     * yet, so every {@code running} row is genuinely orphaned by the previous process.
     */
    @Test
    void theStartupSweepStillUsesTheBlanketRequeue() {
        JobRepository jobRepository = mock(JobRepository.class);
        when(jobRepository.requeueOrphans()).thenReturn(3);

        new JobWorker(jobRepository, mock(JobService.class), mock(TaskExecutor.class), enabled())
            .recoverOrphanedJobs();

        verify(jobRepository, times(1)).requeueOrphans();
    }

    /**
     * {@code app.worker.concurrency <= 0} is coerced to 4 by the record's compact constructor, and
     * a missing/zero/negative {@code poll-interval} to 2s. Nothing else in the codebase re-checks
     * either value: {@code WorkerConfig} feeds {@code concurrency()} straight into
     * {@code setCorePoolSize}/{@code setMaxPoolSize} on a pool whose {@code queueCapacity} is 0,
     * and {@code JobWorker.poll} loops while {@code activeCount < concurrency()}.
     *
     * <p>
     * So losing the coercion is not a config nit, it is a silent full stop: with
     * {@code concurrency=0} the poll loop's condition is {@code 0 < 0}, nothing is ever claimed,
     * nothing is ever dispatched, no exception is thrown and no job ever completes — an operator
     * sees a queue that simply stops draining. (Even if a job were claimed, the pool would reject
     * every submission and {@code requeueJob} would put it straight back.) There is no
     * {@code WorkerProperties} test anywhere; every test in this file constructs
     * {@code new WorkerProperties(true, 4, Duration.ofSeconds(2))}, which cannot exercise the
     * coercion at all, so the assertion below is on the OUTCOME — a worker configured with 0 still
     * dispatches — not merely on the record's accessor.
     */
    @Test
    void aNonPositiveWorkerConcurrencyIsCoercedToFourRatherThanBuildingAPoolThatAcceptsNothing() {
        WorkerProperties zero = new WorkerProperties(true, 0, null);
        assertEquals(4, zero.concurrency(), "concurrency=0 must be coerced to the shipped default");
        assertEquals(Duration.ofSeconds(2), zero.pollInterval(),
            "a missing poll-interval must fall back to 2s, or @Scheduled is invalid at startup");

        WorkerProperties negative = new WorkerProperties(true, -1, Duration.ZERO);
        assertEquals(4, negative.concurrency(), "a negative concurrency must be coerced too");
        assertEquals(Duration.ofSeconds(2), negative.pollInterval(),
            "a zero poll-interval must fall back to 2s");
        assertEquals(Duration.ofSeconds(2),
            new WorkerProperties(true, 1, Duration.ofSeconds(-5)).pollInterval(),
            "a negative poll-interval must fall back to 2s");

        // The outcome that actually matters: a worker built from `app.worker.concurrency=0` must
        // still claim and dispatch. Uncoerced, poll() exits on `0 < 0` before claimNext() is ever
        // called and the queue stalls forever with nothing logged.
        JobRepository jobRepository = mock(JobRepository.class);
        JobService jobService = mock(JobService.class);
        TaskExecutor jobExecutor = mock(TaskExecutor.class);
        when(jobService.claimNext()).thenReturn(Optional.of(job(UUID.randomUUID())),
            Optional.empty());

        new JobWorker(jobRepository, jobService, jobExecutor, zero).poll();

        verify(jobExecutor, times(1)).execute(any(Runnable.class));
        verify(jobRepository, never()).requeueJob(any());
    }

    /** {@code app.worker.enabled=false} must disable both the poll loop and the recovery sweep. */
    @Test
    void aDisabledWorkerNeitherPollsNorRecovers() {
        JobRepository jobRepository = mock(JobRepository.class);
        JobService jobService = mock(JobService.class);
        WorkerProperties disabled = new WorkerProperties(false, 4, Duration.ofSeconds(2));

        JobWorker worker =
            new JobWorker(jobRepository, jobService, mock(TaskExecutor.class), disabled);
        worker.poll();
        worker.recoverOrphanedJobs();

        verify(jobService, never()).claimNext();
        verify(jobRepository, never()).requeueOrphans();
        verify(jobRepository, never()).requeueJob(any());
    }
}
