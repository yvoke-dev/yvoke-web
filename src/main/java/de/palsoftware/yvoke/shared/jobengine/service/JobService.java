package de.palsoftware.yvoke.shared.jobengine.service;

import de.palsoftware.yvoke.shared.jobengine.*;
import de.palsoftware.yvoke.shared.jobengine.model.*;
import de.palsoftware.yvoke.shared.jobengine.repository.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private final JobRepository jobRepository;
    private final JobProgressBroker progressBroker;
    private final Map<String, JobHandler> handlersByKind;
    private final List<EnqueueValidator> enqueueValidators;

    public JobService(JobRepository jobRepository, JobProgressBroker progressBroker,
        List<JobHandler> handlers, List<EnqueueValidator> enqueueValidators) {
        this.jobRepository = jobRepository;
        this.progressBroker = progressBroker;
        this.handlersByKind =
            handlers.stream().collect(Collectors.toMap(JobHandler::kind, Function.identity()));
        this.enqueueValidators = enqueueValidators;
    }

    /**
     * Enqueues a job, or adopts the one already queued/running for the same unit of work. Callers
     * must decide what a duplicate means to their user (a 409, a "already running" notice, a
     * counted skip during a crawl) — hence {@link EnqueueResult#created()} rather than a bare id.
     */
    public EnqueueResult enqueue(EnqueueRequest req) {
        requireNonBlank("kind", req.kind());
        requireNonBlank("sourceRef", req.sourceRef());
        requireNonBlank("collection", req.collection());

        // Domain-specific validation/normalization (e.g. collection/version checks) is contributed
        // by
        // EnqueueValidator beans, keeping the engine itself domain-agnostic.
        EnqueueRequest finalReq = req;
        for (EnqueueValidator validator : enqueueValidators) {
            finalReq = validator.validate(finalReq);
        }

        EnqueueResult result = jobRepository.enqueue(finalReq);
        if (result.created()) {
            log.info("Enqueued job {} (kind={}, tag={})", result.jobId(), finalReq.kind(),
                finalReq.tag());
        } else {
            log.info("Job {} is already active for kind={} sourceRef={} tag={}; adopting it",
                result.jobId(), finalReq.kind(), finalReq.sourceRef(), finalReq.tag());
        }
        return result;
    }

    /**
     * Claims the next queued job in a single transaction so the {@code FOR UPDATE SKIP LOCKED} lock
     * is held across the status update — the guarantee that two workers never claim the same job.
     * Execution happens outside this transaction (on the worker's executor), so the claim tx stays
     * short.
     */
    @Transactional
    public Optional<IngestionJob> claimNext() {
        return jobRepository.claimNext();
    }

    public List<JobStep> getStepsForKind(String kind) {
        if (kind == null) {
            return List.of();
        }
        String baseKind = kind.split(":")[0];
        JobHandler handler = handlersByKind.get(baseKind);
        if (handler != null) {
            return handler.steps();
        }
        return List.of();
    }

    public void execute(IngestionJob job) {
        String baseKind = job.kind().split(":")[0];
        JobHandler handler = handlersByKind.get(baseKind);
        if (handler == null) {
            fail(job.id(), "no handler for kind=" + job.kind());
            return;
        }

        JobCounts counts;
        try {
            JobContext ctx = new DefaultJobContext(job);
            counts = handler.run(ctx);
        } catch (RuntimeException e) {
            log.warn("Job {} failed during execution", job.id(), e);
            fail(job.id(), e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            return;
        }

        String validationError = validateCounts(handler, job, counts);
        if (validationError != null) {
            log.warn("Job {} produced no usable output: {}", job.id(), validationError);
            fail(job.id(), validationError);
            return;
        }

        complete(job.id(), counts);
    }

    private String validateCounts(JobHandler handler, IngestionJob job, JobCounts counts) {
        if (counts == null) {
            return "handler returned no counts";
        }
        if (handler.expectsEntities(job) && counts.entities() <= 0) {
            return "job produced 0 entities (expected non-zero) — treating as failure";
        }
        return null;
    }

    private void complete(UUID id, JobCounts counts) {
        jobRepository.markCompleted(id, counts);
        publishSnapshot(id);
    }

    private void fail(UUID id, String error) {
        jobRepository.markFailed(id, error);
        publishSnapshot(id);
    }

    public void publishSnapshot(UUID id) {
        jobRepository.findById(id).ifPresent(j -> progressBroker.publish(ProgressEvent.of(j)));
    }

    private static void requireNonBlank(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Field '" + field + "' must not be blank");
        }
    }

    private final class DefaultJobContext implements JobContext {

        private final IngestionJob job;

        private DefaultJobContext(IngestionJob job) {
            this.job = job;
        }

        @Override
        public IngestionJob job() {
            return job;
        }

        @Override
        public void report(JobStep step, int progress) {
            report(step, progress, null);
        }

        @Override
        public void report(JobStep step, int progress, String message) {
            int clamped = Math.max(0, Math.min(100, progress));
            jobRepository.updateProgress(job.id(), step, clamped);
            // A reported message is the handler's account of what it did. It used to travel only
            // on the SSE event, which has no replay buffer and is blanked by the terminal
            // snapshot, so it survived for milliseconds and only for an operator already watching
            // — while connectors.html points them at the job page to read it.
            if (message != null && !message.isBlank()) {
                jobRepository.updateSummary(job.id(), message);
            }
            // Build the SSE event from the in-memory job plus the just-written step/progress,
            // instead
            // of re-SELECTing the full row on every progress tick (the running job's
            // status/error/counts
            // are unchanged between claim and terminal state).
            progressBroker.publish(new ProgressEvent(job.id(), job.status().dbValue(),
                step == null ? null : step.dbValue(), clamped, job.error(), job.counts(), message));
        }
    }
}
