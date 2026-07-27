-- Document identity must key on the canonical tag SET, not the raw array.
--
-- V1 created ux_documents_collection_kind_source_file_tags over `tags` directly. Postgres compares
-- arrays element-by-element, so {9.3.1,10.0} and {10.0,9.3.1} are different index keys — the same
-- document ingested with its tags in a different order forks into two rows, each carrying half the
-- chunks, and the upsert that exists to make a concurrent re-crawl a no-op silently inserts a
-- second document instead.
--
-- kg_canonical_tags(tags) (defined in V1) sorts and de-duplicates under COLLATE "C" and is
-- IMMUTABLE, which is exactly why entities and relationships already key on it. Documents were the
-- one identity that did not.
--
-- NOTE: the new index is STRICTER than the old one. If this collection already contains two
-- documents that differ only by tag ORDER, CREATE UNIQUE INDEX fails here and the migration stops.
-- That is the correct outcome — those rows are the bug, and merging them is a data decision that
-- must not be made silently by a schema migration. Resolve the duplicates, then re-run.
--
-- DocumentRepository's ON CONFLICT names this exact expression; an arbiter must match the index
-- expression, so the two have to move together.

DROP INDEX IF EXISTS ux_documents_collection_kind_source_file_tags;

CREATE UNIQUE INDEX ux_documents_collection_kind_source_file_tags
    ON documents (collection_id, kind, (metadata->>'source_file'), kg_canonical_tags(tags));
