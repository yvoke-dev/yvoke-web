package de.palsoftware.yvoke.shared.jobengine.service;

import de.palsoftware.yvoke.shared.jobengine.*;
import de.palsoftware.yvoke.shared.jobengine.model.*;
import de.palsoftware.yvoke.shared.jobengine.repository.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JobServiceTest {

    private JobRepository jobRepository;
    private JobProgressBroker progressBroker;

    @BeforeEach
    void setUp() {
        jobRepository = mock(JobRepository.class);
        progressBroker = mock(JobProgressBroker.class);

        // findById returns a benign snapshot so publishSnapshot/report don't NPE.
        when(jobRepository.findById(any(UUID.class)))
            .thenAnswer(inv -> Optional.of(job(inv.getArgument(0), JobStatus.RUNNING)));
    }

    private JobService service(JobHandler... handlers) {
        return new JobService(jobRepository, progressBroker, List.of(handlers), List.of());
    }

    private static IngestionJob job(UUID id, JobStatus status) {
        return new IngestionJob(id, ItTestJobHandler.KIND, "ref", "1.0", "col", status, null, 0, 1,
            null, null, OffsetDateTime.now(), null, null);
    }

    @Test
    void enqueueRejectsBlankFields() {
        JobService service = service();
        assertThatThrownBy(() -> service.enqueue(new EnqueueRequest("", "ref", "1.0", "col")))
            .isInstanceOf(IllegalArgumentException.class);
        verify(jobRepository, never()).enqueue(any());
    }

    @Test
    void enqueueDelegatesWhenNoValidators() {
        UUID id = UUID.randomUUID();
        when(jobRepository.enqueue(any())).thenReturn(EnqueueResult.created(id));
        JobService service = service();

        EnqueueResult result =
            service.enqueue(new EnqueueRequest(ItTestJobHandler.KIND, "ref", "1.0", "col"));

        assertThat(result).isEqualTo(EnqueueResult.created(id));
        verify(jobRepository)
            .enqueue(new EnqueueRequest(ItTestJobHandler.KIND, "ref", "1.0", "col"));
    }

    /**
     * "Already queued" must survive the trip through the service: it is what tells the API to
     * answer 409, the admin pages to say "already running", and the Confluence crawl to count a
     * skip and keep crawling instead of aborting.
     */
    @Test
    void enqueueReportsAnAdoptedJobAsNotCreated() {
        UUID active = UUID.randomUUID();
        when(jobRepository.enqueue(any())).thenReturn(EnqueueResult.adopted(active));
        JobService service = service();

        EnqueueResult result =
            service.enqueue(new EnqueueRequest(ItTestJobHandler.KIND, "ref", "1.0", "col"));

        assertThat(result.created()).isFalse();
        assertThat(result.jobId()).isEqualTo(active);
    }

    @Test
    void enqueueAppliesValidatorsInOrder() {
        UUID id = UUID.randomUUID();
        when(jobRepository.enqueue(any())).thenReturn(EnqueueResult.created(id));
        // A validator that normalizes the version; the engine must persist the normalized request.
        EnqueueValidator normalizeVersion = req -> new EnqueueRequest(req.kind(), req.sourceRef(),
            "2.0", req.collection(), req.settings());
        JobService service =
            new JobService(jobRepository, progressBroker, List.of(), List.of(normalizeVersion));

        EnqueueResult result =
            service.enqueue(new EnqueueRequest(ItTestJobHandler.KIND, "ref", "1.0", "col"));

        assertThat(result.jobId()).isEqualTo(id);
        verify(jobRepository)
            .enqueue(new EnqueueRequest(ItTestJobHandler.KIND, "ref", "2.0", "col"));
    }

    @Test
    void enqueuePropagatesValidatorFailure() {
        EnqueueValidator rejecting = req -> {
            throw new IllegalArgumentException("nope");
        };
        JobService service =
            new JobService(jobRepository, progressBroker, List.of(), List.of(rejecting));

        assertThatThrownBy(
            () -> service.enqueue(new EnqueueRequest(ItTestJobHandler.KIND, "ref", "1.0", "col")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nope");
        verify(jobRepository, never()).enqueue(any());
    }

    @Test
    void executeMarksCompletedWhenCountsValid() {
        UUID id = UUID.randomUUID();
        JobHandler handler =
            handler(ItTestJobHandler.KIND, true, ctx -> new JobCounts(1, 8, 5, 7, 10));
        JobService service = service(handler);

        service.execute(job(id, JobStatus.RUNNING));

        verify(jobRepository).markCompleted(eq(id), eq(new JobCounts(1, 8, 5, 7, 10)));
        verify(jobRepository, never()).markFailed(any(), any());
    }

    @Test
    void executeMarksFailedWhenExpectedEntitiesAreZero() {
        UUID id = UUID.randomUUID();
        JobHandler handler =
            handler(ItTestJobHandler.KIND, true, ctx -> new JobCounts(10, 50, 0, 200, 0));
        JobService service = service(handler);

        service.execute(job(id, JobStatus.RUNNING));

        verify(jobRepository).markFailed(eq(id), any());
        verify(jobRepository, never()).markCompleted(any(), any());
    }

    @Test
    void executeMarksFailedWhenHandlerThrows() {
        UUID id = UUID.randomUUID();
        JobHandler handler = handler(ItTestJobHandler.KIND, false, ctx -> {
            throw new IllegalStateException("boom");
        });
        JobService service = service(handler);

        service.execute(job(id, JobStatus.RUNNING));

        verify(jobRepository).markFailed(eq(id), eq("boom"));
    }

    @Test
    void executeMarksFailedWhenNoHandlerForKind() {
        UUID id = UUID.randomUUID();
        JobService service = service(); // no handlers registered

        service.execute(job(id, JobStatus.RUNNING));

        verify(jobRepository).markFailed(eq(id), contains("no handler for kind=it_test"));
    }

    // ---------------------------------------------------------------------
    // A kind may carry a ":<instance>" suffix (e.g. "confluence-page-import:icc-wiki") so the job
    // list shows WHICH connector produced the job without any template work. The engine routes on
    // the base kind alone. Nothing produced a qualified kind before the Confluence connector became
    // instance-scoped, so this behaviour is pinned here rather than assumed.
    // ---------------------------------------------------------------------

    @Test
    void executeRoutesAnInstanceQualifiedKindToTheBaseKindHandler() {
        UUID id = UUID.randomUUID();
        AtomicBoolean ran = new AtomicBoolean();
        JobHandler handler = handler("confluence-page-import", false, ctx -> {
            ran.set(true);
            return new JobCounts(1, 3, 0, 0, 0);
        });
        JobService service = service(handler);

        service.execute(qualifiedJob(id, "confluence-page-import:icc-wiki"));

        assertThat(ran).isTrue();
        verify(jobRepository).markCompleted(eq(id), eq(new JobCounts(1, 3, 0, 0, 0)));
        verify(jobRepository, never()).markFailed(any(), any());
    }

    @Test
    void getStepsForKindRoutesAnInstanceQualifiedKindToTheBaseKindHandler() {
        JobHandler handler =
            handler("confluence-page-import", false, ctx -> new JobCounts(1, 1, 0, 0, 0));
        JobService service = service(handler);

        assertThat(service.getStepsForKind("confluence-page-import:icc-wiki"))
            .isEqualTo(handler.steps());
        assertThat(service.getStepsForKind("confluence-page-import")).isEqualTo(handler.steps());
    }

    @Test
    void executeMarksFailedWithTheFullKindWhenTheBaseKindHasNoHandler() {
        UUID id = UUID.randomUUID();
        JobService service = service();

        service.execute(qualifiedJob(id, "confluence-page-import:icc-wiki"));

        verify(jobRepository).markFailed(eq(id),
            contains("no handler for kind=confluence-page-import:icc-wiki"));
    }

    private static IngestionJob qualifiedJob(UUID id, String kind) {
        return new IngestionJob(id, kind, "ref", "1.0", "col", JobStatus.RUNNING, null, 0, 1, null,
            null, OffsetDateTime.now(), null, null);
    }

    private interface RunFn {
        JobCounts run(JobContext ctx);
    }

    private static JobHandler handler(String kind, boolean expectsEntities, RunFn fn) {
        return new JobHandler() {
            @Override
            public String kind() {
                return kind;
            }

            @Override
            public boolean expectsEntities() {
                return expectsEntities;
            }

            @Override
            public JobCounts run(JobContext ctx) {
                return fn.run(ctx);
            }
        };
    }
}
