-- V1: Complete initial schema (consolidated single migration).
--
-- Consolidates what were previously five scripts: the base schema, the tag-scoped graph identity,
-- the search_path-pinned partition triggers, the multi-instance Confluence connector table, and
-- the job/document uniqueness indexes. Kept as one script because the schema has never been
-- released — a fresh database builds it in one step and there is no deployed history to preserve.
-- Any environment that already applied an earlier V1 (or the separate V2/V3) must be REBUILT
-- rather than migrated: Flyway reports a checksum mismatch on V1 otherwise.
--
-- Two things the superseded scripts carried are deliberately absent here, because they only ever
-- had work to do on an already-populated database:
--   * V2's backfill of the singleton `confluence.*` app_config rows into one 'default' instance —
--     V1 seeds no such rows, so on a fresh database it inserts nothing. Nothing writes those keys
--     any more; existing data comes back through a data-only restore, not through a backfill.
--   * V3's LOCK TABLE / duplicate pre-clean / RAISE-EXCEPTION pre-flight blocks, which existed to
--     let CREATE UNIQUE INDEX succeed against rows an older jar had already duplicated. A fresh
--     database has none, and creating the indexes as part of the initial schema means the
--     duplicates can never arise in the first place.
-- Consequence worth knowing: those indexes now exist BEFORE any data is loaded, so restoring a
-- data-only dump that contains duplicates fails at COPY time instead of at migration time. That is
-- the better end to fail at — the dump is still intact and can be repaired — but it does mean a
-- pre-V3 dump may need cleaning before it will load.

-- Enable Postgres Extensions
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS pg_search;
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

-- ============================================================================
-- Tables
-- ============================================================================

-- 1. collections table
CREATE TABLE collections (
    id UUID PRIMARY KEY,
    name TEXT UNIQUE NOT NULL,
    description TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    tags TEXT[] DEFAULT '{}'::TEXT[] NOT NULL
);

-- 2. documents table
CREATE TABLE documents (
    id UUID PRIMARY KEY,
    collection_id UUID NOT NULL CONSTRAINT fk_documents_collections REFERENCES collections(id) ON DELETE CASCADE,
    kind TEXT NOT NULL,
    title TEXT NOT NULL,
    metadata JSONB,
    ingestion_status TEXT NOT NULL DEFAULT 'pending',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    tags TEXT[] DEFAULT '{}'::TEXT[] NOT NULL
);

-- 3. chunks table (LIST partitioned by collection_id)
CREATE TABLE chunks (
    id UUID NOT NULL,
    document_id UUID NOT NULL CONSTRAINT fk_chunks_documents REFERENCES documents(id) ON DELETE CASCADE,
    text TEXT NOT NULL,
    embedding vector(1024),
    heading_path TEXT[],
    heading TEXT,
    depth INT,
    sort_order INT,
    collection_id UUID NOT NULL CONSTRAINT fk_chunks_collections REFERENCES collections(id) ON DELETE CASCADE,
    tags TEXT[] DEFAULT '{}'::TEXT[] NOT NULL,
    kg_ok BOOLEAN,
    kg_model TEXT,
    kg_extracted_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT chunks_pkey PRIMARY KEY (id, collection_id)
) PARTITION BY LIST (collection_id);

-- Partition lifecycle triggers on collections table.
--
-- chunks is schema-qualified and search_path is pinned, so these work for ANY caller. Neither is
-- cosmetic: pg_dump emits set_config('search_path', '', false) ahead of every COPY, so with an
-- unqualified reference a data-only restore fails on the collections load with "no schema has
-- been selected to create in", and a caller whose path starts with a different schema would
-- create the partition in the wrong one. The pin also keeps a future edit to the body from
-- reintroducing the same defect.
CREATE OR REPLACE FUNCTION create_chunks_partition() RETURNS trigger AS $$
BEGIN
    EXECUTE format('CREATE TABLE IF NOT EXISTS public.%I PARTITION OF public.chunks FOR VALUES IN (%L)',
                   'chunks_p_' || replace(NEW.id::text, '-', ''), NEW.id);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql
