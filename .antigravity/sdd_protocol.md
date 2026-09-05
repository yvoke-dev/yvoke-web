# Strict SDD Protocol: Spec-Driven Development

This guide defines the strict Antigravity Spec-Driven Development (SDD) workflow for features, architectural enhancements, and refactors. All plans and task tracking occur natively within the parent agent's `brain` directory; do NOT create *per-task* plan, design, or checklist files in the local workspace.

The durable **functional specification** in `spec/` (indexed by `spec/README.md`) describes what the product does, its limits, and what it deliberately does not do. Read the chapter in `spec/` for the area you are changing at the start of Phase 1 — that is the intent — then the tests owning the feature, which are the contract. Always update the relevant chapter in `spec/` whenever a change alters user-observable behaviour or limits.

```
                    STRICT SDD PIPELINE OVERVIEW
                    
  Phase 1: Discovery & Sparring  sdd_planner reads spec/, investigates implications,
                                 formulates trade-off questions via ask_question.
          │
  Phase 2: Plan & Red Team       sdd_plan_critic attacks draft plan (Pitfalls & Over-engineering).
                                 Parent produces hardened implementation_plan.md artifact.
          │
  Phase 3: Worktree & Tasks      sdd_task_architect creates git worktree in .worktrees/.
                                 sdd_task_critic eliminates Happy-Path Test Syndrome.
                                 Emits task.md with explicit negative/failure test cases.
          │
  Phase 4: Wave Execution Loop   For each wave (inside the worktree):
          │                      1. spring_implementer: Red -> Green -> Refactor (Spotless).
          │                      2. spring_reviewer: Audits diff (Security, N+1, ArchUnit).
          │                      3. Wave Commit: git commit -m "feat(sdd): [Wave N] ..."
          │                      (Mandatory Wave N-1: Update spec/ chapter file)
          │
  Phase 5: Holistic Audit        sdd_auditor verifies spec/, ProductSpecStructureTest,
                                 check_steering.py, spotless, and full test suite.
          │
  Phase 6: PR & Handoff          sdd_auditor pushes branch, opens GitHub PR (gh pr create),
                                 compiles walkthrough.md for user review & squash-merge.
```

---

## Phase 1: Discovery & Architectural Sparring
1. **Spec Investigation**: Invoke `sdd_planner` to inspect the relevant capability chapters in `spec/` (e.g. `spec/01_asking_questions.md`) and understand current behaviour, limits, and intentional absences ("Not supported").
2. **Implications & Trade-offs Check**: The planner analyzes side effects on existing domains, models, and constraints. It identifies what might break or become ambiguous, formulating sharp questions to ensure the user fully understands the architectural implications.
3. **User Clarifications**: The planner sends formulated questions to the parent agent, which presents them via the interactive `ask_question` tool. Relay user answers back to the planner.

---

## Phase 2: Planning Mode & Adversarial Plan Critique (Gate 1)
1. **Adversarial Plan Attack (Gate 1)**: Before presenting the plan to the user, invoke `sdd_plan_critic` to attack the draft plan:
   - Cross-checks against all 47 Known Pitfalls in `.agents/AGENTS.md` / `CLAUDE.md` § 6.
   - Identifies failure modes, concurrency contention, and partial failure orphans.
   - Challenges over-engineering and tests whether the bug can be made *unrepresentable* instead of adding defensive code.
2. **Harden Implementation Plan**: The planner refines the plan based on the critic's report.
3. **Design Plan Artifact**: The parent agent produces `implementation_plan.md` natively in the brain directory, documenting:
   - User Review Required & Breaking Changes
   - Resolved Design Questions & Trade-offs
   - Red Team Critique & Mitigations
   - Architectural Decisions & Domain Invariants
   - Required Spec Delta (`spec/*.md`)
   - Verification Plan
4. **User Approval**: Present the plan to the user (`RequestFeedback: true`) and wait for explicit approval before proceeding.

---

## Phase 3: Task Architecture & Adversarial Test Critique (Gate 2)
1. **Worktree Creation**: Invoke `sdd_task_architect` to create an isolated git worktree inside `.worktrees/`:
   ```bash
   git worktree add -b sdd/<feature-name> .worktrees/sdd-<feature-name> HEAD
   ```
   *(Note: If the branch already exists from a prior attempt, use `git worktree add .worktrees/sdd-<feature-name> sdd/<feature-name>` or delete the stale branch first via `git branch -D sdd/<feature-name>`).*
   *Rule*: All implementation and review work must execute strictly within this worktree. The main working tree on `main` remains untouched and undisturbed.
