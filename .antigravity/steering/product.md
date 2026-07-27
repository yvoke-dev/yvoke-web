# Product Overview

Yvoke is an internal system that makes One Identity Manager (OIM) knowledge—both the product manuals and the live database schema/code—queryable by humans, AI agents, and external systems. It provides grounded, cited answers using hybrid retrieval over a single Postgres instance.

## Core Functionality

- **Core Retrieval Engine**: Vector search (pgvector with Voyage embeddings) and BM25 full-text search (ParadeDB pg_search) fused with Reciprocal Rank Fusion (RRF) and Voyage Rerank.
- **RAG & Chat UI**: Streaming assistant responses via Server-Sent Events (SSE) with inline citation verification, adjustable settings, and rating/feedback.
- **Admin UI**: Document inspection, database object summary cache, data validators, audit trail, and async upload pipeline.
- **External REST API**: Exposes core retrieval and Q&A capabilities to external applications, desktop apps, and automation scripts.
- **MCP Server**: Read-only Model Context Protocol (MCP) server exposing 13+ tools to allow Claude Code/Desktop/Cursor programmatic access to the corpus, Knowledge Graph, and structured JSON data.
- **Ingestion Pipeline**: Multi-pipeline ingestion worker for manuals (Markdown), Confluence pages, database extracts (zipped directories), and structured data (JSON/JSONL).

## Domain Context

- **Corpora / Collections**: Collections are fully dynamic. Administrators can create and manage any number of custom collections representing different corpora, products, or versions as needed.
- **Knowledge Graph (KG)**: Combined conceptual graph (extracted from manuals/Confluence) and structural database graph (tables, FKs, procedures, scripts).
- **Core Models**:
  - `voyage-4-large` (1024-d) via Voyage AI for embeddings.
  - `gemini-3.1-flash-lite` (via Google Vertex/AI Studio) or OpenRouter models (e.g., DeepSeek) for Q&A, RAG, and summarization.
  - Voyage `rerank-2.5` for cross-encoder reranking.

## User Personas & Permissions

- **Administrators**: Have full access to the Admin UI to trigger ingestion jobs, manage dynamic collections/corpora, view audit logs, and inspect raw database summaries.
- **Standard Users**: Interact primarily via the Chat UI or external API clients. They can run searches, provide feedback, and utilize the RAG engine without altering the underlying data.
- **Machine Clients**: External scripts, desktop apps (via REST API), and agentic IDEs (via MCP Server) acting programmatically.

## Core Domain Entities

- **Document**: The top-level knowledge container (e.g., a Confluence page, a Markdown file, or a database SQL script).
- **Chunk**: A smaller, semantically partitioned segment of a Document. Vector embeddings and BM25 indexing are applied at this level for granular retrieval.
- **Entity & Relationship (KG)**: Nodes and edges extracted from text or database structures. They form the Knowledge Graph, providing structured conceptual context alongside unstructured text.
- **Session & Message**: Represents the user's conversational state and chat history.
- **Feedback**: A user-submitted rating (e.g., 1 or -1) attached to a specific `Message` to train or audit system accuracy.
- **JSON Object & Schema**: Structured JSON data ingested into collections, stored in a GIN-indexed JSONB column. Schemas are inferred automatically or managed manually.

## Product Tenets & Guardrails

- **Verifiable Grounding**: The LLM must cite its sources from the chunks/graph. Hallucinated answers without corpus grounding are treated as failures.
- **High-Performance Q&A**: User-facing queries must feel responsive. Chat responses are either streamed (SSE) or managed via non-blocking async submission and status polling to avoid proxy timeouts (e.g. Cloudflare 524).
- **Data Centralization**: All unstructured text, vectors, BM25 indices, and graph relations live in a single PostgreSQL instance. No external vector databases are permitted.
