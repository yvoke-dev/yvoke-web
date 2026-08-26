package de.palsoftware.yvoke.ingest.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.palsoftware.yvoke.ingest.core.service.DocumentIngestService;
import de.palsoftware.yvoke.shared.jobengine.model.IngestionJob;
import de.palsoftware.yvoke.shared.jobengine.model.JobContext;
import de.palsoftware.yvoke.shared.jobengine.model.JobCounts;
import de.palsoftware.yvoke.shared.jobengine.model.JobStep;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link KgDocumentExtractJobHandler} — pins the one behavioral difference from the
 * other handlers ({@code expectsEntities()==true}) plus its custom steps and delegation.
 */
public class KgDocumentExtractJobHandlerTest {

    @Test
    public void registersForKgExtractKindAndExpectsEntities() {
        KgDocumentExtractJobHandler handler =
            new KgDocumentExtractJobHandler(mock(DocumentIngestService.class));

        assertThat(handler.kind()).isEqualTo("kg-extract");
        assertThat(handler.expectsEntities()).isTrue();
        assertThat(handler.steps()).containsExactly(JobStep.CHUNK, JobStep.EXTRACT, JobStep.INJECT);
    }

    @Test
    public void delegatesRunToProcessDocumentKg() {
        DocumentIngestService service = mock(DocumentIngestService.class);
        JobContext ctx = mock(JobContext.class);
        IngestionJob job = new IngestionJob(UUID.randomUUID(), "kg-extract", "sourceRef", List.of(),
            UUID.randomUUID(), "col", null, null, 0, 0, null, null, OffsetDateTime.now(), null,
            null, Map.of(), null);
        JobCounts counts = new JobCounts(1, 0, 9, 14, 0);
        when(ctx.job()).thenReturn(job);
        when(service.processDocumentKg(job, ctx)).thenReturn(counts);

        JobCounts result = new KgDocumentExtractJobHandler(service).run(ctx);

        assertThat(result).isEqualTo(counts);
        verify(service).processDocumentKg(job, ctx);
    }
}
