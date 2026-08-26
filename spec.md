# Yvoke — Functional Specification

> **What this is.** A complete catalogue of what Yvoke does today, written for the product owner.
> Every capability the product actually has, the rules it follows, the limits people will hit, and the
> things it deliberately does not do.
>
> **What this is not.** Not a design document, not a roadmap, and not a *technical* specification: it
> says what the product does, never how it is built. It describes the product as it exists now, not as
> it should be.
>
> **Who reads it.** Two audiences, both first-class. The product owner, to know what the product does
> and where it stops. And **anyone — person or agent — about to make a substantial change: read this
> chapter before you start.** It is the fastest way to learn what a feature is *for*, which behaviours
> are deliberate, and what the product has decided not to do — none of which is obvious from the code,
> and the last of which is invisible in it. For a small, local fix, go straight to the code and its
> tests.
>
> **What it is not a substitute for.** The engineering contract — the exact internal behaviour that
> must be preserved — lives in the test suite: `./mvnw verify -Pit-tests` is what enforces it. So read
> this document for *intent*, then read the tests that own the feature for the *contract*, and **to
> change behaviour, change a test.** If no test fails when you break a rule, that rule is not enforced
> — treat it as undocumented rather than assuming it is safe. The *Limits* and *Not supported*
> sections are the exception: they record what the product deliberately does **not** do, and an
> absence is precisely what no test can fail on.
>
> **Keeping it true.** A change a user would notice must update the affected chapter in the same
> change — a new capability in *What you can do*, a changed rule in *How it behaves*, a raised or
> lowered ceiling in *Limits*, something newly possible struck from *Not supported*. A stale
> specification is worse than none, because agents act on it: that is exactly how the far longer prose
> specification that used to occupy this filename earned its retirement, once the test suite carried
> what it had been trying to say. This document inherited the name afterwards — so a reference to
> `spec.md` predating that retirement means the old engineering specification, not what you are
> reading.
>
> **How to read it.** Eight chapters, one per capability area. Each has the same shape: what the area is
> for, who uses it, **what you can do**, **how it behaves**, **limits**, and **not supported**. The last
> two are the useful ones in a stakeholder conversation — they are where the surprises live.

---

## Contents

