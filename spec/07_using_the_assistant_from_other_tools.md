# 7. Using the assistant from other tools

**What it is for.** The knowledge base is not locked inside the web app. AI coding assistants search it
directly using the same tools the in-app assistant uses, and a desktop app keeps its conversations in the
user's account.

**Who uses it.** Consultants working inside AI coding assistants; desktop app users; the platform team
running corpus scripts and the evaluation harness.

## What you can do

| Capability | What happens |
| --- | --- |
| **Connect an AI client** | A user signs in with their company account from a client such as Claude Code, Claude Desktop or Cursor. The client discovers the available knowledge tools by itself. First-time users are created automatically, with no separate invitation. |
| **Search the knowledge base** | Ask in plain language against one knowledge area and get the most relevant passages back, each with a relevance score, its document, and the heading path it sits under. Every passage carries an id the client can cite. |
| **Browse the document catalogue** | List the documents in an area, optionally filtered by kind or approximate title. Each row shows id, kind, title and passage count, and the response states how many more exist. |
| **Read a table of contents** | For structured documents, an indented outline with a one-line summary per section and its size in both passages and characters, so the client can navigate top-down instead of guessing search terms. Two levels are shown at a time, and naming a section shows the two levels **below** it — so a client can walk down to the part it wants instead of reading a whole chapter to find it. Each entry states whether it is small enough to read whole. |
| **Read a full section or whole document** | Given a document id and an optional section path, get the complete text of that section including everything nested beneath it. This is how a client moves from a search hit to the full source. |
| **Look things up in the knowledge graph** | Search a knowledge area's graph for named things — tables, processes, forms, endpoints — and get each one's kind, version, description and owning document. |
| **Follow connections** | Ask what a named thing connects to and get its connections back: the counterpart, the kind of connection and the direction. Optional filters narrow to one type or direction. |
| **Query structured records** | Read the declared field structure first, then filter records, pick fields, count matches, or group counts by a field. |
| **Check citations in a draft** | Submit citations and get each one back marked real, unverified or fabricated. This catches invented source ids before an answer reaches a person. |
| **Use the shared playbook library** | Every playbook appears in the connected client as a ready-made prompt — **including the internal orchestrator and reviewer ones**, which the chat picker hides but this surface does not. Editing a playbook in the admin screens changes what clients receive, with no release needed. |
| **Keep desktop conversations in the account** | The desktop app creates conversations, appends each turn, retitles, changes settings and deletes. They appear in the web sidebar marked *Desktop*. Ratings are stored too and appear in the same feedback screens. |
| **Same behaviour across surfaces** | The desktop app reads the same playbook library, the same in-depth profiles and the same base instructions the web chat uses, so a change made once reaches both. |
| **Record in-depth runs the desktop app performed** | The desktop app reports the steps it ran locally, and those runs appear in the admin trace screens alongside web runs. |
| **Drive imports from automation** | Scripts with a machine key can list documents, upload files, start graph extraction, queue any import, check a job's state and watch its progress. Confluence imports still require an administrator. |

## How it behaves

- **Every search and browse from an AI client must name one knowledge area**, and a version where the
  area holds several. An unknown name is refused rather than answered with an empty result, so a typo
  can never look like "this is not in the knowledge base" — but only a *missing* tag and an invalid
  document kind list the valid values back; an unknown knowledge area or tag says only that it does
  not exist. *Reading a table of contents, reading a section, or checking a citation works from an id
  and needs neither.*
- **AI clients read only.** Nothing reachable over that connection can add, change or delete content.
- **Anyone who can connect reads every knowledge area.** Access control is the sign-in, not the content.
- **When a name matches several different things, the client is handed the candidates** to choose between
  instead of a merged answer.
- **Any result that hit its ceiling says so**, so a client never mistakes a capped list for a complete one.
- **Citation checking proves the cited source exists.** It does not check that the source supports the
  claim, and the tool says so in its own output.
- **The desktop app lists only the conversations it created.** Web chat conversations, and conversations
  someone shared, are invisible there.
- **Asking for an import that is already running returns the existing job** instead of starting a second
  one. Clients should follow that job rather than read the response as a failure.
- **AI clients and the in-app assistant share one tool set.** Adding, renaming or re-describing a tool
  changes both surfaces at once; there is no way to ship one without the other.
- **A connecting client is told which build of the assistant it is talking to.** The version it receives
  when it connects is the version that was released, so a client or a bug report can state which build
  produced an answer. Builds that are not a release identify themselves as such rather than borrowing
  the last release's number.

## Limits

- **⚠ Searches from AI clients and desktop sync are not rate-limited.** Only web chat sends and
  script-driven imports are. A misbehaving client can run unlimited searches, and every search costs money.
- **Answers written by the desktop app come from that app's own model.** Only the searches it runs against
  the knowledge base appear in the cost dashboard; the answer generation itself does not.
- **If a knowledge area declares no tags**, the generic job route and the prepared-corpus route drop
  the version silently and import unversioned. File uploads and graph-extraction requests keep it.
- **Two import routes create a missing knowledge area automatically**; the others reject it. A typo on
  those two mints a new, empty area rather than failing.
- **A missing or wrongly-typed value in a request's web address comes back as a generic failure**
  rather than a specific complaint, so that one client-side mistake looks like an outage. Everything
  else is reported properly: a malformed request body names the field it rejected, and a rejected or
  not-found import comes back with its own reason.
- **Graph lookups return at most 200 rows and grouped counts at most 100 groups**; the passage cap is the
  same one chapter 2 states, and is owned there. Graph name search cannot be paged at all.
- **Following connections needs a thing's exact name**; an approximate name returns "nothing found" with
  no suggestion.
- **A kind-of-thing filter on a graph lookup is not checked.** Unlike a document kind, a graph kind that
  does not exist is not refused and does not list the valid values — it simply comes back as "nothing
  found", indistinguishable from a thing that genuinely has no connections. This is the one place where a
  typo does read as "this is not in the knowledge base".
- **The document listing used by scripts is unpaged** — it returns every document for an area and version
  in one response. On the OIM corpus that is tens of thousands of entries.
- **Replaying the same batch of desktop messages creates duplicates**; there is no replay protection.
- **Deleting a playbook does not remove it from already-connected clients.** It stays listed, and still hands
  back its old text, until the service is restarted.
- **Progress streams stop after 30 minutes**; clients must ask again.

## Not supported

- Writing to the knowledge base from an AI client. No upload, edit or delete.
- Restricting an AI client to certain knowledge areas.
- Asking the user a clarifying question from an AI client. The request always reports success and nobody
  is ever asked.
- Fill-in-the-blank playbook prompts. A playbook reaches the client as fixed text.
- Seeing web chat conversations in the desktop app, or opening a conversation someone shared.
- Generating an answer through this service from the desktop app. It stores transcripts; it does not
  produce them.
- Push notifications when the corpus changes.
- Signing an AI client in with an API key or personal token. Company accounts only.

---
