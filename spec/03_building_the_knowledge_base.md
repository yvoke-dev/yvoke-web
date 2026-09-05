# 3. Building the knowledge base

**What it is for.** Everything that puts content into Yvoke: knowledge areas and versions, document and
archive imports, the Confluence connector, knowledge-graph extraction and structured-record imports.

**Who uses it.** Administrators onboard and curate content, in the screens or through the integration
interface. Automation scripts import with a machine key. Everyone else sees only the result.

## What you can do

| Capability | What happens |
| --- | --- |
| **Create knowledge areas** | An administrator registers an area with a name and description; it then appears wherever content can be targeted. Names are unique regardless of capitalisation, and *All* and *Both* are reserved. |
| **Declare the versions an area holds** | Versions are declared on the area first, then chosen at import. One area holds several side by side; content, graph and records never mix between them. |
| **Import documents and archives** | Upload a document or a zipped tree, pick the area, version and pipeline, and start. The upload returns immediately and the work runs in the background. Oversized sections are split into numbered parts automatically. |
| **Ask the searchable pipeline for section summaries too** | *Standard* imports offer **Generate section summaries**, off by default. Switching it on requires choosing the summarisation instructions to use. It writes the same per-section summaries the *Hierarchical* pipeline produces, so a searchable document can also be browsed by its table of contents. Summaries are reused across imports whenever a section's text is unchanged, so a manual already imported for browsing usually costs nothing to summarise again. |
| **Import a manual for browsing** | The *Hierarchical* pipeline keeps a manual's heading structure intact and writes an AI summary for every section, built bottom-up so a parent's summary reflects its children. It always summarises, so it always needs summarisation instructions named. A section that cannot be summarised reads *Section summary unavailable* rather than failing the import. |
| **Import a prepared export** | The *Custom* pipeline takes a zipped export of already-prepared documents plus optional entity and relationship files, so an install-kit extract arrives as documents and a knowledge graph in one job. Code-heavy sources are summarised during import, so this pipeline needs summarisation instructions named too. |
| **Import structured records** | The *JSON Import* pipeline loads record-shaped data so the assistant can query facts directly instead of searching prose. Name a unique field to update matching records in place; leave it blank and every import adds new copies. |
| **Connect a Confluence site** | Register any number of sites: address, account e-mail, API token, space, root page, include/exclude label filters, target area and version, whether attachments are read, and whether section summaries are built — with the summarisation instructions to use when they are. **Test Connection** verifies credentials and lists sample pages. |
| **Edit or switch off a connector** | A saved site can be edited, and can be disabled so it is skipped by *Sync All* and cannot crawl, without deleting it. Deleting is separate and also cancels its queued work. |
| **Sync a Confluence space** | Sync one site or every enabled one. The crawl walks the page tree under the root, applies the label filters, and queues one import per changed page. It reports pages queued, pages already queued, and how many carry none of the required labels. |
| **Extract a knowledge graph** | Run extraction on a document, choosing the extraction prompt. A model reads each passage and produces the named things and their connections for that area and version, then duplicates are merged. The prompt is required: it is what tells the model to answer in the strict shape the reader expects, so an extraction without one produces nothing usable rather than a smaller graph. |
| **Import through the integration interface** | Upload documents, prepared exports and record files without using the screen, then poll a job or subscribe to its live progress. Confluence imports are refused to anyone but an administrator. |

## How it behaves

- **Importing requires an administrator or a machine key.** An ordinary user account cannot write to the
  corpus, in the screens or through the integration interface. Confluence imports require an administrator
  specifically, because they use stored credentials.
- **Declare an area's versions before its first import.** If the area declares none, the version chosen at
  import is dropped silently, the content lands unversioned, and a second version's import then replaces
  the first.
- **Once an area declares any tag, every import must name one.** Tag names must match exactly,
  including capitalisation — from the admin screens and from the job-queueing endpoint. **Two
  integration routes are the exception**: file upload and graph-extraction requests *declare* the
  version they were given before the check runs, so a typo there is accepted rather than refused. It
  silently adds a new version to the area and files the content under it, where no ordinary search
  will look. Nothing reports it; only the area's version list shows the stray name.
- **Work that needs AI instructions is refused when none are named**, at the moment it is submitted
  rather than part-way through. Anything that summarises sections, and every graph extraction, must name
  the instructions it will use, and that name must still exist; either way the submission comes back
  refused with the valid choices listed, and nothing is queued. This is deliberately strict: the earlier
  behaviour was to run anyway with no instructions at all, which does not fail — it finishes, reports
  success, and leaves summaries that describe nothing and a graph the model never returned in a usable
  shape. Neither is visible until someone reads the result much later.
- **Instructions are checked for purpose as well as existence.** Summarisation instructions cannot be
  used for graph extraction or the other way round, so picking a stale entry from the wrong list is
  refused instead of quietly steering the model to do the wrong job.
- **Confluence sites do not summarise unless asked.** A newly connected or previously existing site
  imports pages without section summaries until an administrator switches them on, which also means it
  costs no AI calls per section by default. A site with summaries switched on but no instructions chosen
  cannot be saved.
- **Submitting the same file twice while an import of it is running attaches to that job.** A warning
  states that the options chosen this time were not applied; cancel and resubmit to change them.
- **Re-importing a file updates that document instead of duplicating it.** For uploads and prepared exports
  a document is matched by its path in the source **and also by its title**, so a renamed file that keeps
  its title updates the existing one. Confluence pages are matched by path only, because two pages routinely
  share a title. A match is scoped to one area, one pipeline and one exact version set, so the same title
  under a different area, pipeline or version is a different document and nothing is overwritten.
