# Technology Stack

## Language & Runtime

- **Java 25**: Primary language.
- **Spring Boot 4.0.x**: Core framework. (Uses modern idioms like @ServiceConnection and @JdbcClient).
- **Maven**: Dependency management and build tool.

## Core Dependencies

### Spring & Spring AI
- **Spring Boot Starter Web**: Web server and REST endpoints.
- **Spring Boot Starter Thymeleaf**: HTML views.
- **Spring AI**: Used for embeddings (`voyage-4-large`) and the MCP server implementation.
- **Custom LLM Abstraction**: Custom abstraction layer (`de.palsoftware.yvoke.llm`) selected by `AI_PROVIDER` in `LlmConfig`, with clients for Gemini (`google-genai`, direct or via the Cloudflare AI Gateway), OpenRouter (`openai-java`) and Azure OpenAI (`com.azure:azure-ai-openai`). All are wrapped by the `@Primary` `AccountingLlmClient`. The Azure client uses the **async** SDK client over the JDK HTTP transport; that pairing is load-bearing for token-by-token streaming — see § 6 of `AGENTS.md` before changing it.
- **Spring Security (OIDC / OAuth2 client / resource server)**: Authentication against Microsoft Entra ID.
- **Spring Boot Actuator**: Health metrics and diagnostics.

### Database & Migrations
- **PostgreSQL 16**: Underlying database (exposed on port 5433 locally via docker-compose).
- **pgvector**: Vector similarity matching (`vector(1024)`).
- **ParadeDB pg_search**: BM25 keyword lane index (`paradedb.bm25` index).
- **Flyway**: DB schema migration and schema populating. In production, migrations are executed at deployment by a separate `db-migration` Docker service (defined in `docker-compose.yml` via `docker/db/Dockerfile`), while Maven runs Flyway under `<scope>test</scope>` for integration testing with Testcontainers.
- **HikariCP**: High-performance JDBC connection pooling.

### Caching
- **Spring Cache Abstraction**: Driven by `@EnableCaching` and `@Cacheable`.
- **Caffeine Cache**: The underlying cache provider (defined in `pom.xml` as `com.github.ben-manes.caffeine:caffeine`). Used for high-performance, in-memory caching of database objects and frequent queries.

### Testing & Assertions
- **JUnit 5**: The core testing framework (via `spring-boot-starter-test`).
- **Testcontainers**: Used strictly for spinning up transient PostgreSQL databases during integration testing (`-Pit-tests`).
- **Spring Test Annotations**: Use `@SpringBootTest`, `@MockBean` (or `@MockitoBean` in newer Spring Boot), and standard Mockito for service-level mocking.
- **JaCoCo (`${jacoco.version}`, 0.8.15)**: Aggregated code coverage across the unit (surefire) and integration (failsafe) tiers. `prepare-agent`/`prepare-agent-integration` write `target/jacoco-ut.exec` / `jacoco-it.exec`; at the `verify` phase these are merged (`jacoco-merged.exec`) and a single HTML/XML/CSV report is emitted to `target/site/jacoco-aggregate`. Run `./mvnw verify -Pit-tests` for the full unit+IT number; a plain `./mvnw verify` reports unit-only. Under `-Pit-tests` a JaCoCo `check` **coverage ratchet** fails the build if the merged aggregate drops below the floor (instruction/line ≥ 0.77, branch ≥ 0.55); a unit-only `verify` is not gated. See `docs/testing-plan.md`.
- **Playwright (`${playwright.version}`, 1.61.0)**: Browser end-to-end tests (real headless Chromium). Named `*E2EIT.java` and run **only** under the `-Pe2e-tests` profile (`./mvnw verify -Pe2e-tests`); the `it-tests` profile excludes them so the fast IT loop is unaffected. E2E tests extend `AbstractE2E` (`src/it/java/.../web/e2e`), which boots the app on a random port with `app.security.mock=true` + a `@MockitoBean(name = "llmProviderClient") LlmClient` (deterministic answers, no network; the primary `LlmClient` is the `AccountingLlmClient` decorator). First run downloads Chromium to `~/.cache/ms-playwright`. See `docs/testing-plan.md` Phase 1.
- **`node:test` (JS tier)**: Browser-side logic that is pure string→string is unit-tested in Node instead of in a browser. Sources live in `src/main/resources/static/js/chat/citation-render.js` (extracted from `thread.js`, which imports it as an ES module); tests in `src/test/js/*.test.js`. **Zero dependencies** — the root `package.json` only declares `type: module` plus an `npm test` script, so there is no `npm install` and no lockfile. Wired into the Maven `test` phase by **`exec-maven-plugin` 3.5.0** (execution id `js-tests`, runs `node --test`), so `./mvnw test` covers it; disable with `-DskipJsTests=true` (property `skipJsTests`, default `false`). Requires `node` on `PATH` — present on GitHub's `ubuntu-latest` runners, so CI needs no extra setup step. Prefer this tier over e2e for rendering logic: it is ~1000× faster, and e2e **cannot** cover mermaid/KaTeX at all (both are CDN-loaded in `templates/chat/layout.html` while `AbstractE2E` aborts non-localhost requests, so such assertions pass whether the source is intact or destroyed).

