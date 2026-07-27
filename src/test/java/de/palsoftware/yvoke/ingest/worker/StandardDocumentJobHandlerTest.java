package de.palsoftware.yvoke.ingest.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.palsoftware.yvoke.ingest.core.service.DocumentIngestService;
import de.palsoftware.yvoke.shared.jobengine.model.*;
import org.junit.jupiter.api.Test;

public class StandardDocumentJobHandlerTest {

    @Test
    public void registersForStandardKindAndExpectsEntities() {
        StandardDocumentJobHandler handler =
            new StandardDocumentJobHandler(mock(DocumentIngestService.class));

        assertThat(handler.kind()).isEqualTo("standard");
        assertThat(handler.expectsEntities()).isFalse();
    }

    @Test
    public void delegatesRunToService() {
        DocumentIngestService service = mock(DocumentIngestService.class);
        JobContext ctx = mock(JobContext.class);
        IngestionJob job = mock(IngestionJob.class);
        JobCounts counts = new JobCounts(1, 10, 5, 2, 0);
        when(ctx.job()).thenReturn(job);
        when(service.ingest(job, ctx)).thenReturn(counts);

        StandardDocumentJobHandler handler = new StandardDocumentJobHandler(service);
        JobCounts result = handler.run(ctx);

        assertThat(result).isEqualTo(counts);
        verify(service).ingest(job, ctx);
    }
}
