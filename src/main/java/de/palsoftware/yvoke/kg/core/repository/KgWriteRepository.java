package de.palsoftware.yvoke.kg.core.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.shared.db.CollectionIdResolver;
import de.palsoftware.yvoke.shared.db.VectorUtils;
import jakarta.annotation.Nullable;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Write-side of the knowledge graph: entity/relationship upserts (single and batched) plus
 * collection- and tag-scoped graph deletions used by manuals ingest and KG injection. Split out of
 * the former monolithic {@code KgRepository} (MNT-08); the read/query side lives in
 * {@link KgGraphReadRepository}.
 */
@Repository
public class KgWriteRepository {

    private static final Logger log = LoggerFactory.getLogger(KgWriteRepository.class);

    private final JdbcClient jdbcClient;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final CollectionIdResolver collectionIdResolver;

    public KgWriteRepository(JdbcClient jdbcClient, JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper, CollectionIdResolver collectionIdResolver) {
        this.jdbcClient = jdbcClient;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.collectionIdResolver = collectionIdResolver;
    }

    // --- Write operations (manuals ingest / KG injection) ---

    public UUID resolveCollectionId(String collectionName, @Nullable String tag) {
        return resolveCollectionId(collectionName, tag == null ? List.of() : List.of(tag));
    }

    public UUID resolveCollectionId(String collectionName, @Nullable List<String> tags) {
        // KG writes target collections that were validated at job-enqueue time; requireId fails
        // loudly if the collection was deleted mid-job rather than silently recreating a bare one.
        UUID id = collectionIdResolver.requireId(collectionName);

        if (tags != null && !tags.isEmpty()) {
            for (String tag : tags) {
                if (tag != null && !tag.isBlank()) {
                    jdbcClient.sql("""
                        UPDATE collections
                        SET tags = array_append(tags, :tag)
                        WHERE id = :id AND NOT (:tag = ANY(tags))
                        """).param("tag", tag.trim()).param("id", id).update();
                }
            }
        }
        return id;
    }

    public UUID upsertEntity(String collection, String tag, String name, String kind,
        @Nullable String description) {
        requireKind(kind, name);
        UUID collectionId = resolveCollectionId(collection, tag);
        // Probe the FULL identity — (collection, kind, lower(name), tag scope). Matching on the
        // name alone found a different kind's row, and after identity became tag-scoped it would
        // also find a different product version's row and then array_append this tag onto it:
        // precisely the merge that made one version's entities point at the other's documents.
        Optional<UUID> existing = jdbcClient.sql("""
            SELECT id FROM entities
            WHERE collection_id = :collectionId
              AND lower(coalesce(kind, '')) = lower(coalesce(:kind, ''))
              AND lower(name) = lower(:name)
              AND kg_canonical_tags(tags) = kg_canonical_tags(
                    CASE WHEN CAST(:tag AS text) IS NULL THEN '{}'::text[]
                         ELSE ARRAY[CAST(:tag AS text)] END)
            ORDER BY id
            LIMIT 1
            """).param("collectionId", collectionId).param("name", name).param("kind", kind)
            .param("tag", (tag != null && !tag.isBlank()) ? tag.trim() : null).query(UUID.class)
            .optional();
        if (existing.isPresent()) {
            UUID id = existing.get();
            // No tag append: the probe matched this exact tag scope, so the row already carries it.
            return id;
        }

        UUID id = UUID.randomUUID();
        jdbcClient
            .sql(
                """
                    INSERT INTO entities (id, collection_id, name, kind, description, tags)
                    VALUES (:id, :collectionId, :name, :kind, :description, CASE WHEN CAST(:tag AS text) IS NULL THEN '{}'::text[] ELSE ARRAY[CAST(:tag AS text)] END)
                    """)
            .param("id", id).param("collectionId", collectionId).param("name", name)
            .param("kind", kind).param("description", description)
            .param("tag", (tag != null && !tag.isBlank()) ? tag.trim() : null).update();
        return id;
    }