| # | Chapter | Mainly for |
| --- | --- | --- |
| — | [What Yvoke is](#what-yvoke-is) · [Words we use](#words-we-use) | read first |
| 1 | [Asking questions](#1-asking-questions) | every user |
| 2 | [How answers are produced](#2-how-answers-are-produced) | every user · knowledge team |
| 3 | [Building the knowledge base](#3-building-the-knowledge-base) | administrators |
| 4 | [Inspecting and curating content](#4-inspecting-and-curating-content) | administrators |
| 5 | [Long-running work and its progress](#5-long-running-work-and-its-progress) | administrators |
| 6 | [Access, roles and sharing](#6-access-roles-and-sharing) | everyone |
| 7 | [Using the assistant from other tools](#7-using-the-assistant-from-other-tools) | consultants · platform team |
| 8 | [Cost, usage and quality visibility](#8-cost-usage-and-quality-visibility) | product owner · administrators |
| — | [Decisions worth taking](#decisions-worth-taking) | product owner |

---

## What Yvoke is

Yvoke is an AI assistant that answers questions about **One Identity Manager (OIM)** from iC Consult's
own curated documentation, and shows the sources behind every answer.

**The problem it solves.** OIM documentation is large, spread across manuals, Confluence spaces, install
kits and database exports, and it differs between product versions. A consultant answering a customer
question either searches several systems by hand or asks a colleague. Yvoke turns that into one question
and one sourced answer.

**Who uses it.** Consultants and support engineers ask questions, in the browser or from inside an AI
coding assistant. A small knowledge team curates what the assistant knows. Administrators run the
system and watch what it costs.

**The three things it does.**

1. **Answers questions** from a curated corpus, always citing the passages it used, so the answer can be
   checked rather than trusted.
2. **Keeps the corpus current** — documents, Confluence spaces, install-kit exports and structured data
   are imported and kept separated by product version.
3. **Shows its work** — what was searched, what it cost, what users thought of the answer.

**Two things to understand about its shape**, because they explain most of the rules later on:

- **Content is separated by product version.** A knowledge area can hold 9.3.1 and 10.0 side by side.
  Every search names one tag, and an answer about 9.3.1 can never be built from 10.0 material. This
  is the single most important design rule in the product.
- **Answers are only as good as what was imported.** Yvoke does not browse the internet, and it does not
  fall back on the model's general knowledge when the corpus is thin. Curation is the product.

---

## Words we use

| Word | What it means | Also called |
| --- | --- | --- |
| **Knowledge area** | A body of content on one topic, e.g. *OIM Docs*, *OIM Database*. The unit users and imports target. | "collection" in the admin screens |
| **Tag** | A label on content, e.g. `9.3.1`, `10.0`. Usually a product version — but a tag can carry any meaning, which is why the product calls it a tag rather than a version. One knowledge area holds several. |  |
| **Document** | One imported file or Confluence page. |  |
| **Passage** | The piece a document is split into, so the assistant can quote an exact place and cite it. | "chunk" in the admin screens |
| **Playbook** | A named, reusable way of answering a class of question, written by administrators. Users pick one before asking. |  |
| **Conversation** | One thread of questions and answers, belonging to the person who started it. |  |
| **Citation** | A marker in an answer naming the exact passage it came from. Opening it shows that passage in its surrounding section. |  |
| **Knowledge graph** | The named things in a knowledge area — tables, processes, forms, endpoints — and how they connect. |  |
| **Structured records** | Record-shaped data the assistant queries directly rather than reading as prose. | "JSON objects" in the admin screens |
| **In-depth mode** | A team of specialist agents investigates one question and a reviewer checks the draft. | "multi-agent" / "orchestrator profile" |
| **Import job** | A background task that loads content. Everything that writes to the corpus is a job. | "ingestion job" |

---

## 1. Asking questions

**What it is for.** The chat workspace: where someone puts a question to Yvoke and reads the answer
together with the documentation it came from.

**Who uses it.** Every signed-in user. Administrators use the same screens, plus a ratings dashboard and
a register of everyone's conversations.

### What you can do

| Capability | What happens |
| --- | --- |
| **Start a conversation** | *New Chat* opens an empty thread titled *New Conversation*. The first question becomes the title, shortened if long, and is then fixed. Conversations made in the desktop app appear in the same sidebar, marked *Desktop*. |
| **Pick a playbook** | Before asking, the user picks a named way of answering. Suggestions appear as chips on an empty conversation; typing `/` or clicking **+** opens the full list with descriptions. The active choice is shown above the input. |
| **Ask by typing or by voice** | The input grows as you type; Enter sends, Shift+Enter adds a line. A microphone button dictates into the box — tap to start and stop, or hold to record, chosen in voice settings. |
| **Watch the answer being produced** | A placeholder appears immediately with rotating captions — *Thinking*, *Searching the knowledge base*, *Analyzing retrieved resources*, *Formulating the response*, *Polishing the answer* — and names each research tool as it runs. |
| **Read a formatted answer** | Answers render headings, tables, code blocks, mathematical formulas and drawn diagrams. In Streaming mode a diagram shows as its source text while the answer is still arriving, and is drawn once it finishes. |
| **Stop a running answer** | The send button becomes a stop button while an answer runs. Stopping marks the answer *[Generation stopped by user]*. In Standard mode the text so far is kept; in in-depth mode the partial answer is discarded. |
| **Open the source behind a citation** | Clicking a citation opens a *Citation Source* panel showing the section the cited passage sits in — the passage in context, not on its own — with its document title, its version, and a link to the original page where one exists. |
| **See the reasoning and tools used** | *Show thinking & tools* reveals the assistant's reasoning and every tool it called. A *View Thinking Process* button opens the full reasoning in a panel. Forced on in Streaming mode. |
| **See how much work an answer took** | Each answer shows how much text the assistant read, re-used, thought with and wrote. Users see effort, never money — no prices appear in chat. |
| **Answer a clarifying question** | When the assistant needs more information it posts a *Clarification Required* card, often with ready-made options. The input is locked until the user answers. The card then collapses to *Clarification Provided*. |
| **Get a playbook check before the first question** | On the first question Yvoke checks whether the chosen playbook suits it. If not, a *Playbook Recommendation* card explains why and offers **Switch to …** and **Send Anyway**. |
| **Rate an answer** | Thumbs up and thumbs down on every answer. Clicking one opens a comment box; submitting stores the rating and shows *Feedback saved*. Changing the thumb later keeps the comment. |
| **Organise conversations into folders** | Folder tags group conversations in the sidebar; a conversation appears under each tag it carries, or under *No Tag*. Autocomplete offers the user's own existing folder names. |
| **Share a conversation** | Adding the tag `public` moves it into a *Public Conversations* folder that every signed-in user sees, with a *Shared* badge. Removing the tag un-shares it. |
| **Choose model, effort and mode** | Each conversation picks its own model from the allowed list, a reasoning effort of Low / Medium / High, and Standard or Streaming delivery. Where in-depth profiles exist, a selector switches to one. |
| **Toggle prototype playbooks** | A button in the composer toolbar toggles *Show prototype playbooks*. When enabled, experimental and prototype playbooks (marked with 🧪) appear in empty-thread suggestion chips and in `/` and `+` playbook menus. Disabled by default. |
| **Delete a conversation** | Confirmed, then permanent — the thread, its answers and its ratings. |
| **Administrators: review ratings and read any thread** | A *Feedback* page shows the positive-rating share, counts and every comment, filterable by rating, review status and time. A *User Conversations* page lists all conversations with owner, title and source. |

### How it behaves

- **The user never picks the knowledge area or the tag.** The chosen playbook decides which area and which
  tag are searched. Asking about a different product version means picking a different playbook — there is
  no tag selector in chat. *This surprises most new users.*
- **Prototype playbooks (`prototype = true`) are hidden from user selection by default.** An experimental
  playbook does not appear in prompt chips, slash autocomplete or preflight recommendations unless the user
  enables prototype visibility for the conversation or browser session.
- **A playbook is mandatory.** Without one the input shakes, a warning appears, and nothing is sent. The
  server refuses it too. In-depth mode is the only exception; it hides the playbook picker entirely.
- **A playbook's instructions apply once**, the first time that playbook is used in a conversation — not on
  every question. Switching playbooks part-way applies the new one once, and both stay in force afterwards.
- **A playbook adds to the base instructions; it does not replace them.** The administrator's base
  instructions always apply, and the playbook is layered on top for the question that uses it. So rules that
  hold for every answer — how sources are cited, never naming an unverified object, always naming a knowledge
  area and version — are written once in the base instructions, and a playbook only says what is specific to
  its own subject. The same layering applies to each specialist inside an in-depth investigation.
  **The two exceptions are the in-depth lead and its reviewer**: those two run entirely on their own
  instructions, because neither of them searches the knowledge base — the lead only delegates, and the
  reviewer only checks a finished draft against the evidence, so the base instructions about searching and
  citing do not describe their work. A rule that must reach them has to be written into their own
  instructions; it is not inherited.
- **Only the owner may change anything.** Administrators and viewers of a shared conversation can read a
  thread and its ratings, but cannot post, rate, tag, stop or delete it.
- **Sharing is one tag away** — immediate, no confirmation step, no per-person control. Adding `public`
  exposes the whole conversation to every signed-in user.
- **A bare thumb is recorded in web chat.** Clicking thumbs-up or thumbs-down stores the rating
  immediately, with or without a comment, and it survives a reload. The desktop app is stricter on one
  point: it takes a bare thumbs-up, but a thumbs-down without a comment is refused rather than stored.
- **In Standard mode the answer is produced on the server**, so closing the tab or reloading the page
  does not lose it. Re-opening the conversation shows the answer again — still being written, or
  finished while the tab was closed. Streaming mode is the exception; there the answer lives only in
  the open connection.
- **A stopped or failed answer is still saved** and can be rated — except in Streaming mode, where
  stopping saves nothing and leaves the question standing without an answer.
- **Settings changes fail silently.** If a model or effort change is rejected, the selector still shows
  the new value while the next question is answered with the old one.
- **Deleting a conversation** removes its questions, answers and ratings permanently. Spend records are
  kept for reporting, so cost history outlives the conversation.
- **If the assistant runs out of research steps** it must answer with what it has, and the answer carries
  a visible warning that the limit was hit.

### Limits

- **Timing.** The first words appear within a few seconds. A normal answer takes roughly half a minute to
  a couple of minutes depending on how much research it does. An in-depth investigation takes **minutes**,
  not seconds. There is no progress estimate and no notification when it finishes.
- **The sidebar lists only the 100 most recent conversations.** Older ones stay reachable by direct link
  but cannot be found by browsing — there is no paging and no search.
- **A conversation shows only its first 100 messages**, and the assistant is given those same first 100
  as context. A very long thread stops carrying its own recent history.
- **Streaming mode is experimental**: it forces thinking and tools visible, and the whole answer is lost if
  the connection breaks or the answer takes longer than ten minutes.
- **Stopping is not immediate.** The assistant finishes the research step it is on first.
- **Pressing Stop while nothing is running** arms the stop for the *next* question in that conversation,
  which is then cancelled the instant it starts. Nothing clears it in the meantime.
- **Roughly twenty questions per minute per person**, refused before the question is recorded. (The
  separate capacity ceiling behaves differently and does leave an unanswered question — chapter 6.)
- **Voice typing depends on the browser.** It is only offered where the browser supports it, so some
  users never see the button at all. It dictates in **whatever language the browser is set to** — which
  matters for a corpus containing German material.
- **Only the voice preference is stored per browser**, not per account, so it does not follow a user to
  a second device. Response mode and *Show thinking & tools* are remembered on the conversation itself
  and do follow — the browser only supplies the starting value for a conversation that has never had
  one set.
- **Citation checking only confirms a source exists.** It never confirms the source supports the
  statement, so a confident answer with valid-looking citations can still be wrong.
- **Opening a citation can show far more than the passage cited.** A citation names one passage, but the
  panel shows the whole section that passage belongs to, and a section is as large as its heading is
  broad — a passage under a wide chapter heading opens a section of a couple of hundred passages, while
  one under a narrow sub-heading opens a handful, and the depth of the heading alone does not tell you
  which you will get. The panel does not say which passage was the cited one, so in a large section the
  reader has to find it.
- **Folder names are free text** with no length or character limit and no rename. Renaming a folder means
  removing the tag from every conversation and adding a new one.
- **In-depth mode is only available** where an administrator has defined at least one profile.
- **The reasoning effort setting does nothing on some models.** Low / Medium / High is offered on every conversation and always saves, but only models that support extended thinking act on it — on the others the answer is produced with no thinking effort at all, and neither the selector nor the answer says so.

### Not supported

- Editing or deleting an individual question or answer. Only the whole conversation can be deleted.
- Regenerating an answer, retrying with a different playbook, or branching from an earlier point.
- Renaming a conversation or a folder in the web app. The title comes from the first question; **only the
  desktop app can retitle a conversation**.
- Sharing with a named person or group. A conversation is either private or visible to everyone.
- Searching or filtering conversations from the sidebar.
- Exporting, printing or downloading a conversation.
- Attaching a file to a question. Answers draw only on what was imported.
- Any notification when an answer finishes.

---

## 2. How answers are produced

**What it is for.** This is what makes an answer trustworthy. Every answer is built by searching the
knowledge base and citing the passages it used. Hard questions can instead be handed to a team of
specialist agents whose draft is reviewed before it reaches the user.

**Who uses it.** Everyone who asks a question. Administrators curate the playbooks, base instructions and
in-depth profiles, and can inspect any search or trace.

### What you can do

| Capability | What happens |
| --- | --- |
| **Every answer starts from a live search** | The assistant searches two ways at once — by meaning and by exact wording — merges both result lists, then re-orders them by relevance before reading anything. It answers from what it retrieved, not from the model's own memory. |
| **Citations that open the source in place** | Clicking a source marker expands the section containing the cited passage inside the conversation, with document title, version, and how much of the document is shown. If the source has since been deleted, the panel says so rather than failing. |
| **Citation self-check while writing** | The assistant can check its own citations before finishing, and a source reference that is provably invented is removed as the answer is written. Ordinary bracketed text and numbered references are always kept. This applies to in-depth answers as well as ordinary ones. |
| **Playbooks — expert strategies users pick** | A named, reusable instruction set telling the assistant how to attack a class of question and which tools it may use. Its instructions stay invisible in the conversation. |
| **Playbook recommendation** | Before the first message is sent, the system checks whether the chosen playbook fits. If not it offers a one-click switch and a **Send Anyway** button. |
| **A toolbox, not just a search box** | While answering, the assistant can list a knowledge area's documents, open a document's table of contents, read a whole section, look up a thing and its connections in the knowledge graph, and query structured records. |
| **It can ask instead of guessing** | When a question is ambiguous the assistant pauses and asks, optionally with ready-made choices, then continues from the answer. |
| **See how the answer was produced** | A toggle reveals the reasoning and every tool called. Where a playbook allows it, the assistant can also write and run small programs; both the code and its result appear in the answer. |
| **In-depth investigation** | A lead agent breaks the question into sub-questions, hands each to a specialist playbook, and writes the final answer from what they bring back. The user asks once and gets one answer. |
| **Automated review of in-depth answers** | A reviewer agent checks the draft against the evidence the specialists gathered, and either approves it or sends it back with notes. If it still fails after the last attempt, the answer is delivered with a visible warning and the reviewer's notes. |
| **Administrators manage playbooks** | Create, edit, delete, import and export. Each carries a title, description, the tools it may use, whether code execution is allowed, its role, and whether it is flagged as a prototype (`prototype = true`). Prototype playbooks are hidden by default from regular users. A saved playbook is immediately live for users and connected AI clients. |
| **Administrators manage base instructions** | Managed separately from playbooks, with one marked as the active default. Typed by purpose — chat, graph extraction, summarisation — and the type is enforced wherever one is chosen, so an entry can only be picked for the job it was written for. Importable/exportable as files. |
| **Administrators configure in-depth profiles** | A profile names the lead playbook, the reviewer playbook and the available specialists, plus how many review rounds and specialist calls one question may spend, and which model each role runs at. |
| **Administrators test and audit** | A search console runs any query against a knowledge area and shows the passages, their scores and which search found them. A log lists past searches with the rating the user gave. Every investigation keeps a full trace. |

### How it behaves

- **Prototype playbooks are flagged with `prototype = true` (default `false`).** They are excluded from
  normal user-facing playbook discovery and preflight suggestions, allowing new or experimental retrieval
  strategies (such as top-down browsing) to be tested without cluttering standard workflows.
- **If a knowledge area holds several versions, a search must name one.** An unversioned search, or one
  naming a version the area does not offer, is refused rather than answered from the wrong version.
- **A playbook's tool list is exact, and selecting nothing means nothing.** A playbook saved with no tools
  answers from the question alone, without searching — the reply looks entirely normal, just ungrounded.
  The playbook list now badges that case: *"No tools — answers without searching"*. It also badges the
  opposite mistake, tools chosen on a Reviewer or Orchestrator playbook, which are ignored because those
  roles are given a fixed tool list when they run.
- **Choosing an in-depth profile takes over the conversation.** The model, thinking depth, response mode
  and playbook pickers disappear, because the profile decides them.
- **Review rounds are counted from zero**, so a limit of three allows four answers: the first draft
  plus three revisions.
- **A rejection says which kind of fix it needs.** The reviewer separates objections it can see the
  answer to — a claim attached to the wrong source, an uncited claim a supplied source does support —
  from claims nothing supplied backs at all. Only the second kind sends the lead agent back to
  research; the first is repaired from material already in hand, without a specialist. A revision
  whose objections are all of the first kind runs no specialists at all.
- **The lead agent keeps one conversation for the whole investigation.** A rejected draft does not
  restart it: the lead still has its own working notes, what each specialist told it and the answer it
  wrote, and is given the reviewer's notes plus the source material behind those specialist answers. It
  edits rather than investigating the question again, and can still delegate for something genuinely
  missing — but a correction the evidence already supports needs no specialist at all. The reviewer is
  deliberately excluded from that conversation, so each review is an independent check rather than a
  negotiation it took part in.
- **The trace is numbered in the order the work started.** A specialist runs inside the lead agent's
  turn and therefore finishes before it, so numbering on completion listed the answer above the request
  that caused it.
- **The specialist budget is per question, not per round.** Once spent, the lead agent is told to
  conclude with what it has rather than failing the answer.
- **The same passage is never sent to an agent twice.** When a search returns a passage that agent has
  already been given in the same conversation, the passage keeps its place in the ranked results — with
  its title, heading path, relevance score and id — but its text is replaced by a note saying the full
  passage appears earlier. What it matched is still visible; only the repetition is gone. Passages a
  specialist found are also carried once into the review, however many specialists found them.
- **The reviewer sees the sources the answer cites, and only those.** It cannot search, and it can no
  longer open a document either. That is what makes each citation testable as the claim it is — *this
  source supports this statement* — instead of something the reviewer can excuse by finding the fact
  elsewhere in the pile. It is told that uncited sources exist without being shown them, so an
  unsupported claim comes back as "cite what supports this" rather than "no such source exists", and the
  lead agent fixes it by citing material it already holds. Evidence with no passage id, such as a
  version-history table, is always passed on in full: it can never be cited and could never be
  recovered.
- **A review that cannot run does not throw the draft away.** When the reviewer itself fails rather
  than rejecting, the answer is delivered carrying its own warning — that nothing has validated its
  claims or citations, so it should be treated as unverified. That is a different notice from the one a
  rejected answer carries. The exception is a failure with no draft to rescue: then the question fails
  and the user sees the generic error notice.
- **A failing tool never fails the answer.** The assistant is told the step could not be completed and
  asked to continue and state the gap; the technical reason goes to the log only.
- **Users only ever see one generic notice when something breaks.** A failure is never presented as
  though the user had stopped the answer.

### Limits

- **An in-depth answer costs several times a normal answer and takes minutes rather than seconds.** It
  runs a lead agent, several specialists and up to three review rounds. Do not promise it as a free
  quality upgrade.
- **An in-depth investigation still delivers an answer when one of its specialists fails outright**, and the
  run is still recorded as completed. The person who asked sees nothing unusual; the missing sub-answer
  shows up only as a failed step inside that investigation's trace, so "completed" does not mean every
  specialist contributed.
- **The citation check confirms a cited source exists. It never reads the source**, so "all citations
  resolve" does not mean the answer is supported by them.
- **The assistant does not remember its own thinking, only its answers.** The reasoning shown under
  "see how the answer was produced" belongs to the turn that produced it. On the next question the
  assistant is given what it *said* — its answers and the sources it read — not how it got there, so a
  follow-up may re-derive reasoning the user can still see on screen above it. This is deliberate:
  reasoning is a draft, and replaying it invites the assistant to treat its own speculation as
  something it had already established.
- **Repeat suppression is per agent, per conversation.** A specialist that finds a passage the lead agent
  or another specialist already read is still given it in full. The rule only ever withholds text an
  agent demonstrably still holds, so it errs towards sending too much rather than pointing at something
  that is not there. Within one agent it now spans both ways of reading: opening a section counts as
  having seen the passages in it, and a passage already shown is named but not repeated — so a section
  opened after a search that already returned part of it arrives with that part as a reference.
- **A claim the answer failed to cite is reported as unsupported, even when a retrieved source did
  support it.** The reviewer cannot see uncited sources, so the correction runs through the review loop:
  the answer is rejected, the lead agent adds the citation, and the next round checks it. That costs a
  review round, and the limit is three attempts — so an answer with several missing citations can be
  delivered carrying the "did not pass review" warning while being substantially correct.
- **At most 20 research steps per question.** On reaching the limit the assistant must conclude, and the
  answer carries a visible warning that it may be incomplete.
- **The history an answer sees is the first 100 messages**, not the most recent 100.
- **One search returns 10 passages by default, 20 at most.** Asking for more is silently reduced to 20 —
  the ceiling exists because every candidate passage is sent to the relevance re-ranking service in a
  single request, and an oversized request is rejected, which would quietly drop the re-ranking step
  altogether. When the cap is reached the assistant is told results were truncated, but not what it
  missed.
- **If the active base instruction set is deleted or its name mistyped**, the assistant runs with no base
  instructions at all. Answers quietly get worse and nothing reports an error.
- **Tool names inside a playbook are never validated.** A tool that is renamed or withdrawn is silently
  dropped from that playbook, so it stops being used with no visible sign.
- **A playbook's role is stored without validation** (case does not matter). The admin form offers a fixed
  list, but a playbook imported from a file with an unrecognised role is treated as user-facing — so a
  misspelled role puts an internal playbook in front of users.
- **In-depth profiles are saved without validation.** An unknown playbook name or a model outside the allowed
  list is accepted and only fails when someone asks a question. A zero or negative round limit is silently
  replaced by the default.
- **If the relevance re-ranking step fails**, the search still returns results in merged order, ranked
  slightly worse, with no warning to anyone. That merged order treats the two search methods equally —
  a passage found only by meaning and one found only by wording rank the same at the same position
  within their own method — so the fallback is no longer skewed toward either method.
- **Answering depends on an external AI service with a usage quota.** When it is busy the system waits and
  retries, which can add a minute; if it still fails the user sees the generic error notice.
- **An answer where the AI thinks and then says nothing is asked for once more.** Both AI services behave
  the same way here, and they try once more only when the service reported that it finished normally. A turn
  cut short by its own length limit is not asked again — it would simply run out again — and neither is one
  where the connection dropped. In either of those cases the user sees the generic error notice rather than
  a blank message.
- **The admin search console can search across all versions at once**, which the assistant itself is
  forbidden to do — so it does not exactly reproduce what a user's answer saw.
- **Which AI service answers is decided per model.** An operator maps individual models to a service;
  anything unmapped goes to the default one. Two answers in the same conversation can therefore come from
  different services, and nothing on screen says which one answered.

### Not supported

- Automatic switching to another AI service when the chosen one fails or is busy. A model is answered by
  the one service it is mapped to, and a failure there is reported rather than retried elsewhere.
- Version history for playbooks, base instructions or profiles. Saving overwrites, deletion is immediate, and
  nothing warns about conversations still using them.
- Any check that a cited passage actually supports the sentence it is attached to.
- Direct knowledge-base search for end users. The search console and traces are administrator-only.
- Specialists talking to the user. A specialist's clarification request goes to the lead agent, never to
  the person who asked.
- Personal or team-private playbooks. The library is global and administrator-managed.
- Automatic retry of an answer that fails review. It is delivered as it stands.
- User control over which specialists run, or any way to intervene mid-investigation.
- Learning from feedback. Ratings are stored but change neither ranking nor future answers.

---

## 3. Building the knowledge base

**What it is for.** Everything that puts content into Yvoke: knowledge areas and versions, document and
archive imports, the Confluence connector, knowledge-graph extraction and structured-record imports.

**Who uses it.** Administrators onboard and curate content, in the screens or through the integration
interface. Automation scripts import with a machine key. Everyone else sees only the result.

### What you can do

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

### How it behaves

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
- **Structured records are updated in place only when a unique field is named.** The job count reports
  records parsed, not how many were new, so a re-import that duplicates everything still looks normal.
- **Deleting a knowledge area** removes its documents, graph, records and search history. Removing one
  version deletes content carrying only that version and detaches the rest.

### Limits

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

### Not supported

- Scheduled or automatic syncing. Every import and sync is started by a person or an external caller.
- A retry button. A failed job cannot be restarted or resumed — the work must be submitted again.
- Removing a stored Confluence token from the screen. It can only be replaced.
- A preview or dry run. An import writes as it goes.
- De-duplication across knowledge areas. The same file imported into two areas exists twice.
- Any check that a declared data shape matches the stored records.
- Confluence Data Center served under a context path.
- Editing document text inside the product. Content is corrected at source and re-imported.

---

## 4. Inspecting and curating content

**What it is for.** The window into the knowledge base. Administrators see exactly what was imported, how
it was split for search, what the assistant knows about it, and can tidy up or remove anything wrong or
out of date.

**Who uses it.** Administrators and knowledge curators. Chat users touch one part of it only: the source
preview behind a citation.

### What you can do

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

### How it behaves

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

### Limits

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

### Not supported

- Editing content. Document and passage text cannot be corrected on screen.
- Undo or restore. Nothing deleted can be recovered from within the product.
- Renaming a knowledge area, moving a document between areas, or merging two areas.
- Editing the knowledge graph by hand. Entries can only be extracted, merged or cleared.
- A visual graph picture. The graph is explored as tables, not as a drawn network.
- Bulk actions. Documents are deleted, re-tagged and sent for extraction one at a time.
- Comparing two versions of a document side by side, or a report of what changed between versions.
- Exporting the corpus, a filtered document list, or search results.

---

## 5. Long-running work and its progress

**What it is for.** Importing documents and extracting knowledge takes minutes to hours. This runs that
work in the background so nobody waits on a browser tab, and gives a live view of what is running, what
finished, and what went wrong.

**Who uses it.** Administrators who load and maintain the corpus, and integrations pushing content in.
Ordinary chat users never see jobs.

### What you can do

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

### How it behaves

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

### Limits

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

### Not supported

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

## 6. Access, roles and sharing

**What it is for.** Who can sign in, what each role unlocks, who may read a given conversation, and how a
conversation is shared. Every other part of the product sits behind it.

**Who uses it.** Everyone: staff asking questions, administrators running the knowledge base, and
automation or AI coding tools connecting on a company account or a machine key.

### What you can do

| Capability | What happens |
| --- | --- |
| **Sign in with the company account** | People reach Yvoke through the company's Microsoft sign-in; there is no separate Yvoke password. Administrators land on the admin console, everyone else on chat, and a deep link takes you where you originally asked for. |
| **Sign out** | Ends the session for good and shows a confirmation page. Returning requires signing in again — closing the browser alone is not the same as signing out. |
| **Simulated sign-in for demos** | Test and demo installations can offer a simulated sign-in with ready-made user and administrator profiles. It cannot be switched on for the live service. |
| **Roles come from the company directory** | Whether someone is a user or an administrator is decided centrally, not inside Yvoke. An administrator automatically has full user access too. |
| **Conversations are private by default** | A conversation belongs to whoever started it. Nobody else sees it, and nobody else can open it — unless the owner shares it, or an administrator deliberately opens it through the admin console. |
| **Share with everyone** | Adding the folder tag `public` publishes it to every signed-in user, in a *Public Conversations* folder. Removing the tag un-shares it immediately. |
| **Read-only view of a shared conversation** | A viewer sees the whole history with a *Shared* badge and a banner saying it is read-only. The message box, tag controls and delete button are hidden. |
| **Viewers can reveal reasoning for themselves** | Someone reading a shared conversation can toggle *Show thinking & tools*. The choice applies only to them and never changes the owner's settings. |
| **Administrators can read any conversation** | A *User Conversations* page lists every conversation with owner name and email, title, source and timestamps, each linking to the full thread. |
| **Administrators unlock the admin console** | Knowledge areas, documents, records, the graph, playbooks, profiles, prompts, imports, connectors, agent runs, jobs, user conversations, cost monitoring, model pricing, search testing, logs, the audit trail and feedback. Regular users see none of it. |
| **Access from AI coding tools** | An agentic IDE connects on the same company account and reads the same knowledge base, as that person. Setup is the server address plus a sign-in. |
| **Machine access for automation** | Scripts authenticate with a shared machine key. It unlocks importing content, running jobs and reading documents — and nothing else: no chat, no admin console. It is one shared key, so every script has the same identity. |
| **Chat can be switched off** | When web chat is off, chat pages say so while the admin console, the AI-client connection and the automation interfaces keep working. Useful during incidents or cost freezes. |

### How it behaves

- **Administrators may read, never write.** An administrator opening someone else's conversation cannot
  post, rate, stop, retag or delete. Nothing an administrator does can alter another person's history.
- **Sharing is all-or-nothing.** No per-person, per-team or external-link sharing. Private, or visible to
  every signed-in user — with no confirmation step and no notification to anyone.
- **⚠ `public` is a reserved folder name.** Folder names are free text, so typing "public" as an ordinary
  folder name **silently shares that conversation with everyone in the company**. Nothing warns the user.
- **A shared conversation publishes its other folder names.** Once shared, its remaining folder names
  appear in every user's folder-name suggestions — so a folder named after a customer becomes visible to
  all.
- **Automation credentials always win.** If an administrator uses a tool that carries the machine key,
  that work runs as the machine and loses administrator rights — which looks like a permissions bug.
- **Connector imports are administrator-only.** A regular user or machine-key caller is refused.
- **⚠ Conversation access is not audited.** The audit trail records content actions such as imports and
  deletions. **Opening or reading another person's conversation leaves no record anywhere.**
- **Refusals are honest about existence.** A link to a conversation that does not exist reports *not
  found*; a private one that does exist is refused as *access denied* — which confirms to the link holder
  that it exists.
- **Desktop app access is owner-only.** It lists and opens only conversations you own; sharing and
  administrator reading are web-only.

### Limits

- **The sidebar loads only the 100 most recent conversations you can see.** No paging control, no search.
- **Sends are capped per person**, 20 per minute by default.
- **A limited number of live answers run at once** (64 by default). When full the user is asked to retry —
  but the question has already been saved, so the thread shows a question with no answer. That ceiling
  covers only the live word-by-word mode, so the effective limit depends on which mode people use.
- **All automation shares one machine key**, therefore one send budget and one identity. A busy script can
  rate-limit every other integration, and reporting cannot tell them apart.
- **Every signed-in user can read how the assistant is configured.** The playbook library, the
  specialist profiles and the base instructions are readable by any account holding an access token —
  the desktop app and connected AI clients need them — with no administrator check. So how Yvoke works
  is visible to everyone who can sign in, not only what it knows. The machine key, by contrast, cannot
  reach them at all: it is confined to importing, jobs and documents.
- **The offered models, the chat on/off switch, the per-user question limit, the capacity ceiling and the
  number of simultaneous imports are set when the system is deployed** — not editable by an administrator
  in the product. Changing any of them is a deployment change.
- **The *Public Conversations* count counts a conversation once per folder it sits in**, so the number can
  exceed the number of distinct shared conversations.
- **Rating buttons are shown to viewers of a shared conversation**, but any rating they submit is refused
  and the widget silently keeps its previous state.
- **Sharing has no expiry and no viewer list.** It stays visible until the owner removes the tag, and the
  owner cannot see who has read it.

### Not supported

- Sharing with one person, a named group, or via an external link.
- Any user management inside Yvoke — no invite, no role change, no deactivation. Every account is
  nevertheless listed by name in the spend filter (chapter 8) — by email address where no name is
  stored — but only as something to filter a report by; there is nothing there to act on.
- Signing in with anything but the company account. No local password, self-registration or reset.
- Read receipts, share notifications, or a "who viewed this" list.
- **Restricting a knowledge area to a team.** Every signed-in user can query the whole corpus; there are
  no per-area permissions.
- An administrator editing or moderating content inside another person's conversation.
- Transferring ownership of a conversation, or keeping a leaver's conversations accessible after their
  account goes.

---

## 7. Using the assistant from other tools

**What it is for.** The knowledge base is not locked inside the web app. AI coding assistants search it
directly using the same tools the in-app assistant uses, and a desktop app keeps its conversations in the
user's account.

**Who uses it.** Consultants working inside AI coding assistants; desktop app users; the platform team
running corpus scripts and the evaluation harness.

### What you can do

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

### How it behaves

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

### Limits

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

### Not supported

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

## 8. Cost, usage and quality visibility

**What it is for.** Three questions: what the assistant costs to run, who is using it, and whether the
answers are any good.

**Who uses it.** Administrators and the product owner for spend and usage; the knowledge team for
feedback triage and answer traces; every user rates answers.

> **A note on units.** AI providers bill by amount of text processed. Four rates are set per model: text
> the assistant **read**, text it **wrote**, text **re-used** from a previous request, and text it
> **thought with**. Everything below is expressed in those terms.

### What you can do

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

### How it behaves

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

### Limits

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

### Not supported

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

## Decisions worth taking

Positions the product held by default rather than by choice. **All twelve were reviewed on 2026-08-08**:
four were changed, seven were accepted deliberately and stay as they are, and one was handed to the
evaluation harness — that twelfth has since been resolved by an unrelated change. Each row records
which.

An accepted item is not an open question — it is a decision, and the reasoning is in the row. What is
worth re-reading periodically is the *trigger* on item 5, and items 3, 4 and 7, which are the ones an
outside party (a works council, a data-protection review, a budget owner) is most likely to ask about
before we would otherwise revisit them.

| # | The situation | Why it matters |
| --- | --- | --- |
| 1 | ~~Any signed-in user can import content into any knowledge area.~~ **Resolved 2026-08-08** — importing now requires an administrator or a machine key. | Was: anyone could put material in front of every colleague's answers. |
| 2 | **Typing `public` as an ordinary folder name silently shares the conversation** with everyone in the company. No warning, no confirmation. **Accepted 2026-08-08** — behaviour kept as-is. | A plausible accident with a real disclosure consequence. Revisit if it happens in practice. |
| 3 | **Reading another person's conversation is not audited.** Administrators can open any thread — and see other users' message text in the passage inspector — and nothing records it. **Accepted 2026-08-08.** | Works councils and data-protection reviews normally ask about exactly this; the honest answer today is that we cannot say who read what. |
| 4 | **Nothing is ever deleted automatically.** Every question, answer, rating, search record, trace and spend row is kept indefinitely; there is no scheduled cleanup anywhere. **Accepted 2026-08-08.** | There is no retention policy to point at when asked "how long do you keep our questions?" — today the answer is "forever, and administrators can read all of it". |
| 5 | **Every user can query every knowledge area.** There are no per-area permissions. **Accepted 2026-08-08** — correct for a corpus that is entirely internal OIM documentation. **Revisit trigger: the first import of a customer-specific or NDA-bound corpus.** At that point the absence becomes a real exposure, and retrofitting it under time pressure is far worse than building it deliberately. | Fine while all content is internal; blocks any customer-confidential corpus. |
| 6 | **AI-client searches are not rate-limited** while web chat is capped at 20 sends/minute. Each search costs a paid embedding and re-rank call. **Accepted 2026-08-08** — agentic clients legitimately make many calls, and the real protection is spend control (item 7), not a request cap. | One misconfigured client can spend without limit and nothing caps it. |
| 7 | **There are no budgets, limits or alerts on spend.** **Accepted 2026-08-08.** Note the architecture already has the right chokepoint if this changes: every model call passes through one accounting layer. | Cost is observable only after the fact. The failure mode is not a spike — it is a misconfigured playbook or looping client draining steadily for a week before anyone opens the dashboard. |
| 8 | ~~A bare thumbs-up is discarded in web chat but kept in the desktop app.~~ **Resolved 2026-08-08** — both surfaces now store it. | Was: the metric measured two different things. Leaves a step change in the ratio at that date. |
| 9 | ~~The chat UI says "skill" while the admin screens say "playbook".~~ **Resolved 2026-08-08** — the chat UI now says **playbook** everywhere, and **tag** stays the term for a content label (a tag usually carries a product version, but is not limited to that). One vocabulary across the product. | Was: two vocabularies for the same things, surfacing in every training and support conversation. |
| 10 | **Removing a tag deletes content with no confirmation** — one click on a small ×, permanently, cascading to that tag's documents, knowledge graph and orphaned records, then reporting "Tag removed successfully." **Accepted 2026-08-08.** | The only destructive action in the product without a confirmation step — deleting a knowledge area, one row below on the same screen, does confirm. It is at least audited with its document count. |
| 11 | ~~A repeated identical question can be answered from cache without the model running.~~ **Resolved 2026-08-22** — the gateway that replayed requests is no longer used; every question reaches the model. | Was: an evaluation replaying a fixed question set sent byte-identical requests by design, so it could score a replayed answer at near-zero cost. Re-running a suite was the worst case, not the safest. |
| 12 | ~~A playbook saved with no tools answers from the question alone, and nothing warns.~~ **Resolved 2026-08-08** — the tool allow-list now denies by default (an unset list no longer grants everything), and the playbook list badges both a specialist with no tools and a reviewer/orchestrator whose tools are ignored. | Was: one misconfigured playbook silently turned the product back into a plain chatbot. |
| 13 | ~~Summarising and graph extraction ran with no instructions at all when none were chosen, and reported success.~~ **Resolved 2026-08-23** — the instructions are a required part of the job now, checked when it is submitted, and no code path falls back to running without them. | Was: the worst shape of failure this product has. Nothing errored, the job completed with normal counts, and the damage sat in the corpus — section summaries that announced themselves instead of describing anything ("Here is a summary of the section:"), and graph extraction whose output the model was never told to shape, so its unparseable replies were counted as passages containing nothing. Both are indistinguishable from thin source material until somebody reads the result. One connected space was imported this way. |

