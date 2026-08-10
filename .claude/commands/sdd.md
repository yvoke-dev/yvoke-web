---
description: Run the Spec-Driven Development flow for a large task, feature, or refactor
---

# Spec-Driven Development (SDD)

Follow this workflow for large tasks, features, and refactors. For small/medium
tasks, skip it and implement directly. This is the Claude Code adaptation of
`.antigravity/sdd_protocol.md`.

Task: $ARGUMENTS

## Phase 1 — Discovery & Research
0. **Read the spec chapter, then the tests**: Start with `spec.md`'s chapter for the area you are changing — what the feature is for, how it behaves, its limits and its deliberate non-features. That is the intent. Then find and read the tests that own the feature: they are the contract, and if none fails when you break a rule, that rule is not enforced.
1. **Understand & drill**: Use the `AskUserQuestion` tool to resolve any ambiguity, underspecified requirements, or design intent (offer a recommended choice). Do not proceed on assumptions.
2. **Alternatives**: Identify 2–3 viable ways to achieve the feature.
3. **Recommendation**: List pros/cons per strategy and recommend one. Get user approval before Phase 2.

## Phase 2 — Planning
1. Use `EnterPlanMode` (or the `Plan` agent) to produce an implementation plan: files to touch, wave breakdown, the correctness properties tests must cover, and the **security & performance properties** at stake (trust boundaries / tainted input, authz surface, hot paths, N+1 / index needs).
2. Present the plan for approval via `ExitPlanMode`. Do not write code until approved.

## Phase 3 — Task Checklist
1. Create a wave-based checklist with `TaskCreate` (e.g. Wave 0: DB schema, Wave 1: data access, Wave 2: service/controller, Wave 3: UI).
2. Keep the plan in the conversation/task list — do NOT write per-task plan/design/checklist files into the workspace. (The durable functional specification `spec.md` at the repo root is the deliberate exception; see Phase 5.)

## Phase 4 — Sequential Execution (one wave at a time)
For each wave:
1. **Implement**: Invoke the `spring-implementer` subagent (Agent tool) to write code for the wave. Only one code-writing subagent active at a time. It edits the workspace but never commits. Then mark the wave `completed` via `TaskUpdate`.
2. **Prove the wave's tests can fail**: For each test the wave added or changed, break the one thing it claims to pin with a minimal edit to production code, run it, watch it go **red**, then restore by re-reading the original — not from memory. A test that has only ever been green is a claim, not evidence; six of the incidents in `CLAUDE.md`'s *Known Pitfalls* are bugs a passing test was actively hiding. A new test that passes on its **first** run against unfixed code is a defect in the test.
3. **Confirm the wave is actually running**: never read a bare `BUILD SUCCESS` as proof new code ran — check the test *count* changed, and use `set -o pipefail` (`./mvnw … | tail` otherwise reports `tail`'s exit code). If the wave touched a migration, config or anything you will exercise against the local stack, redeploy with `./redeploy.sh` — `docker compose up -d` never rebuilds, so a new `V<N>__*.sql` and every source change since the last build are simply absent while Flyway reports success.
4. **Review**: Invoke the `spring-reviewer` subagent to audit the uncommitted changes for security, transactions, migration safety, and N+1.
5. **Remediate**: If the reviewer finds material issues, present them. On approval, re-invoke `spring-implementer` to fix, then re-review. When a fix lands, ask what parameter, branch or overload can now be **deleted** so the mistake cannot be re-expressed — an unrepresentable bug outlasts any rule written down about it.

## Phase 5 — Spec, Steering Maintenance & Handoff
0. **Pin the behaviour, then keep the spec true**: If the change altered observable behaviour — a route, status value, default, limit, validation rule, or contract — the test that pins it must change in the same change set. If a *user* would notice the change, update that chapter of `spec.md` in the same change too: a new capability in **What you can do**, a changed rule in **How it behaves**, a moved ceiling in **Limits**, something newly possible struck from **Not supported**.
1. **Steering check**: Run `python3 .antigravity/scripts/check_steering.py`. If it flags structural drift, update `.antigravity/steering/{structure,tech,product}.md` accordingly.
1b. **Security pass**: For changes touching auth, SQL, endpoints, or untrusted input, run the built-in `/security-review` over the diff as a final backstop (beyond the per-wave `spring-reviewer` audit).
2. **Walkthrough**: Summarize the testing logs and the uncommitted diff for the user.
3. **Handoff**: Do NOT commit or merge. Tell the user to review the uncommitted changes in their IDE and commit manually.
