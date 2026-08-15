package de.palsoftware.yvoke.document.core.repository;

import de.palsoftware.yvoke.document.core.model.ChunkInsert;
import de.palsoftware.yvoke.document.core.model.ChunkKgStatus;
import de.palsoftware.yvoke.document.core.model.DocumentDetails;
import de.palsoftware.yvoke.document.core.model.DocumentIngestionStatus;
import de.palsoftware.yvoke.document.core.model.DocumentRow;


import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.shared.db.CollectionIdResolver;
import de.palsoftware.yvoke.shared.db.VectorUtils;
import jakarta.annotation.Nullable;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import java.sql.Types;

@Repository
public class DocumentRepository {

    private static final Logger log = LoggerFactory.getLogger(DocumentRepository.class);

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final CollectionIdResolver collectionIdResolver;

    public DocumentRepository(JdbcClient jdbcClient, ObjectMapper objectMapper,
        JdbcTemplate jdbcTemplate, CollectionIdResolver collectionIdResolver) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.collectionIdResolver = collectionIdResolver;
    }

    public Optional<DocumentRow> findById(UUID id) {
        String sql =
            """
                SELECT d.id, d.collection_id, c.name AS collection, d.kind, d.title, d.metadata, d.ingestion_status, d.tags, d.created_at
                FROM documents d
                JOIN collections c ON d.collection_id = c.id
                WHERE d.id = :id
                """;
        return jdbcClient.sql(sql).param("id", id).query(new DocumentRowMapper(objectMapper))
            .optional();
    }

    public Optional<DocumentDetails> findByIdPrefix(String idPrefix) {
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

        String querySql =
            """
                SELECT d.id, d.collection_id, c.name AS collection, d.kind, d.title, d.metadata, d.ingestion_status, d.tags, d.created_at,
                       (SELECT count(*) FROM chunks WHERE document_id = d.id AND collection_id = d.collection_id) AS chunk_count,
                       ((d.metadata ->> 'kg_processed_at') IS NOT NULL
                           OR EXISTS (SELECT 1 FROM chunks WHERE document_id = d.id AND collection_id = d.collection_id AND kg_ok IS NOT NULL)) AS kg_processed,
                       (SELECT count(*) FROM chunks WHERE document_id = d.id AND collection_id = d.collection_id AND kg_ok IS FALSE) AS kg_failed_chunks
                FROM documents d
                JOIN collections c ON d.collection_id = c.id
                WHERE replace(CAST(d.id AS TEXT), '-', '') LIKE :prefix
                """;

        String cleanPrefix = idPrefix.replace("-", "").toLowerCase();
        List<DocumentDetails> matches = jdbcClient.sql(querySql).param("prefix", cleanPrefix + "%")
            .query(new DocumentDetailsMapper(objectMapper)).list();

        if (matches.isEmpty()) {
            return Optional.empty();
        }
        if (matches.size() > 1) {
            StringBuilder sb = new StringBuilder(
                "Ambiguous ID '" + idPrefix + "' — " + matches.size() + " matches:\n");
            for (DocumentDetails d : matches) {
                sb.append("  ").append(d.id().toString().substring(0, 8)).append("  ")
                    .append(d.title()).append("\n");
            }
            throw new IllegalArgumentException(sb.toString().trim());
        }
        return Optional.of(matches.get(0));
    }

    /** Returns which of the given document ids exist, in a single query. */
    public Set<UUID> findExistingIds(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return Set.of();
        }
        String[] idStrings = ids.stream().map(UUID::toString).toArray(String[]::new);
        return new HashSet<>(jdbcClient.sql("SELECT id FROM documents WHERE id = ANY(:ids::uuid[])")
            .param("ids", idStrings).query(UUID.class).list());
    }

    public Optional<DocumentRow> findByManual(String manualSubstring) {
        return findByManual(manualSubstring, null);
    }

    public Optional<DocumentRow> findByManual(String manualSubstring, @Nullable String collection) {
        return findByManual(manualSubstring, collection, null);
    }

    public Optional<DocumentRow> findByManual(String manualSubstring, @Nullable String collection,
        @Nullable List<String> tags) {
        // Resolves any document by name (a manual title or a DB object name like "ADSAccount"),
        // not just kind='manual'. Ambiguity is bounded by the collection filter below.
        String sql =
            """
                SELECT d.id, d.collection_id, c.name AS collection, d.kind, d.title, d.metadata, d.ingestion_status, d.tags, d.created_at
                FROM documents d
                JOIN collections c ON d.collection_id = c.id
                WHERE d.title ILIKE :title
                """;

        Map<String, Object> params = new HashMap<>();
        params.put("title", "%" + manualSubstring + "%");

        if (collection != null && !collection.isBlank()) {
            sql += " AND LOWER(c.name) = LOWER(:collection)";
            params.put("collection", collection.trim());
        }

        if (tags != null && !tags.isEmpty()) {
            sql += " AND d.tags && :tags::text[]";
            params.put("tags", tags.toArray(new String[0]));
        }
        // Total order so the ambiguity listing below is stable across identical calls.
        sql += " ORDER BY d.title ASC, d.kind ASC, d.id ASC";

        List<DocumentRow> matches =
            jdbcClient.sql(sql).params(params).query(new DocumentRowMapper(objectMapper)).list();

        if (matches.isEmpty()) {
            return Optional.empty();
        }
        if (matches.size() > 1) {
            // Prefer an exact title match before reporting ambiguity
            List<DocumentRow> exact =
                matches.stream().filter(r -> manualSubstring.equalsIgnoreCase(r.title())).toList();
            if (exact.size() == 1) {
                return Optional.of(exact.get(0));
            }
            // Same-named documents of different kinds are DIFFERENT objects, so a truncated id plus
            // the (identical) title renders as N indistinguishable lines. List the full id — the
            // caller is told to pass document_id — plus the kind and tags that tell them apart.
            StringBuilder sb =
                new StringBuilder("multiple documents match — pass document_id for one:\n");
            for (DocumentRow r : matches) {
                sb.append("  ").append(r.id()).append("  [")
                    .append(r.kind() != null ? r.kind() : "?").append("]  ").append(r.title());
                if (r.tags() != null && !r.tags().isEmpty()) {
                    sb.append("  (").append(String.join(", ", r.tags())).append(")");
                }
                sb.append("\n");
            }
            throw new IllegalArgumentException(sb.toString().trim());
        }
        return Optional.of(matches.get(0));
    }

    public List<DocumentDetails> listDocuments(@Nullable String collection, int limit, int offset,
        @Nullable String kind) {
        return listDocuments(collection, limit, offset, kind, null, null);
    }

    public List<DocumentDetails> listDocuments(@Nullable String collection, int limit, int offset,
        @Nullable String kind, @Nullable String tag) {
        return listDocuments(collection, limit, offset, kind, tag, null);
    }

    public List<DocumentDetails> listDocuments(@Nullable String collection, int limit, int offset,
        @Nullable String kind, @Nullable String tag, @Nullable String searchId) {
        return listDocuments(collection, limit, offset, kind, tag, searchId, null);
    }

    public List<DocumentDetails> listDocuments(@Nullable String collection, int limit, int offset,
        @Nullable String kind, @Nullable String tag, @Nullable String searchId,
        @Nullable String searchTitle) {
        return listDocuments(collection, limit, offset, kind, tag, searchId, searchTitle, null);
    }

    /**
     * Lists documents with optional filters. {@code fuzzyTitle} is an approximate title match
     * (case-insensitive substring OR trigram similarity &gt; 0.25); when present, results are
     * ordered by descending title similarity so the closest titles surface first.
     */
    public List<DocumentDetails> listDocuments(@Nullable String collection, int limit, int offset,
        @Nullable String kind, @Nullable String tag, @Nullable String searchId,
        @Nullable String searchTitle, @Nullable String fuzzyTitle) {
        String sql =
            """
                SELECT d.id, d.collection_id, c.name AS collection, d.kind, d.title, d.metadata, d.ingestion_status, d.tags, d.created_at,
                       (SELECT count(*) FROM chunks WHERE document_id = d.id AND collection_id = d.collection_id) AS chunk_count,
                       ((d.metadata ->> 'kg_processed_at') IS NOT NULL
                           OR EXISTS (SELECT 1 FROM chunks WHERE document_id = d.id AND collection_id = d.collection_id AND kg_ok IS NOT NULL)) AS kg_processed,
                       (SELECT count(*) FROM chunks WHERE document_id = d.id AND collection_id = d.collection_id AND kg_ok IS FALSE) AS kg_failed_chunks
                FROM documents d
                JOIN collections c ON d.collection_id = c.id
                WHERE 1=1
                """;

        Map<String, Object> params = new HashMap<>();
        params.put("limit", limit);
        params.put("offset", offset);

        if (collection != null) {
            sql += " AND LOWER(c.name) = LOWER(:collection) ";
            params.put("collection", collection.trim());
        }
        if (kind != null) {
            sql += " AND d.kind = :kind ";
            params.put("kind", kind);
        }
        if (tag != null && !tag.isBlank()) {
            List<String> tagsList =
                Arrays.stream(tag.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
            if (!tagsList.isEmpty()) {
                sql += """
                     AND d.tags && :tags::text[]
                    """;
                params.put("tags", tagsList.toArray(new String[0]));
            }
        }
        if (searchId != null && !searchId.isBlank()) {
            sql += " AND CAST(d.id AS text) ILIKE :searchId ";
            params.put("searchId", "%" + searchId.trim() + "%");
        }
        if (searchTitle != null && !searchTitle.isBlank()) {
            sql += " AND d.title ILIKE :searchTitle ";
            params.put("searchTitle", "%" + searchTitle.trim() + "%");
        }
        boolean hasFuzzy = fuzzyTitle != null && !fuzzyTitle.isBlank();
        if (hasFuzzy) {
            sql +=
                " AND (d.title ILIKE '%' || :fuzzyTitle || '%' OR similarity(d.title, :fuzzyTitle) > 0.25) ";
            params.put("fuzzyTitle", fuzzyTitle.trim());
        }

        if (hasFuzzy) {
            sql +=
                " ORDER BY similarity(d.title, :fuzzyTitle) DESC, d.title ASC LIMIT :limit OFFSET :offset";
        } else {
            sql += " ORDER BY d.created_at DESC, d.id ASC LIMIT :limit OFFSET :offset";
        }

        return jdbcClient.sql(sql).params(params).query(new DocumentDetailsMapper(objectMapper))
            .list();
    }

    public long countDocuments(@Nullable String collection, @Nullable String kind) {
        return countDocuments(collection, kind, null, null);
    }

    public long countDocuments(@Nullable String collection, @Nullable String kind,
        @Nullable String tag) {
        return countDocuments(collection, kind, tag, null);
    }

    public long countDocuments(@Nullable String collection, @Nullable String kind,
        @Nullable String tag, @Nullable String searchId) {
        return countDocuments(collection, kind, tag, searchId, null);
    }

    public long countDocuments(@Nullable String collection, @Nullable String kind,
        @Nullable String tag, @Nullable String searchId, @Nullable String searchTitle) {
        return countDocuments(collection, kind, tag, searchId, searchTitle, null);
    }

    public long countDocuments(@Nullable String collection, @Nullable String kind,
        @Nullable String tag, @Nullable String searchId, @Nullable String searchTitle,
        @Nullable String fuzzyTitle) {
        String sql = """
            SELECT count(*)
            FROM documents d
            JOIN collections c ON d.collection_id = c.id
            WHERE 1=1
            """;
        Map<String, Object> params = new HashMap<>();

        if (collection != null) {
            sql += " AND LOWER(c.name) = LOWER(:collection) ";
            params.put("collection", collection.trim());
        }
        if (kind != null) {
            sql += " AND d.kind = :kind ";
            params.put("kind", kind);
        }
        if (tag != null && !tag.isBlank()) {
            List<String> tagsList =
                Arrays.stream(tag.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
            if (!tagsList.isEmpty()) {
                sql += """
                     AND d.tags && :tags::text[]
                    """;
                params.put("tags", tagsList.toArray(new String[0]));
            }
        }
        if (searchId != null && !searchId.isBlank()) {
            sql += " AND CAST(d.id AS text) ILIKE :searchId ";
            params.put("searchId", "%" + searchId.trim() + "%");
        }
        if (searchTitle != null && !searchTitle.isBlank()) {
            sql += " AND d.title ILIKE :searchTitle ";
            params.put("searchTitle", "%" + searchTitle.trim() + "%");
        }
        if (fuzzyTitle != null && !fuzzyTitle.isBlank()) {
            sql +=
                " AND (d.title ILIKE '%' || :fuzzyTitle || '%' OR similarity(d.title, :fuzzyTitle) > 0.25) ";
            params.put("fuzzyTitle", fuzzyTitle.trim());
        }

        return jdbcClient.sql(sql).params(params).query(Long.class).single();
    }

    public List<String> findDistinctCollections() {
        return jdbcClient.sql("SELECT DISTINCT name FROM collections ORDER BY name ASC")
            .query(String.class).list();
    }

    public List<String> findDistinctKinds() {
        return jdbcClient.sql("SELECT DISTINCT kind FROM documents ORDER BY kind ASC")
            .query(String.class).list();
    }

    public List<String> findDistinctKindsInCollection(String collection) {
        return jdbcClient.sql("""
            SELECT DISTINCT d.kind
            FROM documents d
            JOIN collections c ON d.collection_id = c.id
            WHERE LOWER(c.name) = LOWER(:collection)
            ORDER BY d.kind ASC
            """).param("collection", collection).query(String.class).list();
    }

    // ---------------------------------------------------------------------
    // API read projection (formerly IngestApiController raw SQL)
    // ---------------------------------------------------------------------

    public List<DocumentDetails> listByCollectionAndTag(String collection, String tag) {
        String sql =
            """
                SELECT d.id, d.collection_id, c.name AS collection, d.kind, d.title, d.metadata, d.ingestion_status, d.tags, d.created_at,
                       (SELECT count(*) FROM chunks WHERE document_id = d.id AND collection_id = d.collection_id) AS chunk_count,
                       ((d.metadata ->> 'kg_processed_at') IS NOT NULL
                           OR EXISTS (SELECT 1 FROM chunks WHERE document_id = d.id AND collection_id = d.collection_id AND kg_ok IS NOT NULL)) AS kg_processed,
                       (SELECT count(*) FROM chunks WHERE document_id = d.id AND collection_id = d.collection_id AND kg_ok IS FALSE) AS kg_failed_chunks
                FROM documents d
                JOIN collections c ON d.collection_id = c.id
                WHERE c.name = :collection
                  AND (:tag::text IS NULL OR :tag = ANY(d.tags))
                ORDER BY d.metadata->>'source_file' ASC
                """;
        return jdbcClient.sql(sql).param("collection", collection).param("tag", tag)
            .query(new DocumentDetailsMapper(objectMapper)).list();
    }

    public Optional<UUID> findIdByFile(String collection, String tag, String sourceFile) {
        return jdbcClient
            .sql(
                """
                    SELECT d.id FROM documents d
                    JOIN collections c ON d.collection_id = c.id
                    WHERE c.name = :collection
                      AND (:tag::text IS NULL OR :tag = ANY(d.tags))
                      AND (d.metadata->>'source_file' = :sourceFile OR d.metadata->>'source_file' LIKE :sourceFileLike)
                    LIMIT 1
                    """)
            .param("collection", collection).param("tag", tag).param("sourceFile", sourceFile)
            .param("sourceFileLike", "%" + sourceFile).query(UUID.class).optional();
    }

    public List<Map<String, Object>> findSectionSummaries(UUID documentId) {
        return jdbcClient
            .sql("SELECT heading_path, summary FROM section_summaries WHERE document_id = :docId")
            .param("docId", documentId).query((rs, rowNum) -> {
                Array arr = rs.getArray("heading_path");
                String[] path = arr != null ? (String[]) arr.getArray() : new String[0];
                Map<String, Object> map = new HashMap<>();
                map.put("path", Arrays.asList(path));
                map.put("pathStr", String.join(" > ", path));
                map.put("summary", rs.getString("summary"));
                map.put("depth", path.length);
                return map;
            }).list();
    }

    // ---------------------------------------------------------------------
    // Write side (merged from the former ManualDocumentRepository)
    // ---------------------------------------------------------------------

    private UUID resolveCollectionId(String collectionName, @Nullable List<String> tags) {
        // Ingest jobs are validated against existing collections at enqueue time, so a miss here
        // means the collection was deleted mid-job - requireId fails loudly instead of silently
        // recreating a bare collection.
        UUID collectionId = collectionIdResolver.requireId(collectionName);

        if (tags != null && !tags.isEmpty()) {
            for (String tag : tags) {
                if (tag != null && !tag.isBlank()) {
                    jdbcClient.sql("""
                        UPDATE collections
                        SET tags = array_append(tags, :tag)
                        WHERE id = :id AND NOT (:tag = ANY(tags))
                        """).param("tag", tag.trim()).param("id", collectionId).update();
                }
            }
        }
        return collectionId;
    }

    public UUID upsertManualDocument(String collectionName, String tag, String sourceFile,
        String kind, @Nullable String title) {
        return upsertManualDocument(collectionName, tag == null ? List.of() : List.of(tag),
            sourceFile, kind, title);
    }

    public UUID upsertManualDocument(String collectionName, List<String> tags, String sourceFile,
        String kind, @Nullable String title) {
        return upsert(collectionName, tags, sourceFile, kind, title, true);
    }

    /**
     * Upsert keyed strictly on {@code source_file}, for sources where the title is not an identity:
     * two Confluence pages routinely share a title (and a blank one normalises to "Untitled"), so
     * the title branch of {@link #upsertManualDocument} would collapse them onto a single row and
     * the last crawled page would destroy the previous one.
     */
    public UUID upsertDocumentBySourceFile(String collectionName, @Nullable String tag,
        String sourceFile, String kind, @Nullable String title) {
        return upsert(collectionName, tag == null ? List.of() : List.of(tag), sourceFile, kind,
            title, false);
    }

    private UUID upsert(String collectionName, List<String> tags, String sourceFile, String kind,
        @Nullable String title, boolean matchByTitle) {
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("Document kind cannot be null or blank");
        }
        String resolvedTitle = (title != null && !title.isBlank()) ? title.trim() : "Untitled";
        UUID collectionId = resolveCollectionId(collectionName, tags);

        String[] tagsArr = tags != null ? tags.stream().filter(t -> t != null && !t.isBlank())
            .map(String::trim).toArray(String[]::new) : new String[0];

        Optional<UUID> existing =
            findExisting(collectionId, kind, sourceFile, resolvedTitle, tagsArr, matchByTitle);
        if (existing.isPresent()) {
            return existing.get();
        }

        // SELECT-then-INSERT under READ COMMITTED: two concurrent page-import jobs for the same
        // page (a re-triggered crawl draining alongside the first) both see no row and both insert.
        // ux_documents_collection_kind_source_file_tags makes the second INSERT a no-op
        // instead of a second document with a second full chunk set; the loser then adopts the
        // winner's row, so both jobs write chunks into ONE document.
        Optional<UUID> inserted = jdbcClient.sql("""
            INSERT INTO documents (id, collection_id, kind, title, ingestion_status, metadata, tags)
            VALUES (:id, :collectionId, :kind, :title, 'pending',
                    jsonb_build_object('source_file', :sourceFile),
                    :tags)
            ON CONFLICT (collection_id, kind, (metadata->>'source_file'), kg_canonical_tags(tags))
                DO NOTHING
            RETURNING id
            """).param("id", UUID.randomUUID()).param("collectionId", collectionId)
            .param("kind", kind).param("title", resolvedTitle).param("sourceFile", sourceFile)
            .param("tags", tagsArr).query(UUID.class).optional();
        if (inserted.isPresent()) {
            return inserted.get();
        }

        return findExisting(collectionId, kind, sourceFile, resolvedTitle, tagsArr, matchByTitle)
            .orElseThrow(() -> new IllegalStateException(
                "Could not upsert document '" + sourceFile + "' in collection " + collectionId
                    + ": the row that won the insert race is already gone."));
    }

    /**
     * The identity lookup both upsert variants share. {@code matchByTitle} is the manuals rule (a
     * re-ingest under a new file name but the same title is the same document); the Confluence path
     * passes false, because two pages routinely share a title.
     */
    private Optional<UUID> findExisting(UUID collectionId, String kind, String sourceFile,
        String resolvedTitle, String[] tagsArr, boolean matchByTitle) {
        return jdbcClient.sql("""
            SELECT d.id FROM documents d
            WHERE d.collection_id = :collectionId
              AND d.kind = :kind
              AND d.tags @> :tags::text[] AND d.tags <@ :tags::text[]
              AND (d.metadata->>'source_file' = :sourceFile
                   OR (:matchByTitle::boolean AND d.title = :title))
            LIMIT 1
            """).param("collectionId", collectionId).param("kind", kind)
            .param("sourceFile", sourceFile).param("title", resolvedTitle).param("tags", tagsArr)
            .param("matchByTitle", matchByTitle).query(UUID.class).optional();
    }

    /**
     * Clears a document's derived content — its chunks AND its section summaries — before a
     * re-ingest rewrites them.
     *
     * <p>
     * The two are deleted together on purpose. A re-ingest reuses the {@code documents} row
     * (identity is collection + kind + tag set + source_file/title), so anything not deleted here
     * survives into the next revision. When only the chunks went, summaries from the previous run
     * stayed attached under the same {@code document_id} and {@code TocService} joined them back
     * onto the NEW chunks by normalised heading path — serving the old revision's prose through
     * {@code get_toc} and rendering it on the admin page as current, with nothing recording that it
     * predates the text. Deleting only chunks is now unrepresentable rather than merely
     * discouraged.
     *
     * <p>
     * Nothing recoverable is lost: {@code GeneralSummarizer} keys {@code summary_cache} on
     * {@code sha256} of the section body and that table is never pruned, so re-summarising an
     * unchanged section is a cache hit, and a changed one had to be re-summarised anyway.
     *
     * @return the number of chunks deleted (summaries are incidental to the caller)
     */
    public int deleteContentForDocument(UUID documentId) {
        jdbcClient.sql("DELETE FROM section_summaries WHERE document_id = :documentId")
            .param("documentId", documentId).update();
        return jdbcClient.sql("DELETE FROM chunks WHERE document_id = :documentId")
            .param("documentId", documentId).update();
    }

    public int deleteById(UUID id) {
        return jdbcClient.sql("DELETE FROM documents WHERE id = :id").param("id", id).update();
    }

    public int deleteByCollection(String collection) {
        return jdbcClient
            .sql(
                """
                    DELETE FROM documents
                    WHERE collection_id = (SELECT id FROM collections WHERE LOWER(name) = LOWER(:collection))
                    """)
            .param("collection", collection).update();
    }

    /**
     * Tag-aware removal for one collection: deletes only documents whose sole tag is {@code tag}
     * (cascading to chunks via FK) and detaches the tag from documents that are shared with other
     * tags. Documents that never carried the tag are untouched. Returns the number deleted.
     *
     * <p>
     * The detach SKIPS any row whose rewritten tag set would collide with a SIBLING document for
     * the same {@code (collection, kind, source_file)}: tags are part of
     * {@code ux_documents_collection_kind_source_file_tags} (V3), and two versions of one source
     * file separated only by tag is the documented install-kit shape, so {@code {9.3.1,10.0} →
     * {10.0}} next to an existing {@code {10.0}} row is an ordinary situation — and a 23505 here
     * would abort the entire lifecycle cascade as a 500. The skipped rows keep the tag and are
     * named in a WARN so the collision is resolvable rather than silent.
     */
    public int removeTagAndPurgeOrphans(UUID collectionId, String tag) {
        int deleted = jdbcClient.sql("""
            DELETE FROM documents
            WHERE collection_id = :collectionId AND :tag = ANY(tags) AND cardinality(tags) = 1
            """).param("collectionId", collectionId).param("tag", tag).update();
        jdbcClient.sql("""
            UPDATE documents d
            SET tags = array_remove(d.tags, :tag), updated_at = CURRENT_TIMESTAMP
            WHERE d.collection_id = :collectionId
              AND :tag = ANY(d.tags)
              AND NOT EXISTS (
                  SELECT 1 FROM documents s
                  WHERE s.collection_id = d.collection_id
                    AND s.kind = d.kind
                    AND s.metadata->>'source_file' = d.metadata->>'source_file'
                    AND s.id <> d.id
                    AND kg_canonical_tags(s.tags)
                          = kg_canonical_tags(array_remove(d.tags, :tag)))
            """).param("collectionId", collectionId).param("tag", tag).update();
        warnAboutTagRemovalsThatCouldNotBeApplied(collectionId, tag);
        return deleted;
    }

    /**
     * Anything still carrying the tag after the detach was skipped by the collision guard.
     *
     * <p>
     * The COUNT and the SAMPLE are deliberately separate numbers. Several documents can be blocked
     * for ONE source file — two kit versions of {@code install.md} separated only by tag is the
     * documented shape, and each extra version adds another blocked row for the same file — so the
     * size of a DISTINCT, {@code LIMIT 20}-capped source-file list is neither a document count nor
     * an uncapped one. Reporting it as "N document(s)" understated a 100-document backlog spanning
     * 50 files as "20 document(s)". The list stays purely illustrative; the counts are aggregates
     * over the full set, in the same round-trip.
     */
    private void warnAboutTagRemovalsThatCouldNotBeApplied(UUID collectionId, String tag) {
        BlockedTagRemoval blocked = jdbcClient.sql("""
            WITH blocked AS (
                SELECT coalesce(metadata->>'source_file', title) AS source_file
                FROM documents
                WHERE collection_id = :collectionId AND :tag = ANY(tags)
            )
            SELECT (SELECT count(*) FROM blocked) AS document_count,
                   (SELECT count(DISTINCT source_file) FROM blocked) AS source_file_count,
                   (SELECT array_agg(source_file ORDER BY source_file)
                      FROM (SELECT DISTINCT source_file FROM blocked
                            ORDER BY source_file LIMIT 20) sample) AS sample_source_files
            """).param("collectionId", collectionId).param("tag", tag).query((rs, rowNum) -> {
            Array sample = rs.getArray("sample_source_files");
            return new BlockedTagRemoval(rs.getLong("document_count"),
                rs.getLong("source_file_count"),
                sample == null ? List.of() : List.of((String[]) sample.getArray()));
        }).single();

        if (blocked.documentCount() > 0) {
            log.warn(
                "Tag '{}' could not be detached from {} document(s) across {} source file(s) in"
                    + " collection {}: another document for the same source file already occupies"
                    + " the resulting tag set. Merge or delete the duplicates to finish the"
                    + " removal. Affected source files ({} of {} shown): {}",
                tag, blocked.documentCount(), blocked.sourceFileCount(), collectionId,
                blocked.sampleSourceFiles().size(), blocked.sourceFileCount(),
                blocked.sampleSourceFiles());
        }
    }

    /** Counts over ALL blocked rows, plus an illustrative sample of at most 20 source files. */
    private record BlockedTagRemoval(long documentCount, long sourceFileCount,
        List<String> sampleSourceFiles) {}

    public void updateIngestionStatus(UUID documentId, String status) {
        DocumentIngestionStatus.fromValue(status);
        jdbcClient.sql(
            "UPDATE documents SET ingestion_status = :status, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
            .param("status", status).param("id", documentId).update();
    }

    public void markKgProcessed(UUID documentId, String processedAt, int entities, int edges) {
        jdbcClient.sql("""
            UPDATE documents
            SET metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object(
                    'kg_processed_at', :processedAt,
                    'kg_entities', :entities,
                    'kg_edges', :edges),
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :id
            """).param("processedAt", processedAt).param("entities", entities).param("edges", edges)
            .param("id", documentId).update();
    }

    public void insertChunks(UUID documentId, String collection, String tag, String sourceFile,
        String kind, List<ChunkInsert> chunks) {
        insertChunks(documentId, collection, tag == null ? List.of() : List.of(tag), sourceFile,
            kind, chunks);
    }

    public void insertChunks(UUID documentId, String collection, List<String> tags,
        String sourceFile, String kind, List<ChunkInsert> chunks) {
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("Document kind cannot be null or blank");
        }
        UUID collectionId = resolveCollectionId(collection, tags);

        String[] tagsArr = tags != null ? tags.stream().filter(t -> t != null && !t.isBlank())
            .map(String::trim).toArray(String[]::new) : new String[0];

        String sql = """
            INSERT INTO chunks
                (id, document_id, text, embedding, heading_path, heading, depth, sort_order,
                 collection_id, tags)
            VALUES (?, ?, ?, ?::vector, ?, ?, ?, ?, ?, ?)
            """;

        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                ChunkInsert c = chunks.get(i);
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, documentId);
                ps.setString(3, c.text());
                if (c.embedding() != null) {
                    ps.setString(4, VectorUtils.toVectorString(c.embedding()));
                } else {
                    ps.setNull(4, Types.OTHER);
                }
                ps.setArray(5, ps.getConnection().createArrayOf("text", c.headingPath().toArray()));
                ps.setString(6, c.heading());
                ps.setInt(7, c.depth());
                ps.setInt(8, c.sortOrder());
                ps.setObject(9, collectionId);
                ps.setArray(10, ps.getConnection().createArrayOf("text", tagsArr));
            }

            @Override
            public int getBatchSize() {
                return chunks.size();
            }
        });
    }

    public void markChunkKgStatuses(List<ChunkKgStatus> statuses) {
        if (statuses.isEmpty()) {
            return;
        }
        String sql =
            "UPDATE chunks SET kg_ok = ?, kg_model = ?, kg_extracted_at = now() WHERE id = ?";
        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                ChunkKgStatus s = statuses.get(i);
                ps.setBoolean(1, s.ok());
                ps.setString(2, s.model());
                ps.setObject(3, s.chunkId());
            }

            @Override
            public int getBatchSize() {
                return statuses.size();
            }
        });
    }

    public void updateMetadataKey(UUID documentId, String key, Object value) {
        jdbcClient.sql("""
            UPDATE documents
            SET metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object(:key, :value),
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :id
            """).param("key", key).param("value", value).param("id", documentId).update();
    }

    public record DocumentMetadataAndStatus(String ingestionStatus,
        @Nullable String pageVersionStr) {}

    public Optional<DocumentMetadataAndStatus> getMetadataAndStatus(String collection, String tag,
        String sourceFile, String kind) {
        return jdbcClient.sql("""
            SELECT d.ingestion_status,
                   d.metadata->>'confluence_page_version' AS page_version
            FROM documents d
            JOIN collections c ON d.collection_id = c.id
            WHERE d.metadata->>'source_file' = :sourceFile
              AND d.kind = :kind
              AND LOWER(c.name) = LOWER(:collection)
              AND (:tag::text IS NULL OR :tag = ANY(d.tags))
            LIMIT 1
            """).param("sourceFile", sourceFile).param("kind", kind).param("collection", collection)
            .param("tag", tag).query((rs, rowNum) -> {
                String status = rs.getString("ingestion_status");
                String versionStr = rs.getString("page_version");
                return new DocumentMetadataAndStatus(status, versionStr);
            }).optional();
    }
}