    public UUID upsertEntity(String collection, String tag, String name, String kind,
        @Nullable String description, @Nullable float[] embedding,
        @Nullable Map<String, Object> metadata) {
        requireKind(kind, name);
        UUID collectionId = resolveCollectionId(collection, tag);
        // Probe the FULL identity — (collection, kind, lower(name), tag scope). Matching on the
        // name alone found a different kind's row, and after identity became tag-scoped it would
        // also find a different product version's row and then array_append this tag onto it:
        // precisely the merge that made one version's entities point at the other's documents.
        Optional<UUID> existing = jdbcClient.sql("""
            SELECT id FROM entities
            WHERE collection_id = :collectionId
              AND lower(coalesce(kind, '')) = lower(coalesce(:kind, ''))
              AND lower(name) = lower(:name)
              AND kg_canonical_tags(tags) = kg_canonical_tags(
                    CASE WHEN CAST(:tag AS text) IS NULL THEN '{}'::text[]
                         ELSE ARRAY[CAST(:tag AS text)] END)
            ORDER BY id
            LIMIT 1
            """).param("collectionId", collectionId).param("name", name).param("kind", kind)
            .param("tag", (tag != null && !tag.isBlank()) ? tag.trim() : null).query(UUID.class)
            .optional();

        String metadataJson = null;
        if (metadata != null && !metadata.isEmpty()) {
            try {
                metadataJson = objectMapper.writeValueAsString(metadata);
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize entity metadata: {}", metadata, e);
            }
        }

        String embeddingStr = embedding != null ? VectorUtils.toVectorString(embedding) : null;

        if (existing.isPresent()) {
            UUID id = existing.get();
            jdbcClient.sql("""
                UPDATE entities
                SET kind = COALESCE(:kind, kind),
                    description = COALESCE(:description, description),
                    embedding = COALESCE(:embedding::vector, embedding),
                    metadata = COALESCE(:metadata::jsonb, metadata)
                WHERE id = :id
                """).param("id", id).param("kind", kind).param("description", description)
                .param("embedding", embeddingStr).param("metadata", metadataJson).update();

            // No tag append: the probe matched this exact tag scope, so the row already carries it.
            return id;
        }

        UUID id = UUID.randomUUID();
        jdbcClient
            .sql(
                """
                    INSERT INTO entities (id, collection_id, name, kind, description, embedding, metadata, tags)
                    VALUES (:id, :collectionId, :name, :kind, :description, :embedding::vector, :metadata::jsonb, CASE WHEN CAST(:tag AS text) IS NULL THEN '{}'::text[] ELSE ARRAY[CAST(:tag AS text)] END)
                    """)
            .param("id", id).param("collectionId", collectionId).param("name", name)
            .param("kind", kind).param("description", description).param("embedding", embeddingStr)
            .param("metadata", metadataJson)
            .param("tag", (tag != null && !tag.isBlank()) ? tag.trim() : null).update();
        return id;
    }

    public boolean upsertRelationship(String collection, String tag, String subject,
        String predicate, String object, @Nullable UUID subjectId, @Nullable UUID objectId,
        @Nullable String description) {
        return upsertRelationship(collection, tag, subject, predicate, object, subjectId, objectId,
            description, null);
    }