## Release Versioning

- **The git tag is the source of truth, and the version is the tag verbatim.** Tag `1.0.0` builds Maven version `1.0.0` and ships `edipal/yvoke-app:1.0.0`. There is no `v` prefix to add or strip anywhere — a transformation would be a second derivation, and the places that must agree on the string each derive it independently.
- **`<version>${revision}</version>`** (`pom.xml`) with a `<revision>0.0.0-SNAPSHOT</revision>` default in `<properties>`. A release build passes `-Drevision=<tag>`; nothing edits the pom per release. `revision` is one of exactly three property names Maven resolves inside `<version>` (with `sha1`/`changelist`) — any other name is accepted by the XML and then silently fails to resolve. **No `flatten-maven-plugin`**: it exists to publish a resolvable consumer POM, and this project declares no `<distributionManagement>` and never runs `install`/`deploy`. Adding either would make the unflattened pom a real defect.
- **`release-version.sh`** (repo root) answers one question — what version the current working tree IS: an exact tag on a clean tree prints it verbatim, anything else prints `<git-describe>-SNAPSHOT`. Tree cleanliness is `git status --porcelain`, which counts untracked files (`git describe --dirty` does not), so anything generated into the working tree must be git-ignored or every later build derives a SNAPSHOT. It does **not** choose a release version — `release.yml` does that by bumping the newest tag — and it has exactly two callers: `redeploy.sh` (so a local build carries a meaningful version and the push guard has something to test) and `release.yml`'s final gate, which requires the freshly tagged tree to derive exactly the tagged version. That gate is only meaningful because it runs the same script a local build runs; an inline `git describe` in the workflow would be asserting against its own reimplementation.
- **`<finalName>yvoke</finalName>`** pins the artifact filename so it does not move with the version, and `Dockerfile` copies `target/yvoke.jar` by name. `COPY target/*.jar` into a file destination is a hard Docker error once two jars match, which a version-derived filename makes ordinary.
- **`spring-boot-maven-plugin` `build-info` execution** writes `target/classes/META-INF/build-info.properties` (`build.version`, `build.time`, …) at `generate-resources`. That resource is load-bearing beyond the file itself: `BuildProperties` is `@ConditionalOnResource` on it, so unbinding the execution removes the bean **silently**. It also populates `/actuator/info`, which is anonymously permitted on management port 9090 — a deliberate disclosure of group/artifact/version/time, accepted because nothing publishes that port.
- Pinned by `ReleaseVersionTest` (unit tier), including two behavioural assertions that run the script against a throwaway tagged git repository.

### Image tags and deployment