- **Re-importing replaces a document's passages and its section summaries together.** Summaries never
  outlive the text they describe, so a re-import without *Generate section summaries* leaves the document
  with none rather than with the previous revision's. Re-generating them later is usually free — a section
  whose text has not changed is reused rather than summarised again.
- **Content, graph and records are always scoped to one area and one version**, so an answer about 9.3
  can never be built from 10.0 material.
- **Imports never retry themselves.** A failed job stays failed until the work is submitted again, and a
  restart of the service re-runs interrupted jobs from the beginning.
- **Graph extraction by the model reports what it could not use and still completes**, because one stray
  name must not cost a whole document. An extraction that found nothing at all is the exception: it is
  recorded as failed, not completed with an empty graph. A prepared-export import instead fails and
  names the offending files — up to twenty, plus a total count.
- **A Confluence re-sync only re-imports pages whose version changed**, so repeat syncs over a large space
  are cheap. A page with no usable content is recorded as empty so it is not re-queued forever.
- **A Confluence page is kept whole where it fits.** Neighbouring sections are grouped into one passage
  rather than split one per heading, so a short section is never retrieved stripped of the material around
  it, and its subheadings stay visible inside the passage. A section too large to fit is split into numbered
  parts. The ceiling is 3,500 characters per passage, provenance line included.
- **Every Confluence passage carries where it came from** — a link to the page, who last edited it, when, and
  which version. It travels with the text, so it appears in the citation panel and is available to the
  assistant when judging how current a source is.
- **Structured records are updated in place only when a unique field is named.** The job count reports
  records parsed, not how many were new, so a re-import that duplicates everything still looks normal.
- **Deleting a knowledge area** removes its documents, graph, records and search history. Removing one
  version deletes content carrying only that version and detaches the rest.

## Limits

- **One file per upload, 200 MB maximum.** The form offers Markdown, zip, JSON and line-delimited JSON, but
  nothing rejects another file type — an unsupported file is accepted and the job fails when it runs.
- **An archive import only ever reads Markdown.** *Standard* and *Hierarchical* imports match `**/*.md`
  inside the zip and ignore everything else; only the *Custom* pipeline offers a file filter that can be
  changed. A zip holding any other format finishes as *Completed* with zero documents.
- **Inside an archive, a file that fails is skipped without a message.** An archive in which every file
  fails still finishes as *Completed* with zero documents — **read the document count, not the status**.
- **A prepared-export import silently skips** a document with no metadata header, one it cannot read, and
  one left empty after summarising. Only a lower document count reveals the loss.
- **If a prepared-export import fails on an unresolvable connection**, its documents and entities are
  already stored. Fix the export and re-run; do not assume the failed job left nothing behind.
- **Documents imported with the Hierarchical pipeline are not reachable by meaning-based search**, only by
  literal word matches. Use it for material meant to be browsed and summarised.
- **Re-importing a single uploaded file whose title changed creates a second document.** Uploads are matched
  by title in practice, because each upload is stored under a fresh internal name that never matches the
  previous one. Edit the heading a manual opens with and the re-import is treated as new material: the
  earlier revision keeps its passages, stays searchable, and answers can cite it as current. Nothing warns,
  and only the document count in the area reveals it. Change a title and the superseded document has to be
  deleted by hand. Files inside an archive are unaffected: they are matched by their path within the zip,
  which stays the same from one import to the next, so a changed title still updates the same document.
- **A hand-written declaration of structured data is frozen permanently.** Later imports never update it,
  nothing warns, and it cannot be rebuilt from the data — the only way to change it is to write it again
  by hand. Until someone does, the assistant reports data that exists as missing.
- **The field named to match structured records is not checked for uniqueness.** If it already occurs on
  more than one record — after an earlier import that added copies, for instance — an import updates one
  of them, chosen arbitrarily, and the rest silently keep their old content.
- **Merging duplicate entities keeps only the longest description** and discards the rest of the
  duplicate, including its link to a document.
- **A Confluence site with section summaries switched on summarises every page it imports**, so syncing a
  large space costs AI calls per section and shows up on the spend report as import cost. The setting is
  per site and off by default, so this is a cost a site opts into rather than one every sync carries.
- **A connector saves a target version the knowledge area does not declare without complaint**; that only
  surfaces as a failure when the first sync runs.
- **Switching a connector off does not stop a crawl already under way.** It still walks the whole page
  tree to the end and still queues one import per changed page; each of those imports then fails as soon
  as it needs the switched-off connector's credentials. Nothing is imported, but the job list fills with
  failures. Stopping the running job is the only way to halt it cleanly.
- **Renaming a Confluence page does not change the document's title** — the title is fixed when the
  document is first created. Attachments over 50 MB are skipped and extracted text is cut at 100,000
  characters.
- **Integration imports are throttled per caller** (twenty requests a minute by default). Two of the
  import routes **create a missing knowledge area on the fly**, so a typo in an automated import quietly
  mints an empty area instead of failing.

## Not supported

- Scheduled or automatic syncing. Every import and sync is started by a person or an external caller.
- A retry button. A failed job cannot be restarted or resumed — the work must be submitted again.
- Removing a stored Confluence token from the screen. It can only be replaced.
- A preview or dry run. An import writes as it goes.
- De-duplication across knowledge areas. The same file imported into two areas exists twice.
- Any check that a declared data shape matches the stored records.
- Confluence Data Center served under a context path.
- Editing document text inside the product. Content is corrected at source and re-imported.

---
