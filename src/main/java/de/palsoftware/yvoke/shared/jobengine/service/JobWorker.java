package de.palsoftware.yvoke.shared.jobengine.service;

import de.palsoftware.yvoke.shared.jobengine.*;
import de.palsoftware.yvoke.shared.jobengine.model.*;
import de.palsoftware.yvoke.shared.jobengine.repository.*;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class JobWorker {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    private final JobRepository jobRepository;
    private final JobService jobService;
    private final TaskExecutor jobExecutor;
    private final WorkerProperties properties;
    private final AtomicInteger activeCount = new AtomicInteger(0);

    public JobWorker(JobRepository jobRepository, JobService jobService,
        @org.springframework.beans.factory.annotation.Qualifier(WorkerConfig.JOB_EXECUTOR_BEAN) TaskExecutor jobExecutor,
        WorkerProperties properties) {
        this.jobRepository = jobRepository;
        this.jobService = jobService;
        this.jobExecutor = jobExecutor;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOrphanedJobs() {
        if (!properties.enabled()) {
            return;
        }
        int requeued = jobRepository.requeueOrphans();
        if (requeued > 0) {
            log.warn("Recovery sweep re-queued {} orphaned running job(s)", requeued);
        }
    }

    @Scheduled(fixedDelayString = "${app.worker.poll-interval}")
    public void poll() {
        if (!properties.enabled()) {
            return;
        }
        try {
            while (activeCount.get() < properties.concurrency()) {
                Optional<IngestionJob> claimed = jobService.claimNext();
                if (claimed.isEmpty()) {
                    return;
                }
                dispatch(claimed.get());
            }
        } catch (RuntimeException e) {
            log.error("Job poll tick failed; will retry next interval", e);
        }
    }

    private void dispatch(IngestionJob job) {
        activeCount.incrementAndGet();
        try {
            jobExecutor.execute(() -> {
                try {
                    jobService.execute(job);
                } finally {
                    activeCount.decrementAndGet();
                }
            });
        } catch (RuntimeException e) {
            // Submission was rejected (e.g. shutdown): undo the reservation and re-queue.
            activeCount.decrementAndGet();
            log.warn("Failed to submit job {} to executor; re-queueing", job.id(), e);
            jobRepository.requeueOrphans();
        }
    }
}