SET search_path = public, pg_temp;

CREATE TRIGGER trg_collections_create_chunks_partition
    AFTER INSERT ON collections
    FOR EACH ROW EXECUTE FUNCTION create_chunks_partition();

CREATE OR REPLACE FUNCTION drop_chunks_partition() RETURNS trigger AS $$
BEGIN
    EXECUTE format('DROP TABLE IF EXISTS public.%I',
                   'chunks_p_' || replace(OLD.id::text, '-', ''));
    RETURN OLD;
END;
$$ LANGUAGE plpgsql
SET search_path = public, pg_temp;

CREATE TRIGGER trg_collections_drop_chunks_partition
    AFTER DELETE ON collections
    FOR EACH ROW EXECUTE FUNCTION drop_chunks_partition();

-- 4. entities table
CREATE TABLE entities (
    id UUID PRIMARY KEY,
    collection_id UUID NOT NULL CONSTRAINT fk_entities_collections REFERENCES collections(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    -- An entity without a kind is not a valid node: identity is (collection_id, kind, lower(name)),
    -- so a kind-less row splits one logical entity into a kinded row plus a kind-less twin that
    -- the kind-aware consolidator correctly refuses to merge.
    kind TEXT NOT NULL,
    description TEXT,
    embedding vector(1024),
    metadata JSONB,
    tags TEXT[] DEFAULT '{}'::TEXT[] NOT NULL
);

-- 5. relationships table
CREATE TABLE relationships (
    id UUID PRIMARY KEY,
    collection_id UUID NOT NULL CONSTRAINT fk_relationships_collections REFERENCES collections(id) ON DELETE CASCADE,
    subject TEXT NOT NULL,
    predicate TEXT NOT NULL,
    object TEXT NOT NULL,
    subject_id UUID CONSTRAINT fk_relationships_subject REFERENCES entities(id) ON DELETE CASCADE,
    object_id UUID CONSTRAINT fk_relationships_object REFERENCES entities(id) ON DELETE CASCADE,
    description TEXT,
    metadata JSONB,
    tags TEXT[] DEFAULT '{}'::TEXT[] NOT NULL
);

-- 6. ingestion_jobs table
CREATE TABLE ingestion_jobs (
    id UUID PRIMARY KEY,
    kind TEXT NOT NULL,
    source_ref TEXT NOT NULL,
    tags TEXT[] DEFAULT '{}'::TEXT[] NOT NULL,
    collection_id UUID NOT NULL CONSTRAINT fk_ingestion_jobs_collections REFERENCES collections(id) ON DELETE CASCADE,
    status TEXT NOT NULL,
    progress INT NOT NULL DEFAULT 0,
    step TEXT,
    attempts INT NOT NULL DEFAULT 0,
    doc_count INT,
    chunk_count INT,
    entity_count INT,
    edge_count INT,
    json_object_count INT,
    -- Graph output a job could not persist (LLM-extraction path): entities the model returned
    -- without a kind, edges whose endpoints were never declared. Reported rather than thrown, so
    -- a job cannot claim a healthy edge count while quietly dropping graph.
    skipped_entity_count INT,
    skipped_edge_count INT,
    error TEXT,
    settings JSONB DEFAULT '{}'::jsonb NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMP WITH TIME ZONE,
    finished_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 7. users table
CREATE TABLE users (
    id UUID PRIMARY KEY,
    entra_oid TEXT UNIQUE NOT NULL,
    email TEXT,
    display_name TEXT,
    last_seen_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 8. conversations table
CREATE TABLE conversations (
    id UUID PRIMARY KEY,
    user_id UUID CONSTRAINT fk_conversations_users REFERENCES users(id) ON DELETE SET NULL,
    title TEXT,
    settings JSONB,
    source TEXT NOT NULL DEFAULT 'web',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    tags TEXT[] DEFAULT '{}'::TEXT[] NOT NULL
);

-- 9. messages table
CREATE TABLE messages (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL CONSTRAINT fk_messages_conversations REFERENCES conversations(id) ON DELETE CASCADE,
    role TEXT NOT NULL,
    content TEXT NOT NULL,
    playbook TEXT,
    retrieved_chunk_ids UUID[],
    citations JSONB,
    prompt_tokens INTEGER,
    completion_tokens INTEGER,
    total_tokens INTEGER,
    cached_tokens INTEGER,
    thought_tokens INTEGER,
    status TEXT NOT NULL DEFAULT 'done',
    model VARCHAR(100),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 10. message_feedback table
CREATE TABLE message_feedback (
    id UUID PRIMARY KEY,
    message_id UUID NOT NULL CONSTRAINT fk_message_feedback_messages REFERENCES messages(id) ON DELETE CASCADE,
    rating SMALLINT NOT NULL,
    comment TEXT,
    reviewed BOOLEAN NOT NULL DEFAULT FALSE,
    notes TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_message_feedback_message_id UNIQUE (message_id)
);

-- 11. retrieval_logs table
CREATE TABLE retrieval_logs (
    id UUID PRIMARY KEY,
    message_id UUID CONSTRAINT fk_retrieval_logs_messages REFERENCES messages(id) ON DELETE SET NULL,
    collection_id UUID NOT NULL CONSTRAINT fk_retrieval_logs_collections REFERENCES collections(id) ON DELETE CASCADE,
    tag TEXT,
    query TEXT,
    pools JSONB,
    final JSONB,
    rerank JSONB,
    initial_chunk_ids UUID[],
    fused_chunk_ids UUID[],
    reranked_chunk_ids UUID[],
    retrieved_chunk_ids UUID[],
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 12. summary_cache table
CREATE TABLE summary_cache (
    source_sha TEXT PRIMARY KEY,
    kind TEXT NOT NULL,
    summary TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 13. audit_log table
CREATE TABLE audit_log (
    id UUID PRIMARY KEY,
    entra_oid TEXT NOT NULL,
    action TEXT NOT NULL,
    target TEXT,
    detail JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 14. app_config table
CREATE TABLE app_config (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL,
    description TEXT,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 15. tags table
CREATE TABLE tags (
    id UUID PRIMARY KEY,
    name TEXT UNIQUE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 16. playbooks table
CREATE TABLE playbooks (
    name VARCHAR(255) PRIMARY KEY,
    title TEXT NOT NULL,
    description TEXT,
    template_text TEXT NOT NULL,
    tools TEXT[] DEFAULT '{}'::TEXT[] NOT NULL,
    code_execution BOOLEAN NOT NULL DEFAULT FALSE,
    target_agent VARCHAR(50) DEFAULT 'specialist' NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 17. section_summaries table
CREATE TABLE section_summaries (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL CONSTRAINT fk_section_summaries_documents REFERENCES documents(id) ON DELETE CASCADE,
    heading_path TEXT[] NOT NULL,
    summary TEXT NOT NULL,
    summary_embedding vector(1024),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (document_id, heading_path)
);

-- 18. system_prompts table
CREATE TABLE system_prompts (
    name VARCHAR(255) PRIMARY KEY,
    type VARCHAR(50) NOT NULL,
    system_prompt TEXT NOT NULL,
    description TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 19. json_objects table
CREATE TABLE json_objects (
    id            UUID PRIMARY KEY,
    collection_id UUID NOT NULL REFERENCES collections(id) ON DELETE CASCADE,
    data          JSONB NOT NULL,
    source_file   TEXT,
    tags          TEXT[] DEFAULT '{}'::TEXT[] NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 20. json_schemas table
CREATE TABLE json_schemas (
    id            UUID PRIMARY KEY,
    collection_id UUID NOT NULL REFERENCES collections(id) ON DELETE CASCADE,
    schema_data   JSONB NOT NULL,
    source        TEXT NOT NULL DEFAULT 'inferred',
    tag           TEXT,
    updated_at    TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 21. agent_runs table
CREATE TABLE agent_runs (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL CONSTRAINT fk_agent_runs_conversations REFERENCES conversations(id) ON DELETE CASCADE,
    message_id UUID CONSTRAINT fk_agent_runs_messages REFERENCES messages(id) ON DELETE SET NULL,
    profile_name TEXT NOT NULL,
    status TEXT NOT NULL,
    config JSONB,
    review_rounds INTEGER NOT NULL DEFAULT 0,
    final_verdict JSONB,
    prompt_tokens INTEGER,
    completion_tokens INTEGER,
    total_tokens INTEGER,
    cached_tokens INTEGER,
    thought_tokens INTEGER,
    error TEXT,
    started_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMP WITH TIME ZONE
);

-- 22. agent_steps table
CREATE TABLE agent_steps (
    id UUID PRIMARY KEY,
    agent_run_id UUID CONSTRAINT fk_agent_steps_agent_runs REFERENCES agent_runs(id) ON DELETE CASCADE,
    seq INTEGER NOT NULL,
    role TEXT NOT NULL,
    round INTEGER NOT NULL DEFAULT 0,
    playbook_name TEXT,
    model TEXT,
    thinking_level TEXT,
    input TEXT,
    output TEXT,
    messages JSONB,
    verdict JSONB,
    prompt_tokens INTEGER,
    completion_tokens INTEGER,
    total_tokens INTEGER,
    cached_tokens INTEGER,
    thought_tokens INTEGER,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 23. chat_model_pricing table
CREATE TABLE chat_model_pricing (
    id UUID PRIMARY KEY,
    model_name VARCHAR(100) UNIQUE NOT NULL,
    prompt_price_per_million NUMERIC(19, 6) NOT NULL,
    completion_price_per_million NUMERIC(19, 6) NOT NULL,
    cached_price_per_million NUMERIC(19, 6) NOT NULL,
    thought_price_per_million NUMERIC(19, 6) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- 24. orchestrator_profiles table
CREATE TABLE orchestrator_profiles (
    name VARCHAR(255) PRIMARY KEY,
    max_review_rounds INTEGER NOT NULL DEFAULT 2,
    max_specialist_calls INTEGER NOT NULL DEFAULT 8,
    orchestrator_playbook VARCHAR(255) NOT NULL,
    reviewer_playbook VARCHAR(255) NOT NULL,
    specialist_playbooks TEXT[] DEFAULT '{}'::TEXT[] NOT NULL,
    orchestrator_model VARCHAR(255),
    orchestrator_thinking_level VARCHAR(255),
    reviewer_model VARCHAR(255),
    reviewer_thinking_level VARCHAR(255),
    specialist_model VARCHAR(255),
    specialist_thinking_level VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 25. llm_call_logs table
CREATE TABLE llm_call_logs (
    id UUID PRIMARY KEY,
    conversation_id UUID CONSTRAINT fk_llm_call_logs_conversations REFERENCES conversations(id) ON DELETE SET NULL,
    message_id UUID CONSTRAINT fk_llm_call_logs_messages REFERENCES messages(id) ON DELETE SET NULL,
    agent_run_id UUID CONSTRAINT fk_llm_call_logs_agent_runs REFERENCES agent_runs(id) ON DELETE SET NULL,
    user_id UUID CONSTRAINT fk_llm_call_logs_users REFERENCES users(id) ON DELETE SET NULL,
    source TEXT NOT NULL,
    role TEXT,
    model TEXT NOT NULL,
    prompt_tokens INT NOT NULL DEFAULT 0,
    completion_tokens INT NOT NULL DEFAULT 0,
    cached_tokens INT NOT NULL DEFAULT 0,
    thought_tokens INT NOT NULL DEFAULT 0,
    total_tokens INT NOT NULL DEFAULT 0,
    prompt_price_per_million NUMERIC(12,6) NOT NULL DEFAULT 0,
    completion_price_per_million NUMERIC(12,6) NOT NULL DEFAULT 0,
    cached_price_per_million NUMERIC(12,6) NOT NULL DEFAULT 0,
    thought_price_per_million NUMERIC(12,6) NOT NULL DEFAULT 0,
    total_cost NUMERIC(14,6) NOT NULL DEFAULT 0,
    call_duration_ms INT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 26. confluence_instances table
--
-- The Confluence connector is multi-instance: several Confluence sites/spaces can be connected at
-- once, each instance owning its own credentials, page root, label filters and ingest target. This
-- replaces what used to be a single set of flat `confluence.*` rows in app_config; nothing writes
-- or reads those keys any more.
CREATE TABLE confluence_instances (
    id UUID PRIMARY KEY,
    -- Human label shown in the admin UI.
    name TEXT UNIQUE NOT NULL,
    -- Identifies the instance inside the job kind: "confluence-page-import:<slug>".
    slug TEXT UNIQUE NOT NULL,
    -- The Confluence base URL, canonicalized on save: lower-cased scheme+host, default port
    -- dropped, trailing slash stripped, PATH left byte-for-byte intact (Confluence Data Center
    -- legitimately runs under a context path). Canonical form matters because `source_file` — which
    -- IS document identity — is built from the domain, so host-casing drift mints duplicate
    -- documents rather than updating them.
    domain TEXT NOT NULL,
    email TEXT NOT NULL,
    -- SecretCipher ciphertext; NULL means no token has been set yet. token_key_id is the
    -- fingerprint of the key the ciphertext was produced with, so a key rotation can tell which
    -- rows still need re-encryption instead of silently decrypting to "" at first use. The two
    -- columns must always be written together — a row carrying one without the other reports a
    -- token-health status that lies.
    api_token_enc TEXT,
    token_key_id TEXT,
    space TEXT NOT NULL,
    root_page_id TEXT NOT NULL,
    include_labels TEXT,
    exclude_labels TEXT,
    -- The collection NAME, resolved case-insensitively at use time, and deliberately NOT a foreign
    -- key to collections(id): every other consumer in this pipeline resolves collections by name,
    -- and an FK with ON DELETE CASCADE would mean deleting a collection silently destroys the
    -- connector configuration (root page id, filters, credentials) from a form one click away.
    -- Do not "fix" this into an FK.
    target_collection TEXT NOT NULL,
    -- NULL means "no tag". The empty string must never be stored: it becomes List.of("")
    -- downstream, which hard-fails enqueue once the target collection declares any tag, and it also
    -- defeats the ingest version-skip (which tests `:tag IS NULL`, and '' is neither NULL nor a
    -- member of the tags array) — so every sync would re-embed the entire corpus. Rejected here
    -- rather than at the far end.
    target_tag TEXT,
    process_attachments BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_confluence_instances_target_tag_not_blank
        CHECK (target_tag IS NULL OR target_tag <> ''),
    -- The slug is embedded in the job kind "confluence-page-import:<slug>", which JobService parses
    -- with kind().split(":")[0], and it is rendered verbatim in job-list labels. A slug containing
    -- ':', whitespace or '/' therefore round-trips to a different instance (or to none) and
    -- corrupts the label, so the safe alphabet is enforced here rather than trusted from the form.
    CONSTRAINT ck_confluence_instances_slug_format
        CHECK (slug ~ '^[a-z0-9][a-z0-9-]*$')
);

-- There is deliberately NO composite uniqueness over (domain, space, root_page_id,
-- target_collection). Every candidate key of that shape rejects a legitimate configuration: two
-- instances over the same page tree distinguished only by include_labels (one label subset per
-- language, or per product version) feeding one collection is a supported setup, and widening the
-- key with include_labels/target_tag until it admits that leaves it guarding nothing. `name` and
-- `slug` are unique, the table holds single-digit rows and the admin list shows them all, so an
-- accidental duplicate is visible. Preventing duplicate WORK is the job of job-level admission
-- control (ux_ingestion_jobs_active_work, below), not of a config-table constraint.


-- ============================================================================
-- Functions used by indexes
-- ============================================================================

-- Graph identity is tag-scoped: the OIM corpus keeps two versions of the same installation kit in
-- ONE collection, separated only by tag, and the per-version document link lives in
-- entities.metadata->>'document_id'. With a tag-blind identity the second version's ingest
-- resolves onto the first version's rows and the link is written exactly once, so most of the
-- second version's entities point at the first version's documents. The read paths already filter
-- ':tag = ANY(tags)'; putting the tag set into identity is what lets that filter find the right
-- row. Documents work the same way (exact tag-set equality), so this gives the graph the same rule
-- as the documents it links to rather than a third convention.
--
-- Identity is the tag SET, so it must not depend on array order: '{9.3.1,10.0}' and '{10.0,9.3.1}'
-- are one scope. Indexing this expression rather than the raw column means no writer can fork an
-- identity by writing its tags in a different order, and hand-seeded rows (tests, fixtures) need
-- no canonical form. COLLATE "C" pins the ordering to byte order so the result cannot shift with
-- the database's lc_collate — an index on a collation-dependent expression would silently corrupt.
CREATE FUNCTION kg_canonical_tags(t TEXT[]) RETURNS TEXT[]
    LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT coalesce(
        (SELECT array_agg(x ORDER BY x COLLATE "C")
           FROM (SELECT DISTINCT btrim(y) AS x
                   FROM unnest(coalesce(t, ARRAY[]::TEXT[])) AS y
                  WHERE btrim(y) <> '') s),
        ARRAY[]::TEXT[])
$$;

-- ============================================================================
-- Indexes
-- ============================================================================

-- Collections indexes
CREATE INDEX idx_collections_tags ON collections USING gin (tags);

-- Documents indexes
CREATE INDEX idx_documents_tags ON documents USING gin (tags);
CREATE INDEX idx_documents_collection_id ON documents (collection_id);
CREATE INDEX idx_documents_metadata_source_file ON documents ((metadata->>'source_file'));
CREATE INDEX idx_documents_id_nodash ON documents ((replace(id::text, '-', '')) text_pattern_ops);
-- Fuzzy title filter on list_documents: ILIKE '%q%' prefilter + similarity(title, q) ranking.
CREATE INDEX idx_documents_title_trgm ON documents USING gin (title gin_trgm_ops);
-- Document identity: one document per (collection, kind, source file, tag set).
--
-- This is the key DocumentRepository's upsert already searches on, promoted to a constraint so its
-- SELECT-then-INSERT under READ COMMITTED cannot mint a second row — and a second full chunk set —
-- for one source file when two jobs race. The upsert pairs it with ON CONFLICT DO NOTHING plus a
-- re-SELECT, so the loser adopts the winner's row instead of throwing. Its tag comparison is
-- set-based (@> and <@) while this index compares arrays, which is the safe direction: the lookup
-- is the WEAKER predicate, so it can never miss a row this index would reject.
--
-- NULL source_file rows do not conflict with each other — Postgres treats NULLs as distinct — so
-- documents ingested without a source_file are simply not covered. That is acceptable: they have
-- no identity to key on. Deliberately NOT declared NULLS NOT DISTINCT, which would collapse every
-- such row in a collection onto one.
--
-- tags being part of the key makes an in-place tag rewrite able to collide with a SIBLING row for
-- the same source file (two kit versions in one collection separated only by tag is the documented
-- OIM shape). DocumentRepository.removeTagAndPurgeOrphans and TagRepository.addTagToDocument /
-- removeTagFromDocument therefore skip rows whose rewrite would land on an occupied tag scope
-- instead of failing the batch with a raw 23505.
CREATE UNIQUE INDEX ux_documents_collection_kind_source_file_tags
    ON documents (collection_id, kind, (metadata->>'source_file'), tags);

-- Chunks indexes
CREATE INDEX chunks_embedding_hnsw_idx ON chunks USING hnsw (embedding vector_cosine_ops);
CREATE INDEX chunks_bm25_idx ON chunks USING bm25 (id, text, collection_id, document_id, (tags::pdb.literal)) WITH (key_field='id');
CREATE INDEX chunks_document_id_sort_order_idx ON chunks (document_id, sort_order);
CREATE INDEX idx_chunks_document_id_kg_ok ON chunks (document_id, kg_ok);
CREATE INDEX idx_chunks_id_nodash ON chunks ((replace(id::text, '-', '')) text_pattern_ops);

-- Entities indexes
CREATE INDEX entities_name_trgm_idx ON entities USING gin (name gin_trgm_ops);
CREATE INDEX idx_entities_tags ON entities USING gin (tags);
CREATE INDEX idx_entities_collection_id ON entities (collection_id);
CREATE INDEX idx_entities_lower_name ON entities (lower(name));
CREATE INDEX idx_entities_collection_name ON entities (collection_id, name);
-- Kind- and tag-aware find-or-create key. KgWriteRepository's ON CONFLICT targets this exact
-- expression list, so coalesce() must stay even though kind is NOT NULL, and kg_canonical_tags(tags)
-- must be written the same way there.
CREATE UNIQUE INDEX ux_entities_collection_kind_lower_name_tags
    ON entities (collection_id, coalesce(kind, ''), lower(name), kg_canonical_tags(tags));

-- Relationships indexes
CREATE INDEX relationships_subject_id_idx ON relationships (subject_id);
CREATE INDEX relationships_object_id_idx ON relationships (object_id);
CREATE INDEX relationships_subject_idx ON relationships (subject);
CREATE INDEX relationships_object_idx ON relationships (object);
CREATE INDEX relationships_predicate_idx ON relationships (predicate);
CREATE INDEX idx_relationships_tags ON relationships USING gin (tags);
CREATE INDEX idx_relationships_collection_id ON relationships (collection_id);
CREATE INDEX idx_relationships_lower_subject ON relationships (lower(subject));
CREATE INDEX idx_relationships_lower_object ON relationships (lower(object));
-- Relationships get no unique index (they never had one — identity is enforced in Java on
-- (subject_id, lower(predicate), object_id)). They are tag-scoped for free: once the endpoint
-- entity rows are per-tag, subject_id/object_id carry the scope. This index supports the
-- scope-aware existing-edge lookup.
CREATE INDEX idx_relationships_collection_subject_tags
    ON relationships (collection_id, subject_id, kg_canonical_tags(tags));

-- Ingestion Jobs indexes
CREATE INDEX ingestion_jobs_status_created_idx ON ingestion_jobs (status, created_at);
CREATE INDEX ingestion_jobs_queued_idx ON ingestion_jobs (created_at) WHERE status = 'queued';
-- Job admission control: one ACTIVE job per unit of work.
--
-- The key is all FOUR columns, not (kind, source_ref). A confluence-page-import's source_ref is
-- "confluence/<space>/<pageId>" and carries neither the collection nor the tag, so two connector
-- instances importing the same space into DIFFERENT collections would block each other — the exact
-- multi-instance setup confluence_instances exists to support. kg-extract has the same shape in
-- reverse: its source_ref is a document id, and IngestService.processKg exists precisely to
-- extract one document into a different target collection/tag.
--
-- Partial on the active statuses so history never blocks new work: a completed, failed or
-- cancelled job leaves the index, and the same work can be enqueued again. JobRepository.enqueue
-- arbitrates on this index and must repeat this predicate VERBATIM in its ON CONFLICT clause — a
-- partial index is only inferred as arbiter when the statement restates its predicate — and, when
-- nothing is returned, re-SELECTs and hands back the existing active job as
-- EnqueueResult(jobId, created=false) rather than throwing.
--
-- tags is the raw column, so the key is array ORDER sensitive: two spellings of one tag set would
-- fork the key. Every writer is JobRepository.enqueue, which writes at most one tag, so there is no
-- order to disagree about; a future multi-tag enqueue must canonicalize (see kg_canonical_tags
-- above) rather than rely on callers.
CREATE UNIQUE INDEX ux_ingestion_jobs_active_work
    ON ingestion_jobs (kind, source_ref, collection_id, tags)
    WHERE status IN ('queued', 'running');

-- Conversations indexes
CREATE INDEX conversations_user_id_idx ON conversations (user_id);
CREATE INDEX conversations_user_source_idx ON conversations (user_id, source);
CREATE INDEX conversations_created_at_idx ON conversations (created_at DESC, id DESC);
CREATE INDEX idx_conversations_tags ON conversations USING gin (tags);

-- Messages indexes
CREATE INDEX messages_conversation_created_idx ON messages (conversation_id, created_at, id);
CREATE INDEX idx_messages_model ON messages(model);

-- Retrieval Logs indexes
CREATE INDEX retrieval_logs_message_id_idx ON retrieval_logs (message_id);
CREATE INDEX retrieval_logs_created_at_idx ON retrieval_logs (created_at);

-- Audit Log indexes
CREATE INDEX audit_log_entra_oid_idx ON audit_log (entra_oid);

-- Section summaries indexes
CREATE INDEX idx_section_summaries_path ON section_summaries (document_id, heading_path);

-- Json Objects indexes
CREATE INDEX idx_json_objects_collection_created ON json_objects (collection_id, created_at DESC);
CREATE INDEX idx_json_objects_data_gin ON json_objects USING GIN (data);
CREATE INDEX idx_json_objects_tags ON json_objects USING gin (tags);

-- Json Schemas indexes
CREATE UNIQUE INDEX idx_json_schemas_collection_tag_unique ON json_schemas (collection_id, tag) NULLS NOT DISTINCT;

-- Agent Runs indexes
CREATE INDEX idx_agent_runs_conversation_id ON agent_runs(conversation_id);
CREATE INDEX idx_agent_runs_message_id ON agent_runs(message_id);

-- Agent Steps indexes
CREATE INDEX idx_agent_steps_run_seq ON agent_steps(agent_run_id, seq);
CREATE INDEX idx_agent_steps_model ON agent_steps(model);

-- LLM Call Logs indexes
CREATE INDEX idx_llm_call_logs_created_at ON llm_call_logs (created_at);
CREATE INDEX idx_llm_call_logs_model ON llm_call_logs (model);
CREATE INDEX idx_llm_call_logs_source ON llm_call_logs (source);
CREATE INDEX idx_llm_call_logs_conversation_id ON llm_call_logs (conversation_id);
CREATE INDEX idx_llm_call_logs_agent_run_id ON llm_call_logs (agent_run_id);

-- performance indexes for hot filter / sort / join paths

CREATE INDEX idx_llm_call_logs_user_id_created_at ON llm_call_logs (user_id, created_at);

CREATE INDEX idx_audit_log_created_at ON audit_log (created_at);

CREATE INDEX idx_retrieval_logs_collection_id ON retrieval_logs (collection_id);

CREATE INDEX idx_agent_runs_started_at ON agent_runs (started_at DESC);

CREATE INDEX idx_messages_retrieved_chunk_ids_gin ON messages USING gin (retrieved_chunk_ids);
