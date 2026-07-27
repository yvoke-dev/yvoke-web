---
description: Run the Spec-Driven Development flow for a large task, feature, or refactor
---

# Spec-Driven Development (SDD)

Follow this workflow for large tasks, features, and refactors. For small/medium
tasks, skip it and implement directly. This is the Claude Code adaptation of
`.antigravity/sdd_protocol.md`.

Task: $ARGUMENTS

## Phase 1 — Discovery & Research
1. **Understand & drill**: Use the `AskUserQuestion` tool to resolve any ambiguity, underspecified requirements, or design intent (offer a recommended choice). Do not proceed on assumptions.
2. **Alternatives**: Identify 2–3 viable ways to achieve the feature.
3. **Recommendation**: List pros/cons per strategy and recommend one. Get user approval before Phase 2.

## Phase 2 — Planning
1. Use `EnterPlanMode` (or the `Plan` agent) to produce an implementation plan: files to touch, wave breakdown, the correctness properties tests must cover, and the **security & performance properties** at stake (trust boundaries / tainted input, authz surface, hot paths, N+1 / index needs).
2. Present the plan for approval via `ExitPlanMode`. Do not write code until approved.

## Phase 3 — Task Checklist
1. Create a wave-based checklist with `TaskCreate` (e.g. Wave 0: DB schema, Wave 1: data access, Wave 2: service/controller, Wave 3: UI).
2. Keep the plan in the conversation/task list — do NOT write spec files into the workspace.

## Phase 4 — Sequential Execution (one wave at a time)
For each wave:
1. **Implement**: Invoke the `spring-implementer` subagent (Agent tool) to write code for the wave. Only one code-writing subagent active at a time. It edits the workspace but never commits. Then mark the wave `completed` via `TaskUpdate`.
2. **Review**: Invoke the `spring-reviewer` subagent to audit the uncommitted changes for security, transactions, migration safety, and N+1.
3. **Remediate**: If the reviewer finds material issues, present them. On approval, re-invoke `spring-implementer` to fix, then re-review.

## Phase 5 — Steering Maintenance & Handoff
1. **Steering check**: Run `python3 .antigravity/scripts/check_steering.py`. If it flags structural drift, update `.antigravity/steering/{structure,tech,product}.md` accordingly.
1b. **Security pass**: For changes touching auth, SQL, endpoints, or untrusted input, run the built-in `/security-review` over the diff as a final backstop (beyond the per-wave `spring-reviewer` audit).
2. **Walkthrough**: Summarize the testing logs and the uncommitted diff for the user.
3. **Handoff**: Do NOT commit or merge. Tell the user to review the uncommitted changes in their IDE and commit manually.
