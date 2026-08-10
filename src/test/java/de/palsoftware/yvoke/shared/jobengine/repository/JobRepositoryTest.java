package de.palsoftware.yvoke.shared.jobengine.repository;

import de.palsoftware.yvoke.shared.jobengine.*;
import de.palsoftware.yvoke.shared.jobengine.model.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.shared.db.CollectionIdResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Assertions;

public class JobRepositoryTest {

    private JdbcClient jdbcClient;
    private ObjectMapper objectMapper;
    private CollectionIdResolver collectionIdResolver;
    private JobRepository jobRepository;

    @BeforeEach
    public void setUp() {
        jdbcClient = mock(JdbcClient.class);
        objectMapper = new ObjectMapper();
        collectionIdResolver = mock(CollectionIdResolver.class);
        jobRepository = new JobRepository(jdbcClient, objectMapper, collectionIdResolver);
    }

    @Test
    public void testEnqueue_blankKindThrowsException() {
        // The engine routes by the registered handler's kind string and is otherwise agnostic about
        // kind vocabulary (kinds are owned per-domain, e.g. IngestJobKind). The only enqueue-time
        // guard is that the kind must be present; unknown kinds fail later at execution ("no
        // handler").
        EnqueueRequest req = new EnqueueRequest("  ", "source-ref", "some-tag", "collection");

        Assertions.assertThrows(IllegalArgumentException.class, () -> {
            jobRepository.enqueue(req);
        });
    }

    @Test
    public void testEnqueue_validKindSucceeds() {
        UUID inserted = UUID.randomUUID();
        stubJdbc(Optional.of(inserted), Optional.empty());
        // The collection must pre-exist: enqueue resolves it via CollectionIdResolver and no longer
        // auto-creates missing collections.
        when(collectionIdResolver.requireId("collection")).thenReturn(UUID.randomUUID());

        EnqueueRequest req = new EnqueueRequest(ItTestJobHandler.KIND + ":param", "source-ref",
            "some-tag", "collection");
        EnqueueResult result = jobRepository.enqueue(req);

        assertThat(result).isEqualTo(EnqueueResult.created(inserted));
        verify(jdbcClient).sql(contains("INSERT INTO ingestion_jobs"));
    }

    /**
     * The INSERT must arbitrate on the PARTIAL admission-control index, and a partial index is only
     * inferred as arbiter when the statement repeats its predicate — so the predicate being present
     * in the SQL is a correctness requirement, not formatting.
     */
    @Test
    public void testEnqueue_insertArbitratesOnThePartialActiveWorkIndex() {
        stubJdbc(Optional.of(UUID.randomUUID()), Optional.empty());
        when(collectionIdResolver.requireId("collection")).thenReturn(UUID.randomUUID());

        jobRepository.enqueue(
            new EnqueueRequest(ItTestJobHandler.KIND, "source-ref", "some-tag", "collection"));

        verify(jdbcClient)
            .sql(argThat(sql -> sql.contains("ON CONFLICT (kind, source_ref, collection_id, tags)")
                && sql.contains("WHERE status IN ('queued', 'running')")
                && sql.contains("DO NOTHING") && sql.contains("RETURNING id")));
    }

    /**
     * S7.3: enqueue is a LOOP of two passes, and the second pass is the whole point.
     *
     * <p>
     * "Inserted nothing AND found nothing" is a real, reachable state, not a paranoid branch: the
     * job holding the admission slot can reach a terminal status between the {@code INSERT ... ON
     * CONFLICT DO NOTHING} and the {@code SELECT}, which takes it out of the partial index. The
     * conflict is real, the lookup is empty, and the work still needs doing — so a second pass
     * inserts cleanly. Collapse the loop to one pass (the natural "why is this a for-loop?" edit)
     * and that race becomes an {@link IllegalStateException}, which nothing maps: {@code POST
     * /api/jobs/v1} answers 500, and on the Confluence crawl — which enqueues INSIDE its batch
     * consumer, one call per page — a single unlucky page would have abandoned the rest of the tree
     * before the crawl's own catch was added. The window is widest exactly when the queue is
     * busiest.
     *
     * <p>
     * {@code testEnqueue_validKindSucceeds} and {@code testEnqueue_duplicateAdoptsTheActiveJob}
     * both stub a SINGLE insert/select pair, so pass two and the terminal throw are unexecuted
     * today; both of those tests stay green with the loop bound reduced to one. Four consecutive
     * empty answers below are what makes the give-up condition observable as "both passes failed"
     * rather than "one did".
     */
    @Test
    public void theAdoptPathRetriesTheInsertSelectPassBeforeGivingUp() {
        JdbcClient.StatementSpec statementSpec = mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<UUID> mapped = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbcClient.sql(anyString())).thenReturn(statementSpec);
        when(statementSpec.param(anyString(), any())).thenReturn(statementSpec);
        when(statementSpec.query(UUID.class)).thenReturn(mapped);
        when(collectionIdResolver.requireId("collection")).thenReturn(UUID.randomUUID());

