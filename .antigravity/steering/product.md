# Product Overview

> This file is the **short** product orientation: personas, domain glossary, and guardrails. For what the
> product does, the limits people hit and what it deliberately does not do, read the relevant chapter of
> [`spec/`](../../spec/README.md) at the repo root — do that before any substantial change, since intent and
> deliberate non-features are not visible in the code. The MUST/MUST NOT rules an agent can regress
> against are not written down in prose — they are enforced by the **test suite**; if no test fails when
> a rule is broken, that rule is not enforced.

Yvoke is an internal system that makes One Identity Manager (OIM) knowledge—both the product manuals and the live database schema/code—queryable by humans, AI agents, and external systems. It provides grounded, cited answers using hybrid retrieval over a single Postgres instance.

## Core Functionality

- **Core Retrieval Engine**: Vector search (pgvector with Voyage embeddings) and BM25 full-text search (ParadeDB pg_search) fused with Reciprocal Rank Fusion (RRF) and Voyage Rerank.
- **RAG & Chat UI**: Streaming assistant responses via Server-Sent Events (SSE) with inline citation verification, adjustable settings, and rating/feedback.
- **Admin UI**: Document inspection, database object summary cache, data validators, audit trail, and async upload pipeline.
- **External REST API**: Exposes core retrieval and Q&A capabilities to external applications, desktop apps, and automation scripts.
- **MCP Server**: Read-only Model Context Protocol (MCP) server exposing 14 tools (10 shared with the in-app assistant, 4 client-only) to allow Claude Code/Desktop/Cursor programmatic access to the corpus, Knowledge Graph, and structured JSON data. What each tool offers a user is described in [`spec/` ch. 7](../../spec/07_using_the_assistant_from_other_tools.md); the authoritative per-tool contract is the tool's own tests under `src/test/java/de/palsoftware/yvoke/mcp/` and `src/it/java/de/palsoftware/yvoke/mcp/`.
- **Ingestion Pipeline**: Multi-pipeline ingestion worker for manuals (Markdown), Confluence pages, database extracts (zipped directories), and structured data (JSON/JSONL).

## Domain Context

- **Corpora / Collections**: Collections are fully dynamic. Administrators can create and manage any number of custom collections representing different corpora, products, or versions as needed.
- **Knowledge Graph (KG)**: Combined conceptual graph (extracted from manuals/Confluence) and structural database graph (tables, FKs, procedures, scripts).
- **Core Models**:
  - `voyage-4-large` (1024-d) via Voyage AI for embeddings.
  - `gemini-3.8-flash` (element 0 of `allowed-models`, so it is stamped on every new conversation) and `gemini-3.5-flash-lite` on Gemini, plus the Azure OpenAI deployments `gpt-5.4-mini`, `DeepSeek-V4-Flash` and `gpt-5.6-luna`, for Q&A and RAG; `gemini-3.5-flash-lite` for KG extraction and summarization in the deployment (the shipped `application.yml` default is still `gemini-3.1-flash-lite`, so a local stack runs on that unless `KG_MODEL`/`SUMMARIZE_MODEL` are set). `gemini-3.7-flash` and `gemini-3.6-flash` were retired from the picker; they still exist upstream, so conversations pinned to one keep working — see `ChatConversationService.effectiveModel`. Which provider client answers which model is deployment configuration — see [`tech.md`](tech.md); Gemini is the default route and OpenRouter is retired.
  - Voyage `rerank-2.5` for cross-encoder reranking.

## User Personas & Permissions

Permissions are managed via Microsoft Entra ID app role assignments, parsed into four logical role levels with multi-role union semantics:

