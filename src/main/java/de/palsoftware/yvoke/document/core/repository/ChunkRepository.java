package de.palsoftware.yvoke.document.core.repository;

import de.palsoftware.yvoke.shared.config.JdbcMappers;

import de.palsoftware.yvoke.document.core.model.ChunkPathRow;
import de.palsoftware.yvoke.document.core.model.ChunkRow;


import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Nullable;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ChunkRepository {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public ChunkRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public List<ChunkRow> findSemanticCandidates(String vectorStr, int limit, int offset,
        List<String> tags, List<String> collections) {
        return findSemanticCandidates(vectorStr, limit, offset, tags, collections, null);
    }

    public List<ChunkRow> findSemanticCandidates(String vectorStr, int limit, int offset,
        List<String> tags, List<String> collections, @Nullable List<UUID> documentIds) {
        String tagClause = "";
        if (tags != null && !tags.isEmpty()) {
            tagClause = " AND EXISTS (SELECT 1 FROM unnest(d.tags) t WHERE t IN (:tags)) ";
        }

        List<String> collDocClauses = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        params.put("embedding", vectorStr);
        params.put("limit", limit);
        params.put("offset", offset);

        if (collections != null && !collections.isEmpty()) {
            // Filter on ch.collection_id (the partition key) rather than coll.name so the
            // planner can prune chunk partitions; the join stays for the collection name output.
            List<UUID> collectionIds = resolveCollectionIds(collections);
            if (collectionIds.isEmpty()) {
                return List.of();
            }
            collDocClauses.add("ch.collection_id IN (:collectionIds)");
            params.put("collectionIds", collectionIds);
        }
        if (documentIds != null && !documentIds.isEmpty()) {
            collDocClauses.add("ch.document_id IN (:documentIds)");
            params.put("documentIds", documentIds);
        }

        String collDocClause = "";
        if (!collDocClauses.isEmpty()) {
            collDocClause = " AND " + String.join(" AND ", collDocClauses) + " ";
        }

        // hnsw.iterative_scan=relaxed_order may emit index results slightly out of distance
        // order; the outer sort restores strict ranking (callers use row position as rank).
        String sql =
            """
                SELECT * FROM (
                SELECT ch.id, ch.document_id, ch.text, ch.heading_path, ch.heading, ch.depth, ch.sort_order, d.tags[1] AS tag, d.title AS document_title, d.kind, coll.name AS collection, d.metadata,
                       (1.0 - (ch.embedding <=> :embedding::vector)) AS cosine_similarity
                FROM chunks ch
                JOIN collections coll ON ch.collection_id = coll.id
                JOIN documents d ON ch.document_id = d.id
                WHERE 1=1
                """
                + collDocClause + tagClause + """
                    ORDER BY ch.embedding <=> :embedding::vector ASC
                    LIMIT :limit OFFSET :offset
                    ) candidates ORDER BY cosine_similarity DESC
                    """;

        if (tags != null && !tags.isEmpty()) {
            params.put("tags", tags);
        }

        return jdbcClient.sql(sql).params(params)
            .query(new ChunkRowMapper(objectMapper, "cosine_similarity")).list();
    }

    public List<ChunkRow> findFulltextCandidates(String query, int limit, int offset,
        List<String> tags, List<String> collections) {
        return findFulltextCandidates(query, limit, offset, tags, collections, null);
    }

    public List<ChunkRow> findFulltextCandidates(String query, int limit, int offset,
        List<String> tags, List<String> collections, @Nullable List<UUID> documentIds) {

        List<UUID> collectionIds = resolveCollectionIds(collections);
        if (collections != null && !collections.isEmpty() && collectionIds.isEmpty()) {
            return List.of();
        }

        // Filters are plain SQL predicates (pdb operator syntax, pg_search >= 0.20): the
        // collection_id predicate additionally drives partition pruning, so BM25 scoring uses
        // the target partition's collection-local statistics.
        List<String> filterClauses = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        params.put("query", query);
        params.put("limit", limit);
        params.put("offset", offset);

        if (!collectionIds.isEmpty()) {
            filterClauses.add("ch.collection_id IN (:collectionIds)");
            params.put("collectionIds", collectionIds);
        }
        if (documentIds != null && !documentIds.isEmpty()) {
            filterClauses.add("ch.document_id IN (:documentIds)");
            params.put("documentIds", documentIds);
        }
        if (tags != null && !tags.isEmpty()) {
            // === is a term match; on text[] it matches when any element equals the tag
            // (tags are indexed with the literal tokenizer, so matching is exact).
            List<String> tagTerms = new ArrayList<>();
            for (int i = 0; i < tags.size(); i++) {
                tagTerms.add("ch.tags === :tag" + i);
                params.put("tag" + i, tags.get(i));
            }
            filterClauses.add("(" + String.join(" OR ", tagTerms) + ")");
        }

        String filterClause =
            filterClauses.isEmpty() ? "" : " AND " + String.join(" AND ", filterClauses) + " ";

        String sql =
            """
                SELECT ch.id, ch.document_id, ch.text, ch.heading_path, ch.heading, ch.depth, ch.sort_order, d.tags[1] AS tag, d.title AS document_title, d.kind, coll.name AS collection, d.metadata,
                       pdb.score(ch.id) AS bm25_score
                FROM chunks ch
                JOIN collections coll ON ch.collection_id = coll.id
                JOIN documents d ON ch.document_id = d.id
                WHERE ch.text ||| :query
                """
                + filterClause + """
                    ORDER BY bm25_score DESC
                    LIMIT :limit OFFSET :offset
                    """;

        return jdbcClient.sql(sql).params(params)
            .query(new ChunkRowMapper(objectMapper, "bm25_score")).list();
    }

    private List<UUID> resolveCollectionIds(@Nullable List<String> collections) {
        if (collections == null || collections.isEmpty()) {
            return List.of();
        }
        return jdbcClient.sql("SELECT id FROM collections WHERE name IN (:names)")
            .param("names", collections).query(UUID.class).list();
    }

    public List<ChunkRow> findChunksByDocumentId(UUID documentId, @Nullable String tag) {
        String sql =
            """
                SELECT ch.id, ch.document_id, ch.text, ch.heading_path, ch.heading, ch.depth, ch.sort_order, d.tags[1] AS tag, d.title AS document_title, d.kind, coll.name AS collection, d.metadata
                FROM chunks ch
                JOIN collections coll ON ch.collection_id = coll.id
                JOIN documents d ON ch.document_id = d.id
                WHERE ch.document_id = :documentId
                """;

        Map<String, Object> params = new HashMap<>();
        params.put("documentId", documentId);

        if (tag != null) {
            sql += " AND :tag = ANY(d.tags)";
            params.put("tag", tag);
        }

        sql += " ORDER BY ch.sort_order ASC";

        return jdbcClient.sql(sql).params(params).query(new ChunkRowMapper(objectMapper, null))
            .list();
    }

    /**
     * Hierarchy-only projection of a document's chunks (no text/metadata transfer) for TOC building
     * and section-path matching.
     */
    public List<ChunkPathRow> findChunkPathsByDocumentId(UUID documentId) {
        String sql = """
            SELECT ch.id, ch.heading_path, ch.heading, ch.sort_order,
                   coalesce(length(ch.text), 0) AS text_len
            FROM chunks ch
            WHERE ch.document_id = :documentId
            ORDER BY ch.sort_order ASC
            """;
        return jdbcClient.sql(sql).param("documentId", documentId).query((rs, rowNum) -> {
            List<String> headingPath = JdbcMappers.arrayToStringList(rs, "heading_path");
            return new ChunkPathRow(rs.getObject("id", UUID.class), headingPath,
                rs.getString("heading"), rs.getObject("sort_order", Integer.class),
                rs.getInt("text_len"));
        }).list();
    }

    /** Full chunk rows for the given ids, ordered by sort_order. */
    public List<ChunkRow> findChunksByIds(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        String sql =
            """
                SELECT ch.id, ch.document_id, ch.text, ch.heading_path, ch.heading, ch.depth, ch.sort_order, d.tags[1] AS tag, d.title AS document_title, d.kind, coll.name AS collection, d.metadata
                FROM chunks ch
                JOIN collections coll ON ch.collection_id = coll.id
                JOIN documents d ON ch.document_id = d.id
                WHERE ch.id = ANY(:ids::uuid[])
                ORDER BY ch.sort_order ASC
                """;
        String[] idStrings = ids.stream().map(UUID::toString).toArray(String[]::new);
        return jdbcClient.sql(sql).param("ids", idStrings)
            .query(new ChunkRowMapper(objectMapper, null)).list();
    }

    public Optional<ChunkRow> findByIdPrefix(String idPrefix) {
        if (idPrefix == null) {
            throw new IllegalArgumentException("ID prefix cannot be null.");
        }
        if (!idPrefix.isEmpty()) {
            if (!idPrefix.matches("^[0-9a-fA-F-]+$")) {
                throw new IllegalArgumentException(
                    "Invalid ID prefix format. Must contain only hex characters and dashes.");
            }
            if (idPrefix.length() < 8) {
                throw new IllegalArgumentException("ID prefix must be at least 8 characters long.");
            }
        }

        String sql =
            """
                SELECT ch.id, ch.document_id, ch.text, ch.heading_path, ch.heading, ch.depth, ch.sort_order, d.tags[1] AS tag, d.title AS document_title, d.kind, coll.name AS collection, d.metadata
                FROM chunks ch
                JOIN collections coll ON ch.collection_id = coll.id
                JOIN documents d ON ch.document_id = d.id
                WHERE replace(CAST(ch.id AS TEXT), '-', '') LIKE :prefix
                """;

        String cleanPrefix = idPrefix.replace("-", "").toLowerCase();
        List<ChunkRow> matches = jdbcClient.sql(sql).param("prefix", cleanPrefix + "%")
            .query(new ChunkRowMapper(objectMapper, null)).list();

        if (matches.isEmpty()) {
            return Optional.empty();
        }
        if (matches.size() > 1) {
            StringBuilder sb = new StringBuilder(
                "Ambiguous chunk ID '" + idPrefix + "' — " + matches.size() + " matches:\n");
            for (ChunkRow c : matches) {
                sb.append("  ").append(c.id().toString().substring(0, 8)).append("  ")
                    .append(c.documentTitle()).append("  ").append(c.heading()).append("\n");
            }
            throw new IllegalArgumentException(sb.toString().trim());
        }
        return Optional.of(matches.get(0));
    }

    /** Returns which of the given chunk ids exist, in a single query. */
    public Set<UUID> findExistingIds(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return Set.of();
        }
        String[] idStrings = ids.stream().map(UUID::toString).toArray(String[]::new);
        return new HashSet<>(jdbcClient.sql("SELECT id FROM chunks WHERE id = ANY(:ids::uuid[])")
            .param("ids", idStrings).query(UUID.class).list());
    }

    public boolean chunkHasEmbedding(UUID chunkId) {
        return jdbcClient.sql("SELECT (embedding IS NOT NULL) FROM chunks WHERE id = :id")
            .param("id", chunkId).query(Boolean.class).optional().orElse(false);
    }

    public long countByIdPrefix(String idPrefix, @Nullable String tag) {
        String sql = "SELECT COUNT(*) FROM chunks ch ";
        if (tag != null) {
            sql += " JOIN documents d ON ch.document_id = d.id ";
        }
        sql += " WHERE replace(CAST(ch.id AS TEXT), '-', '') LIKE :prefix ";
        Map<String, Object> params = new HashMap<>();
        params.put("prefix", idPrefix.replace("-", "").toLowerCase() + "%");
        if (tag != null) {
            sql += " AND :tag = ANY(d.tags) ";
            params.put("tag", tag);
        }
        return jdbcClient.sql(sql).params(params).query(Long.class).single();
    }

    public List<String> findDistinctDocumentTitles(@Nullable String tag) {
        String sql = "SELECT DISTINCT d.title FROM chunks ch "
            + " JOIN documents d ON ch.document_id = d.id " + " WHERE d.title IS NOT NULL ";
        Map<String, Object> params = new HashMap<>();
        if (tag != null) {
            sql += " AND :tag = ANY(d.tags) ";
            params.put("tag", tag);
        }
        sql += " ORDER BY d.title ASC";
        return jdbcClient.sql(sql).params(params).query(String.class).list();
    }
}