- **No `:latest` anywhere.** Both release images (`yvoke-app`, `yvoke-db-migration`) are tagged with the derived version, and `imagePullPolicy` is `IfNotPresent` — an immutable tag identifies its content, so re-pulling buys nothing and would make every pod start depend on the registry. **The corollary is a rule: a published tag must never be overwritten**, or a node keeps serving cached bytes.
- **`k8s/app/kustomization.yaml` DECLARES the release the cluster runs**, with both `newTag` values pinned to the version and committed on the commit that gets tagged. Nothing is generated at deploy time: `kustomize build k8s/app | kubectl apply -f -` is a complete deploy from any machine, `git show 1.0.0:k8s/app/kustomization.yaml` answers "what does this release deploy?" with nothing to run, and a pull-based GitOps controller (ArgoCD/Flux) could read it directly. `deployment.yaml` keeps a `SET-BY-RELEASE-OVERLAY` sentinel so applying it *without* kustomize fails by name rather than deploying something plausible.
- **The manifest is a MIRROR of the git tag, never a second source of it.** The version is always derived from git (`release-version.sh`); reading it from the manifest instead would let a dirty working tree build a jar stamping itself as a release. `redeploy.sh` compares the two and refuses to push when they disagree — a manifest pinned to 1.0.0 on a commit tagged 1.0.1 would deploy a release that was never built, and nothing else in the system checks. **Bump both `newTag` values in the release commit, immediately before creating the tag.**
- **`redeploy.sh`** derives the version once and uses it for both the jar (`-Drevision`) and both image tags. The version is **not** environment-overridable: it was, and the override defeated every guard at once, because they all tested `TAG` while the jar was stamped from `VERSION` — so `TAG=<a released version>` on a dirty tree wrapped a SNAPSHOT jar in release-labelled images and pushed them over the published ones, reporting success throughout. It refuses to push a `SNAPSHOT` (`ALLOW_SNAPSHOT_PUSH=1`), refuses when the derived version disagrees with the manifest, refuses when `REGISTRY` is not what the manifest deploys from, and probes the registry to refuse overwriting an already-published tag (`ALLOW_TAG_OVERWRITE=1`) — that last one matters because the mirror guard otherwise makes overwriting the only thing a local push can do. All guards run before the Maven build, so an unreleasable tree fails in a second rather than after a full integration run. `--deploy-only` applies the manifest without rebuilding; the rollback path is `git checkout <version> && ./redeploy.sh k8s --deploy-only` — the CHECKOUT selects the version, since the tracked manifest is what the apply reads, and there is deliberately no `TAG=` to set. `kubectl rollout restart` is gone — it only existed to force a re-pull of a moving tag — replaced by `rollout status`.
- Consequence to accept: an untagged build can no longer be deployed to k8s without deliberately editing the manifest. That is intended for a production cluster; the escape hatch is an explicit, visible edit rather than an env var.
- **`PG_TAG` stays outside the scheme deliberately**: the custom Postgres image is a content version (pg16 + pg_search), pinned in CNPG's `spec.imageName` where the kustomize `images:` transformer cannot reach it.
- **Migrations and rollback.** The migration image is the sole schema authority (the app's Flyway dependencies are `test`-scoped) and is pinned to an exact `flyway/flyway:10.22.0`. That image ships **no** config file, so Flyway's compiled-in defaults apply — and `ignoreMigrationPatterns` defaults to `*:future` (verified in the shipped jar). **Rolling the migration image back is therefore a silent no-op, not an error**: nothing warns that the schema is still ahead of the code. Worse, the Deployment declares no `strategy:`, so on the RollingUpdate default the new pod's migration initContainer applies the new schema *while the previous release's container is still serving*. **Hence migrations must be additive-only on every release, forward deploys included**; a destructive change is a two-release operation (stop using it, then drop it), and rolling back past one needs a database restore, not a redeploy.
- Pinned by `ReleaseImageTagPolicyTest` (unit tier).

### Where the version is visible at runtime

- **MCP `serverInfo.version`** — `spring.ai.mcp.server.version` is `"@project.version@"`, substituted by the parent's resource filtering (`@` is the ONLY delimiter for `application*.yml`, so `${project.version}` would **not** be substituted and would reach Spring's runtime resolver instead; the quotes are mandatory because YAML treats a leading `@` as reserved). This is the only version an external MCP client is ever told, and it was previously a hardcoded literal that had already drifted. Pinned in the unit tier (`ApplicationYamlInvariantsTest`, which reads the *filtered* classpath copy) and on the wire in `McpServerEndpointsIT`.
- **`/actuator/info`** — populated automatically by the `build-info` contributor. `management.info.git.enabled` is explicitly `false`: it defaults to **enabled**, so a `git.properties` appearing on the classpath would silently add branch/commit to an anonymous endpoint, and `mode: full` would add the build host and committer email. What the build *is* may be disclosed; where it was built may not.
- **Sidebar footer of every signed-in layout** — `${@buildProperties.version}` in BOTH `templates/admin/layout.html` and `templates/chat/layout.html`, reusing the existing `${@bean.method()}` idiom so no new production Java is involved. The CSS class is `build-version`, never bare `version`, which already means the corpus tag (9.3.1 / 10.0) throughout this codebase. Deliberately **not** on `login.html` or any other pre-authentication template: that is `permitAll`, so a version there is disclosed to anyone who can reach the host — a materially different decision from showing it to a signed-in user, and the one placement rule that is a security judgement rather than a product one. Both the presence in the two layouts and the absence before sign-in are asserted (`ReleaseVersionTest`), the latter by walking every pre-auth template rather than naming one, since the risk arrives with a new page.
- Note the `BuildProperties` bean is `@ConditionalOnResource` on `META-INF/build-info.properties`: if that Maven execution were unbound the bean would vanish **silently**, `/actuator/info` would go empty and the admin footer would fail at *render* time. `McpServerEndpointsIT` injects the bean, so that context fails loudly instead.