2. **Draft Wave Breakdown**: The task architect organizes the work into sequential waves (Foundation -> Domain -> Web/UI -> Spec -> Audit).
3. **Adversarial Task & Test Critique (Gate 2)**: Invoke `sdd_task_critic` to attack the task list:
   - **Eliminates Happy-Path Test Syndrome**: Mandates that **every wave must include at least one explicit Negative / Failure Test** (e.g. boundary conditions, constraint violations, timeouts, bad input rejection).
   - Verifies tests assert real state changes rather than trivial assertions.
4. **Task Artifact Generation (`task.md`)**: The task architect incorporates all negative tests and emits the hardened `task.md` in the brain directory:
   - **Mandatory Wave N-1**: Update `spec/` capability chapter.
   - **Mandatory Wave N**: SDD Auditor verification and PR creation.

---

## Phase 4: Wave Execution Loop & Resilience Review (Gate 3)
Execute each wave sequentially inside the worktree directory:

1. **Implementer (Strict Red-Green TDD)**:
   Invoke `spring_implementer` inside `.worktrees/sdd-<feature-name>`:
   - **Red Phase**: Write the test first (both happy-path and the mandatory negative tests). Run the targeted test command (e.g. `./mvnw test -Dtest=MyTest -DskipJsTests=true`) and verify it fails (RED) with the expected assertion error.
   - **Green Phase**: Write the minimal production code to satisfy the test. Run the targeted test and verify it passes (GREEN).
   - **Refactor & Formatting**: Clean up code and run `./mvnw spotless:apply`.
   - **Test Mutation Proof**: A test does not count until you have seen it fail. If modifying an existing test, break the production code minimally to confirm RED, then restore.
2. **Adversarial Code & Resilience Review (Gate 3)**:
   Invoke `spring_reviewer` inside `.worktrees/sdd-<feature-name>` to audit `git diff`:
   - Inspects for SQL injection (parameterized queries only), inert `@PreAuthorize`, `@Transactional(readOnly=true)`, N+1 queries, ArchUnit boundaries, and Spotless compliance.
   - Probes for tainted input vulnerabilities (corpus, request params, LLM outputs).
   - Verifies test quality and confirms the test actually ran Red -> Green (not a false-green tautology).
3. **Remediation**:
   If the reviewer finds material issues, invoke `spring_implementer` to remediate, followed by a re-review.
4. **Wave Commit**:
   Once the wave passes review and tests, commit the wave on the feature branch:
   ```bash
   git add -A && git commit -m "feat(<domain>): [Wave N] <wave description>"
   ```

---

## Phase 5: Holistic Audit & Verification
Invoke `sdd_auditor` inside `.worktrees/sdd-<feature-name>` to run the release gatekeeper checks:
1. **Spec Verification**: Verify the relevant chapter in `spec/` was updated, and run:
   ```bash
   ./mvnw test -Dtest=ProductSpecStructureTest -DskipJsTests=true
   ```
2. **Steering Check**: Run `python3 .antigravity/scripts/check_steering.py`. If structural changes occurred, update `.antigravity/steering/`.
3. **Spotless & Parity Check**: Run `./mvnw spotless:check` and `./mvnw test -Dtest=AgentRuleFilesParityTest -DskipJsTests=true`.
4. **Full Test Suite**: Run `./mvnw test -DskipJsTests=true` (and `./mvnw verify -Pit-tests` when integration tests were added/modified).

---

## Phase 6: Branch Push, PR Creation & User Handoff
1. **Push Branch**: Inside the worktree, push the feature branch to origin:
   ```bash
   git push -u origin sdd/<feature-name>
   ```
2. **Open Pull Request**: Create a PR against `main` using GitHub CLI:
   ```bash
   gh pr create --base main --head sdd/<feature-name> --title "feat(<domain>): <feature-title>" --body "<plan, changes, and test summary>"
   ```
   *(Note: If `gh` is unauthenticated or not installed, provide the direct GitHub URL or instructions to open the PR manually).*
3. **Walkthrough & Handoff**:
   - Compile `walkthrough.md` in the brain directory with the PR link, summary of wave commits, and verification logs.
   - Instruct the user to review the PR on GitHub and squash-merge when ready.
   - Once merged, the worktree can be cleanly removed:
     ```bash
     git worktree remove --force .worktrees/sdd-<feature-name>
     git branch -D sdd/<feature-name>
     ```

