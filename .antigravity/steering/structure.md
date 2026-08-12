# Project Structure

## Directory Layout

```
yvoke/
├── .antigravity/           # Steering files, subagents, and guidelines
│   ├── scripts/            # Steering verification and maintenance scripts
│   │   └── check_steering.py
│   ├── steering/           # Product, structure, and tech guidelines
│   │   ├── product.md
│   │   ├── structure.md
│   │   └── tech.md
│   └── agents/             # Subagent roles (spring_implementer, spring_reviewer)
│       ├── spring_implementer.md
│       └── spring_reviewer.md
├── docker/                 # Docker config files
│   ├── db/                 # Database migrations and Flyway Dockerfile
│   │   └── migration/      # Flyway SQL scripts — consolidated V1__init_schema.sql (schema + indexes + partition triggers + tag-scoped graph identity + confluence_instances + job/document uniqueness, no seed) PLUS incremental V2..V6; add the next as V7, never edit an existing one
│   └── postgres/           # Custom Postgres Dockerfile (pgvector + pg_search)
├── src/                    # Source files
│   ├── main/
│   │   ├── java/de/palsoftware/yvoke/
│   │   │   ├── chat/       # Chat sessions, messages, feedback, RAG orchestration, desktop sync
│   │   │   │   ├── api/    # Desktop-sync REST API (/api/chat/v1) + DTOs
│   │   │   │   │   └── model/ # Desktop sync request/response DTOs
│   │   │   │   ├── core/   # Conversations/messages/feedback repositories & services; admin queries. ChatConfig/ChatProperties sit here directly, not in a config/ subpackage
│   │   │   │   │   ├── model/ # Chat model records (Conversation, Message, settings)
│   │   │   │   │   ├── repository/ # Chat database repositories and mappers
│   │   │   │   │   ├── service/ # Chat business logic and conversation services
│   │   │   │   │   └── tool/ # LLM tool callbacks (e.g., ask-clarifying-question)
│   │   │   │   ├── orchestration/ # Multi-agent orchestration, agent runs/steps persistence
│   │   │   │   └── web/    # Chat + SSE MVC controllers
│   │   │   │       └── admin/ # Chat-domain admin UI (feedback, conversations)
│   │   │   ├── collection/ # Document collection management
│   │   │   │   ├── core/   # Collection entity, repository, service
│   │   │   │   │   ├── model/ # Collection model record
│   │   │   │   │   ├── repository/ # Collection database repository
│   │   │   │   │   └── service/ # Collection business logic (service-layer transaction boundary)
│   │   │   │   └── web/    # Collection admin UI layer
│   │   │   │       └── admin/ # Collections + tags admin controller
│   │   │   ├── document/   # Document metadata, sections, hierarchy, table-of-contents
│   │   │   │   ├── api/    # Document REST API (/api/document/v1) + DTO
│   │   │   │   ├── core/   # Document/chunk repositories, mappers, section/TOC services, read-models
│   │   │   │   │   ├── model/ # Document core models and records
│   │   │   │   │   ├── repository/ # Document/chunk repositories and row mappers
│   │   │   │   │   └── service/ # Section, TOC, and search/surfacing services
│   │   │   │   └── web/    # Citation fragment controller
│   │   │   │       └── admin/ # Documents/chunks admin UI
│   │   │   ├── ingest/     # Parsing, chunking, summarizing, ingestion pipelines (incl. Confluence)
│   │   │   │   ├── api/    # Upload + custom-zip + process-kg REST endpoints (/api/ingest/v1)
│   │   │   │   ├── core/   # Ingest services, enqueue validation, IngestJobKind vocabulary
│   │   │   │   │   ├── model/ # Parsed-markdown tree/sections + IngestJobKind enum
│   │   │   │   │   ├── confluence/ # Confluence connector and parser services
│   │   │   │   │   └── service/ # Core ingestion services
│   │   │   │   ├── web/    # Ingest admin UI layer
│   │   │   │   │   └── admin/ # Ingest upload page + Confluence connector controller
│   │   │   │   └── worker/ # Background task handlers for ingestion jobs
│   │   │   ├── jsonobject/ # JSON object storage, schemas, query
│   │   │   │   ├── core/   # JsonObject, JsonSchema, repositories, extraction service
│   │   │   │   │   ├── model/ # JSON object and schema model records
│   │   │   │   │   ├── repository/ # JSON object and schema database repositories
│   │   │   │   │   └── service/ # JSON object import/query + tag-aware removal, schema extraction
│   │   │   │   └── web/    # JSON Objects admin UI layer
│   │   │   │       └── admin/ # JSON objects list, detail, and schema edit controllers
│   │   │   ├── kg/         # Knowledge graph extraction, repositories, models, and queries
│   │   │   │   ├── core/   # KG repository, extractor, consolidator, entities/relationships/models
│   │   │   │   │   ├── model/ # Knowledge graph entity, relationship, and graph model records
│   │   │   │   │   ├── repository/ # KG write/graph-read repositories and row mappers
│   │   │   │   │   └── service/ # KG generation services
│   │   │   │   └── web/    # KG admin UI layer
│   │   │   │       └── admin/ # KG overview/view/clear/consolidate controller
│   │   │   ├── lifecycle/  # Cross-domain content-deletion coordinator (document+KG+collection cascades)
│   │   │   │   ├── core/   # Cross-domain deletion orchestration
│   │   │   │   │   └── service/ # LifecycleService (transactional cascade + tag-aware tag removal + audit)
│   │   │   │   └── web/    # Lifecycle admin UI layer
│   │   │   │       └── admin/ # Deletion + collection tag-removal controllers
│   │   │   ├── llm/        # Abstraction layer for LLM providers (Gemini, OpenRouter)
│   │   │   │   └── core/   # LLM clients, config, retry, request/response models
│   │   │   │       ├── context/ # Call context holder for propagating ThreadLocal tracking context
│   │   │   │       ├── event/ # LLM call logging events (LlmCallLoggedEvent)
│   │   │   │       ├── model/ # LLM request/response/log domain models
│   │   │   │       ├── repository/ # LLM call log database repository (LlmCallLogRepository)
│   │   │   │       └── service/ # LLM generation services
│   │   │   ├── mcp/        # Model Context Protocol server tools and prompts
│   │   │   │   ├── prompts/ # MCP prompt services
│   │   │   │   └── tools/  # Graph neighbors, entity/corpus search, citation verification tools
│   │   │   ├── rag/        # RAG pipeline, retrieval, and prompt generation
│   │   │   │   ├── core/   # RAG service, context, request, and result models
│   │   │   │   │   ├── model/ # RAG context/request/result records
│   │   │   │   │   └── service/ # RagService + streaming tool-call accumulator, citation verifier
│   │   │   │   ├── prompt/ # Prompt management, system prompt enums, and services
│   │   │   │   ├── retrieval/ # Vector and keyword retrieval services, telemetry, and configs
│   │   │   │   └── web/    # RAG admin UI layer
│   │   │   │       └── admin/ # Search/logs/playbooks/prompts admin controller
│   │   │   ├── shared/     # Cross-cutting INFRASTRUCTURE ONLY (no domain logic; enforced by ArchitectureTest)
│   │   │   │   ├── api/    # Global API exception handler
│   │   │   │   ├── audit/  # Audit log repository
│   │   │   │   ├── config/ # App config, cache, JDBC mappers, RestClient beans
│   │   │   │   ├── db/     # Shared DB helpers (pgvector formatting, collection-id resolution)
│   │   │   │   ├── jobengine/ # Postgres-backed background task queue + EnqueueValidator SPI
│   │   │   │   │   ├── api/ # Job status REST API (/api/jobs/v1)
│   │   │   │   │   ├── model/ # Job engine records (IngestionJob, EnqueueRequest, steps)
│   │   │   │   │   ├── repository/ # Job queue repository (claim/enqueue/progress SQL)
│   │   │   │   │   └── service/ # JobService (claim transaction boundary) + JobWorker poll loop
│   │   │   │   ├── security/ # Auth (OIDC, API key), login, protected-resource metadata
│   │   │   │   ├── user/   # User entity, repository, service
│   │   │   │   │   ├── model/ # User record (id, entraOid, email, displayName, lastSeenAt)
│   │   │   │   │   ├── repository/ # User upsert/lookup; the identity columns are COALESCEd because the bearer path may supply neither
│   │   │   │   │   └── service/ # UserService — syncUser (OIDC login) + getCurrentUser (per bearer request)
│   │   │   │   └── web/    # Generic MVC config + interceptors
│   │   │   │       └── admin/ # Admin landing + job/audit infra pages
│   │   │   └── tag/        # Tagging support for collections and documents
│   │   │       └── core/   # Tag business logic
│   │   │           ├── repository/ # Tag database repository
│   │   │           └── service/ # TagService — transactional entry point for tag mutations (ARC-08)
│   │   └── resources/      # Application properties and Thymeleaf templates
│   │       ├── templates/  # Thymeleaf views (.html)
│   │       ├── static/     # Static UI assets (CSS, images)
│   │       ├── application.yml # Core configuration file
│   │       └── agentic-system-prompt.md # Grounding playbooks and prompt for RAG
│   ├── test/               # JUnit 5 unit tests
│   └── it/                 # Spring Integration tests (annotated with @SpringBootTest, running in Testcontainers)
├── pom.xml                 # Maven project configuration
├── docker-compose.yml      # Service orchestration (postgres + db-migration + app; no reverse proxy)
├── spec.md                 # FUNCTIONAL SPECIFICATION — what the product does, its limits, and what it deliberately does not do
└── README.md               # Quickstart and run instructions
```