## Continuous Integration

- **Release automation** — `.github/workflows/release.yml` (manual `workflow_dispatch`, patch/minor/major) resolves the next version from the newest release tag, pins it into `k8s/app/kustomization.yaml`, runs `verify -Pit-tests` at that version, then commits, tags, asserts the tagged tree derives that version, pushes atomically and creates the GitHub Release with the jar attached. `.github/workflows/docker-build-publish.yml` reacts to `release: published`, validates the tag shape and the manifest, **downloads** that verified jar (never rebuilds it — a rebuild would differ in `build.time`) and pushes both images. It is also `workflow_dispatch`-able with a version, so the publish half can be re-driven without deleting the Release, which would destroy the jar asset it depends on. Both need `GH_TOKEN` (a PAT — a Release raised with the default `GITHUB_TOKEN` does not trigger downstream workflows) and `DOCKERHUB_TOKEN`.
- **GitHub Actions** (`.github/workflows/ci.yml`): on every push to `main` / PR, the `test` job builds the custom Postgres image (`yvoke/pgvector-pg_search:pg16-0.24.0`) locally (it is not in a registry), then runs `./mvnw verify -Pit-tests` (unit + IT + JaCoCo aggregate, uploaded as an artifact). The hermetic test path requires **no secrets** (LLM mocked, mock auth under the `test` profile).
- A browser end-to-end (`e2e`) job runs `./mvnw verify -Pe2e-tests` against the Playwright harness (installs Chromium `--with-deps`). It is **non-blocking** (`continue-on-error: true`) until proven stable in CI; flip to blocking once green (`docs/testing-plan.md` Phase 1/2).
- The IT suite raises the Spring TestContext cache (`src/it/resources/spring.properties`, `spring.test.context.cache.maxSize=64`) so the growing set of distinct `@SpringBootTest` contexts doesn't thrash and poison the `RANDOM_PORT` MCP context. See `CLAUDE.md` Known Pitfalls.

### Utilities
- **Voyage Reranker API**: Voyage `rerank-2.5` accessed via `RestClient`.
- **Jackson / Gson**: JSON serialization.

## Configuration

- **YAML-based**: Config in `src/main/resources/application.yml`.

- **Environment variables**:
  - `AI_PROVIDER`: Selects the default LLM provider (`cloudflare-gemini`, `gemini`, `openrouter` or `azure-openai`).
  - `GEMINI_API_KEY`: API key for Gemini models.
  - `OPENROUTER_API_KEY`: API key for OpenRouter (DeepSeek) models.
  - `AZURE_OPENAI_ENDPOINT`: Azure OpenAI resource endpoint. Required when the provider is `azure-openai` — startup fails without it, because the SDK would otherwise target the public OpenAI service and forward the key there.
  - `AZURE_OPENAI_API_KEY`: API key for Azure OpenAI.
  - `AZURE_OPENAI_REASONING_MODELS`: Optional comma-separated deployment names that address a reasoning model, when the deployment is not named after the model it serves.
  - `VOYAGE_API_KEY`: API key for Voyage embeddings and reranker.
  - `APP_SECURITY_MOCK`: Set to `true` to bypass OAuth2 authentication and use a mock user context for local offline development. **Fails closed (SEC-09):** mock auth is only permitted when a development profile (`dev`/`local`/`test`) is active; with no such profile the context refuses to start. Local `docker-compose` sets `SPRING_PROFILES_ACTIVE=local`; the IT harness (`PostgresTestContainerInitializer`) activates `test`.
  - `APP_SECRET_KEY` / `APP_SECRET_SALT`: at-rest encryption key + per-deployment KDF salt for stored secrets (e.g. the Confluence token). **Fail closed (SEC-05/15):** outside a dev profile the app refuses to start without a key; a configured key requires its own salt (there is no shared default salt).
  - `APP_RATE_LIMIT_*` (`ENABLED`/`CAPACITY`/`REFILL_SECONDS`): per-principal token-bucket rate limit on the generation/ingest endpoints (SEC-03).
  - `APP_GENERATION_MAX_CONCURRENT`: app-wide cap on concurrently-running SSE generations; excess requests get 429 (PRF-15). 0 disables.
  - `APP_CHAT_COST_EXPLORER_MAX_ROWS`: hard row cap for the per-call/per-message cost-explorer views so a wide date range cannot load the whole `llm_call_logs` table into heap (PRF-01).
  - `SPRING_DATASOURCE_URL`: PostgreSQL connection URL.
  - `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD`: DB credentials.

