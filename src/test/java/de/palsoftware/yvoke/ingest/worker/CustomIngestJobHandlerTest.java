package de.palsoftware.yvoke.ingest.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.palsoftware.yvoke.ingest.core.service.CustomIngestService;
import de.palsoftware.yvoke.shared.jobengine.model.IngestionJob;
import de.palsoftware.yvoke.shared.jobengine.model.JobContext;
import de.palsoftware.yvoke.shared.jobengine.model.JobCounts;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link CustomIngestJobHandler} — kind registration + pure delegation. */
public class CustomIngestJobHandlerTest {

    @Test
    public void registersForCustomKindAndSkipsEntities() {
        CustomIngestJobHandler handler =
            new CustomIngestJobHandler(mock(CustomIngestService.class));

        assertThat(handler.kind()).isEqualTo("custom");
        assertThat(handler.expectsEntities()).isFalse();
    }

    @Test
    public void delegatesRunToServiceWithJobAndContext() {
        CustomIngestService service = mock(CustomIngestService.class);
        JobContext ctx = mock(JobContext.class);
        IngestionJob job = new IngestionJob(UUID.randomUUID(), "custom", "sourceRef", List.of(),
            UUID.randomUUID(), "col", null, null, 0, 0, null, null, OffsetDateTime.now(), null,
            null, Map.of(), null);
        JobCounts counts = new JobCounts(3, 42, 5, 8, 0);
        when(ctx.job()).thenReturn(job);
        when(service.ingest(job, ctx)).thenReturn(counts);

        JobCounts result = new CustomIngestJobHandler(service).run(ctx);

        assertThat(result).isEqualTo(counts);
        verify(service).ingest(job, ctx);
    }
}
