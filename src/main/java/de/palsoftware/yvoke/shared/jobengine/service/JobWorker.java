package de.palsoftware.yvoke.shared.jobengine.service;

import de.palsoftware.yvoke.shared.jobengine.WorkerConfig;
import de.palsoftware.yvoke.shared.jobengine.WorkerProperties;
import de.palsoftware.yvoke.shared.jobengine.model.IngestionJob;
import de.palsoftware.yvoke.shared.jobengine.repository.JobRepository;
import jakarta.annotation.PreDestroy;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
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
    private final String workerId;
    private final AtomicInteger activeCount = new AtomicInteger(0);
    private final Set<UUID> activeJobIds = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    public JobWorker(JobRepository jobRepository, JobService jobService,
        @Qualifier(WorkerConfig.JOB_EXECUTOR_BEAN) TaskExecutor jobExecutor,
        WorkerProperties properties) {
        this.jobRepository = jobRepository;
        this.jobService = jobService;
        this.jobExecutor = jobExecutor;
        this.properties = properties;
        this.workerId = UUID.randomUUID().toString();
    }

    public String workerId() {
        return workerId;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOrphanedJobs() {
        if (!properties.enabled()) {
            return;
        }
        int requeued = jobRepository.requeueStaleRunningJobs(properties.staleAfter());
        if (requeued > 0) {
            log.warn("Recovery sweep re-queued {} stale running job(s)", requeued);
        }
    }

    @Scheduled(fixedDelayString = "${app.worker.heartbeat-interval}")
    public void heartbeatAndRecoverStale() {
        if (!properties.enabled()) {
            return;
        }
        for (UUID jobId : activeJobIds) {
            try {
                jobRepository.refreshHeartbeat(jobId, workerId);
            } catch (RuntimeException e) {
                log.warn("Failed to refresh heartbeat for job {}", jobId, e);
            }
        }
        if (shuttingDown.get()) {
            return;
        }
        try {
            int requeued = jobRepository.requeueStaleRunningJobs(properties.staleAfter());
            if (requeued > 0) {
                log.warn("Heartbeat sweep re-queued {} stale running job(s)", requeued);
            }
        } catch (RuntimeException e) {
            log.error("Stale job recovery sweep failed", e);
        }
    }

    @Scheduled(fixedDelayString = "${app.worker.poll-interval}")
    public void poll() {
        if (!properties.enabled() || shuttingDown.get()) {
            return;
        }
        try {
            while (!shuttingDown.get() && activeCount.get() < properties.concurrency()) {
                Optional<IngestionJob> claimed = jobService.claimNext(workerId);
                if (claimed.isEmpty()) {
                    return;
                }
                dispatch(claimed.get());
            }
        } catch (RuntimeException e) {
            log.error("Job poll tick failed; will retry next interval", e);
        }
    }

    @PreDestroy
    public void onShutdown() {
        shuttingDown.set(true);
        log.info("JobWorker {} stopping new job claims ({} job(s) currently active)", workerId,
            activeCount.get());
    }

    private void dispatch(IngestionJob job) {
        activeCount.incrementAndGet();
        activeJobIds.add(job.id());
        try {
            jobExecutor.execute(() -> {
                try {
                    jobService.execute(job);
                } finally {
                    activeJobIds.remove(job.id());
                    activeCount.decrementAndGet();
                }
            });
        } catch (RuntimeException e) {
            activeJobIds.remove(job.id());
            activeCount.decrementAndGet();
            log.warn("Failed to submit job {} to executor; re-queueing", job.id(), e);
            jobRepository.requeueJob(job.id());
        }
    }
}
