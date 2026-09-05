# 1. Asking questions

**What it is for.** The chat workspace: where someone puts a question to Yvoke and reads the answer
together with the documentation it came from.

**Who uses it.** Every signed-in user. Administrators use the same screens, plus a ratings dashboard and
a register of everyone's conversations.

## What you can do

| Capability | What happens |
| --- | --- |
| **Start a conversation** | *New Chat* opens an empty thread titled *New Conversation*. The first question becomes the title, shortened if long, and is then fixed. Conversations made in the desktop app appear in the same sidebar, marked *Desktop*. |
| **Pick a playbook** | Before asking, the user picks a named way of answering. Suggestions appear as chips on an empty conversation; typing `/` or clicking **+** opens the full list with descriptions. The active choice is shown above the input. |
| **Ask by typing or by voice** | The input grows as you type; Enter sends, Shift+Enter adds a line. A microphone button dictates into the box — tap to start and stop, or hold to record, chosen in voice settings. |
| **Watch the answer being produced** | A placeholder appears immediately with rotating captions — *Thinking*, *Searching the knowledge base*, *Analyzing retrieved resources*, *Formulating the response*, *Polishing the answer* — and names each research tool as it runs. |
| **Read a formatted answer** | Answers render headings, tables, code blocks, mathematical formulas and drawn diagrams. Markup an answer quotes — an XML fragment, a configuration snippet, a stray HTML tag — is shown verbatim as source rather than rendered, so it survives intact instead of vanishing; a multi-line fragment is shown as a code block. A link to somewhere outside Yvoke opens in a new tab, so following one never loses the conversation. In Streaming mode a diagram shows as its source text while the answer is still arriving, and is drawn once it finishes. |
| **Stop a running answer** | The send button becomes a stop button while an answer runs. Stopping marks the answer *[Generation stopped by user]*. In Standard mode the text so far is kept; in in-depth mode the partial answer is discarded. |
| **Open the source behind a citation** | Clicking a citation opens a *Citation Source* panel showing the cited passage and nothing else — deliberately, since a citation is the claim that *this* passage supports the sentence, and padding it out with neighbouring text invites confirming a claim against material the assistant never read. The panel carries the document title, its version, the passage's place in the document, and a link to the original page where one exists. |
| **See the reasoning and tools used** | *Show thinking & tools* reveals the assistant's reasoning and every tool it called. A *View Thinking Process* button opens the full reasoning in a panel. Forced on in Streaming mode. |
| **See how much work an answer took** | Each answer shows how much text the assistant read, re-used, thought with and wrote. Users see effort, never money — no prices appear in chat. |
| **Answer a clarifying question** | When the assistant needs more information it posts a *Clarification Required* card, often with ready-made options. The input is locked until the user answers. The card then collapses to *Clarification Provided*. |
| **Get a playbook check before the first question** | On the first question Yvoke checks whether the chosen playbook suits it. If not, a *Playbook Recommendation* card explains why and offers **Switch to …** and **Send Anyway**. |
| **Rate an answer** | Thumbs up and thumbs down on every answer. Clicking one opens a comment box; submitting stores the rating and shows *Feedback saved*. Changing the thumb later keeps the comment. |
| **Organise conversations into folders** | Folder tags group conversations in the sidebar; a conversation appears under each tag it carries, or under *No Tag*. Autocomplete offers the user's own existing folder names. |
| **Share a conversation** | Adding the tag `public` moves it into a *Public Conversations* folder that every signed-in user sees, with a *Shared* badge. Removing the tag un-shares it. |
| **Choose model, effort and mode** | Each conversation picks its own model from the allowed list, a reasoning effort of Low / Medium / High, and Standard or Streaming delivery. Where in-depth profiles exist, a selector switches to one. |
| **Toggle prototypes** | A button in the composer toolbar toggles *Show prototype playbooks & profiles*. When enabled, experimental playbooks (marked with 🧪) appear in empty-thread suggestion chips and in `/` and `+` playbook menus, and experimental in-depth profiles appear in the profile selector. Disabled by default. |
| **Delete a conversation** | Confirmed, then permanent — the thread, its answers and its ratings. |
| **Administrators: review ratings and read any thread** | A *Feedback* page shows the positive-rating share, counts and every comment, filterable by rating, review status and time. A *User Conversations* page lists all conversations with owner, title and source. |

## How it behaves

- **The user never picks the knowledge area or the tag.** The chosen playbook decides which area and which
  tag are searched. Asking about a different product version means picking a different playbook — there is
  no tag selector in chat. *This surprises most new users.*
- **Prototypes (`prototype = true`) are hidden from user selection by default.** An experimental
  playbook does not appear in prompt chips, slash autocomplete or preflight recommendations, and an
  experimental in-depth profile does not appear in the profile selector, unless the user enables prototype
  visibility for the conversation or browser session. One toggle governs both.
- **The profile a conversation already uses is never hidden from it.** Turning prototype visibility off
  hides every experimental profile except the one this conversation is set to, which stays listed and keeps
  running — otherwise the selector would read *Single playbook* over a conversation that is still answering
  in depth. A deployment whose only profiles are hidden prototypes shows no profile selector at all.
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

## Limits

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

## Not supported

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