    public boolean upsertRelationship(String collection, String tag, String subject,
        String predicate, String object, @Nullable UUID subjectId, @Nullable UUID objectId,
        @Nullable String description, @Nullable Map<String, Object> metadata) {
        UUID collectionId = resolveCollectionId(collection, tag);
        // Scoped to this tag: the two versions' copies of an edge are textually identical, so a
        // tag-blind probe merged them onto one row carrying both tags — the edge-side twin of the
        // entity merge.
        Optional<UUID> existing = jdbcClient.sql("""
            SELECT id FROM relationships
            WHERE collection_id = :collectionId
              AND subject = :subject AND predicate = :predicate AND object = :object
              AND kg_canonical_tags(tags) = kg_canonical_tags(
                    CASE WHEN CAST(:tag AS text) IS NULL THEN '{}'::text[]
                         ELSE ARRAY[CAST(:tag AS text)] END)
            ORDER BY id
            LIMIT 1
            """).param("collectionId", collectionId).param("subject", subject)
            .param("predicate", predicate).param("object", object)
            .param("tag", (tag != null && !tag.isBlank()) ? tag.trim() : null).query(UUID.class)
            .optional();

        if (existing.isPresent()) {
            // Already in this scope, tag included — nothing to append.
            return false;
        }

        String metadataJson = null;
        if (metadata != null && !metadata.isEmpty()) {
            try {
                metadataJson = objectMapper.writeValueAsString(metadata);
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize relationship metadata: {}", metadata, e);
            }
        }

        jdbcClient
            .sql(
                """
                    INSERT INTO relationships
                        (id, collection_id, subject, predicate, object, subject_id, object_id, description, metadata, tags)
                    VALUES (:id, :collectionId, :subject, :predicate, :object, :subjectId, :objectId, :description, :metadata::jsonb, CASE WHEN CAST(:tag AS text) IS NULL THEN '{}'::text[] ELSE ARRAY[CAST(:tag AS text)] END)
                    """)
            .param("id", UUID.randomUUID()).param("collectionId", collectionId)
            .param("subject", subject).param("predicate", predicate).param("object", object)
            .param("subjectId", subjectId).param("objectId", objectId)
            .param("description", description).param("metadata", metadataJson)
            .param("tag", (tag != null && !tag.isBlank()) ? tag.trim() : null).update();
        return true;
    }

    // --- Batch write operations (avoid per-row N+1 during ingestion) ---

    public record EntityUpsert(
        String name,
        @Nullable String kind,
        @Nullable String description,
        @Nullable float[] embedding,
        @Nullable Map<String, Object> metadata) {
        public EntityUpsert(String name, @Nullable String kind, @Nullable String description) {
            this(name, kind, description, null, null);
        }
    }

    public record RelationshipUpsert(String subject, String predicate, String object,
        @Nullable UUID subjectId, @Nullable UUID objectId, @Nullable String description,
        @Nullable Map<String, Object> metadata) {}

    /**
     * The tag SET of one write, canonical: trimmed, non-blank, distinct, sorted. Identity is the
     * set, not the array, so the stored order must not be able to fork one logical scope into two.
     * The database indexes {@code kg_canonical_tags(tags)} (V2) rather than the raw column, so this
     * only keeps what we store tidy — correctness does not depend on Java and SQL sorting alike.
     */
    static String[] canonicalTags(@Nullable List<String> tags) {
        if (tags == null) {
            return new String[0];
        }
        return tags.stream().filter(t -> t != null && !t.isBlank()).map(String::trim).distinct()
            .sorted().toArray(String[]::new);
    }