- **Superadmin** (`Admin`): Unrestricted access to the entire Admin Console (including cost monitoring, model pricing, audit trail, area creation/deletion, and global prompt activation) and unrestricted user access across all knowledge areas in Web Chat, Desktop API, and MCP tools.
- **Area Admin** (`<Area>.Admin`, e.g. `OIM.Admin`): Access to the Admin Console scoped strictly to their assigned area(s) (managing collections, documents, chunks, KG, JSON records, playbooks, prompts, profiles, and Confluence connectors within those areas) plus user access to those areas. Excluded from Superadmin-only sections (`/admin/costs`, `/admin/pricing`, `/admin/audit`, `/admin/areas/delete`, `/admin/prompts/active`).
- **Global User** (`User`): User access to all knowledge areas across Web Chat, Desktop Sync API, and MCP Server. No access to the Admin Console.
- **Area User** (`<Area>.User`, e.g. `OIM.User`): User access strictly scoped to their assigned knowledge area(s) across Web Chat, Desktop Sync API, and MCP Server. No access to the Admin Console.
- **Multi-Role Union**: A user may hold any combination of roles (e.g. `OIM.Admin` + `PingID.User`); effective user and admin areas are the union of all corresponding role grants. Users with no valid role claims are denied by default.
- **Machine Clients**: Automated external scripts authenticate via `X-API-Key` using either the global machine key (`APP_SECURITY_API_KEY`) for unrestricted ingestion or per-area machine keys (`APP_SECURITY_AREA_API_KEYS`) scoped to designated areas on `/api/ingest/v1/**`, `/api/document/v1/**`, and `/api/jobs/v1/**`.

## Core Domain Entities

- **Area**: The top-level administrative and security boundary (`areas` table). Groups collections, playbooks, orchestrator profiles, and system prompts. Every resource belongs to an area. Conversations lock onto an area on the first turn (`conversations.area`).
- **Corpora / Collections**: Collections belong to an Area (`collections.area`). Administrators can create and manage collections representing different corpora, products, or versions within their authorized areas. Moving a collection between areas requires admin access to both source and target areas.
- **Document**: The top-level knowledge container (e.g., a Confluence page, a Markdown file, or a database SQL script) belonging to a collection.
- **Chunk**: A smaller, semantically partitioned segment of a Document. Vector embeddings and BM25 indexing are applied at this level for granular retrieval.
- **Entity & Relationship (KG)**: Nodes and edges extracted from text or database structures. They form the Knowledge Graph, scoped by collection and canonical tags, providing structured conceptual context alongside unstructured text.
- **Conversation & Message**: A **conversation** is one chat thread — owner, title, settings, source (`web` or `desktop`), tags, and `area` (`conversations.area` column, V16 migration). A conversation is locked to an area upon the first turn, and shared (`public`) conversations are visible only to users authorized for that conversation's area. Its **messages** are the individual questions and answers, each carrying its role, status, model, token counts, citations and retrieved chunk ids. There is no `sessions` table and no session concept in the domain: the vocabulary is `conversations` + `messages` throughout the schema, the repositories and the UI.
- **Feedback**: A user-submitted rating (`1` or `-1`) with an optional comment, at most one per `Message` (`message_feedback` is unique on `message_id`; a new thumb replaces the previous one). It exists for **human** review only — the feedback dashboard (scoped by conversation area in the Admin Console), the reviewed flag and reviewer notes. Nothing is trained on it: a rating changes neither retrieval ranking nor future answers, which [`spec/` ch. 2](../../spec/02_how_answers_are_produced.md) records under *Not supported*.
- **JSON Object & Schema**: Structured JSON data ingested into collections, stored in a GIN-indexed JSONB column. Schemas are inferred automatically or managed manually.

## Product Tenets & Guardrails

- **Verifiable Grounding**: The LLM must cite its sources from the chunks/graph. Hallucinated answers without corpus grounding are treated as failures.
- **High-Performance Q&A**: User-facing queries must feel responsive. Chat responses are either streamed (SSE) or managed via non-blocking async submission and status polling to avoid proxy timeouts (e.g. Cloudflare 524).
- **Data Centralization**: All unstructured text, vectors, BM25 indices, and graph relations live in a single PostgreSQL instance. No external vector databases are permitted.
- **Per-Area Boundary Isolation**: Multi-tenant authorization is enforced at the Area boundary across Web Chat, Desktop Sync API, MCP Server, In-App RAG Tools, Machine APIs, and the Admin Console. Opaque refusal (`404 Not Found` / filtered lists) ensures no unauthorized resource existence is disclosed.
