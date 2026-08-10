package de.palsoftware.yvoke.ingest.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.ingest.core.UploadPathGuard;
import de.palsoftware.yvoke.ingest.core.confluence.ConfluenceIngestService;
import de.palsoftware.yvoke.ingest.core.model.IngestJobKind;
import de.palsoftware.yvoke.ingest.core.service.CustomIngestService;
import de.palsoftware.yvoke.ingest.core.service.DocumentIngestService;
import de.palsoftware.yvoke.jsonobject.core.service.JsonObjectService;
import de.palsoftware.yvoke.shared.jobengine.JobHandler;
import de.palsoftware.yvoke.shared.jobengine.model.JobStep;
import de.palsoftware.yvoke.shared.jobengine.repository.JobRepository;
import de.palsoftware.yvoke.shared.jobengine.service.JobProgressBroker;
import de.palsoftware.yvoke.shared.jobengine.service.JobService;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The step ribbon on {@code /admin/jobs/{id}} is built from {@code JobHandler.steps()} — one row
 * per declared step, highlighted by the {@code ingestion_jobs.step} value the handler reports
 * through {@code JobContext.report(...)}. The two halves are written in different files by
 * different hands and nothing ties them together: a handler can declare {@code EMBED} and never
 * report it (the row stays grey forever, so a finished job looks stuck), or report {@code EXTRACT}
 * while never declaring it (the progress value is written to the database and matches no row at
 * all, so the bar simply never moves). Neither produces an exception, a log line, or a failing
 * assertion anywhere else in the suite — the operator just reads a wrong picture of a job that may
 * be running fine, or concludes a worker has hung.
 *
 * <p>
 * Three of the seven handlers pin their own list ({@code ConfluenceImportJobHandlerTest},
 * {@code ConfluencePageImportJobHandlerTest}, {@code KgDocumentExtractJobHandlerTest}); the other
 * four do not, and {@code CustomIngestJobHandler} — the ONLY one with no {@code steps()} override,
 * so the only consumer of the {@code JobHandler} default {@code CHUNK, EMBED, INSERT, INJECT} — has
 * no witness at all. Adding an override to it, or "tidying" the default to match the most common
 * override, silently rewrites its detail page. This catalogue is deliberately a single test that
 * fails loudly for the whole family rather than seven scattered ones that can be forgotten
 * individually.
 *
 * <p>
 * It also pins the two routing rules the page depends on: an instance-qualified kind
 * ({@code confluence-page-import:icc-wiki}) resolves to the base kind's handler, and an unknown or
 * null kind yields an empty list rather than a throw — the detail page is precisely where an
 * operator goes to read why a job with a bad kind failed, so it must render.
 */
public class JobHandlerStepsCatalogueTest {

    /** The step ribbon the job-detail page renders for each registered kind. */
    private static final Map<String, List<JobStep>> EXPECTED_STEPS = expectedSteps();

    private static Map<String, List<JobStep>> expectedSteps() {
        Map<String, List<JobStep>> steps = new LinkedHashMap<>();
        steps.put(IngestJobKind.STANDARD.getValue(),
            List.of(JobStep.CHUNK, JobStep.EMBED, JobStep.INSERT));
        steps.put(IngestJobKind.HIERARCHICAL.getValue(),
            List.of(JobStep.CHUNK, JobStep.EMBED, JobStep.INSERT, JobStep.EXTRACT));
        steps.put(IngestJobKind.KG_EXTRACT.getValue(),
            List.of(JobStep.CHUNK, JobStep.EXTRACT, JobStep.INJECT));
        steps.put(IngestJobKind.CONFLUENCE_IMPORT.getValue(),
            List.of(JobStep.CRAWL, JobStep.DISPATCH));
        steps.put(IngestJobKind.CONFLUENCE_PAGE_IMPORT.getValue(),
            List.of(JobStep.CHUNK, JobStep.EMBED, JobStep.INJECT));
        // The ONLY handler with no steps() override — this row IS the JobHandler default, and is
        // the only thing standing between that default and a silent change.
        steps.put(IngestJobKind.CUSTOM.getValue(),
            List.of(JobStep.CHUNK, JobStep.EMBED, JobStep.INSERT, JobStep.INJECT));
        steps.put(IngestJobKind.JSON_IMPORT.getValue(), List.of(JobStep.CHUNK, JobStep.INSERT));
        return steps;
    }

    /** Every handler the ingest domain registers, with collaborators stubbed: nothing is run. */
    private static List<JobHandler> allHandlers() {
        DocumentIngestService documents = mock(DocumentIngestService.class);
        ConfluenceIngestService confluence = mock(ConfluenceIngestService.class);
        return List.of(new StandardDocumentJobHandler(documents),
            new HierarchicalDocumentJobHandler(documents),
            new KgDocumentExtractJobHandler(documents), new ConfluenceImportJobHandler(confluence),
            new ConfluencePageImportJobHandler(confluence),
            new CustomIngestJobHandler(mock(CustomIngestService.class)),
            new JsonImportJobHandler(mock(JsonObjectService.class), new ObjectMapper(),
                mock(JobRepository.class), new UploadPathGuard("uploads")));
    }

    @Test
    public void everyHandlerDeclaresTheStepsItActuallyReports() {
        List<JobHandler> handlers = allHandlers();
        JobService jobService = new JobService(mock(JobRepository.class),
            mock(JobProgressBroker.class), handlers, List.of());
        List<String> declaredKinds =
            Arrays.stream(IngestJobKind.values()).map(IngestJobKind::getValue).toList();

        assertThat(handlers).extracting(JobHandler::kind)
            .as("a kind with no handler enqueues fine and only fails at execution")
            .containsExactlyInAnyOrderElementsOf(declaredKinds);
        assertThat(EXPECTED_STEPS.keySet())
            .as("a new IngestJobKind must be added to this catalogue in the same change")
            .containsExactlyInAnyOrderElementsOf(declaredKinds);

        EXPECTED_STEPS.forEach((kind, expected) -> {
            assertThat(jobService.getStepsForKind(kind))
                .as("steps rendered on /admin/jobs/{id} for kind=%s", kind)
                .containsExactlyElementsOf(expected);
            // A ":<instance>" suffix must route to the same handler, or an instance-scoped
            // connector job renders a detail page with no step ribbon at all.
            assertThat(jobService.getStepsForKind(kind + ":icc-wiki"))
                .as("instance-qualified kind=%s must resolve to the base kind's steps", kind)
                .containsExactlyElementsOf(expected);
        });

        // No steps rather than a throw: this page is where the operator reads WHY a job whose kind
        // has no handler failed, so it must still render.
        assertThat(jobService.getStepsForKind("no-such-kind")).isEmpty();
        assertThat(jobService.getStepsForKind(null)).isEmpty();
    }
}
