package de.palsoftware.yvoke.ingest.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.palsoftware.yvoke.ingest.core.confluence.ConfluenceIngestService;
import de.palsoftware.yvoke.shared.jobengine.model.IngestionJob;
import de.palsoftware.yvoke.shared.jobengine.model.JobContext;
import de.palsoftware.yvoke.shared.jobengine.model.JobCounts;
import de.palsoftware.yvoke.shared.jobengine.model.JobStatus;
import de.palsoftware.yvoke.shared.jobengine.model.JobStep;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ConfluencePageImportJobHandler} — the only Confluence handler with real
 * branch logic (settings extraction, null-title default, missing-pageId guard).
 */
public class ConfluencePageImportJobHandlerTest {

    private static IngestionJob jobWithSettings(Map<String, Object> settings) {
        return new IngestionJob(UUID.randomUUID(), "confluence-page-import", "sourceRef", List.of(),
            UUID.randomUUID(), "col", JobStatus.RUNNING, null, 0, 0, null, null,
            OffsetDateTime.now(), null, null, settings, null);
    }

    @Test
    public void registersForPageImportKindWithChunkEmbedInjectSteps() {
        ConfluencePageImportJobHandler handler =
            new ConfluencePageImportJobHandler(mock(ConfluenceIngestService.class));

        assertThat(handler.kind()).isEqualTo("confluence-page-import");
        assertThat(handler.expectsEntities()).isFalse();
        assertThat(handler.steps()).containsExactly(JobStep.CHUNK, JobStep.EMBED, JobStep.INJECT);
    }

    @Test
    public void delegatesToIngestPageWithPageIdAndTitle() {
        ConfluenceIngestService service = mock(ConfluenceIngestService.class);
        JobContext ctx = mock(JobContext.class);
        IngestionJob job = jobWithSettings(Map.of("pageId", "12345", "title", "OAuth Guide"));
        JobCounts counts = new JobCounts(1, 12, 0, 0, 0);
        when(ctx.job()).thenReturn(job);
        when(service.ingestPage(ctx, "12345", "OAuth Guide")).thenReturn(counts);

        JobCounts result = new ConfluencePageImportJobHandler(service).run(ctx);

        assertThat(result).isEqualTo(counts);
        verify(service).ingestPage(ctx, "12345", "OAuth Guide");
    }

    @Test
    public void defaultsTitleToUnknownPageWhenTitleAbsent() {
        ConfluenceIngestService service = mock(ConfluenceIngestService.class);
        JobContext ctx = mock(JobContext.class);
        Map<String, Object> settings = new HashMap<>();
        settings.put("pageId", "999"); // no "title" key
        IngestionJob job = jobWithSettings(settings);
        JobCounts counts = new JobCounts(1, 3, 0, 0, 0);
        when(ctx.job()).thenReturn(job);
        when(service.ingestPage(ctx, "999", "Unknown Page")).thenReturn(counts);

        JobCounts result = new ConfluencePageImportJobHandler(service).run(ctx);

        assertThat(result).isEqualTo(counts);
        verify(service).ingestPage(ctx, "999", "Unknown Page");
    }

    @Test
    public void throwsWhenPageIdMissing() {
        ConfluenceIngestService service = mock(ConfluenceIngestService.class);
        JobContext ctx = mock(JobContext.class);
        IngestionJob job = jobWithSettings(Map.of("title", "No id here"));
        when(ctx.job()).thenReturn(job);

        ConfluencePageImportJobHandler handler = new ConfluencePageImportJobHandler(service);

        assertThatThrownBy(() -> handler.run(ctx)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Missing pageId");
        verifyNoInteractions(service);
    }

    @Test
    public void throwsWhenPageIdBlank() {
        ConfluenceIngestService service = mock(ConfluenceIngestService.class);
        JobContext ctx = mock(JobContext.class);
        IngestionJob job = jobWithSettings(Map.of("pageId", "   ", "title", "x"));
        when(ctx.job()).thenReturn(job);

        assertThatThrownBy(() -> new ConfluencePageImportJobHandler(service).run(ctx))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Missing pageId");
        verifyNoInteractions(service);
    }
}
