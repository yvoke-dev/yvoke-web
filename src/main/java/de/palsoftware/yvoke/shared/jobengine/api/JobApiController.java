package de.palsoftware.yvoke.shared.jobengine.api;


import de.palsoftware.yvoke.shared.jobengine.PrivilegedJobKindGuard;
import de.palsoftware.yvoke.shared.jobengine.model.EnqueueRequest;
import de.palsoftware.yvoke.shared.jobengine.model.EnqueueResult;
import de.palsoftware.yvoke.shared.jobengine.model.IngestionJob;
import de.palsoftware.yvoke.shared.jobengine.service.JobProgressBroker;
import de.palsoftware.yvoke.shared.jobengine.repository.JobRepository;
import de.palsoftware.yvoke.shared.jobengine.service.JobService;
import de.palsoftware.yvoke.shared.jobengine.model.ProgressEvent;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/jobs/v1")
public class JobApiController {

    private final JobService jobService;
    private final JobRepository jobRepository;
    private final JobProgressBroker progressBroker;

    public JobApiController(JobService jobService, JobRepository jobRepository,
        JobProgressBroker progressBroker) {
        this.jobService = jobService;
        this.jobRepository = jobRepository;
        this.progressBroker = progressBroker;
    }

    /**
     * Enqueues a job. Duplicate work is not an error the server failed at, so it is a <strong>409
     * Conflict</strong> carrying the id of the job already in flight — the client can poll or
     * subscribe to that one — rather than a 500 from the admission-control unique index.
     */
    @PostMapping
    public ResponseEntity<Map<String, UUID>> enqueue(@Valid @RequestBody EnqueueRequest request) {
        PrivilegedJobKindGuard.requireAdminForPrivilegedKind(request.kind());
        EnqueueResult result;
        try {
            result = jobService.enqueue(request);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        HttpStatus status = result.created() ? HttpStatus.ACCEPTED : HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(Map.of("id", result.jobId()));
    }

    @GetMapping("/{id}")
    public IngestionJob get(@PathVariable UUID id) {
        return jobRepository.findById(id).orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown job id: " + id));
    }

    @GetMapping("/{id}/progress")
    public SseEmitter progress(@PathVariable UUID id) {
        IngestionJob job = jobRepository.findById(id).orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown job id: " + id));

        SseEmitter emitter = progressBroker.subscribe(id);
        try {
            emitter.send(SseEmitter.event().name("progress").data(ProgressEvent.of(job)));
            if (job.status().isTerminal()) {
                emitter.complete();
            }
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }
}
