package de.palsoftware.yvoke.shared.jobengine.api;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import de.palsoftware.yvoke.shared.jobengine.model.EnqueueRequest;
import de.palsoftware.yvoke.shared.jobengine.model.EnqueueResult;
import de.palsoftware.yvoke.shared.jobengine.model.IngestionJob;
import de.palsoftware.yvoke.shared.jobengine.ItTestJobHandler;
import de.palsoftware.yvoke.shared.jobengine.service.JobProgressBroker;
import de.palsoftware.yvoke.shared.jobengine.repository.JobRepository;
import de.palsoftware.yvoke.shared.jobengine.service.JobService;
import de.palsoftware.yvoke.shared.jobengine.model.JobStatus;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class JobApiControllerTest {

    private JobService jobService;
    private JobRepository jobRepository;
    private JobProgressBroker progressBroker;
    private JobApiController controller;

    @BeforeEach
    void setUp() {
        jobService = mock(JobService.class);
        jobRepository = mock(JobRepository.class);
        progressBroker = mock(JobProgressBroker.class);
        controller = new JobApiController(jobService, jobRepository, progressBroker);
    }

    private static IngestionJob job(UUID id, JobStatus status) {
        return new IngestionJob(id, ItTestJobHandler.KIND, "ref", "1.0", "col", status, null, 0, 1,
            null, null, OffsetDateTime.now(), null, null);
    }

    @Test
    void enqueueReturnsAcceptedWithId() {
        UUID id = UUID.randomUUID();
        EnqueueRequest req = new EnqueueRequest(ItTestJobHandler.KIND, "ref", "1.0", "col");
        when(jobService.enqueue(req)).thenReturn(EnqueueResult.created(id));

        ResponseEntity<Map<String, UUID>> response = controller.enqueue(req);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).containsEntry("id", id);
    }

    /**
     * Duplicate work is not a server error: the admission-control index would otherwise surface as
     * a 500. A 409 carrying the id of the job already in flight lets the client poll THAT one.
     */
    @Test
    void enqueueOfWorkAlreadyQueuedYields409WithTheExistingJobId() {
        UUID active = UUID.randomUUID();
        EnqueueRequest req = new EnqueueRequest(ItTestJobHandler.KIND, "ref", "1.0", "col");
        when(jobService.enqueue(req)).thenReturn(EnqueueResult.adopted(active));

        ResponseEntity<Map<String, UUID>> response = controller.enqueue(req);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("id", active);
    }

    @Test
    void enqueueWithBlankFieldYields400() {
        EnqueueRequest req = new EnqueueRequest("", "ref", "1.0", "col");
        when(jobService.enqueue(req)).thenThrow(new IllegalArgumentException("blank"));

        assertThatThrownBy(() -> controller.enqueue(req))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void getUnknownJobYields404() {
        UUID id = UUID.randomUUID();
        when(jobRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.get(id)).isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void progressUnknownJobYields404() {
        UUID id = UUID.randomUUID();
        when(jobRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.progress(id))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND));
        verify(progressBroker, never()).subscribe(any());
    }

    @Test
    void progressKnownJobSubscribesAndSendsSnapshot() {
        UUID id = UUID.randomUUID();
        when(jobRepository.findById(id)).thenReturn(Optional.of(job(id, JobStatus.RUNNING)));
        when(progressBroker.subscribe(id)).thenReturn(new SseEmitter());

        SseEmitter emitter = controller.progress(id);

        assertThat(emitter).isNotNull();
        verify(progressBroker).subscribe(id);
    }

    // ---------------------------------------------------------------------
    // SEC: the connector kinds run with the STORED Confluence admin credentials and take an
    // arbitrary pageId, so they must not be reachable by a plain ROLE_USER / ROLE_INGEST caller.
    // ---------------------------------------------------------------------

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateWith(String... roles) {
        SecurityContextHolder.getContext()
            .setAuthentication(new UsernamePasswordAuthenticationToken("principal", "n/a",
                java.util.Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList()));
    }

    private static void assertForbidden(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void confluenceEnqueueIsForbiddenForPlainUser() {
        authenticateWith("ROLE_USER");
        EnqueueRequest req = new EnqueueRequest("confluence-page-import", "ref", "1.0", "col");

        assertForbidden(() -> controller.enqueue(req));
        verify(jobService, never()).enqueue(any());
    }

    @Test
    void confluenceEnqueueIsForbiddenForApiKeyIngestRole() {
        authenticateWith("ROLE_INGEST");
        EnqueueRequest req = new EnqueueRequest("confluence-import", "ref", "1.0", "col");

        assertForbidden(() -> controller.enqueue(req));
        verify(jobService, never()).enqueue(any());
    }

    @Test
    void confluenceEnqueueWithInstanceSuffixIsForbiddenForPlainUser() {
        // Wave 3 will emit `confluence-page-import:<slug>`; the check matches the base kind.
        authenticateWith("ROLE_USER");
        EnqueueRequest req =
            new EnqueueRequest("confluence-page-import:oim-space", "ref", "1.0", "col");

        assertForbidden(() -> controller.enqueue(req));
        verify(jobService, never()).enqueue(any());
    }

    @Test
    void confluenceEnqueueIsForbiddenWhenUnauthenticated() {
        SecurityContextHolder.clearContext();
        EnqueueRequest req = new EnqueueRequest("confluence-page-import", "ref", "1.0", "col");

        assertForbidden(() -> controller.enqueue(req));
        verify(jobService, never()).enqueue(any());
    }

    @Test
    void confluenceEnqueueIsAllowedForAdmin() {
        authenticateWith("ROLE_ADMIN");
        UUID id = UUID.randomUUID();
        EnqueueRequest req = new EnqueueRequest("confluence-page-import", "ref", "1.0", "col");
        when(jobService.enqueue(req)).thenReturn(EnqueueResult.created(id));

        ResponseEntity<Map<String, UUID>> response = controller.enqueue(req);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).containsEntry("id", id);
    }

    @Test
    void nonConfluenceEnqueueStaysOpenToPlainUser() {
        authenticateWith("ROLE_USER");
        UUID id = UUID.randomUUID();
        EnqueueRequest req = new EnqueueRequest(ItTestJobHandler.KIND, "ref", "1.0", "col");
        when(jobService.enqueue(req)).thenReturn(EnqueueResult.created(id));

        ResponseEntity<Map<String, UUID>> response = controller.enqueue(req);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).containsEntry("id", id);
    }
}
