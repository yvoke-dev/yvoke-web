# Yvoke

This is Yvoke, built using Java, Spring Boot, and PostgreSQL.

## Prerequisites

- **Java**: Java 25
- **Docker**: Desktop or CLI with Compose support
- **Node**: Node 22+ — required, not optional. The browser-side JavaScript has its own test tier
  bound to the Maven `test` phase, so without `node` on `PATH` every `./mvnw test`, `package`,
  `verify` and `install` fails. There is nothing to install: the tier has **zero dependencies**
  (no `npm install`, no `node_modules`, no lockfile). To build without it, pass `-DskipJsTests=true`.

## Testing

| command | covers |
| --- | --- |
| `./mvnw test` | Java unit tests **+ the JS tier** (fast, no Docker) |
| `./mvnw verify -Pit-tests` | unit + integration (Testcontainers); emits the JaCoCo aggregate and enforces the coverage ratchet |
| `./mvnw verify -Pe2e-tests` | Playwright browser e2e; first run downloads Chromium |
| `npm test` | the JS tier alone |
| `npm run test:coverage` | the JS tier with its per-file coverage table and floors |

The table above is the current command reference. `docs/testing-plan.md` records how this suite was
built and why — the locked decisions, the tier model, and the Playwright/auth gotchas in its
appendices — but note that the whole `docs/` tree is **git-ignored and local-only**, so it is not in
your clone and is deliberately not linked here.

The JS tier (`src/test/js/`) covers the pure string→string logic extracted out of `thread.js` and
the admin templates into `static/js/chat/` and `static/js/admin/`. It also carries a **syntax gate**
that parses every app-owned `.js` file *and* every inline `<script>` in every Thymeleaf template —
Spotless is Java-only, so without it a JavaScript syntax error can otherwise reach production.

## Local Development Setup

### 1. Create `.env`

All three Compose services (`postgres`, `db-migration`, `app`) declare `env_file: .env`, so **the
stack will not start without it** and it is deliberately not in the repository — it holds real
credentials. There is no `.env.example`; ask a maintainer for a copy. It supplies the datastore and
Flyway settings (`POSTGRES_*`, `SPRING_DATASOURCE_*`, `FLYWAY_*`), the auth mode (`APP_SECURITY_MOCK`,
`APP_SECURITY_API_KEY`, `ENTRA_*`) and the model/provider keys (`AI_PROVIDER`, `GEMINI_API_KEY`,
`VOYAGE_API_KEY`, `OPENROUTER_API_KEY`, `CLOUDFLARE_*`, `ALLOWED_MODELS`, the `*_THINKING_LEVEL` set).

### 2. Build and start

```bash
./redeploy.sh
```

This is the supported path: Maven build → `docker compose build` → `down` → `up -d`. Use it rather
than `docker compose up -d`, which **never rebuilds** — the migrations and the jar are baked into the
images, so a new `V<N>__*.sql` or any source change since the last build simply will not be running
while the stack still starts cleanly and Flyway reports success. Add `--skip-tests` to build with
`package -DskipTests` instead of the full `verify -Pit-tests`.

Note: the database container maps host port `5433` to container port `5432`, to avoid clashing with
other local Postgres instances.

### 3. Verify

```bash
docker compose ps                                     # postgres healthy, app up
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/login   # 200 once Tomcat is serving
docker compose logs app | grep 'Started YvokeApplication'
```

The schema is worth asserting explicitly, because a stale image is invisible otherwise — this must
list every file in `docker/db/migration/`:

```bash
docker compose exec postgres psql -U postgres -d postgres -c "select version, description, success from flyway_schema_history order by installed_rank;"
```

Postgres extensions (`vector` and `pg_search` ship in the custom image; this is a no-op check):

```bash
docker compose exec postgres psql -U postgres -d postgres -c "CREATE EXTENSION IF NOT EXISTS vector; CREATE EXTENSION IF NOT EXISTS pg_search;"
```

There is **no `/health` endpoint** on the app, and Actuator is bound to `management.server.port: 9090`
which Compose does not publish — so neither `localhost:8080/health` nor `localhost:8080/actuator/health`
answers. Publish 9090 in `docker-compose.yml` if you want `/actuator/health` from the host.

## Project Module Layout

For detailed information on the vertical domain packaging and module layout, please refer to the single source of truth: `.antigravity/steering/structure.md`.

## Spec-Driven Workflow

This repository carries **two agent toolchains**, kept deliberately in step because the same rules
have to reach whichever one is driving:

| | Antigravity (ASDD) | Claude Code |
| --- | --- | --- |
| rules | `.agents/AGENTS.md` | `CLAUDE.md` |
| flow | `.antigravity/sdd_protocol.md` | `.claude/commands/sdd.md` (`/sdd`) |
| subagents | `.antigravity/agents/` | `.claude/agents/` |

`.antigravity/steering/` holds durable repository guidance for product, structure and technology, and
is shared by both. The *Known Pitfalls* sections of the two rule files are a full mirror of each
other — `AgentRuleFilesParityTest` fails the build if they diverge, or if a pitfall is restated
outside that section.

All **transient** project-planning files (implementation plan, tasks, and walkthroughs) are managed natively in Antigravity's local memory directory (`<appDataDir>/brain/<conversation-id>/`). No per-task design or checklist files are written to the workspace root or the `.antigravity/` folder, to keep the codebase clean.

The one deliberate exception is [`spec.md`](spec.md) — the durable **functional specification**: what the system does, the limits people will hit, and what it deliberately does not do. It has two audiences. The product owner reads it to know what the product does; anyone about to make a substantial change — person or agent — reads the relevant chapter first, because intent and deliberate non-features are not visible in the code. It stops there: **the test suite is the engineering contract** (`./mvnw verify -Pit-tests`), so read the chapter for intent and the tests that own the feature for the exact behaviour. A change a user would notice must update that chapter in the same commit.

A far longer prose specification of the *engineering* contract once occupied this same path and was retired when the tests came to carry it — a document nothing executes drifts from the code, and agents act on it — so a reference to `spec.md` in an older branch or note means that retired document, not this one.
