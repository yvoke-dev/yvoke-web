package de.palsoftware.yvoke.shared.jobengine.repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.shared.db.CollectionIdResolver;
import de.palsoftware.yvoke.shared.jobengine.model.EnqueueRequest;
import de.palsoftware.yvoke.shared.jobengine.model.EnqueueResult;
import de.palsoftware.yvoke.shared.jobengine.model.IngestionJob;
import de.palsoftware.yvoke.shared.jobengine.model.JobCounts;
import de.palsoftware.yvoke.shared.jobengine.model.JobStatus;
import de.palsoftware.yvoke.shared.jobengine.model.JobStep;
import de.palsoftware.yvoke.shared.jobengine.model.QueuedKindSummary;

@Repository
public class JobRepository {

    private static final Logger log = LoggerFactory.getLogger(JobRepository.class);

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final CollectionIdResolver collectionIdResolver;

    public JobRepository(JdbcClient jdbcClient, ObjectMapper objectMapper,
        CollectionIdResolver collectionIdResolver) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.collectionIdResolver = collectionIdResolver;
    }

    /**
     * Enqueues a job, or adopts the one already in flight for the same unit of work.
     *
     * <p>
     * {@code ux_ingestion_jobs_active_work} (V3) permits one queued/running job per
     * {@code (kind, source_ref, collection_id, tags)}. Letting the resulting
     * {@code DataIntegrityViolationException} reach the caller would be catastrophic on the path
     * that enqueues in a LOOP — {@code ConfluenceIngestService} enqueues inside the crawl's batch
     * consumer, so one collision would abort a several-hundred-page crawl mid-flight and leave a
     * partial corpus a retry could not reproduce. So a duplicate is a normal, reportable outcome:
     * the existing job's id comes back with {@code created=false}.
     */
    public EnqueueResult enqueue(EnqueueRequest req) {
        if (req.kind() == null || req.kind().isBlank()) {
            throw new IllegalArgumentException("Job kind cannot be null or blank");
        }
        String settingsJson = serializeSettings(req.settings());

        // Resolve collectionId from collection name (req.collection()). Enqueue requests for
        // unknown collections are rejected (CollectionTagEnqueueValidator normally catches this
        // earlier); requireId is the backstop that fails loudly rather than creating a bare one.
        UUID collectionId = collectionIdResolver.requireId(req.collection());
        String[] tagsArr = req.tags().toArray(new String[0]);

        // Two attempts, because "inserted nothing AND found nothing" is reachable: the active job
        // can reach a terminal status between the INSERT and the SELECT, which takes it out of the
        // partial index and means the work still needs doing. A second pass then inserts cleanly.
        for (int attempt = 0; attempt < 2; attempt++) {
            Optional<UUID> inserted = insertIfNoActiveJob(req, collectionId, tagsArr, settingsJson);
            if (inserted.isPresent()) {
                return EnqueueResult.created(inserted.get());
            }
            Optional<UUID> active =
                findActiveJobId(req.kind(), req.sourceRef(), collectionId, tagsArr);
            if (active.isPresent()) {
                return EnqueueResult.adopted(active.get());
            }
        }
        throw new IllegalStateException("Could not enqueue job (kind=" + req.kind()
            + "): an active job for the same work kept appearing and finishing; retry.");
    }

    /**
     * The ON CONFLICT clause repeats the index predicate verbatim on purpose: a PARTIAL unique
     * index can only be inferred as the arbiter when the statement restates its {@code WHERE}.
     */
    private Optional<UUID> insertIfNoActiveJob(EnqueueRequest req, UUID collectionId,
        String[] tagsArr, String settingsJson) {
        String sql =
            """
                INSERT INTO ingestion_jobs
                    (id, kind, source_ref, tags, collection_id, status, progress, attempts, created_at, settings)
                VALUES
                    (:id, :kind, :source_ref, :tags, :collectionId, 'queued', 0, 0, CURRENT_TIMESTAMP, :settings::jsonb)
                ON CONFLICT (kind, source_ref, collection_id, tags)
                    WHERE status IN ('queued', 'running')
                    DO NOTHING
                RETURNING id
                """;
        return jdbcClient.sql(sql).param("id", UUID.randomUUID()).param("kind", req.kind())
            .param("source_ref", req.sourceRef()).param("tags", tagsArr)
            .param("collectionId", collectionId).param("settings", settingsJson).query(UUID.class)
            .optional();
    }

    /** The queued/running job holding the admission slot for this unit of work, if any. */
    private Optional<UUID> findActiveJobId(String kind, String sourceRef, UUID collectionId,
        String[] tagsArr) {
        return jdbcClient.sql("""
            SELECT id
            FROM ingestion_jobs
            WHERE kind = :kind
              AND source_ref = :source_ref
              AND collection_id = :collectionId
              AND tags = :tags::text[]
              AND status IN ('queued', 'running')
            """).param("kind", kind).param("source_ref", sourceRef)
            .param("collectionId", collectionId).param("tags", tagsArr).query(UUID.class)
            .optional();
    }

    /**
     * Atomically claims the next queued job. The {@code SELECT ... FOR UPDATE SKIP LOCKED} + status
     * {@code UPDATE} must run in one transaction, or two workers can claim the same row — so this
     * is only ever called through
     * {@link de.palsoftware.yvoke.shared.jobengine.service.JobService#claimNext()}, which owns the
     * transaction boundary (transaction demarcation lives on the service layer).
     */
    public Optional<IngestionJob> claimNext() {
        String selectSql = """
            SELECT id
            FROM ingestion_jobs
            WHERE status = 'queued'
            ORDER BY created_at
            FOR UPDATE SKIP LOCKED
            LIMIT 1
            """;
        Optional<UUID> claimedId = jdbcClient.sql(selectSql).query(UUID.class).optional();
        if (claimedId.isEmpty()) {
            return Optional.empty();
        }

        String updateSql = """
            UPDATE ingestion_jobs
            SET status = 'running',
                step = NULL,
                error = NULL,
                started_at = CURRENT_TIMESTAMP,
                attempts = attempts + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :id
            """;
        jdbcClient.sql(updateSql).param("id", claimedId.get()).update();

        return findById(claimedId.get());
    }

    /**
     * Records a progress tick — unless the job was CANCELLED, in which case it is left alone.
     *
     * <p>
     * The third member of the {@link #markCompleted(UUID, JobCounts)} /
     * {@link #markFailed(UUID, String)} guard, for the same cooperative-cancellation reason: a
     * handler stopped between two checkpoints runs on to the next one and reports again, so a crawl
     * cancelled after its last batch would write progress=100 and a final step onto a row that
     * correctly stays 'cancelled'. Status and counts would be right while the progress bar and step
     * text say the work finished.
     */
    /**
     * Records a job's end-of-run summary. Kept separate from {@code error}: a non-empty error is
     * what marks a job failed, so overloading it would dress a successful crawl as a failure.
     */
    public void updateSummary(UUID id, String summary) {
        jdbcClient.sql("""
            UPDATE ingestion_jobs SET summary = :summary, updated_at = CURRENT_TIMESTAMP
            WHERE id = :id
            """).param("id", id).param("summary", summary).update();
    }

    public void updateProgress(UUID id, JobStep step, int progress) {
        String sql = """
            UPDATE ingestion_jobs
            SET step = :step,
                progress = :progress,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND status <> 'cancelled'
            """;
        jdbcClient.sql(sql).param("id", id).param("step", step.dbValue())
            .param("progress", progress).update();
    }

    /**
     * Records success — unless the job was CANCELLED, in which case it is left alone.
     *
     * <p>
     * The mirror of {@link #markFailed(UUID, String)}, and the half that was missing. Cancellation
     * is cooperative, so a handler stopped between two checkpoints still runs to its end: a crawl
     * cancelled after its last batch reaches this method normally and would flip its own row from
     * 'cancelled' back to 'completed' — after the UI had already closed the SSE stream on the
     * cancellation, and while the operator believes the work is stopped.
     */
    public void markCompleted(UUID id, JobCounts counts) {
        String sql = """
            UPDATE ingestion_jobs
            SET status = 'completed',
                progress = 100,
                error = NULL,
                doc_count = :docs,
                chunk_count = :chunks,
                entity_count = :entities,
                edge_count = :edges,
                json_object_count = :jsonObjects,
                skipped_entity_count = :skippedEntities,
                skipped_edge_count = :skippedEdges,
                finished_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND status <> 'cancelled'
            """;
        jdbcClient.sql(sql).param("id", id).param("docs", counts.docs())
            .param("chunks", counts.chunks()).param("entities", counts.entities())
            .param("edges", counts.edges()).param("jsonObjects", counts.jsonObjects())
            .param("skippedEntities", counts.skippedEntities())
            .param("skippedEdges", counts.skippedEdges()).update();
    }

    /**
     * Records a failure — unless the job was CANCELLED, in which case it is left alone.
     *
     * <p>
     * Cancelling a running job is cooperative: the handler notices the status change and throws,
     * and that exception arrives here. Overwriting 'cancelled' with 'failed' would erase the one
     * thing this distinction exists for — telling an operator's stop apart from a genuine ingest
     * failure — and would make every bulk cancel look like a wave of failures.
     */
    public void markFailed(UUID id, String error) {
        String sql = """
            UPDATE ingestion_jobs
            SET status = 'failed',
                error = :error,
                finished_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND status <> 'cancelled'
            """;
        jdbcClient.sql(sql).param("id", id).param("error", error != null ? error : "unknown error")
            .update();
    }

    /**
     * Re-queues ONE job that was reserved but never actually started (an executor rejection). It is
     * deliberately not {@link #requeueOrphans()}: that has no id predicate, so using it here would
     * flip every currently-executing job back to {@code queued} while its thread is still running,
     * and the next poll tick would claim those rows a second time — two workers on one job, which
     * the whole claim protocol exists to prevent. Scoped to {@code running} so it cannot resurrect
     * a job an admin cancelled in the meantime.
     */
    public int requeueJob(UUID id) {
        return jdbcClient.sql("""
            UPDATE ingestion_jobs
            SET status = 'queued',
                step = NULL,
                started_at = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND status = 'running'
            """).param("id", id).update();
    }

    public int requeueOrphans() {
        String sql = """
            UPDATE ingestion_jobs
            SET status = 'queued',
                step = NULL,
                started_at = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE status = 'running'
            """;
        return jdbcClient.sql(sql).update();
    }

    public List<IngestionJob> listJobs(int limit, int offset) {
        String sql =
            """
                SELECT j.id, j.kind, j.source_ref, j.tags, j.collection_id, c.name AS collection, j.status, j.step, j.progress, j.attempts,
                       j.error, j.doc_count, j.chunk_count, j.entity_count, j.edge_count, j.json_object_count,
                       j.skipped_entity_count, j.skipped_edge_count,
                       j.created_at, j.started_at, j.finished_at, j.settings::text AS settings_text,
                       j.summary
                FROM ingestion_jobs j
                JOIN collections c ON j.collection_id = c.id
                ORDER BY j.created_at DESC, j.id ASC
                LIMIT :limit OFFSET :offset
                """;
        return jdbcClient.sql(sql).param("limit", limit).param("offset", offset)
            .query(new JobRowMapper(objectMapper)).list();
    }

    public long countJobs() {
        return jdbcClient.sql("SELECT count(*) FROM ingestion_jobs").query(Long.class).single();
    }

    public Optional<IngestionJob> findById(UUID id) {
        String sql =
            """
                SELECT j.id, j.kind, j.source_ref, j.tags, j.collection_id, c.name AS collection, j.status, j.step, j.progress, j.attempts,
                       j.error, j.doc_count, j.chunk_count, j.entity_count, j.edge_count, j.json_object_count,
                       j.skipped_entity_count, j.skipped_edge_count,
                       j.created_at, j.started_at, j.finished_at, j.settings::text AS settings_text,
                       j.summary
                FROM ingestion_jobs j
                JOIN collections c ON j.collection_id = c.id
                WHERE j.id = :id
                """;
        return jdbcClient.sql(sql).param("id", id).query(new JobRowMapper(objectMapper)).optional();
    }

    /**
     * Stops one queued or running job. The row becomes CANCELLED, not FAILED: an operator's stop is
     * not an ingest failure, and the jobs list is unreadable if every stopped page job of a
     * repointed connector shows up as red.
     */
    public int stopJob(UUID id) {
        return jdbcClient.sql("""
            UPDATE ingestion_jobs
            SET status = 'cancelled',
                error = 'Stopped by administrator',
                finished_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND (status = 'running' OR status = 'queued')
            """).param("id", id).update();
    }

    /**
     * Bulk-cancels QUEUED jobs of one kind and returns how many were cancelled.
     *
     * <p>
     * Running jobs are deliberately untouched: they are mid-write, and stopping one is a per-job
     * decision ({@link #stopJob(UUID)}) that the handler has to cooperate with. Queued jobs have
     * not started, so cancelling them is free — which is the whole point after a 600-page crawl
     * whose connector has since been repointed.
     *
     * <p>
     * A bare kind ({@code confluence-page-import}) matches every instance; a qualified one
     * ({@code confluence-page-import:icc-wiki}) matches exactly that instance. The base-kind match
     * uses {@code split_part} rather than a LIKE prefix so a kind containing {@code %} or {@code _}
     * cannot widen the match.
     */
    public List<UUID> cancelQueued(String kind) {
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("Job kind cannot be null or blank");
        }
        String trimmed = kind.trim();
        boolean matchAllInstances = !trimmed.contains(":");
        return jdbcClient.sql("""
            UPDATE ingestion_jobs
            SET status = 'cancelled',
                error = 'Cancelled by administrator',
                finished_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE status = 'queued'
              AND (kind = :kind
                   OR (:matchAllInstances::boolean AND split_part(kind, ':', 1) = :kind))
                RETURNING id
            """).param("kind", trimmed).param("matchAllInstances", matchAllInstances)
            .query(UUID.class).list();
    }

    /**
     * Bulk-cancels QUEUED jobs whose settings snapshot carries {@code key = value}, returning how
     * many were cancelled.
     *
     * <p>
     * The companion to {@link #cancelQueued(String)} for the case a kind cannot express: a job's
     * kind carries a human-editable LABEL (the Confluence instance slug), so renaming it orphans
     * every job already queued under the old one — a kind-based cancel silently matches nothing and
     * those jobs are left to fail individually at execution. A snapshotted id cannot be edited, so
     * it is the handle that survives.
     *
     * <p>
     * Deliberately generic over {@code (key, value)} rather than taking a Confluence instance:
     * {@code shared} is cross-cutting infrastructure and must not depend on a domain package
     * (ArchitectureTest enforces it). The key is BOUND, not interpolated, so it cannot carry SQL.
     *
     * <p>
     * Running jobs are untouched for the same reason as in {@link #cancelQueued(String)}: they are
     * mid-write and stop cooperatively. A NULL {@code settings} yields NULL from {@code ->>} and so
     * never matches, which is correct — a job with no snapshot has no id to be identified by.
     */
    public List<UUID> cancelQueuedBySetting(String key, String value) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Settings key cannot be null or blank");
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Settings value cannot be null or blank");
        }
        return jdbcClient.sql("""
            UPDATE ingestion_jobs
            SET status = 'cancelled',
                error = 'Cancelled by administrator',
                finished_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE status = 'queued'
              AND settings ->> :key = :value
                RETURNING id
            """).param("key", key.trim()).param("value", value.trim()).query(UUID.class).list();
    }

    /** Current status of a job, for cooperative cancellation checks. Empty if the row is gone. */
    public Optional<JobStatus> findStatusById(UUID id) {
        return jdbcClient.sql("SELECT status FROM ingestion_jobs WHERE id = :id").param("id", id)
            .query(String.class).optional().map(JobStatus::fromDbValue);
    }

    /**
     * Queued work grouped by kind, for the jobs admin page — one aggregate query, never a count per
     * row.
     */
    public List<QueuedKindSummary> listQueuedKinds() {
        return jdbcClient.sql("""
            SELECT kind, count(*) AS queued_count
            FROM ingestion_jobs
            WHERE status = 'queued'
            GROUP BY kind
            ORDER BY count(*) DESC, kind ASC
            """).query(
            (rs, rowNum) -> new QueuedKindSummary(rs.getString("kind"), rs.getLong("queued_count")))
            .list();
    }

    private String serializeSettings(Map<String, Object> settings) {
        if (settings == null || settings.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(settings);
        } catch (Exception e) {
            log.warn("Failed to serialize job settings", e);
            return "{}";
        }
    }
}
