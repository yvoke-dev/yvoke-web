package de.palsoftware.yvoke.ingest.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.jsonobject.core.service.JsonObjectService;
import de.palsoftware.yvoke.shared.jobengine.model.IngestionJob;
import de.palsoftware.yvoke.shared.jobengine.model.JobContext;
import de.palsoftware.yvoke.shared.jobengine.model.JobCounts;
import de.palsoftware.yvoke.shared.jobengine.model.JobStatus;
import de.palsoftware.yvoke.shared.jobengine.repository.JobRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Cooperative cancellation for the JSON import, which had none: the whole import is a single
 * {@code @Transactional} bulk write preceded by a parse that is minutes long for a large
 * {@code .jsonl}, so a stop issued during that parse used to be ignored and the objects were
 * written anyway. There are no batches to check between (unlike the Confluence crawl), so the
 * checkpoint is the one that matters — immediately before the write.
 */
class JsonImportJobHandlerTest {

    private static final UUID JOB_ID = UUID.randomUUID();
    private static final UUID COLLECTION_ID = UUID.randomUUID();

    @TempDir
    Path tempDir;

    private JsonObjectService jsonObjectService;
    private JobRepository jobRepository;
    private JsonImportJobHandler handler;
    private Path jsonFile;

    @BeforeEach
    void setUp() throws Exception {
        jsonObjectService = mock(JsonObjectService.class);
        jobRepository = mock(JobRepository.class);
        handler = new JsonImportJobHandler(jsonObjectService, new ObjectMapper(), jobRepository);
        jsonFile = tempDir.resolve("objects.json");
        Files.writeString(jsonFile, "[{\"name\":\"Alice\"},{\"name\":\"Bob\"}]",
            StandardCharsets.UTF_8);
    }

    private JobContext contextFor(JobStatus liveStatus) {
        JobContext ctx = mock(JobContext.class);
        IngestionJob job = mock(IngestionJob.class);
        when(ctx.job()).thenReturn(job);
        when(job.id()).thenReturn(JOB_ID);
        when(job.sourceRef()).thenReturn(jsonFile.toString());
        when(job.collectionId()).thenReturn(COLLECTION_ID);
        when(job.collectionName()).thenReturn("OIM");
        when(job.tags()).thenReturn(List.of("1.0"));
        when(jobRepository.findStatusById(JOB_ID)).thenReturn(Optional.ofNullable(liveStatus));
        return ctx;
    }

    @Test
    void cancelledImportWritesNothing() {
        JobContext ctx = contextFor(JobStatus.CANCELLED);

        assertThatThrownBy(() -> handler.run(ctx)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("cancelled");

        verify(jsonObjectService, never()).importObjects(any(), anyString(), anyList(), anyString(),
            anyList(), any());
    }

    @Test
    void runningImportStillWrites() {
        JobContext ctx = contextFor(JobStatus.RUNNING);

        JobCounts counts = handler.run(ctx);

        assertThat(counts.jsonObjects()).isEqualTo(2);
        verify(jsonObjectService).importObjects(COLLECTION_ID, "OIM",
            List.of(Map.of("name", "Alice"), Map.of("name", "Bob")), "objects.json", List.of("1.0"),
            null);
    }
}
