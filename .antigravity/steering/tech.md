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

## Continuous Integration

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
