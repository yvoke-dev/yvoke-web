package de.palsoftware.yvoke.shared.jobengine.model;

import java.util.UUID;

/**
 * Outcome of an enqueue: the id of the ACTIVE job for the requested unit of work, plus whether this
 * call is what created it.
 *
 * <p>
 * Enqueue is idempotent per {@code (kind, source_ref, collection_id, tags)} while a job for that
 * key is queued or running (unique index {@code ux_ingestion_jobs_active_work}). A duplicate
 * therefore adopts the job already in flight instead of failing — a hard failure would abort the
 * Confluence crawl mid-flight, since it enqueues one page job per crawled page inside a batch
 * consumer. Callers that need to tell the user something different for a duplicate (409, "already
 * running") read {@link #created()}; callers that only need somewhere to redirect read
 * {@link #jobId()}.
 */
public record EnqueueResult(UUID jobId,boolean created){

public static EnqueueResult created(UUID jobId){return new EnqueueResult(jobId,true);}

/** The work was already queued or running; {@code jobId} is that pre-existing job. */
public static EnqueueResult adopted(UUID jobId){return new EnqueueResult(jobId,false);}}