## Background Job Worker

- Async ingestion jobs run on a Postgres-backed queue (`ingestion_jobs`) drained by a
  `@Scheduled` poll loop that claims work with `SELECT ... FOR UPDATE SKIP LOCKED`.
- Execution is bounded by a `ThreadPoolTaskExecutor` (`app.worker.concurrency`, default **4**).
- Restart safety: a startup recovery sweep re-queues orphaned `running` jobs from DB state.
- Config keys (`application.yml`, prefix `app.worker`):
  - `enabled` (default `true`) — disables polling/recovery without affecting enqueue or schema.
  - `concurrency` (default `4`) — max simultaneous jobs.
  - `poll-interval` (default `2s`) — delay between poll ticks.

## MCP Server

- **Framework**: Spring AI Starter MCP Server (WebMVC) using the SSE protocol.
- **Endpoints**:
  - SSE Connection: `GET /mcp`
  - Client Messages: `POST /mcp/message`
- **Security**: OAuth2 token authentication mapping against Microsoft Entra ID. Requires the configured audience `api://oim-kb` and scope `api://oim-kb/mcp.read`.
- **Testing**: Can be verified locally using an SSE-compatible client or MCP inspector tools pointing to the SSE endpoint.

## Manuals Ingestion & KG Extraction

- The `kind="manual"` pipeline (`ingest.core`) chunks consolidated Markdown with a **faithful Java port** of the reference `_md_tree.py` (`MarkdownTree`); do not introduce a different chunking algorithm. Parity is locked by a golden fixture generated from the Python reference.
- **Write-side repositories** own inserts: `ManualDocumentRepository` (documents/chunks) and the write methods on `KgRepository` (`upsertEntity`/`upsertRelationship`).
  All are idempotent by their natural key so re-ingest replaces rather than duplicates.
  - Naming: the ingest document repo is `ManualDocumentRepository` (not `DocumentRepository`) to avoid
    a Spring default-bean-name collision with `document.DocumentRepository`.
- KG extraction uses a **non-reasoning** chat model with a generous output budget. Config keys
  (`application.yml`, prefix `app.ai.kg`): `model`, `max-tokens` (default **4096**, must stay
  `>= 4096` to avoid mid-element truncation), `temperature` (default `0.0`), and `system-prompt`.
  The extractor overrides only the model per call on the shared `LlmClient`, reusing
  the existing provider wiring (`app.ai.rag.*`).

## Chat Configuration

- Config keys (`application.yml`, prefix `app.chat`):
  - `enabled` (default `true`) — enables or disables the web chat module.
  - `allowed-models` — list of models that standard users can select for conversation.
  - `playbook-validation-enabled` (default `true`) — configures whether the LLM preflight validation runs on the first message of a conversation.

## Web & UI Layer Guidelines

### Dynamic Updates with htmx
- Use **htmx** for dynamic, AJAX-driven updates instead of heavy client-side JavaScript frameworks.
- Controllers should return partial HTML pages (Thymeleaf fragments) when handling htmx requests (identified by headers like `HX-Request`).
- Example: return `"fragments/search-results :: results"` instead of a full page.
- Cleanly map user interactions to htmx triggers (`hx-get`, `hx-post`, `hx-target`, `hx-swap`).

### Server-Sent Events (SSE) Streaming
- For streaming responses (such as real-time LLM replies or background job progress), configure Server-Sent Events (SSE).
- Spring controllers should return `SseEmitter` or `Publisher<ServerSentEvent<T>>` objects.
- Ensure appropriate timeouts, thread pooling, and error handling for emitters.
- On the frontend, use the `sse` extension in htmx (`sse-connect`, `sse-swap`) or standard `EventSource` API.