    /**
     * Batch find-or-create entities keyed by kind-aware, TAG-SCOPED identity. Entities are unique
     * within a collection by {@code (coalesce(kind,''), lower(name), kg_canonical_tags(tags))}, so
     * a same-named entity of a different kind (table vs process vs module) is its own row, and so
     * is the same entity ingested under a different tag set — two versions of one product in one
     * collection are two rows, each with its own {@code metadata.document_id}.
     *
     * <p>
     * The returned map is keyed by {@code lower(kind) + ":" + lower(name)} (blank/null kind → the
     * empty string, i.e. a leading colon) for every distinct input identity — callers must resolve
     * by that exact key. The tag deliberately stays OUT of that key: one call is one tag set, so
     * the key is already unambiguous within it, and callers parse the two-part form positionally.
     */
    public Map<String, UUID> upsertEntitiesBatch(String collection, List<String> tags,
        List<EntityUpsert> entities) {
        LinkedHashMap<String, EntityUpsert> byKey = new LinkedHashMap<>();
        for (EntityUpsert e : entities) {
            requireKind(e.kind(), e.name());
            byKey.putIfAbsent(entityKey(e.kind(), e.name()), e);
        }
        Map<String, UUID> result = new HashMap<>();
        if (byKey.isEmpty()) {
            return result;
        }
        UUID collectionId = resolveCollectionId(collection, tags);
        String[] tagSet = canonicalTags(tags);

        // 1. Resolve existing ids in one query. Match on name AND kind AND this write's tag scope,
        // so a different-kind homonym and a different version both keep their own rows.
        String[] lowerNames = byKey.values().stream().map(e -> e.name().toLowerCase(Locale.ROOT))
            .distinct().toArray(String[]::new);
        resolveIdsByKey(collectionId, lowerNames, tagSet, byKey.keySet(), result);

        // Refresh what a re-ingest recomputes. The tags need no appending any more — a resolved row
        // matched on this exact scope, so it already carries them. What DOES need writing is
        // metadata: CustomIngestService deletes and recreates every document on re-ingest, with a
        // new uuid, so an entity resolved from a previous run points at a document that no longer
        // exists. Nothing here ever updated an existing row, which is how F4 stayed invisible.
        refreshResolved(byKey, result);

        // 2. Batch-insert the entities that are not yet present.
        List<Map.Entry<String, EntityUpsert>> toInsert = new ArrayList<>();
        for (Map.Entry<String, EntityUpsert> entry : byKey.entrySet()) {
            if (!result.containsKey(entry.getKey())) {
                UUID id = UUID.randomUUID();
                result.put(entry.getKey(), id);
                toInsert.add(entry);
            }
        }
        if (!toInsert.isEmpty()) {
            // ON CONFLICT matches ux_entities_collection_kind_lower_name_tags (V2) exactly. KG
            // extraction
            // runs app.ingest.kg.concurrency workers over documents of one collection, so a name
            // recurring across documents means another worker may have inserted this identity
            // between our SELECT above and this INSERT. Skipping the conflict keeps the job alive;
            // step 3 then repairs the ids we handed out.
            String insertSql =
                "INSERT INTO entities (id, collection_id, name, kind, description, embedding, metadata, tags) "
                    + "VALUES (?, ?, ?, ?, ?, ?::vector, ?::jsonb, ?) "
                    + "ON CONFLICT (collection_id, coalesce(kind, ''), lower(name), kg_canonical_tags(tags)) DO NOTHING";
            jdbcTemplate.batchUpdate(insertSql, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int idx) throws SQLException {
                    Map.Entry<String, EntityUpsert> entry = toInsert.get(idx);
                    EntityUpsert e = entry.getValue();
                    ps.setObject(1, result.get(entry.getKey()));
                    ps.setObject(2, collectionId);
                    ps.setString(3, e.name());
                    ps.setString(4, e.kind());
                    ps.setString(5, e.description());
                    ps.setString(6,
                        e.embedding() != null ? VectorUtils.toVectorString(e.embedding()) : null);
                    ps.setString(7, toJson(e.metadata()));
                    ps.setArray(8, ps.getConnection().createArrayOf("text", tagSet));
                }

                @Override
                public int getBatchSize() {
                    return toInsert.size();
                }
            });