## Architecture Patterns

### Layering & Separation

1. **Domain-Driven Design (Vertical Slices)**: The application is modularized by domain (`chat`, `document`, `ingest`, `kg`, `rag`). Each domain can contain its own `api`, `core`, `web`, or `worker` packages, isolating business logic and dependencies.
2. **Repositories & Data Access**: Implemented within each domain using Spring's `JdbcClient` for raw SQL flexibility (pg_search, RRF, recursive CTEs). Read repositories live alongside write-side ingest repositories.
3. **Service Layer**: Handles orchestration of business logic and transaction boundaries within each domain.
4. **Web & UI Layer**: Uses **Thymeleaf** server-side rendering combined with **htmx** and **Server-Sent Events (SSE)** for streaming responses.
5. **LLM Abstraction Layer**: Centralized under `llm/`, isolating provider specifics (Gemini, OpenRouter) from the core RAG and KG logic.
6. **MCP Layer**: Exposes core capabilities as Model Context Protocol (MCP) tools using Spring AI's native MCP support.
7. **Worker Layer (Job Engine)**: Background jobs (like ingestion and KG extraction) are executed using a Postgres-backed task queue (`FOR UPDATE SKIP LOCKED`) located in `shared/jobengine/`.

### Domain Boundaries & Flow

8. **Domain Dependency Flow (Enforced by ArchUnit)**: Two invariants are enforced by `ArchitectureTest` (`src/test/java/de/palsoftware/yvoke/ArchitectureTest.java`) and fail the build on regression:
   - `shared` must not depend on **any** domain package — it holds cross-cutting infrastructure only.
   - A domain's `core` must not depend on any `api`/`web` (presentation) package — presentation depends on core, never the reverse.
   - All domain packages must be free of cycles.
   Cross-domain orchestration that legitimately spans domains lives in its own domain: `lifecycle.core` coordinates cascading deletes across `document`, `kg`, and `collection` (a domain orchestrator may depend on domains; `shared` may not). This clean dependency layout and the cycle-free rules are fully enforced by `ArchitectureTest`.
