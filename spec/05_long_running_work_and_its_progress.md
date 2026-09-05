# 5. Long-running work and its progress

**What it is for.** Importing documents and extracting knowledge takes minutes to hours. This runs that
work in the background so nobody waits on a browser tab, and gives a live view of what is running, what
finished, and what went wrong.

**Who uses it.** Administrators who load and maintain the corpus, and integrations pushing content in.
Ordinary chat users never see jobs.

## What you can do

| Capability | What happens |
| --- | --- |
| **Start an import** | Pick a file, the target area and version, and the pipeline. The upload is accepted immediately and the browser lands on that job's live progress page. |
| **Choose how a file is processed** | Four pipelines: **Standard** (searchable documents), **Hierarchical** (browse-only structure), **Custom** (a prepared export carrying its own graph lists), and **JSON Import** (structured records). Choosing one reveals only that pipeline's options. |
| **See everything that has run, is running, or is waiting** | The jobs list shows newest first, twenty per page: type, target area and version, status (Queued, Running, Completed, Failed, Cancelled), a progress bar, and creation time. A separate card counts what is still queued per type. |
| **Watch a job progress live** | The job page updates by itself: a percentage, a one-line message from the current stage, and a diagram whose stages highlight as they are reached and turn green when finished. No refreshing needed. |
| **See what a finished job indexed** | On completion: counts of documents, passages, graph entries and records, plus counts of graph entries the run could not store. A *Run Summary* card keeps the run's own description permanently. |
| **Understand a failure or cancellation** | A failed job shows a red *Failure details* card with the reason in plain words, naming up to twenty offending files. A stopped job shows a neutral *Cancellation details* card instead, never styled as a fault. |
| **Stop a job** | Cancels a single running or queued job after a confirmation, and reports back either that it was cancelled or that it had already finished. |
| **Cancel a whole backlog** | The *Queued Work* card cancels every job still waiting for one type — typically a crawl that queued hundreds of pages before the connector was repointed. Running jobs are untouched. |
| **See per-document import state** | The document list shows each document's import status and whether a graph was extracted: Yes, No, or how many passages failed. This is how an operator spots partial results without going through job history. |
| **Follow imports from outside the web UI** | An integration can submit a job, read its state, and subscribe to a live progress feed. It cannot stop or cancel anything — that stays with administrators. |
| **Avoid accidental duplicate runs** | Asking for work that is already queued or running does not start a second run; the screen opens the existing job, warning that the options chosen this time were not applied. Work is recognised by a stable reference — the same document sent for graph extraction, the same Confluence page, the same job submitted by an integration. Uploads are never recognised: each uploaded file is stored under a fresh name of its own, so re-uploading the same file always starts a second run. |

## How it behaves

- **Starting, viewing, stopping and cancelling jobs all require an administrator or a machine key.** An
  ordinary user account reaches none of it. Confluence imports require an administrator specifically.
- **Four jobs run at the same time by default**; the rest wait and are picked up in submission order.
- **Stopping is cooperative** — the job ends at its next checkpoint, not instantly. Everything already
  written stays in the corpus and remains searchable.
- **A cancelled job is never presented as a failure**, and is shown neutrally with its own stopped-job
  card. It records no counts at all, though: what it managed to index before stopping stays in the corpus
  but is never reported on the job.
- **A job that cannot succeed is refused at submission, not queued and failed later.** Missing or
  unknown AI instructions are caught before the work is accepted, so the queue holds only jobs that had
  everything they needed when they were submitted.
- **There is no automatic retry.** A failed job stays failed until someone submits the same work again.
- **If the service restarts while jobs are running, they go back into the queue and start over from the
  beginning.** An *Attempts* figure records how often a job was started.
- **Re-importing the same source file updates its documents in place**, so re-running after a partial
  failure is safe.
- **Cancelling frees the duplicate check immediately**, so re-submitting at once can create a second job
  that overlaps the one still winding down.
- **A graph extraction that finds nothing at all is reported as a failure**, not as an empty success. A
  crawl that finds no pages likewise fails rather than reporting success.

## Limits

- **Uploads are capped at 200 MB per file.** An empty upload is refused.
- **The live progress view gives up after thirty minutes.** A longer job stops updating on screen and
  needs a page reload to show its real state.
- **If the connection drops the job page freezes on its last known state and does not reconnect.** What it
  shows can be badly out of date until reloaded.
- **Progress stays at *Queued* until the first stage reports**, and a restarted job briefly shows the
  percentage it had reached before. Neither reflects real work at that moment.
- **Counts appear only when a job ends.** While it runs there is no indication of how much has been
  indexed so far.
- **The live message line is not kept.** Once the job ends, only the *Run Summary* survives.
- **A zipped bulk import silently skips files it cannot process**, without counting or naming them.
- **Graph extraction reports what it could not store as skipped rather than failing**, so a run can finish
  green while the counts show it was incomplete.
- **Stopping a crawl does not cancel the page imports it already queued**; those must be cancelled in bulk.
- **Re-running an import with a narrower file filter leaves previously imported documents in place** —
  nothing removes documents a re-run no longer matches.

## Not supported

- No retry button and no automatic re-run of a failed job.
- No pause or resume. A job can only be stopped, and stopping cannot be undone.
- **No notification when a job finishes or fails.** Someone has to open the jobs page and look.
- No scheduled or recurring imports.
- No estimate of time remaining — progress is a share of the pipeline, not a clock.
- No way to reprioritise, reorder or hold a queued job.
- Chat users get no sign that a knowledge area is mid-import; answers are built from whatever is indexed
  at that moment.
- No step-by-step timeline of a past run — only its final counts and one run summary.

---