        UUID insertedOnSecondPass = UUID.randomUUID();
        // Pass 1: the INSERT hits the partial index (empty), and the SELECT finds nothing because
        // the job holding the slot finished in between. Pass 2: the slot is free, the INSERT lands.
        doReturn(Optional.empty(), Optional.empty(), Optional.of(insertedOnSecondPass)).when(mapped)
            .optional();

        EnqueueResult result = jobRepository.enqueue(
            new EnqueueRequest(ItTestJobHandler.KIND, "source-ref", "some-tag", "collection"));

        assertThat(result)
            .as("a duplicate that finishes between the INSERT and the SELECT must be re-inserted, "
                + "not reported as a server fault")
            .isEqualTo(EnqueueResult.created(insertedOnSecondPass));

        // Only when BOTH passes come up empty is it an IllegalStateException.
        doReturn(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty())
            .when(mapped).optional();

        assertThatThrownBy(() -> jobRepository.enqueue(
            new EnqueueRequest(ItTestJobHandler.KIND, "source-ref", "some-tag", "collection")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("kept appearing and finishing");
    }

    /**
     * A duplicate enqueue adopts the active job instead of throwing: the Confluence crawl enqueues
     * inside its batch consumer, so a thrown DataIntegrityViolationException would abandon the rest
     * of the page tree.
     */
    @Test
    public void testEnqueue_duplicateAdoptsTheActiveJob() {
        UUID active = UUID.randomUUID();
        stubJdbc(Optional.empty(), Optional.of(active));
        when(collectionIdResolver.requireId("collection")).thenReturn(UUID.randomUUID());

        EnqueueResult result = jobRepository.enqueue(
            new EnqueueRequest(ItTestJobHandler.KIND, "source-ref", "some-tag", "collection"));

        assertThat(result).isEqualTo(EnqueueResult.adopted(active));
    }

    @Test
    public void testCancelQueued_rejectsBlankKind() {
        assertThatThrownBy(() -> jobRepository.cancelQueued("  "))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> jobRepository.cancelQueued(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * The base-kind match must not go through LIKE: a kind carrying {@code %} or {@code _} would
     * then cancel other kinds' queues.
     */
    @SuppressWarnings("unchecked")
    private static JdbcClient.MappedQuerySpec<UUID> mockUuidQuery(int rows) {
        JdbcClient.MappedQuerySpec<UUID> query = mock(JdbcClient.MappedQuerySpec.class);
        when(query.list())
            .thenReturn(IntStream.range(0, rows).mapToObj(i -> UUID.randomUUID()).toList());
        return query;
    }

    @Test
    public void testCancelQueued_matchesInstancesWithoutLikeWildcards() {
        JdbcClient.StatementSpec statementSpec = mock(JdbcClient.StatementSpec.class);
        when(jdbcClient.sql(anyString())).thenReturn(statementSpec);
        when(statementSpec.param(anyString(), any())).thenReturn(statementSpec);
        JdbcClient.MappedQuerySpec<UUID> query = mockUuidQuery(3);
        when(statementSpec.query(UUID.class)).thenReturn(query);

        assertThat(jobRepository.cancelQueued("confluence-page-import")).hasSize(3);

        verify(jdbcClient).sql(contains("split_part(kind, ':', 1) = :kind"));
        verify(jdbcClient).sql(argThat(sql -> !sql.contains("LIKE")));
        verify(statementSpec).param("matchAllInstances", true);
    }

    @Test
    public void testCancelQueued_qualifiedKindTargetsThatInstanceOnly() {
        JdbcClient.StatementSpec statementSpec = mock(JdbcClient.StatementSpec.class);
        when(jdbcClient.sql(anyString())).thenReturn(statementSpec);
        when(statementSpec.param(anyString(), any())).thenReturn(statementSpec);
        JdbcClient.MappedQuerySpec<UUID> query = mockUuidQuery(1);
        when(statementSpec.query(UUID.class)).thenReturn(query);

        jobRepository.cancelQueued("confluence-page-import:icc-wiki");

        verify(statementSpec).param("matchAllInstances", false);
        verify(statementSpec).param("kind", "confluence-page-import:icc-wiki");
    }

    /**
     * Only queued work is bulk-cancellable; a running job is mid-write and needs a per-job stop.
     */
    @Test
    public void testCancelQueued_neverTouchesRunningJobs() {
        JdbcClient.StatementSpec statementSpec = mock(JdbcClient.StatementSpec.class);
        when(jdbcClient.sql(anyString())).thenReturn(statementSpec);
        when(statementSpec.param(anyString(), any())).thenReturn(statementSpec);
        JdbcClient.MappedQuerySpec<UUID> query = mockUuidQuery(0);
        when(statementSpec.query(UUID.class)).thenReturn(query);

        jobRepository.cancelQueued("kg-extract");

        verify(jdbcClient).sql(contains("WHERE status = 'queued'"));
    }

    /**
     * Stopping a job records CANCELLED, and a later failure must not overwrite it — otherwise an
     * operator's stop is indistinguishable from a genuine ingest failure the moment the handler
     * notices the cancellation and throws.
     */
    @Test
    public void testStopJobWritesCancelledAndMarkFailedLeavesItAlone() {
        JdbcClient.StatementSpec statementSpec = mock(JdbcClient.StatementSpec.class);
        when(jdbcClient.sql(anyString())).thenReturn(statementSpec);
        when(statementSpec.param(anyString(), any())).thenReturn(statementSpec);
        when(statementSpec.update()).thenReturn(1);

        jobRepository.stopJob(UUID.randomUUID());
        verify(jdbcClient).sql(contains("SET status = 'cancelled'"));

        jobRepository.markFailed(UUID.randomUUID(), "boom");
        verify(jdbcClient).sql(contains("WHERE id = :id AND status <> 'cancelled'"));
    }

    /**
     * The SUCCESS side of the same guard, and the one that was missing: a cancelled job whose
     * handler then finishes normally (the crawl was stopped after its last batch) would flip back
     * to 'completed' — after the UI had already closed its SSE stream on the cancellation.
     */
    @Test
    public void testMarkCompletedLeavesACancelledJobAlone() {
        JdbcClient.StatementSpec statementSpec = mock(JdbcClient.StatementSpec.class);
        when(jdbcClient.sql(anyString())).thenReturn(statementSpec);
        when(statementSpec.param(anyString(), any())).thenReturn(statementSpec);
        when(statementSpec.update()).thenReturn(0);

        jobRepository.markCompleted(UUID.randomUUID(), new JobCounts(1, 1, 0, 0, 0));

        verify(jdbcClient).sql(argThat(sql -> sql.contains("SET status = 'completed'")
            && sql.contains("WHERE id = :id AND status <> 'cancelled'")));
    }

    /**
     * The PROGRESS side of the same guard. Cancellation is cooperative, so a handler stopped
     * between two checkpoints keeps reporting: a crawl cancelled after its last batch calls
     * {@code ctx.report(...)} one more time, which would write progress=100 and a final step onto a
     * row whose status correctly stays 'cancelled'. The status and counts would be right and only
     * the progress bar and step text would lie, which is the worst kind of wrong.
     */
    @Test
    public void testUpdateProgressLeavesACancelledJobAlone() {
        JdbcClient.StatementSpec statementSpec = mock(JdbcClient.StatementSpec.class);
        when(jdbcClient.sql(anyString())).thenReturn(statementSpec);
        when(statementSpec.param(anyString(), any())).thenReturn(statementSpec);
        when(statementSpec.update()).thenReturn(0);

        jobRepository.updateProgress(UUID.randomUUID(), JobStep.INSERT, 100);

        verify(jdbcClient).sql(argThat(sql -> sql.contains("SET step = :step")
            && sql.contains("WHERE id = :id AND status <> 'cancelled'")));
    }

    /** Stubs the enqueue round-trip: the INSERT ... RETURNING, then the active-job lookup. */
    private void stubJdbc(Optional<UUID> insertReturns, Optional<UUID> lookupReturns) {
        JdbcClient.StatementSpec statementSpec = mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<UUID> mappedQuerySpec = mock(JdbcClient.MappedQuerySpec.class);

        when(jdbcClient.sql(anyString())).thenReturn(statementSpec);
        when(statementSpec.param(anyString(), any())).thenReturn(statementSpec);
        when(statementSpec.update()).thenReturn(1);
        when(statementSpec.query(UUID.class)).thenReturn(mappedQuerySpec);
        when(mappedQuerySpec.optional()).thenReturn(insertReturns, lookupReturns);
    }
}
