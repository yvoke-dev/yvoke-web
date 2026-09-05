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
> and where it stops. And **anyone — person or agent — about to make a substantial change: read the
> relevant chapter in `spec/` before you start.** It is the fastest way to learn what a feature is *for*, which behaviours
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
> **Keeping it true.** A change a user would notice must update the affected chapter file in `spec/` in the same
> change — a new capability in *What you can do*, a changed rule in *How it behaves*, a raised or
> lowered ceiling in *Limits*, something newly possible struck from *Not supported*. A stale
> specification is worse than none, because agents act on it: that is exactly how the far longer prose
> specification that used to occupy this filename earned its retirement, once the test suite carried
> what it had been trying to say. This document inherited the name afterwards — so a reference to
> `spec.md` predating that retirement means the old engineering specification, not what you are
> reading.
>
> **How to read it.** Eight capability chapters, each in its own file under `spec/`. Each has the same shape: what the area is
> for, who uses it, **what you can do**, **how it behaves**, **limits**, and **not supported**. The last
> two are the useful ones in a stakeholder conversation — they are where the surprises live.

---

## Contents

| # | Chapter | Mainly for | Summary |
| --- | --- | --- | --- |
| — | [What Yvoke is](#what-yvoke-is) · [Words we use](#words-we-use) | read first | Overview of Yvoke, core architectural shape, and domain glossary. |
| 1 | [Asking questions](01_asking_questions.md) | every user | The chat workspace: where someone puts a question to Yvoke and reads the answer together with the documentation it came from. |
| 2 | [How answers are produced](02_how_answers_are_produced.md) | every user · knowledge team | Retrieval, playbooks, multi-agent in-depth mode, and how answers cite source passages. |
| 3 | [Building the knowledge base](03_building_the_knowledge_base.md) | administrators | Loading content into Yvoke: knowledge areas, tags/versions, Confluence connector, and knowledge-graph extraction. |
| 4 | [Inspecting and curating content](04_inspecting_and_curating_content.md) | administrators | Admin inspection of imported documents, search chunk splits, entities, and content curation. |
| 5 | [Long-running work and its progress](05_long_running_work_and_its_progress.md) | administrators | Background ingestion queues, worker pools, status tracking, and failure handling. |
| 6 | [Access, roles and sharing](06_access_roles_and_sharing.md) | everyone | User authentication, role capabilities, conversation visibility, and sharing controls. |
| 7 | [Using the assistant from other tools](07_using_the_assistant_from_other_tools.md) | consultants · platform team | External MCP server tools, REST API endpoints, and desktop client integration. |
| 8 | [Cost, usage and quality visibility](08_cost_usage_and_quality_visibility.md) | product owner · administrators | LLM cost accounting, token usage monitoring, ratings, and analytics dashboard. |
| — | [Decisions worth taking](#decisions-worth-taking) | product owner | Open product questions, tradeoffs, and architectural decisions. |

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
