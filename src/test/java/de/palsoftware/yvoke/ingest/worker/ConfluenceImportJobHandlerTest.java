package de.palsoftware.yvoke.ingest.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.palsoftware.yvoke.ingest.core.confluence.ConfluenceIngestService;
import de.palsoftware.yvoke.shared.jobengine.model.JobContext;
import de.palsoftware.yvoke.shared.jobengine.model.JobCounts;
import de.palsoftware.yvoke.shared.jobengine.model.JobStep;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ConfluenceImportJobHandler} — custom CRAWL/DISPATCH steps + delegation. */
public class ConfluenceImportJobHandlerTest {

    @Test
    public void registersForConfluenceImportKindWithCrawlDispatchSteps() {
        ConfluenceImportJobHandler handler =
            new ConfluenceImportJobHandler(mock(ConfluenceIngestService.class));

        assertThat(handler.kind()).isEqualTo("confluence-import");
        assertThat(handler.expectsEntities()).isFalse();
        assertThat(handler.steps()).containsExactly(JobStep.CRAWL, JobStep.DISPATCH);
    }

    @Test
    public void delegatesRunToIngestService() {
        ConfluenceIngestService service = mock(ConfluenceIngestService.class);
        JobContext ctx = mock(JobContext.class);
        JobCounts counts = new JobCounts(7, 0, 0, 0, 0);
        when(service.ingest(ctx)).thenReturn(counts);

        JobCounts result = new ConfluenceImportJobHandler(service).run(ctx);

        assertThat(result).isEqualTo(counts);
        verify(service).ingest(ctx);
    }
}
