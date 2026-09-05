# 2. How answers are produced

**What it is for.** This is what makes an answer trustworthy. Every answer is built by searching the
knowledge base and citing the passages it used. Hard questions can instead be handed to a team of
specialist agents whose draft is reviewed before it reaches the user.

**Who uses it.** Everyone who asks a question. Administrators curate the playbooks, base instructions and
in-depth profiles, and can inspect any search or trace.

## What you can do

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
| **Administrators configure in-depth profiles** | A profile names the lead playbook, the reviewer playbook and the available specialists, plus how many review rounds and specialist calls one question may spend, which model each role runs at, and whether it is flagged as a prototype (`prototype = true`). Prototype profiles are hidden by default from regular users. |
| **Administrators test and audit** | A search console runs any query against a knowledge area and shows the passages, their scores and which search found them. A log lists past searches with the rating the user gave. Every investigation keeps a full trace. |

## How it behaves

- **Playbooks and in-depth profiles are flagged with `prototype = true` (default `false`).** They are
  excluded from normal user-facing discovery — playbook pickers and preflight suggestions, and the in-depth
  profile selector — allowing new or experimental retrieval strategies (such as top-down browsing) to be
  tested without cluttering standard workflows. The flag governs discovery only: a prototype profile that
  is already selected resolves and runs exactly like any other, and both chat clients read the flag from
  the same one setting the user controls.
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

## Limits

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

## Not supported

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
