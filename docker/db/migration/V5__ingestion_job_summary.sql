-- A job's end-of-run summary, so it survives the run.
--
-- A Confluence crawl reports how many pages it queued, how many already had an active job, how
-- many could not be queued, and how many pages under the root carry none of the include labels.
-- That text was published only as the message field of a progress event: JobContext.report writes
-- step and progress to ingestion_jobs and passes the message to JobProgressBroker, which has no
-- replay buffer, and the terminal snapshot then blanks it because ProgressEvent.of hard-codes
-- message to null. The summary therefore existed for a few milliseconds, and only for an operator
-- who already had the page open — while connectors.html tells them verbatim to "see the sync job"
-- for exactly that unlabelled-page count.
--
-- The common case this hides: re-triggering Sync while the previous crawl's page jobs are still
-- queued makes every page "already queued", so the crawl finishes completed with doc_count = 0 and
-- error NULL — indistinguishable from a crawl that found nothing to do.
--
-- Deliberately NOT reusing `error` for this. A non-empty error is what marks a job as failed, and
-- overloading it would recreate the conflation that rendered a deliberate cancellation under a red
-- "Failure details" heading.

ALTER TABLE ingestion_jobs
    ADD COLUMN summary TEXT;
