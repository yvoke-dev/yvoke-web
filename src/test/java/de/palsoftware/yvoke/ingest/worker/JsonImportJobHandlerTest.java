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
import static org.mockito.Mockito.times;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.ingest.core.UploadPathGuard;
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
        handler = new JsonImportJobHandler(jsonObjectService, new ObjectMapper(), jobRepository,
            new UploadPathGuard(tempDir.toString()));
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

    /**
     * SEC: a job's sourceRef is attacker-influenced — {@code POST /api/jobs/v1} lets any
     * ROLE_INGEST/USER/ADMIN caller name an arbitrary path. Every other file-backed handler
     * resolves it through {@link UploadPathGuard}; this one read {@code Path.of(sourceRef)}
     * directly, so an absolute path outside {@code app.upload-dir} was ingested into a collection
     * the caller can then search — an arbitrary-file-read primitive. The guard MUST reject it
     * before any read.
     */
    @Test
    void aSourceRefOutsideTheUploadDirectoryIsRejectedBeforeAnyRead() throws Exception {
        Path outside = Files.createTempDirectory("json-import-escape").resolve("secrets.json");
        Files.writeString(outside, "[{\"secret\":\"value\"}]", StandardCharsets.UTF_8);

        JobContext ctx = mock(JobContext.class);
        IngestionJob job = mock(IngestionJob.class);
        when(ctx.job()).thenReturn(job);
        when(job.id()).thenReturn(JOB_ID);
        when(job.sourceRef()).thenReturn(outside.toString());

        assertThatThrownBy(() -> handler.run(ctx)).isInstanceOf(SecurityException.class)
            .hasMessageContaining("outside the permitted upload directory");

        verify(jsonObjectService, never()).importObjects(any(), anyString(), anyList(), anyString(),
            anyList(), any());
    }

    /**
     * A file that parses as neither a JSON array nor a single JSON object must FAIL the job, not
     * import zero rows. The difference is everything the operator has to go on: swallowing the
     * parse error finishes the job GREEN with {@code jsonObjects: 0}, and because
     * {@link JsonObjectService#importObjects} returns immediately for an empty list, nothing is
     * written, nothing is logged as wrong, and the collection is left in exactly the state it would
     * be in if the corpus had never been imported — indistinguishable from "not ingested yet", with
     * a success badge on top of it. The thrown message is also the ONLY place the Jackson diagnosis
     * (which token, at which line/column) ever reaches a human: {@code JobService} turns the
     * exception into the job's {@code error} column, which is what the admin job-detail page shows.
     *
     * <p>
     * No other test in this file reaches the fallback at all — they all stage a well-formed array —
     * so the inner {@code catch}, the one branch that decides fail-versus-silently-empty, has never
     * executed here. That is the same shape as the {@code jsonUniqueField} form-binding gap: one
     * unexercised branch between a producer and a consumer that only a human keeps honest.
     */
    @Test
    void aFileThatIsNeitherAnArrayNorAnObjectFailsInsteadOfImportingNothing() throws Exception {
        jsonFile = tempDir.resolve("garbage.json");
        Files.writeString(jsonFile, "not json at all", StandardCharsets.UTF_8);
        JobContext ctx = contextFor(JobStatus.RUNNING);

        assertThatThrownBy(() -> handler.run(ctx)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(
                "File must contain a JSON array of objects or a single JSON object");

        verify(jsonObjectService, never()).importObjects(any(), anyString(), anyList(), anyString(),
            anyList(), any());
    }

    /**
     * S8 r39: the parser is chosen by the staged file's EXTENSION, and an extension arriving
     * uppercase is ordinary — Windows exports, archive tools and hand-renamed corpus drops all
     * produce {@code .JSONL}. The two branches are not interchangeable: the else-branch parses the
     * file as a single JSON value, and Jackson's {@code FAIL_ON_TRAILING_TOKENS} is OFF by default,
     * so a newline-delimited file falls back to the single-object read, silently consumes only the
     * FIRST record and discards the rest. A 200,000-line import therefore lands one object,
     * {@code JobCounts.jsonObjects} reports 1, and the job finishes green — a corpus that is
     * 99.999% missing with no error anywhere and nothing to compare the count against.
     *
     * <p>
     * Dropping {@code toLowerCase()} looks like removing a redundant allocation, and nothing in the
     * test suite covers it: this file's fixtures are all {@code objects.json}, so the
     * {@code .jsonl} branch is entered by exactly one spelling of one filename and is otherwise
     * never executed at all.
     */
    @Test
    void anUppercaseJsonlFileIsStillParsedLineByLine() throws Exception {
        jsonFile = tempDir.resolve("DATA.JSONL");
        Files.writeString(jsonFile, "{\"name\":\"Alice\"}\n{\"name\":\"Bob\"}\n",
            StandardCharsets.UTF_8);
        JobContext ctx = contextFor(JobStatus.RUNNING);

        JobCounts counts = handler.run(ctx);

        assertThat(counts.jsonObjects())
            .as("both lines must be parsed — the JSON branch would silently keep only the first")
            .isEqualTo(2);
        verify(jsonObjectService).importObjects(COLLECTION_ID, "OIM",
            List.of(Map.of("name", "Alice"), Map.of("name", "Bob")), "DATA.JSONL", List.of("1.0"),
            null);
    }

    @Test
    void cancelledImportWritesNothing() {
        JobContext ctx = contextFor(JobStatus.CANCELLED);

        assertThatThrownBy(() -> handler.run(ctx)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("cancelled");

        verify(jsonObjectService, never()).importObjects(any(), anyString(), anyList(), anyString(),
            anyList(), any());
    }

    /**
     * The {@code .jsonl} suffix is matched case-INSENSITIVELY, and it is the only thing that
     * selects the line-by-line parser. An operator uploading {@code objects.JSONL} (Windows tooling
     * and export scripts upper-case suffixes routinely) would otherwise fall into the JSON-array
     * branch, where Jackson reads the FIRST object and stops: {@code FAIL_ON_TRAILING_TOKENS} is
     * off by default in Jackson 2, so the remaining lines are not an error — they are simply
     * discarded. A 20,000-line corpus then imports as one row and the job reports success with
     * {@code jsonObjects: 1}. Nothing anywhere compares the file's line count to the imported
     * count, so the loss is invisible until an agent is told the data does not exist.
     *
     * <p>
     * The blank line is not padding either: real exports end with a trailing newline and are often
     * concatenated with blank separators, and an unguarded {@code readValue("")} throws, which
     * turns a cosmetic whitespace difference into a failed job. Both halves are asserted on the
     * OUTCOME (the maps that actually reach the service and the reported count), not on the branch
     * taken — asserting only that the filename was echoed would pass for either parser.
     */
    @Test
    void aJsonlFileIsParsedLineByLineWithBlankLinesSkippedRegardlessOfSuffixCase()
        throws Exception {
        jsonFile = tempDir.resolve("objects.JSONL");
        Files.writeString(jsonFile, "{\"name\":\"Alice\"}\n\n{\"name\":\"Bob\"}\n",
            StandardCharsets.UTF_8);
        JobContext ctx = contextFor(JobStatus.RUNNING);

        JobCounts counts = handler.run(ctx);

        assertThat(counts.jsonObjects()).isEqualTo(2);
        verify(jsonObjectService).importObjects(COLLECTION_ID, "OIM",
            List.of(Map.of("name", "Alice"), Map.of("name", "Bob")), "objects.JSONL",
            List.of("1.0"), null);
    }

    /**
     * The non-{@code .jsonl} branch is a two-step contract, and both steps carry weight. A file
     * holding ONE object is a legitimate import (a single exported record, a hand-written fixture),
     * so the array parse failing is not an error yet — dropping that fallback turns every
     * single-object upload into a failed job with a message about arrays that does not describe the
     * file the operator actually uploaded. But the fallback must stay narrow: a JSON array of
     * SCALARS parses as neither shape, and it has to fail loudly rather than import nothing,
     * because a green job over an empty collection is indistinguishable from a corpus that was
     * never ingested.
     *
     * <p>
     * The two halves are asserted together on purpose — they are the two exits of the same
     * {@code try/catch}, and widening one at the cost of the other is exactly the refactor that
     * would slip through. The closing {@code times(1)} is the load-bearing assertion of the second
     * half: it proves the scalar array reached the service ZERO times, which a bare
     * {@code assertThatThrownBy} would not (the throw could have come after a write).
     */
    @Test
    void aSingleJsonObjectFileIsImportedAsOneRowAndAScalarArrayFailsTheJob() throws Exception {
        jsonFile = tempDir.resolve("single.json");
        Files.writeString(jsonFile, "{\"name\":\"Solo\"}", StandardCharsets.UTF_8);

        JobCounts counts = handler.run(contextFor(JobStatus.RUNNING));

        assertThat(counts.jsonObjects()).isEqualTo(1);
        verify(jsonObjectService).importObjects(COLLECTION_ID, "OIM",
            List.of(Map.of("name", "Solo")), "single.json", List.of("1.0"), null);

        jsonFile = tempDir.resolve("scalars.json");
        Files.writeString(jsonFile, "[1, 2, 3]", StandardCharsets.UTF_8);
        JobContext scalars = contextFor(JobStatus.RUNNING);

        assertThatThrownBy(() -> handler.run(scalars)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(
                "File must contain a JSON array of objects or a single JSON object");

        // Still exactly the ONE call from the single-object half: the scalar array wrote nothing.
        verify(jsonObjectService, times(1)).importObjects(any(), anyString(), anyList(),
            anyString(), anyList(), any());
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