9. **DTO vs. Entity Boundaries (Strict Mapping)**: The Service layer must map all database Entities/Records to Data Transfer Objects (DTOs) before returning them to the Web/UI layer. Raw database entities must not leak into Thymeleaf templates.
10. **Frontend Asset Organization (Domain-Grouped)**: Thymeleaf templates and fragments must be grouped by their respective domain (e.g., `src/main/resources/templates/chat/fragments/`).
11. **Configuration Boundaries**: Domain-specific Spring `@Configuration` classes live inside the domain they configure. Genuinely app-wide infrastructure beans (e.g. `ObjectMapper`, `RestClient.Builder`) live in `shared/config`, and shared low-level helpers (e.g. pgvector formatting) in `shared/db`.

## Code Organization Conventions

- Package root: `de.palsoftware.yvoke`

- **Sub-packages per Domain**: Within each domain package, subdivide by technical role only when necessary (e.g., `core`, `web`, `worker`).
- **Cross-cutting Shared Concerns**: Only truly generic and cross-domain utility code (e.g., auditing, core security configuration, or the shared job engine) belongs in the `shared/` package.
- **Enforced via check_steering.py**: Any new package introduced *must* be documented in `structure.md` and approved; otherwise, `check_steering.py` will fail the verification step.
- **Transient** design plans and checklists are managed natively using Antigravity's Planning Mode and artifacts, keeping the workspace clean of per-task planning files. The durable **functional specification** `spec.md` at the repo root is the deliberate exception: it documents what the product does, the limits people hit and what it deliberately does not do, is checked in, read before any substantial change, and updated in the same change as any behaviour change a user would notice. Exact internal behaviour is specified by the **test suite**, not in prose. `spec.md` is the *what*; this file is the *where*, `tech.md` the *with what*, and `CLAUDE.md` / `.agents/AGENTS.md` the *how*.
- Keep database *schema* migrations managed by Flyway. Migration scripts are **immutable once applied**: never edit an existing `V<N>__*.sql` — add a new one. (A `protect-migrations` Claude Code hook exists, but its wiring lives in the git-ignored `.claude/settings.local.json`, so it is a machine-local convenience for one toolchain — **not** an enforcement mechanism. Nothing stops a fresh clone, a CI run or an Antigravity session from editing V1 in place, which breaks Flyway's checksum on every environment that already applied it.) The base schema lives in the consolidated `V1__init_schema.sql` (tables + triggers + indexes, **no seed section**) — it already carries what earlier standalone migrations added: `entities.kind` NOT NULL (a kind-less row would split one logical entity into a kinded and a kind-NULL row that consolidation cannot merge, so ingest skips relationship endpoints it cannot resolve to a declared entity instead of materializing placeholders), the `documents(title) gin_trgm` index behind the fuzzy `list_documents` title filter, and `ingestion_jobs.skipped_entity_count`/`skipped_edge_count` so graph output a job could NOT persist is part of its terminal result instead of a WARN-only log line. It also carries what were briefly separate scripts, consolidated back in on 2026-07-27 while the schema is still unreleased: graph identity is **tag-scoped** via `UNIQUE (collection_id, coalesce(kind,''), lower(name), kg_canonical_tags(tags))`, so one collection can hold two kit versions separated only by tag without the second version's ingest resolving onto the first version's rows (see CLAUDE.md § 6); and `create_chunks_partition()`/`drop_chunks_partition()` schema-qualify `public.chunks` and pin `SET search_path = public, pg_temp`, so the partition triggers work for any caller — notably a data-only restore, where pg_dump's `set_config('search_path', '', false)` preamble otherwise makes the `collections` load fail with `no schema has been selected to create in`. On 2026-08-02 the same move folded in the two remaining scripts — `confluence_instances` and the job/document uniqueness indexes (both detailed below) — so the *base schema* is a single file. **That consolidation was a one-off and is not the house style — do not repeat it.** `V2`–`V6` are ordinary incremental scripts that are never folded back, so the next migration you add is `V7`. Consolidating an applied migration is an unreleased-schema-only move: any database that already applied the older split scripts must be rebuilt, not migrated, since Flyway sees a checksum mismatch on V1. Verified for that fold by applying the merged V1 to an empty database and diffing `pg_dump --schema-only` against the live V1+V2+V3 database: identical but for pg_dump's random `\restrict` nonce. DB *content* is managed via the export/import toolkit in the separate `yvoke-icc/yvoke-exports` repo (a **sibling** of `yvoke-icc/yvoke-web`, not a subdirectory of it), **not** seeded in migrations. It covers exactly six tables — `collections`, `json_schemas`, `playbooks`, `system_prompts`, `orchestrator_profiles`, `chat_model_pricing` — so everything else in the database is either schema (Flyway) or runtime data with no export path: notably `users`, `conversations`/`messages`, `documents`/`chunks`, the graph tables, `json_objects`, `llm_call_logs`, `ingestion_jobs`, `audit_log` and `confluence_instances` (whose credentials come back only through a restore, see `yvoke-icc/yvoke-backups/scripts`).
- **`V6__drop_tags_registry.sql`** removes the `tags` table. The tag vocabulary is **derived**, not registered: `CollectionRepository.findAllTagNames()` reads `SELECT DISTINCT unnest(tags) FROM collections` (0.05 ms over 31 rows) for corpus/version tags, and `ChatConversationService.distinctTags` derives chat folder names from the conversations the sidebar already loaded. The registry had exactly one writer — `TagRepository.getOrCreateTag`, reached only from `addTagTo{Collection,Document,Conversation}` — so it learned a tag only when one arrived through an admin form or the ingest enqueue, and never from the writers that set the `TEXT[]` column directly: the corpus import (`yvoke-exports/lib/objects.py` writes `tags = EXCLUDED.tags`) and every ingest service. It held 2 names while `10.0`/`9.3.1` were on 27 collections and 22k documents, so the admin tag dropdowns could not offer them at all. Nothing depended on the table (no FK either way, `tags.id` never read), so the drop loses nothing recoverable. **Rule going forward: a tag list is read from the array that carries it** — `Collection::tags` is the declaration `CollectionTagEnqueueValidator` enforces, and corpus tags and chat folder names are separate namespaces that must not share one source.
- **`confluence_instances`** (folded into V1 from a since-removed script; unrelated to today's `V2__agent_step_failure_status.sql`) makes the Confluence connector multi-instance instead of a single set of `confluence.*` rows in `app_config`; nothing writes or reads those keys any more. Two rules the table encodes on purpose: `target_collection` is the collection **name**, not an FK to `collections(id)`, because everything else in this pipeline resolves collections by name and an `ON DELETE CASCADE` would let deleting a collection silently destroy the connector's credentials and filters; and `target_tag` carries `CHECK (target_tag IS NULL OR target_tag <> '')` because `''` becomes `List.of("")` downstream and hard-fails enqueue *and* defeats the ingest version-skip (which tests `:tag IS NULL`, and `''` is neither NULL nor a member of the array — so every sync would re-embed the whole corpus). `slug` is `CHECK (slug ~ '^[a-z0-9][a-z0-9-]*$')` because it is embedded in the job kind `confluence-page-import:<slug>`, which `JobService` parses with `kind().split(":")[0]`. The table deliberately carries **no composite uniqueness** over `(domain, space, root_page_id, target_collection)`: two instances over the same page tree distinguished only by `include_labels` (one label subset per language or per product version) feeding one collection is a supported setup, and widening the key with labels/tag until it admits that guards nothing. What the former V2 also carried and V1 does **not** is the backfill of the old singleton `app_config` connector into an instance named `default` — V1 seeds no `confluence.*` rows, so on a fresh database it inserted nothing, and existing installations get that row back through a data-only restore rather than through a migration.
- **`ux_ingestion_jobs_active_work` / `ux_documents_collection_kind_source_file_tags`** (folded into V1 from a since-removed script; unrelated to today's `V3__gateway_cache_accounting.sql`) are the two uniqueness rules the writers had only ever *assumed*, and they ship alongside the `ON CONFLICT` handling that makes them non-fatal. **Job admission control**: UNIQUE on `(kind, source_ref, collection_id, tags) WHERE status IN ('queued','running')` — all four columns, because a `confluence-page-import`'s `source_ref` (`confluence/<space>/<pageId>`) carries neither collection nor tag, so a `(kind, source_ref)` key would make two connector instances importing one space into different collections block each other, and `kg-extract` exists precisely to extract one document into a different target. Partial on the active statuses so history never blocks new work. `JobRepository.enqueue` arbitrates on it with `ON CONFLICT … WHERE status IN ('queued','running') DO NOTHING RETURNING id` — **a partial index is only inferred as arbiter when the statement repeats its predicate** — and, when nothing is returned, re-SELECTs and returns the existing active job as `EnqueueResult(jobId, created=false)`. Callers surface that themselves: the crawl counts a skip and keeps crawling (it enqueues inside the batch consumer, so throwing would abandon the page tree), `JobApiController` answers **409** with the existing id, the admin sync/process-kg/upload pages flash "already queued or running" and redirect to that job. **Document identity**: UNIQUE on `(collection_id, kind, (metadata->>'source_file'), tags)`; NULL `source_file` rows do not conflict with each other (deliberately not `NULLS NOT DISTINCT`, which would collapse every such row in a collection onto one). `DocumentRepository`'s upsert adds `ON CONFLICT … DO NOTHING` plus a re-SELECT so the loser of the SELECT-then-INSERT race adopts the winner's row instead of throwing; its tag comparison stays set-based (`@>`/`<@`) while the index compares arrays, which is the safe direction — the lookup is the weaker predicate, so it can never miss a row the index would reject. What the former V3 also carried and V1 does **not** is the `LOCK TABLE … IN SHARE MODE` / duplicate pre-clean / `RAISE EXCEPTION` pre-flight scaffolding: all of it existed to let `CREATE UNIQUE INDEX` succeed against rows an older jar had already duplicated during a rolling deploy, and a fresh database has none. **Consequence to respect**: these indexes now exist *before* any data is loaded, so a data-only restore carrying duplicates fails at `COPY` time rather than at migration time — the better end to fail at, since the dump is still intact, but a pre-uniqueness dump may need cleaning before it will load.
- Avoid ORMs in the core retrieval/search paths to maintain absolute control over the custom vector and BM25 queries.
