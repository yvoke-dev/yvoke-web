package de.palsoftware.yvoke.ingest.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.palsoftware.yvoke.ingest.core.service.IngestService;
import de.palsoftware.yvoke.shared.jobengine.model.EnqueueResult;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

/**
 * After MNT-04 the controller is pure HTTP glue: each endpoint delegates to {@link IngestService}
 * and wraps the returned job id as {@code 202 ACCEPTED} + {@code {"id": <jobId>}}. The
 * orchestration and path-traversal behaviour is covered by {@code IngestServiceTest}; the full HTTP
 * contract by {@code IngestApiControllerIT}.
 */
public class IngestApiControllerTest {

    private IngestService ingestService;
    private IngestApiController controller;

    @BeforeEach
    public void setUp() {
        ingestService = mock(IngestService.class);
        controller = new IngestApiController(ingestService);
    }

    @Test
    public void processDocumentKgDelegatesAndWraps202() {
        UUID jobId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        when(ingestService.processKg(docId, null, null, null, "OIM", "v1", null))
            .thenReturn(EnqueueResult.created(jobId));

        ResponseEntity<Map<String, UUID>> response =
            controller.processDocumentKg(docId, null, null, null, "OIM", "v1", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).containsEntry("id", jobId);
        verify(ingestService).processKg(docId, null, null, null, "OIM", "v1", null);
    }

    /**
     * Duplicate work is a <strong>409</strong> carrying the in-flight job's id, exactly as
     * {@code POST /api/jobs/v1} reports it. Returning 202 for an adopted job told the caller their
     * request had been accepted when nothing was created — and the adopted job runs with the
     * settings it was enqueued with, not the ones this call asked for.
     */
    @Test
    public void processDocumentKgReports409WhenItAdoptedAnInFlightJob() {
        UUID activeJobId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        when(ingestService.processKg(docId, null, null, null, "OIM", "v1", null))
            .thenReturn(EnqueueResult.adopted(activeJobId));

        ResponseEntity<Map<String, UUID>> response =
            controller.processDocumentKg(docId, null, null, null, "OIM", "v1", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("id", activeJobId);
    }

    @Test
    public void enqueueCustomDelegatesAndWraps202() {
        UUID jobId = UUID.randomUUID();
        MockMultipartFile file =
            new MockMultipartFile("file", "c.zip", "application/zip", "z".getBytes());
        when(ingestService.enqueueCustom(file, "OIM", "v1", "**/*.md", "graph/entities.jsonl",
            "graph/relationships.jsonl", null)).thenReturn(jobId);

        ResponseEntity<Map<String, UUID>> response = controller.enqueueCustom(file, "OIM", "v1",
            "**/*.md", "graph/entities.jsonl", "graph/relationships.jsonl", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).containsEntry("id", jobId);
    }

    @Test
    public void uploadAndEnqueueDelegatesAndWraps202() {
        authenticateWith("ROLE_USER");
        UUID jobId = UUID.randomUUID();
        MockMultipartFile file =
            new MockMultipartFile("file", "d.md", "text/markdown", "d".getBytes());
        when(ingestService.uploadAndEnqueue(file, "OIM", "v1", "standard", null, null, null))
            .thenReturn(jobId);

        ResponseEntity<Map<String, UUID>> response =
            controller.uploadAndEnqueue(file, "OIM", "v1", "standard", null, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).containsEntry("id", jobId);
    }

    // ---------------------------------------------------------------------
    // SEC: /upload takes the job kind straight from a request param and the API chain grants
    // ROLE_INGEST/USER/ADMIN, so it was a second door onto the connector kinds — a plain user could
    // POST a 1-byte file with kind=confluence-import and trigger a full space crawl with the stored
    // admin token.
    // ---------------------------------------------------------------------

    @AfterEach
    public void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateWith(String... roles) {
        SecurityContextHolder.getContext()
            .setAuthentication(new UsernamePasswordAuthenticationToken("principal", "n/a",
                Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList()));
    }

    private static MockMultipartFile tinyFile() {
        return new MockMultipartFile("file", "d.md", "text/markdown", "d".getBytes());
    }

    @Test
    public void uploadWithAConfluenceKindIsForbiddenForPlainUser() {
        authenticateWith("ROLE_USER");
        MockMultipartFile file = tinyFile();

        assertThatThrownBy(() -> controller.uploadAndEnqueue(file, "anything", null,
            "confluence-import", null, null, null)).isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN));

        verifyNoInteractions(ingestService);
    }

    @Test
    public void uploadWithAConfluenceKindIsForbiddenForTheApiKeyIngestRole() {
        authenticateWith("ROLE_INGEST");
        MockMultipartFile file = tinyFile();

        assertThatThrownBy(() -> controller.uploadAndEnqueue(file, "anything", null,
            "confluence-page-import:oim", null, null, null))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN));

        verifyNoInteractions(ingestService);
    }

    @Test
    public void uploadWithAConfluenceKindIsForbiddenWhenUnauthenticated() {
        SecurityContextHolder.clearContext();
        MockMultipartFile file = tinyFile();

        assertThatThrownBy(() -> controller.uploadAndEnqueue(file, "anything", null,
            "confluence-import", null, null, null)).isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN));

        verifyNoInteractions(ingestService);
    }
}
