# ASDD Protocol: Spec-Driven Development

This guide defines the strict Antigravity Spec-Driven Development (ASDD) workflow for large tasks, features, and refactors. All plans and task tracking occur natively within the parent agent's `brain` directory; do NOT create *per-task* plan, design, or checklist files in the local workspace.

The durable **functional specification** `spec.md` (repo root) is the exception and is checked in deliberately: it describes what the product does, its limits, and what it deliberately does not do. Read its chapter for the area you are changing at the start of Phase 1 — that is the intent — then the tests owning the feature, which are the contract. Change the tests in Phase 5 if the change alters observable behaviour, and the chapter too if a user would notice it.

## Phase 1: Discovery & Research
1. **Understand & Drill**: Use the native `ask_question` tool to present multiple-choice questions (with a recommended choice and a default write-in option) to resolve any ambiguity, underspecified requirements, or design intent. Do not proceed with assumptions.
2. **Alternative Strategies**: Perform research to identify 2 to 3 different ways of achieving the feature.
3. **Recommendation**: For each strategy, list pros/cons and conclude with a recommended path. Only transition to Phase 2 after user approval.

## Phase 2: Planning Mode
1. **Design**: The parent agent enters Planning Mode natively to create the `implementation_plan.md` artifact.
2. **Review**: Present the implementation plan for user approval.

## Phase 3: Task Checklist & Execution Setup
1. **Task Setup**: Create a wave-based checklist (`task.md`) natively in the brain directory (e.g., Wave 0: DB Schema, Wave 1: Data Access, Wave 2: Service/Controller).
2. **Subagent Definition**: Before invoking subagents, read their templates in `.antigravity/agents/`. Define them using the `define_subagent` tool with the exact name, description, and system prompt.

## Phase 4: Sequential Subagent Execution
1. **Implementer**: Invoke `spring_implementer` (with `Workspace: inherit`) to write code for the current wave directly in the local workspace.
   - *Rule*: Only one code-writing subagent may be active at a time. The implementer modifies files without committing.
   - Upon completion, the parent agent updates `task.md`.
2. **Prove the wave's tests can fail**: For each test the wave added or changed, break the one thing it claims to pin with a minimal edit to production code, run it, watch it go **red**, then restore by re-reading the original — not from memory. A test that has only ever been green is a claim, not evidence; six of the incidents in `.agents/AGENTS.md`'s *Known Pitfalls* are bugs a passing test was actively hiding. A new test that passes on its **first** run against unfixed code is a defect in the test.
3. **Confirm the wave is actually running**: never read a bare `BUILD SUCCESS` as proof new code ran — check the test *count* changed, and use `set -o pipefail` (`./mvnw … | tail` otherwise reports `tail`'s exit code). If the wave touched a migration, config or anything you will exercise against the local stack, redeploy with `./redeploy.sh` — `docker compose up -d` never rebuilds, so a new `V<N>__*.sql` and every source change since the last build are simply absent while Flyway reports success.
4. **Reviewer Audit**: Invoke `spring_reviewer` (with `Workspace: inherit`) to audit the uncommitted changes for security, N+1 queries, and transactional integrity.
5. **Remediation**: If the reviewer finds material issues, present them to the user. Upon approval, invoke `spring_implementer` to remediate, followed by another reviewer check. When a fix lands, ask what parameter, branch or overload can now be **deleted** so the mistake cannot be re-expressed — an unrepresentable bug outlasts any rule written down about it.

## Phase 5: Steering Maintenance & Ship
0. **Pin the Behaviour, then keep the spec true**: If the change altered observable behaviour — a route, status value, default, limit, validation rule, or contract — the test pinning it must change in the same change set. If a *user* would notice the change, update that chapter of `spec.md` in the same change too: a new capability in **What you can do**, a changed rule in **How it behaves**, a moved ceiling in **Limits**, something newly possible struck from **Not supported**.
1. **Steering Check**: Run `python3 .antigravity/scripts/check_steering.py`. If structural changes occurred, update the relevant steering files (`structure.md`, `tech.md`, `product.md`).
2. **Walkthrough**: Compile a final `walkthrough.md` in the native brain folder presenting testing logs and the uncommitted diff.
3. **Final Handoff**: Do NOT commit or merge changes. Instruct the user to review the uncommitted changes in their IDE and commit manually.