            // 3. Repair the ids of any identity whose insert was skipped as a conflict: the id we
            // optimistically generated was never persisted, and callers use these ids as
            // relationships.subject_id/object_id — handing back a phantom id would surface as an FK
            // violation. Re-resolve against the rows that actually exist and give the surviving row
            // this batch's tags, so a document that lost the race still contributes its version
            // tag.
            Map<String, UUID> persisted = new HashMap<>();
            List<String> attemptedKeys =
                toInsert.stream().map(Map.Entry::getKey).collect(Collectors.toList());
            resolveIdsByKey(collectionId, lowerNames, tagSet, attemptedKeys, persisted);
            List<String> lostRace = new ArrayList<>();
            for (Map.Entry<String, UUID> entry : persisted.entrySet()) {
                if (!entry.getValue().equals(result.get(entry.getKey()))) {
                    result.put(entry.getKey(), entry.getValue());
                    lostRace.add(entry.getKey());
                }
            }
            if (!lostRace.isEmpty()) {
                log.debug("{} entity identities lost an insert race and were re-resolved",
                    lostRace.size());
                // The winner of the race inserted the same scope, so its tags are already right;
                // what it may lack is this batch's metadata.
                Map<String, EntityUpsert> raced = new LinkedHashMap<>();
                for (String key : lostRace) {
                    raced.put(key, byKey.get(key));
                }
                refreshResolved(raced, result);
            }
        }
        return result;
    }

    /**
     * Resolves {@code entities} ids for the given lower-cased names into {@code into}, keyed by
     * {@link #entityKey}. Restricted to rows whose tag scope is exactly {@code tagSet}: without
     * that, one version's ingest resolves to the other version's row and its own document link is
     * discarded. Only keys present in {@code wantedKeys} are collected, so over-fetching by name
     * alone never leaks a different-kind homonym into the result.
     */
    private void resolveIdsByKey(UUID collectionId, String[] lowerNames, String[] tagSet,
        Collection<String> wantedKeys, Map<String, UUID> into) {
        Set<String> wanted = new HashSet<>(wantedKeys);
        String selectSql = "SELECT lower(coalesce(kind, '')) AS lkind, lower(name) AS lname, id "
            + "FROM entities WHERE collection_id = ? AND lower(name) = ANY(?) "
            + "AND kg_canonical_tags(tags) = kg_canonical_tags(?)";
        jdbcTemplate.query(con -> {
            PreparedStatement ps = con.prepareStatement(selectSql);
            ps.setObject(1, collectionId);
            ps.setArray(2, con.createArrayOf("text", lowerNames));
            ps.setArray(3, con.createArrayOf("text", tagSet));
            return ps;
        }, rs -> {
            String key = rs.getString("lkind") + ":" + rs.getString("lname");
            if (wanted.contains(key)) {
                into.put(key, (UUID) rs.getObject("id"));
            }
        });
    }

    /**
     * Writes this batch's payload onto rows that already existed in this exact tag scope.
     *
     * <p>
     * A re-ingest recreates every document with a new uuid, so an entity carried over from a
     * previous run points at a document that has been deleted — the row survives, its
     * {@code metadata.document_id} does not. COALESCE keeps the LLM extraction path (which upserts
     * entities with a null metadata map) from wiping the document link the custom path wrote.
     */
    private void refreshResolved(Map<String, EntityUpsert> byKey, Map<String, UUID> resolved) {
        List<Map.Entry<String, UUID>> targets =
            resolved.entrySet().stream().filter(e -> byKey.containsKey(e.getKey())).toList();
        if (targets.isEmpty()) {
            return;
        }
        String updateSql = "UPDATE entities SET " + "metadata = coalesce(?::jsonb, metadata), "
            + "description = coalesce(?, description), "
            + "embedding = coalesce(?::vector, embedding) WHERE id = ?";
        jdbcTemplate.batchUpdate(updateSql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int idx) throws SQLException {
                Map.Entry<String, UUID> target = targets.get(idx);
                EntityUpsert e = byKey.get(target.getKey());
                ps.setString(1, toJson(e.metadata()));
                ps.setString(2, e.description());
                ps.setString(3,
                    e.embedding() != null ? VectorUtils.toVectorString(e.embedding()) : null);
                ps.setObject(4, target.getValue());
            }

            @Override
            public int getBatchSize() {
                return targets.size();
            }
        });
    }

    /**
     * Batch find-or-create relationships keyed by ENDPOINT ID
     * ({@code subjectId|lower(predicate)|objectId}). Keying by name text collapsed edges whose
     * endpoints merely share a name — {@code notification:Person ─references_table→ table:Person}
     * and {@code object_methods:Person ─references_table→ table:Person} are different edges, and
     * the second was silently dropped. An upsert missing an endpoint id (or a predicate) has no
     * identity at all: it is skipped and counted in a WARN rather than keyed by an ambiguous name.
     */
    public int insertRelationshipsBatch(String collection, List<String> tags,
        List<RelationshipUpsert> rels) {
        if (rels == null || rels.isEmpty()) {
            return 0;
        }
        UUID collectionId = resolveCollectionId(collection, tags);
        LinkedHashMap<String, RelationshipUpsert> byKey = new LinkedHashMap<>();
        int unidentifiable = 0;
        for (RelationshipUpsert r : rels) {
            if (r.subjectId() == null || r.objectId() == null || r.predicate() == null
                || r.predicate().isBlank()) {
                unidentifiable++;
                continue;
            }
            byKey.putIfAbsent(relKey(r.subjectId(), r.predicate(), r.objectId()), r);
        }
        if (unidentifiable > 0) {
            log.warn(
                "Skipped {} relationship(s) in collection '{}' with an unresolved endpoint id or a blank predicate",
                unidentifiable, collection);
        }
        if (byKey.isEmpty()) {
            return 0;
        }

        // Pre-fetch the existing edges of this batch's subjects (over-fetch by subject id), keeping
        // ONLY the rows whose exact key is in this batch AND whose tag scope is this batch's — the
        // remainder must never be touched.
        String[] tagSet = canonicalTags(tags);
        Map<String, UUID> existingMap = new HashMap<>();
        UUID[] subjectIds = byKey.values().stream().map(RelationshipUpsert::subjectId).distinct()
            .toArray(UUID[]::new);
        String selectSql = "SELECT id, subject_id, predicate, object_id FROM relationships "
            + "WHERE collection_id = ? AND subject_id = ANY(?) "
            + "AND kg_canonical_tags(tags) = kg_canonical_tags(?)";
        jdbcTemplate.query(con -> {
            PreparedStatement ps = con.prepareStatement(selectSql);
            ps.setObject(1, collectionId);
            ps.setArray(2, con.createArrayOf("uuid", subjectIds));
            ps.setArray(3, con.createArrayOf("text", tagSet));
            return ps;
        }, rs -> {
            UUID objectId = (UUID) rs.getObject("object_id");
            if (objectId == null) {
                return;
            }
            String key =
                relKey((UUID) rs.getObject("subject_id"), rs.getString("predicate"), objectId);
            if (byKey.containsKey(key)) {
                existingMap.put(key, (UUID) rs.getObject("id"));
            }
        });

        // No tag-append here any more. The prefetch above only returns edges already in THIS tag
        // scope, so they carry these tags by construction; and an edge in a different scope is a
        // different edge now — its endpoints are that version's entity rows.

        List<RelationshipUpsert> toInsert = new ArrayList<>();
        for (Map.Entry<String, RelationshipUpsert> entry : byKey.entrySet()) {
            if (!existingMap.containsKey(entry.getKey())) {
                toInsert.add(entry.getValue());
            }
        }
        if (toInsert.isEmpty()) {
            return 0;
        }
        String insertSql = "INSERT INTO relationships "
            + "(id, collection_id, subject, predicate, object, subject_id, object_id, description, metadata, tags) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)";
        jdbcTemplate.batchUpdate(insertSql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int idx) throws SQLException {
                RelationshipUpsert r = toInsert.get(idx);
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, collectionId);
                ps.setString(3, r.subject());
                ps.setString(4, r.predicate());
                ps.setString(5, r.object());
                ps.setObject(6, r.subjectId());
                ps.setObject(7, r.objectId());
                ps.setString(8, r.description());
                ps.setString(9, toJson(r.metadata()));
                ps.setArray(10, ps.getConnection().createArrayOf("text", tagSet));
            }

            @Override
            public int getBatchSize() {
                return toInsert.size();
            }
        });
        return toInsert.size();
    }

    /** Edge identity: the two endpoint ids plus the predicate — never the (ambiguous) name text. */
    private static String relKey(UUID subjectId, String predicate, UUID objectId) {
        return subjectId + "|" + predicate.toLowerCase(Locale.ROOT) + "|" + objectId;
    }

    /**
     * A kind is mandatory for every entity ({@code entities.kind} is NOT NULL since V3): identity
     * is {@code (collection, kind, lower(name))}, so a kind-less row splits one logical entity in
     * two — a kinded row plus a kind-NULL one the kind-aware consolidator then refuses to merge.
     */
    private static void requireKind(@Nullable String kind, String name) {
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException(
                "Entity kind is required (entities.kind is NOT NULL); none given for entity '"
                    + name + "'");
        }
    }

    /** Kind-aware entity identity key: {@code lower(kind):lower(name)}, blank/null kind → "". */
    public static String entityKey(@Nullable String kind, String name) {
        String lkind = (kind == null || kind.isBlank()) ? "" : kind.toLowerCase(Locale.ROOT);
        return lkind + ":" + name.toLowerCase(Locale.ROOT);
    }

    @Nullable
    private String toJson(@Nullable Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize KG metadata: {}", metadata, e);
            return null;
        }
    }

    public void deleteCollectionGraph(String collection) {
        jdbcClient
            .sql(
                """
                    DELETE FROM relationships
                    WHERE collection_id = (SELECT id FROM collections WHERE LOWER(name) = LOWER(:collection))
                    """)
            .param("collection", collection).update();
        jdbcClient
            .sql(
                """
                    DELETE FROM entities
                    WHERE collection_id = (SELECT id FROM collections WHERE LOWER(name) = LOWER(:collection))
                    """)
            .param("collection", collection).update();
    }

    public void deleteTagGraph(String collection, @Nullable String tag) {
        UUID collectionId = resolveCollectionId(collection, tag);
        if (tag != null && !tag.isBlank()) {
            String trimmedTag = tag.trim();
            // Multi-tagged content survives, shrunk to the remaining tags — the same contract
            // documents and json_objects have. But the tag set is now part of a graph row's
            // identity (V2), so shrinking one can collide with a row that already occupies the
            // remaining scope: dropping '9.3.1' from a '{9.3.1,10.0}' row makes it '{10.0}', which
            // the real 10.0 row already is. Retire the shrinking row in that case instead of
            // failing the whole tag removal on a unique-index violation.
            jdbcClient.sql("""
                DELETE FROM entities e
                WHERE e.collection_id = :collectionId AND :tag = ANY(e.tags)
                  AND EXISTS (
                      SELECT 1 FROM entities o
                       WHERE o.collection_id = e.collection_id
                         AND o.id <> e.id
                         AND coalesce(o.kind, '') = coalesce(e.kind, '')
                         AND lower(o.name) = lower(e.name)
                         AND kg_canonical_tags(o.tags)
                             = kg_canonical_tags(array_remove(e.tags, :tag)))
                """).param("collectionId", collectionId).param("tag", trimmedTag).update();

            // Remove tag from relationships
            jdbcClient.sql("""
                UPDATE relationships
                SET tags = array_remove(tags, :tag)
                WHERE collection_id = :collectionId AND :tag = ANY(tags)
                """).param("collectionId", collectionId).param("tag", trimmedTag).update();
            // Delete relationships with no tags left
            jdbcClient.sql("""
                DELETE FROM relationships
                WHERE collection_id = :collectionId AND cardinality(tags) = 0
                """).param("collectionId", collectionId).update();

            // Remove tag from entities
            jdbcClient.sql("""
                UPDATE entities
                SET tags = array_remove(tags, :tag)
                WHERE collection_id = :collectionId AND :tag = ANY(tags)
                """).param("collectionId", collectionId).param("tag", trimmedTag).update();
            // Delete entities with no tags left
            jdbcClient.sql("""
                DELETE FROM entities
                WHERE collection_id = :collectionId AND cardinality(tags) = 0
                """).param("collectionId", collectionId).update();
        } else {
            // Delete all for the collection
            jdbcClient.sql("""
                DELETE FROM relationships
                WHERE collection_id = :collectionId
                """).param("collectionId", collectionId).update();
            jdbcClient.sql("""
                DELETE FROM entities
                WHERE collection_id = :collectionId
                """).param("collectionId", collectionId).update();
        }
    }
}
