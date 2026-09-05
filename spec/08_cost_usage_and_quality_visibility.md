# 8. Cost, usage and quality visibility

**What it is for.** Three questions: what the assistant costs to run, who is using it, and whether the
answers are any good.

**Who uses it.** Administrators and the product owner for spend and usage; the knowledge team for
feedback triage and answer traces; every user rates answers.

> **A note on units.** AI providers bill by amount of text processed. Four rates are set per model: text
> the assistant **read**, text it **wrote**, text **re-used** from a previous request, and text it
> **thought with**. Everything below is expressed in those terms.

## What you can do

| Capability | What happens |
| --- | --- |
| **Spend explorer, three levels of detail** | One screen shows what was spent in a chosen period, at conversation level, message-turn level, or every individual model call. Each view shows total cost, average per item, total text processed, and how many items matched. |
| **Filter spend** | Presets for this month, last month, all time, or a custom range, plus multi-select filters for models and users. At the most granular level, also by call type and **by which surface the call came from — web chat or the desktop app**. At turn level, by playbook or in-depth profile. |
| **See what the money was spent on** | Every call is labelled with the work it did: writing an answer, reviewing one, a specialist answering a sub-question, summarising during import, extracting the graph, and the calls that interpret a question and re-rank search results. |
| **Open the conversation behind any row** | Conversation and message rows link into the conversation, so an expensive turn can be read in full. Rows also show the user, models, in-depth profile and playbooks involved. |
| **Cache savings reported separately** | Where a call was answered by replaying an earlier identical request instead of running the model, it is billed at zero and its list price is reported as *Saved by Cache*, with a hit rate. **No AI service currently in use replays**, so this figure covers past usage only and no longer grows. |
| **Editable model prices, with a coverage check** | Administrators set the four rates per model. The list exports and imports as a file. A companion view lists every model actually used beside every priced one and counts those still missing a price. |
| **Register of all conversations** | Page through every conversation with owner name and email, title, start and last-active times, and whether it came from web chat or the desktop app. Conversations open read-only. |
| **Effort shown on every answer** | Each answer shows how much text it read, re-used, thought with and wrote. Users see effort, never money. |
| **Rate an answer with a comment** | Thumbs up and down on every answer; choosing one opens a comment box. |
| **Feedback dashboard** | The satisfaction ratio and counts of helpful and unhelpful ratings, then filter by rating, review status and time. Each entry shows the comment and links to its conversation. |
| **Mark feedback reviewed, attach notes** | A reviewed switch and a free-text notes field per entry, both saving without leaving the page, so a team can work through unhelpful answers and record what was done. |
| **Search log behind an answer** | For each question: the query, the area and version searched, and the exact sources returned — each clickable through to the passage — plus how the final source set was composed and the rating that answer received. |
| **Full trace of an in-depth answer** | Every orchestrated answer recorded run by run: which profile ran, its status, how many review rounds, resources used, and a step-by-step trace of lead, specialists and reviewer — each step's instructions, its output, and the reviewer's verdict. Failed runs carry a plain-language diagnosis. |
| **Audit trail** | Permanently records who did what and when: deleting documents, areas and versions, configuring and syncing connectors, starting an import **from the admin screens**, stopping or cancelling jobs, and graph processing. |

## How it behaves

- **Spend reflects the billed cost recorded at execution time.** Historical spend in reports is read from
  the persisted cost ledger (`llm_call_logs.total_cost`), preserving the exact pricing applied when
  the call was executed rather than retroactively revaluing past calls upon price edits.
- **A model with no price set counts as zero cost everywhere, silently** — no error, no warning.
- **A cache-replayed call costs nothing and is reported as a saving.** Anything that cannot be positively
  confirmed as replayed is billed in full, so an unclear signal never wipes out a real charge. **No AI
  service currently in use replays a request**, so this describes usage recorded while one did; a repeated
  question is now always re-answered by the model.
- **Only administrators reach any of these screens**, and they can read every user's conversations and
  every logged question. That is a data-protection position to take deliberately, not a technical detail.
- **A bare thumb is stored**, so the satisfaction figures count every rating rather than only commented
  ones — with one gap: the desktop app refuses a thumbs-down that carries no comment, so negatives from
  that surface are undercounted relative to web chat. **Note the discontinuity:** ratings recorded before
  2026-08-08 count web positives only when they carried a comment, so the ratio steps up at that date
  without quality having changed.
- **One rating per answer**; a new thumb replaces the previous one and keeps any comment. Only the
  conversation's owner can rate.
- **Deleting a conversation** destroys its answers, ratings and trace. The spend stays in the totals and
  is still attributed to the person who spent it, but can no longer be traced to that conversation.
- **Deleting a knowledge area also deletes every search record logged against it.**
- **Renaming an in-depth profile splits its cost history** — spend from before the rename stays under the
  old name and is never added to the new one.
- **Search and ranking calls count as spend**, but only when they succeed. A failed search leaves no cost
  record, while a failed answer still records what the provider charged.
- **Every signed-in page states which build it is running**, in the sidebar — both in chat and in the
  administration console — so a report about an answer, a behaviour or a cost can name the build it
  came from without the reporter needing admin access. It is never shown before sign-in, and a build
  that is not a release says so rather than showing the last release's number.

## Limits

- **The per-call view is paged, but the message and conversation views stop at 5,000 rows.** Beyond that
  their totals cover only part of the period, **and nothing on the page says so**.
- **Costs are estimates.** They are calculated from recorded usage and persisted into the ledger at
  execution time using configured model prices; they are not the provider's invoice and will not reconcile
  to the cent.
- **The cost page offers only the explorer.** There is no per-user, per-conversation, per-profile or
  top-spender summary — those would need a new screen.
- **Usage recorded before cache accounting was introduced is valued as if charged in full.** Those older
  records cannot be corrected.
- **Model names are matched exactly.** A typo or trailing space creates a second price entry that never
  matches real usage, while the real model stays unpriced and free. A model's name cannot be edited once
  saved — correcting it means deleting and re-adding.
- **The satisfaction ratio and counts always cover all feedback ever recorded.** The filters change only
  the table beneath them.
- **Not every answer has a search record**, and the link between a recorded search and its answer can be
  lost.
- **⚠ Nothing is ever deleted automatically.** Questions, answers, ratings, search records, traces and
  spend rows are kept indefinitely and stay readable by administrators. There is no retention policy and
  nothing expires.
- **The audit trail and search log page twenty at a time with no search or filter**, and the run list shows
  only the most recent runs.
- **Imports started through the integration interface are not audited** — only those started from the
  admin screens.

## Not supported

- **Budgets, spend limits or alerts.** Nothing warns anyone or stops work when spend rises.
- Charge-back by team or department. Spend attributes to individuals and conversations only.
- Exporting a cost report. Only the model price list can be exported.
- Trend charts or month-over-month comparison. Every view is a table covering one period.
- A record of who changed a model price. Pricing edits are not audited.
- Alerts, assignment or categories for negative feedback. A reviewed switch and a notes field are the
  whole workflow.
- Replying to a user who left feedback.
- Automatic quality scoring. The only quality signals are user ratings and the reviewer verdict.

---
