# ASDD Protocol: Spec-Driven Development

This guide defines the strict Antigravity Spec-Driven Development (ASDD) workflow for large tasks, features, and refactors. All plans and task tracking occur natively within the parent agent's `brain` directory; do NOT create spec files in the local workspace.

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
2. **Reviewer Audit**: Invoke `spring_reviewer` (with `Workspace: inherit`) to audit the uncommitted changes for security, N+1 queries, and transactional integrity.
3. **Remediation**: If the reviewer finds material issues, present them to the user. Upon approval, invoke `spring_implementer` to remediate, followed by another reviewer check.

## Phase 5: Steering Maintenance & Ship
1. **Steering Check**: Run `python3 .antigravity/scripts/check_steering.py`. If structural changes occurred, update the relevant steering files (`structure.md`, `tech.md`, `product.md`).
2. **Walkthrough**: Compile a final `walkthrough.md` in the native brain folder presenting testing logs and the uncommitted diff.
3. **Final Handoff**: Do NOT commit or merge changes. Instruct the user to review the uncommitted changes in their IDE and commit manually.
