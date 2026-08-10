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
import org.mockito.ArgumentCaptor;
import java.util.ArrayList;
import static org.assertj.core.api.Assertions.assertThatCode;

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

    /**
     * Two validators must run as a CHAIN, in the order of the injected list: each receives the
     * request the previous one RETURNED, and the request finally persisted is the last one's
     * output.
     *
     * <p>
     * Nothing in the engine re-reads the original request after the fold, so a validator that only
     * inspects is indistinguishable from one that normalizes unless the accumulator is carried
     * forward. Exactly one implementation exists today — {@code CollectionTagEnqueueValidator},
     * which trims every tag AND replaces the caller's spelling of the collection with the
     * catalogue's stored {@code col.name()} — so the fold currently looks like a loop over a single
     * element and reads as decorative. The moment a second validator is added (the obvious next one
     * being a per-kind settings check) the difference becomes silent data loss: a validator handed
     * the ORIGINAL request would validate the caller's spelling rather than the canonical name, or
     * would re-derive a tag that was already trimmed, and the row lands in {@code ingestion_jobs}
     * carrying whichever version won. There is no exception and no log line — the job is simply
     * enqueued against something nobody checked.
     *
     * <p>
     * {@code enqueueAppliesValidatorsInOrder} does not cover this despite its name: it constructs
     * the service with {@code List.of(normalizeVersion)} — ONE validator — so it proves only that a
     * normalized request reaches the repository. It stays green if the loop is reversed, and it
     * stays green if the accumulator is dropped in favour of validating {@code req} every time.
     *
     * <p>
     * Note what is deliberately NOT pinned here: no {@code @Order} exists on any
     * {@code EnqueueValidator} (the repo's only {@code @Order}s are on {@code SecurityConfig}'s
     * four chains and the two exception advices), so WHICH bean Spring puts first in the injected
     * list is Spring's contract, not this one — the interface javadoc's "bean-discovery order" is
     * descriptive prose. What the engine owns, and what this test holds it to, is that it applies
     * the validators in the order it was GIVEN and folds each result into the next.
     */
    @Test
    void enqueueChainsTwoValidatorsInTheOrderTheListIsInjected() {
        UUID id = UUID.randomUUID();
        when(jobRepository.enqueue(any())).thenReturn(EnqueueResult.created(id));

        // Each validator records the collection it was HANDED and then rewrites it, so the recorded
        // sequence proves both the order and that the second saw the first's output.
        StringBuilder collectionSeenByEachValidator = new StringBuilder();
        EnqueueValidator first = req -> {
            collectionSeenByEachValidator.append(req.collection()).append('|');
            return new EnqueueRequest(req.kind(), req.sourceRef(), req.tags(), "after-first",
                req.settings());
        };
        EnqueueValidator second = req -> {
            collectionSeenByEachValidator.append(req.collection()).append('|');
            return new EnqueueRequest(req.kind(), req.sourceRef(), req.tags(),
                req.collection() + "+second", req.settings());
        };
        JobService service =
            new JobService(jobRepository, progressBroker, List.of(), List.of(first, second));

        EnqueueResult result =
            service.enqueue(new EnqueueRequest(ItTestJobHandler.KIND, "ref", "1.0", "col"));

        assertThat(result.jobId()).isEqualTo(id);
        assertThat(collectionSeenByEachValidator.toString())
            .as("the second validator must see what the first returned, not the original request")
            .isEqualTo("col|after-first|");
        verify(jobRepository)
            .enqueue(new EnqueueRequest(ItTestJobHandler.KIND, "ref", "1.0", "after-first+second"));
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

    /**
     * A handler that returns {@code null} counts MUST fail the job with a message, not escape as an
     * NPE.
     *
     * <p>
     * {@code validateCounts} runs OUTSIDE {@code execute}'s try/catch — that catch wraps only
     * {@code handler.run(ctx)} — so anything thrown while validating propagates all the way out of
     * {@code JobService.execute}. {@code JobWorker.dispatch} runs that call inside an executor task
     * whose {@code finally} only decrements {@code activeCount}: nothing calls {@code markFailed},
     * so the row stays {@code running} forever. It is never re-queued either, because
     * {@code requeueOrphans()} only runs on the startup sweep. The job hangs at whatever progress
     * it reached, blocks its own {@code sourceRef} from being re-enqueued (the active-job adoption
     * check in {@code JobRepository.enqueue} treats it as still live), and gives the operator no
     * error to read.
     *
     * <p>
     * The existing coverage cannot see this. Every other {@code execute} test hands back a real
     * {@code JobCounts}, and the one closest to it — {@code executeMarksFailedWhenExpectedEntities
     * AreZero} — proves only the zero-entities branch, which is precisely the branch that
     * DEREFERENCES the null. A handler returning null is not hypothetical: any {@code run} whose
     * body ends in a conditional return produces exactly this.
     */
    @Test
    void aHandlerReturningNullCountsFailsTheJobInsteadOfEscapingAsAnNpe() {
        UUID id = UUID.randomUUID();
        // expectsEntities = true so the next line of validateCounts would dereference the null:
        // without the guard this does not merely mis-report, it throws out of execute().
        JobHandler handler = handler(ItTestJobHandler.KIND, true, ctx -> null);
        JobService service = service(handler);

        assertThatCode(() -> service.execute(job(id, JobStatus.RUNNING)))
            .as("a null JobCounts must never escape execute() as an exception")
            .doesNotThrowAnyException();

        verify(jobRepository).markFailed(eq(id), eq("handler returned no counts"));
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

    /**
     * A non-blank progress message MUST be persisted to {@code ingestion_jobs.summary}; a null or
     * blank one MUST NOT be written at all.
     *
     * <p>
     * The message used to travel only on the SSE {@code ProgressEvent}. SSE here has no replay
     * buffer and the terminal snapshot carries a different message, so a handler's account of what
     * it did survived for milliseconds and only for an operator who already had the page open —
     * while {@code connectors.html} sends them to the job page precisely to read it. Dropping the
     * persist makes every message-only report (a crawl's "skipped N unchanged pages", a parser's
     * chunk count) unrecoverable the instant it is emitted, with nothing in the UI hinting that a
     * message ever existed.
     *
     * <p>
     * The blank half matters just as much and in the opposite direction: the two-arg
     * {@code report(step, progress)} delegates to the three-arg form with a null message, so EVERY
     * plain progress tick would otherwise overwrite {@code summary} — erasing the real message
     * recorded moments earlier and leaving a job whose summary is blank exactly when a handler was
     * chattiest. Nothing else in the suite constructs {@code DefaultJobContext} or calls
     * {@code report} at all, so neither half has a witness today.
     */
    @Test
    void aReportedMessagePersistsToTheJobSummaryAndABlankOneDoesNot() {
        UUID reported = UUID.randomUUID();
        UUID silent = UUID.randomUUID();
        JobHandler reporting = handler("summary-reporting", false, ctx -> {
            ctx.report(JobStep.CHUNK, 10, "Parsed 3 chunks");
            return new JobCounts(1, 3, 0, 0, 0);
        });
        JobHandler quiet = handler("summary-silent", false, ctx -> {
            ctx.report(JobStep.CHUNK, 10, null);
            ctx.report(JobStep.EMBED, 20, "   ");
            ctx.report(JobStep.INSERT, 30); // two-arg form: message is null
            return new JobCounts(1, 3, 0, 0, 0);
        });
        JobService service = service(reporting, quiet);

        service.execute(qualifiedJob(reported, "summary-reporting"));
        service.execute(qualifiedJob(silent, "summary-silent"));

        verify(jobRepository).updateSummary(reported, "Parsed 3 chunks");
        verify(jobRepository, never()).updateSummary(eq(silent), any());
        // The guard may only gate the SUMMARY write — progress itself must still be recorded.
        verify(jobRepository).updateProgress(silent, JobStep.CHUNK, 10);
        verify(jobRepository).updateProgress(silent, JobStep.EMBED, 20);
        verify(jobRepository).updateProgress(silent, JobStep.INSERT, 30);
    }

    /**
     * A handler's reported progress is arbitrary arithmetic and MUST be clamped to 0–100 before it
     * reaches either consumer — the {@code ingestion_jobs.progress} row AND the SSE frame.
     *
     * <p>
     * Out-of-range values are not hypothetical: every batching handler computes progress as a
     * ratio, and {@code ScaledJobContext} rescales it a second time on the zip path
     * ({@code start + (progress / 100.0) * (end - start)}), so an off-by-one item count or an empty
     * denominator produces 101, 150 or a negative just as easily as it produces 60. Both consumers
     * treat the number as a percentage with no validation of their own: {@code job-detail.html}
     * feeds it straight into the progress bar's width, so 150 paints a bar past its own container,
     * and a negative paints nothing at all — an operator watching a job that is in fact advancing
     * sees a bar stuck at zero and concludes the worker has hung.
     *
     * <p>
     * The two writes are the point of this test. {@code report} writes the number twice — once
     * through {@code jobRepository.updateProgress} and once into a freshly built
     * {@link ProgressEvent} — and the clamp is a single local computed before both. Clamping only
     * on the way to the database (or only on the way to the broker) leaves the persisted row and
     * the live stream disagreeing about the same instant, which is unfalsifiable from either side.
     *
     * <p>
     * Nothing else in the suite exercises the range at all: every other test that calls
     * {@code report} passes an already-valid percentage, so the clamp could be deleted outright and
     * the whole class would stay green.
     */
    @Test
    void reportedProgressOutsideTheRangeIsClampedBeforeItIsWritten() {
        UUID id = UUID.randomUUID();
        JobHandler handler = handler(ItTestJobHandler.KIND, false, ctx -> {
            ctx.report(JobStep.CHUNK, 150);
            ctx.report(JobStep.EMBED, -5);
            return new JobCounts(1, 3, 0, 0, 0);
        });
        JobService service = service(handler);

        service.execute(job(id, JobStatus.RUNNING));

        verify(jobRepository).updateProgress(id, JobStep.CHUNK, 100);
        verify(jobRepository).updateProgress(id, JobStep.EMBED, 0);
        // The published frame carries the SAME clamped number, not the raw one: the row and the
        // live stream describe one instant and may never disagree about it.
        verify(progressBroker).publish(new ProgressEvent(id, "running", "chunk", 100, null, null));
        verify(progressBroker).publish(new ProgressEvent(id, "running", "embed", 0, null, null));

        ArgumentCaptor<ProgressEvent> events = ArgumentCaptor.forClass(ProgressEvent.class);
        verify(progressBroker, atLeastOnce()).publish(events.capture());
        assertThat(events.getAllValues())
            .as("no frame may ever carry a percentage the UI cannot render")
            .allSatisfy(e -> assertThat(e.progress()).isBetween(0, 100));
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
