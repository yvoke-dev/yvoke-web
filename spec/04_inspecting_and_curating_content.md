# 4. Inspecting and curating content

**What it is for.** The window into the knowledge base. Administrators see exactly what was imported, how
it was split for search, what the assistant knows about it, and can tidy up or remove anything wrong or
out of date.

**Who uses it.** Administrators and knowledge curators. Chat users touch one part of it only: the source
preview behind a citation.

## What you can do

| Capability | What happens |
| --- | --- |
| **Browse everything imported** | The corpus browser lists every document with its area, type, number of passages, import status, versions, and whether graph extraction has run. Filters by area, type and version; searches by title or id; pages twenty at a time. |
| **Open a document and see how it was split** | Shows area, type, versions, import status, passage count, creation date, section summaries, its properties, and the full ordered list of passages with heading breadcrumb, text preview and whether each is searchable. |
| **Inspect a passage and see where it was used** | Full text, heading breadcrumb, position and depth, whether it is searchable, and a list of the conversations where the assistant used it as evidence — conversation title, message text and time. |
| **Preview the real source behind a citation** | Any signed-in user clicking a citation sees the source text, headed by document title and version. If the source was deleted, or cannot be resolved to a single source, a short plain notice is shown instead. |
| **Delete a document, a version, or an area** | Removes content permanently. Deleting an area also removes its documents, passages, graph, records and search history. |
| **Run graph extraction on a document** | Pick target area, version and extraction prompt; the work runs as a background job and the screen jumps to it. Documents already processed offer a re-run, and the list flags how many passages failed extraction. |
| **See what graphs exist and clean them up** | Lists every graph scope — one per area and version — with counts. From there, merge duplicate things and connections, or clear the scope entirely. |
| **Explore the knowledge graph** | Search things by name, filter by kind, page through them. Selecting one shows its description, properties and incoming and outgoing connections. For the OIM database area there are two extra views: which tables reference which other tables, and which processes call which other processes. |
| **Browse structured records** | Pick area and version, page through records, open one to see its content, source file and versions. A filter builder assembles conditions — equals, contains, greater than — or a query can be typed directly. |
| **See and correct the declared shape of structured data** | A panel shows the declared field list, marked *Inferred* or *Manual*. It can be edited by hand, or rebuilt from the records currently stored. This declaration is what the assistant reads to learn what it can ask for. |
| **Test search quality** | The search console runs a query against a chosen area and version. The two search methods — by meaning and by exact wording — can each be switched off, and each hit shows which one found it, its score, breadcrumb, document and full text. |
| **See search statistics** | A panel shows how many possible sources were considered, how many came from each of the two search methods, and how much the relevance ordering changed the result. The figures are always those of the search just run. |
| **Trace where every result came from** | Below the statistics, each returned passage is listed with its rank before re-ordering and its rank within each of the two search methods — a dash where that method did not find it at all. A second list shows the merged order before re-ordering, labelled with the total it was cut from. Together these show whether a result was promoted by re-ordering, found by both methods or only one, and how deep in a method it was buried. |
| **Review search history and reader feedback** | A log lists past searches with time, query, area and version, and flags the ones that came from the search console or an integration rather than a conversation. Where a search came from a real conversation it also shows that reader's thumbs up or down and comment. What is shown is always the query that was actually run — never the chat message that prompted it. |

## How it behaves

- **Only administrators reach these screens.** Any signed-in user can open a source preview, and any
  authorised user can retrieve from **every** knowledge area — there is no per-area read restriction.
- **The assistant never mixes two versions in one answer.** The admin screens are looser: the corpus
  browser and the structured-records browser both offer *All Tags*, which lists content from every
  version side by side. The graph is always browsed one version at a time.
- **Versions must be declared on the area before content is imported under them.** While an area declares
  none, the version chosen at import is dropped and the content lands unversioned — the next version's
  import then overwrites it, with no error anywhere. Once the area declares even one version, an import
  naming a version it does not hold is refused instead.
- **Deletion is immediate and permanent.** There is no recycle bin and no undo. Deleting a document or a
  knowledge area asks for confirmation first. **Removing a tag does not** — one click on the small ×
  beside a version permanently deletes every document that carried only that version.
