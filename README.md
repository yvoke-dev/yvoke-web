# Yvoke

This is Yvoke, built using Java, Spring Boot, and PostgreSQL.

## Prerequisites

- **Java**: Java 25
- **Docker**: Desktop or CLI with Compose support. The stock builder is enough — nothing here
  cross-builds. Every image the cluster runs is built and pushed by GitHub Actions (see
  [Releasing](#releasing)), and `docker compose build` produces single-platform images for this
  machine only. A `docker-container` buildx builder left over from an earlier setup is harmless.
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
`package -DskipUnitTests=true -DskipJsTests=true` instead of the full `verify -Pit-tests`. Note the
property names: `-DskipTests` is a **no-op** in this project, because the pom wires surefire's
`skipTests` parameter to `skipUnitTests` and a plugin configuration value overrides the user
property — pass it and the whole unit suite runs anyway, reporting success.

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

## Releasing

**The git tag is the version, verbatim.** Tag `1.0.0` builds Maven version `1.0.0`, publishes
`edipal/yvoke-app:1.0.0` and `edipal/yvoke-db-migration:1.0.0`, reports `1.0.0` to MCP clients and
shows `1.0.0` in the admin sidebar. There is no `v` prefix to add anywhere and none to strip — a
transformation would be a second derivation, and two derivations can disagree.

### Cutting a release

1. **Run the *Release* workflow** from GitHub Actions on `main`, choosing `patch`, `minor` or
   `major`. It resolves the next version from the newest release tag, pins it into
   `k8s/app/kustomization.yaml`, runs `./mvnw verify -Pit-tests` at that version, then commits and
   tags. Nothing reaches the remote until every check has passed.
2. ***Docker Build and Publish* runs automatically** when that workflow publishes the GitHub
   Release. It downloads the verified jar from the release assets — it does not rebuild — and pushes
   both images under the release tag.
3. **Deploy once both are green:** `git pull && ./redeploy.sh k8s`. Between step 1 and the end of
   step 2 the manifest references images that do not exist yet, so deploying early gives an
   `ImagePullBackOff`.

Nothing is released by hand. Do not create a tag yourself: the workflow sets the manifest version
and the tag from one value, and that is the only thing keeping them from disagreeing.

`redeploy.sh k8s` builds and pushes nothing — it applies the release the tracked manifest declares.
All three images are published by GitHub Actions, so no local machine needs registry credentials and
no local build can reach the cluster.

### The Postgres image

The custom Postgres image (pgvector + ParadeDB `pg_search`) is the third release image and the odd
one out: it is versioned by what it *contains*, not by the git tag, so it moves only when Postgres
or `pg_search` is bumped — roughly never. It has its own manual workflow, ***Publish Postgres
Image***.

Both versions live in `docker/postgres/Dockerfile` — the base image tag carries the Postgres major,
`ARG PG_SEARCH_VERSION` selects the ParadeDB `.deb`. To bump one:

1. Edit `docker/postgres/Dockerfile`, and set the matching tag in `k8s/app/database/cluster.yaml`
   **in the same commit**. The workflow reads both and refuses to run when they disagree;
   `PostgresImageVersionTest` fails the build if any other reference drifts.
2. Run ***Publish Postgres Image***. It publishes exactly the image `cluster.yaml` names.
3. Deploy with `./redeploy.sh k8s`.

It refuses to overwrite a published tag. That rule is stricter here than for the release images,
because CNPG pulls this one with `imagePullPolicy: Always` — a replaced tag is adopted by the next
pod restart, with no deploy and nothing in git recording that the database engine moved.

### Which version is where

The manifests say what *should* be deployed; the cluster says what *is*. Both are worth knowing, and
they are different questions:

```bash
grep -A3 'name: yvoke/app' k8s/app/kustomization.yaml
```

```bash
kubectl get deploy yvoke-app -n yvoke -o jsonpath='{range .spec.template.spec.containers[*]}{.image}{"\n"}{end}{range .spec.template.spec.initContainers[*]}{.image}{"\n"}{end}'
```

Note `k8s/app/yvoke-app/deployment.yaml` deliberately carries a `SET-BY-KUSTOMIZE` placeholder rather
than a version: the `images:` transformer in `kustomization.yaml` supplies both the registry and the
tag, so a literal there would be overridden — dead text that still looks authoritative. The
placeholder cannot be pulled, so applying that file directly (bypassing kustomize, and therefore the
release declaration) fails by name instead of deploying something plausible.

A signed-in user can also just read the sidebar footer, and `/actuator/info` reports it on the
management port.

### What counts as a break

| Bump | Examples |
| --- | --- |
| **MAJOR** | Removing or renaming an MCP tool, or making a tool parameter newly required; removing a field a desktop or REST client reads; changing an endpoint path or the auth guarding it; removing or renaming an environment variable or a required config key; any destructive migration. |
| **MINOR** | A new MCP tool, a new optional parameter, a new endpoint, a new optional response field, a new admin capability, an additive migration, a new config key that has a default. |
| **PATCH** | Bug fixes, prompt/model/retrieval tuning, performance, UI-only changes, dependency bumps with no contract change. |

The surfaces that define a break are the MCP tool catalogue (external clients cache it), the desktop
client's DTOs, the REST `/api/**/v1` paths and their auth, the operator config contract, and the
database schema.

### Migrations bound what a release can do

The app does not run Flyway — the `db-migration` image is the sole schema authority, and it runs as
an init container in the app's own pod. Two consequences decide how migrations must be written:

- **Migrations must be additive-only**, on every release and not just before a rollback. The
  Deployment uses a rolling update, so the new pod migrates the schema while the *previous*
  release's container is still serving traffic.
- **A destructive change is a two-release operation**: one release stops using the object, the next
  drops it. Rolling back past a destructive migration is a database restore, not a redeploy.

### Rolling back

```bash
git checkout <previous-version> && ./redeploy.sh k8s
```

The images are still in the registry (tags are never overwritten, which is why the manifests pull
with `IfNotPresent`), so this needs no rebuild. The checked-out manifest is what selects the version
— there is no `TAG=` to set, and no local build can produce a different answer. It rolls the code
**and** the migration image back together, safe only because migrations are additive: the schema
stays forward, and rolling the migration image back is a silent no-op rather than an error.

**The checkout also restores that release's own `redeploy.sh`** — the script that runs is the one
from the tag, not this one. Releases cut before the push was removed (anything up to and including
`1.0.0`) carry the build-and-push script, whose bare `k8s` target spends a full
`verify -Pit-tests` and then refuses at the registry probe without applying anything. Roll back to
one of those with `./redeploy.sh k8s --deploy-only` instead — that flag exists there and is what
the old script's rollback path used.

If *Docker Build and Publish* fails after the release exists, the tag and manifest are public but the
images are not. Re-drive it from the Actions tab with the version as input rather than deleting the
Release — deleting it destroys the jar asset that workflow builds from.

### Versions outside a release

`./release-version.sh` prints what a **local** build will identify itself as: an exact tag on a clean
tree prints that tag, anything else prints `<git-describe>-SNAPSHOT`. `redeploy.sh` stamps it into
the jar it builds for `docker compose`, which is what the sidebar footer and `/actuator/info` then
report. A plain `./mvnw verify -Pit-tests` never consults it — it resolves the pom's own
`0.0.0-SNAPSHOT` default — so no version flag is needed there either way.

That version reaches nothing outside your machine. `redeploy.sh` cannot publish an image, so the
guards it used to need are gone with the push: there is no `ALLOW_SNAPSHOT_PUSH`, no
`ALLOW_TAG_OVERWRITE`, no `TAG=`, and no way to talk a laptop into putting a dirty-tree build behind
a release tag. Releases are cut by the workflow, from a tagged commit, or not at all.

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