- **Removing a version can be carried out only partly and still report success.** A document that also
  carries other versions keeps the removed one whenever another document of the same source file already
  holds exactly the versions it would be left with. The version disappears from the area's list either
  way, so that content is left marked with a version the area no longer offers. The screen still says the
  version was removed; only the server log records what was skipped.
- **Graph extraction runs as a background job**, so the screen hands you the job to watch. If that
  document is already queued, your newly chosen settings are ignored and you are shown the running job.
- **Deletions, version removals, graph merges and extraction requests are audited.** Adding or removing a
  version on a single document is not — and neither is clearing a graph scope, the one action on that
  screen that deletes every thing and connection for an area and version, while the merge beside it is
  recorded.
- **Where a name is ambiguous** — the same name existing under several kinds — the assistant returns the
  candidates and asks which one, instead of merging them.
- **An administrator inspecting a passage can see the conversations where it was used as evidence**,
  including other users' message text.

## Limits

- The corpus browser pages twenty documents at a time and the graph explorer fifty things; graph name
  search returns at most fifty matches **with no paging**, and one thing's connection list stops at 200.
- **On these admin screens a capped list looks like a complete one.** The "more may match" labelling is
  something the assistant gets, not these screens.
- **The graph browser lists only versioned graphs.** A graph extracted without a version does not appear
  there at all, and cannot be browsed or cleared from that page.
- **The graph explorer merges every thing sharing a name**, even when they are different kinds of thing,
  so a neighbourhood shown there can be wider than what the assistant would actually use.
- **The kinds of thing in the graph are whatever the imports happened to call them.** There is no agreed
  list, so the same sort of thing can be labelled differently in two areas, and the kind filter can only
  offer the labels that happen to be present.
- **Merging keeps only the longest description**; everything else on the discarded copies — their link to
  a document above all — is lost. Duplicates are only ever recognised within one version, so a merge
  never pulls two versions together.
- **Deleting a document leaves its graph entries behind**, still pointing at content that no longer
  exists. Only clearing or re-extracting that graph cleans them up.
- **Import status shows only pending or completed.** A document stuck on pending means the import was
  interrupted; nothing ever marks a document as failed.
- **The search console can search across all versions at once**, which the assistant is never allowed to
  do, so console results can differ from a real answer. Switching both search methods off is refused
  rather than returning nothing.
- **The result trace needs both search methods.** Switching one off leaves nothing to trace, so the
  trace section disappears — the statistics above it still appear. It is also omitted rather than shown
  partially if the recorded figures do not add up, on the grounds that a confidently wrong trace is
  worse than none.
- **"Returned Rows" counts what came back, not what was considered.** The number of candidates weighed
  is the total reported under the merged-order list, and is normally several times larger.
- **Two navigation traps:** opening the structured-records screen with no area chosen shows the
  alphabetically first one, and choosing *All Tags* on a versioned area hides the declaration panel
  entirely.
- **Rebuilding a declaration from the stored records recovers the field names only** — never which values
  a field may hold, nor which fields are required. The assistant learns the shape of the data but not its
  vocabulary unless someone writes that in by hand.
- **A declaration once written by hand can never be rebuilt from the records.** The rebuild is refused
  with a notice saying the declaration must be switched back to *Inferred* first, and nothing on the
  screen can switch it back — from then on the only way to change it is to write it out by hand again.
- **A typed record filter that does not start from the whole record is treated as plain text.** On the
  records screen the list and the total agree about it. Asking the assistant to group counts by such a
  filter is refused with an explanation of how to rewrite it. One divergence is left: the assistant's own
  record listing insists on a whole-record expression and reports an error, while a count of the very
  same filter quietly returns a plain-text match.

## Not supported

- Editing content. Document and passage text cannot be corrected on screen.
- Undo or restore. Nothing deleted can be recovered from within the product.
- Renaming a knowledge area, moving a document between areas, or merging two areas.
- Editing the knowledge graph by hand. Entries can only be extracted, merged or cleared.
- A visual graph picture. The graph is explored as tables, not as a drawn network.
- Bulk actions. Documents are deleted, re-tagged and sent for extraction one at a time.
- Comparing two versions of a document side by side, or a report of what changed between versions.
- Exporting the corpus, a filtered document list, or search results.

---
