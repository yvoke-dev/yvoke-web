package de.palsoftware.yvoke.kg.core.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.kg.core.model.KgCall;
import de.palsoftware.yvoke.kg.core.model.KgEntity;
import de.palsoftware.yvoke.kg.core.model.KgEntityKindEdgeCount;
import de.palsoftware.yvoke.kg.core.model.KgNeighborEdges;
import de.palsoftware.yvoke.kg.core.model.KgNeighborEdges.Direction;
import de.palsoftware.yvoke.kg.core.model.KgNeighborhood;
import de.palsoftware.yvoke.kg.core.model.KgRelationship;
import de.palsoftware.yvoke.kg.core.model.KgScope;
import de.palsoftware.yvoke.kg.core.model.KgWalk;
import de.palsoftware.yvoke.shared.db.CollectionIdResolver;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read/query-side of the knowledge graph: scope listing, entity/relationship browse and search,
 * neighborhoods, call graphs, and FK walks. Split out of the former monolithic {@code KgRepository}
 * (MNT-08); the write side lives in {@link KgWriteRepository}.
 */
@Repository
public class KgGraphReadRepository {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final CollectionIdResolver collectionIdResolver;

    public KgGraphReadRepository(JdbcClient jdbcClient, ObjectMapper objectMapper,
        CollectionIdResolver collectionIdResolver) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.collectionIdResolver = collectionIdResolver;
    }

    // --- Constants & helpers ---

    /**
     * Hard ceiling on rows returned by every listing/search query here — a larger {@code limit} is
     * silently clamped to it. Public because callers must clamp to the same value to know whether a
     * full page means "that is all there is" or "there is more"; testing {@code size() == limit}
     * against an unclamped request misses the truncation entirely.
     */
    public static final int MAX_LIMIT = 200;
    private static final Set<String> ALLOWED_ALIASES = Set.of("r", "e", "c", "d");

    static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    // --- Tag clause helpers ---

    private static String tc(@Nullable String tag) {
        return tag != null ? " AND :tag = ANY(tags) " : " ";
    }

    static String tc(@Nullable String tag, String alias) {
        if (!ALLOWED_ALIASES.contains(alias)) {
            throw new IllegalArgumentException("Invalid SQL alias: " + alias);
        }
        return tag != null ? " AND :tag = ANY(" + alias + ".tags) " : " ";
    }

    public UUID findCollectionId(String collectionName) {
        return collectionIdResolver.findId(collectionName).orElse(null);
    }

    public int getEntityCount(String collection, @Nullable String tag) {
        UUID collectionId = findCollectionId(collection);
        return jdbcClient.sql("""
            SELECT COUNT(*) FROM entities
            WHERE collection_id = :collectionId
            """ + tc(tag)).param("collectionId", collectionId).param("tag", tag)
            .query(Integer.class).single();
    }

    public int getRelationshipCount(String collection, @Nullable String tag) {
        UUID collectionId = findCollectionId(collection);
        return jdbcClient.sql("""
            SELECT COUNT(*) FROM relationships
            WHERE collection_id = :collectionId
            """ + tc(tag)).param("collectionId", collectionId).param("tag", tag)
            .query(Integer.class).single();
    }

    public List<KgScope> listKgScopes() {
        String sql = """
            WITH scopes AS (
                SELECT collection_id, UNNEST(tags) AS tag
                FROM entities
                UNION
                SELECT collection_id, UNNEST(tags) AS tag
                FROM relationships
            )
            SELECT c.name             AS collection,
                   s.tag              AS tag,
                   COALESCE(e.cnt, 0) AS entity_count,
                   COALESCE(r.cnt, 0) AS relationship_count
            FROM scopes s
            JOIN collections c ON s.collection_id = c.id
            LEFT JOIN (
                SELECT collection_id, UNNEST(tags) AS tag, COUNT(*) AS cnt
                FROM entities
                GROUP BY collection_id, tag
            ) e ON e.collection_id = s.collection_id AND e.tag = s.tag
            LEFT JOIN (
                SELECT collection_id, UNNEST(tags) AS tag, COUNT(*) AS cnt
                FROM relationships
                GROUP BY collection_id, tag
            ) r ON r.collection_id = s.collection_id AND r.tag = s.tag
            ORDER BY collection, tag
            """;
        return jdbcClient
            .sql(sql).query((rs, rowNum) -> new KgScope(rs.getString("collection"),
                rs.getString("tag"), rs.getLong("entity_count"), rs.getLong("relationship_count")))
            .list();
    }

    public List<String> listKinds(String collection, @Nullable String tag) {
        String sql = """
            SELECT DISTINCT lower(e.kind) AS kind
            FROM entities e
            JOIN collections c ON e.collection_id = c.id
            WHERE c.name = :collection AND e.kind IS NOT NULL
            """ + tc(tag, "e") + """
            ORDER BY kind ASC
            """;
        Map<String, Object> params = new HashMap<>();
        params.put("collection", collection);
        if (tag != null) {
            params.put("tag", tag);
        }
        return jdbcClient.sql(sql).params(params).query(String.class).list();
    }

    public KgNeighborEdges getEntityRelationships(String name, @Nullable String tag,
        String collection) {
        return getEntityRelationships(name, null, tag, collection, null, "both", MAX_LIMIT);
    }

    /**
     * Kind-aware neighbor query. Resolves the starting node to entity ids by
     * {@code (lower(name)[, kind])} within the collection, then matches relationships by
     * {@code subject_id}/{@code object_id} (respecting {@code direction}, {@code predicate} and
     * {@code tag}). Direction and counterpart are resolved here against those start ids, so two
     * different nodes that merely share a name are never reported as a self-reference. There is no
     * name-text fallback: matching {@code lower(subject)/lower(object)} ignores the kind and hands
     * one node another node's edges, so an empty id match IS the answer. {@code matchedKinds} lists
     * the distinct kinds sharing the name, so an ambiguous name with no kind filter can be flagged
     * upstream.
     */
    public KgNeighborEdges getEntityRelationships(String name, @Nullable String kind,
        @Nullable String tag, String collection, @Nullable String predicate, String direction,
        int limit) {
        int safeLim=Math.min(Math.max(limit,1),MAX_LIMIT);UUID collectionId=findCollectionId(collection);if(collectionId==null){return new KgNeighborEdges(List.of(),List.of());}

        // 1. Resolve the starting node to entity ids (kind-aware), collecting matched kinds.
        Map<String,Object>entityParams=new HashMap<>();entityParams.put("collectionId",collectionId);entityParams.put("name",name);if(kind!=null){entityParams.put("kind",kind);}if(tag!=null){entityParams.put("tag",tag);}String entitySql="SELECT e.id AS id, e.kind AS kind FROM entities e "+"WHERE e.collection_id = :collectionId AND lower(e.name) = lower(:name)"+(kind!=null?" AND lower(coalesce(e.kind, '')) = lower(:kind) ":" ")+tc(tag,"e");

        List<String>matchedKinds=new ArrayList<>();Set<UUID>startIds=new LinkedHashSet<>();jdbcClient.sql(entitySql).params(entityParams).query((rs,rowNum)->{startIds.add(rs.getObject("id",UUID.class));String k=rs.getString("kind");String label=(k==null||k.isBlank())?"?":k;if(!matchedKinds.contains(label)){matchedKinds.add(label);}return null;}).list();

        if(startIds.isEmpty()){return new KgNeighborEdges(matchedKinds,List.of());}

        // 2. Edge match by endpoint id. Direction is whitelisted here, never interpolated.
        String dirClause=switch(direction==null?"both":direction){case"outgoing"->"  AND r.subject_id = ANY(:ids::uuid[])\n";case"incoming"->"  AND r.object_id = ANY(:ids::uuid[])\n";default->"  AND (r.subject_id = ANY(:ids::uuid[]) OR r.object_id = ANY(:ids::uuid[]))\n";};String sql="""
            SELECT r.subject, r.predicate, r.object, r.subject_id, r.object_id,
                   es.kind AS subject_kind, eo.kind AS object_kind, r.description
            FROM relationships r
            LEFT JOIN entities es ON es.id = r.subject_id
            LEFT JOIN entities eo ON eo.id = r.object_id
            WHERE r.collection_id = :collectionId
            """+dirClause+(predicate!=null?"  AND lower(r.predicate) = lower(:predicate)\n":"")+tc(tag,"r")+"""
                ORDER BY r.predicate, r.subject, r.object
                LIMIT :limit
                """;

        Map<String,Object>params=new HashMap<>();params.put("collectionId",collectionId);params.put("ids",startIds.stream().map(UUID::toString).toArray(String[]::new));params.put("limit",safeLim);if(predicate!=null){params.put("predicate",predicate);}if(tag!=null){params.put("tag",tag);}

        List<KgNeighborEdges.Edge>edges=jdbcClient.sql(sql).params(params).query(neighborEdgeRowMapper(startIds)).list();return new KgNeighborEdges(matchedKinds,edges);
    }

    /**
     * Lists every entity kind sharing {@code name} in the collection together with the number of
     * edges that kind's node has under the very same {@code tag} / {@code relationType} /
     * {@code direction} filters the neighbor query applies — so the counts match what a kind-scoped
     * re-query returns. A name is ambiguous when this returns more than one row. Identity is
     * tag-scoped and the query groups by {@code e.id}, so a name yields one row per
     * <strong>(kind, tag)</strong> — NOT one row per kind: the same object in two product versions
     * is two rows sharing a kind. Callers rendering these candidates must therefore surface
     * {@link KgEntityKindEdgeCount#tags()}, or those rows are indistinguishable. Counted in a
     * single grouped query; ordered by {@code edges DESC, kind ASC, tags ASC, id ASC}.
     */
    public List<KgEntityKindEdgeCount> findEntityKindsWithEdgeCounts(String name,
        @Nullable String tag, String collection, @Nullable String relationType, String direction) {
        UUID collectionId = findCollectionId(collection);
        if (collectionId == null) {
            return List.of();
        }
        // Direction is whitelisted here (never interpolated from the caller's string).
        String dirClause = switch (direction == null ? "both" : direction) {
            case "outgoing" -> " AND r.subject_id = e.id ";
            case "incoming" -> " AND r.object_id = e.id ";
            default -> " AND (r.subject_id = e.id OR r.object_id = e.id) ";
        };

        String sql = """
            SELECT coalesce(e.kind, '') AS kind,
                   e.metadata->>'document_id' AS document_id,
                   array_to_string(e.tags, ', ') AS tags,
                   count(r.id) AS edge_count
            FROM entities e
            LEFT JOIN relationships r
                   ON r.collection_id = e.collection_id
            """ + dirClause + (relationType != null ? " AND lower(r.predicate) = lower(:predicate) "
                : " ") + tc(tag, "r") + """
                    WHERE e.collection_id = :collectionId
                      AND lower(e.name) = lower(:name)
                    """ + tc(tag, "e") + """
                        GROUP BY e.id, e.kind, e.metadata->>'document_id', e.tags
                        ORDER BY count(r.id) DESC, coalesce(e.kind, '') ASC,
                                 array_to_string(e.tags, ', ') ASC, e.id ASC
                        """;

        Map<String, Object> params = new HashMap<>();
        params.put("collectionId", collectionId);
        params.put("name", name);
        if (tag != null) {
            params.put("tag", tag);
        }
        if (relationType != null) {
            params.put("predicate", relationType);
        }

        return jdbcClient.sql(sql).params(params)
            .query((rs, rowNum) -> new KgEntityKindEdgeCount(rs.getString("kind"),
                rs.getLong("edge_count"), rs.getString("document_id"), rs.getString("tags")))
            .list();
    }

    public List<KgEntity> listEntities(String collection, @Nullable String tag,
        @Nullable String kind, int limit, int offset) {
        int safeLim = Math.min(Math.max(limit, 1), MAX_LIMIT);
        int safeOffset = Math.max(offset, 0);
        UUID collectionId = findCollectionId(collection);

        String sql =
            """
                SELECT e.id, e.collection_id, c.name AS collection, e.name, e.kind, e.tags, e.description, e.metadata
                FROM entities e
                JOIN collections c ON e.collection_id = c.id
                WHERE e.collection_id = :collectionId
                """
                + tc(tag, "e") + (kind != null ? " AND lower(e.kind) = lower(:kind) " : " ") + """
                    ORDER BY e.name ASC, coalesce(e.kind, '') ASC, e.tags ASC, e.id ASC
                    LIMIT :limit OFFSET :offset
                    """;

        Map<String, Object> params = new HashMap<>();
        params.put("collectionId", collectionId);
        params.put("limit", safeLim);
        params.put("offset", safeOffset);
        if (tag != null) {
            params.put("tag", tag);
        }
        if (kind != null) {
            params.put("kind", kind);
        }

        return jdbcClient.sql(sql).params(params).query(entityRowMapper(false)).list();
    }

    public long countEntities(String collection, @Nullable String tag, @Nullable String kind) {
        UUID collectionId = findCollectionId(collection);
        String sql = "SELECT COUNT(*) FROM entities e WHERE e.collection_id = :collectionId"
            + tc(tag, "e") + (kind != null ? " AND lower(e.kind) = lower(:kind) " : " ");
        Map<String, Object> params = new HashMap<>();
        params.put("collectionId", collectionId);
        if (tag != null) {
            params.put("tag", tag);
        }
        if (kind != null) {
            params.put("kind", kind);
        }
        return jdbcClient.sql(sql).params(params).query(Long.class).single();
    }

    public List<KgEntity> fuzzySearchEntities(String name, int limit, @Nullable String tag,
        String collection) {
        return fuzzySearchEntities(name, limit, tag, collection, null);
    }

    public List<KgEntity> fuzzySearchEntities(String name, int limit, @Nullable String tag,
        String collection, @Nullable String kind) {
        int safeLim = Math.min(Math.max(limit, 1), MAX_LIMIT);
        UUID collectionId = findCollectionId(collection);

        String sql =
            """
                SELECT e.id, e.collection_id, c.name AS collection, e.name, e.kind, e.tags, e.description, e.metadata,
                       similarity(e.name, :name) AS similarity
                FROM entities e
                JOIN collections c ON e.collection_id = c.id
                WHERE e.collection_id = :collectionId
                  AND (e.name ILIKE :likeName ESCAPE '\\' OR e.name % :name)
                """
                + tc(tag, "e") + (kind != null ? " AND lower(e.kind) = lower(:kind) " : " ") + """
                    ORDER BY similarity DESC, length(e.name) ASC, lower(e.name) ASC,
                             coalesce(e.kind, '') ASC, e.tags ASC, e.id ASC
                    LIMIT :limit
                    """;

        Map<String, Object> params = new HashMap<>();
        params.put("collectionId", collectionId);
        params.put("name", name);
        params.put("likeName", "%" + escapeLike(name) + "%");
        params.put("limit", safeLim);
        if (tag != null) {
            params.put("tag", tag);
        }
        if (kind != null) {
            params.put("kind", kind);
        }

        return jdbcClient.sql(sql).params(params).query(entityRowMapper(true)).list();
    }

    public KgNeighborhood getNeighborhood(String entityName, @Nullable String tag,
        String collection) {
        UUID collectionId = findCollectionId(collection);
        Map<String, Object> params = new HashMap<>();
        params.put("collectionId", collectionId);
        params.put("entityName", entityName);
        if (tag != null) {
            params.put("tag", tag);
        }

        // 1. Fetch entity
        String entitySql =
            """
                SELECT e.id, e.collection_id, c.name AS collection, e.name, e.kind, e.tags, e.description, e.metadata
                FROM entities e
                JOIN collections c ON e.collection_id = c.id
                WHERE e.collection_id = :collectionId
                  AND lower(e.name) = lower(:entityName)
                """
                + tc(tag, "e") + """
                    ORDER BY coalesce(e.kind, '') ASC, e.tags ASC, e.id ASC
                    LIMIT 1
                    """;

        List<KgEntity> entities =
            jdbcClient.sql(entitySql).params(params).query(entityRowMapper(false)).list();

        KgEntity entity = null;
        if (!entities.isEmpty()) {
            entity = entities.get(0);
        } else {
            // Check if any edges exist referencing this entity (graceful degradation)
            String checkEdgesSql =
                """
                    SELECT count(*) AS n
                    FROM relationships r
                    WHERE r.collection_id = :collectionId
                      AND (lower(r.subject) = lower(:entityName) OR lower(r.object) = lower(:entityName))
                    """
                    + tc(tag, "r");

            Long edgeCount = jdbcClient.sql(checkEdgesSql).params(params)
                .query((rs, rowNum) -> rs.getLong("n")).optional().orElse(0L);

            if (edgeCount > 0) {
                entity = new KgEntity(null, collectionId, collection, entityName, "table",
                    tag != null ? List.of(tag) : List.of("ALL"),
                    "_(no entity node in the graph — description unavailable; showing edges matched by name. Run oim_db_summary for the full table summary.)_",
                    Collections.emptyMap(), null);
            } else {
                return null;
            }
        }

        // 2. Fetch Outgoing edges
        String outgoingSql =
            """
                SELECT r.id, r.collection_id, c.name AS collection, r.subject, r.predicate, r.object, r.subject_id, r.object_id, r.tags, r.description, r.metadata
                FROM relationships r
                JOIN collections c ON r.collection_id = c.id
                WHERE r.collection_id = :collectionId
                  AND lower(r.subject) = lower(:entityName)
                """
                + tc(tag, "r") + """
                    ORDER BY r.predicate, r.object
                    """;

        List<KgRelationship> outgoing =
            jdbcClient.sql(outgoingSql).params(params).query(relationshipRowMapper()).list();

        // 3. Fetch Incoming edges
        String incomingSql =
            """
                SELECT r.id, r.collection_id, c.name AS collection, r.subject, r.predicate, r.object, r.subject_id, r.object_id, r.tags, r.description, r.metadata
                FROM relationships r
                JOIN collections c ON r.collection_id = c.id
                WHERE r.collection_id = :collectionId
                  AND lower(r.object) = lower(:entityName)
                """
                + tc(tag, "r") + """
                    ORDER BY r.predicate, r.subject
                    """;

        List<KgRelationship> incoming =
            jdbcClient.sql(incomingSql).params(params).query(relationshipRowMapper()).list();

        return new KgNeighborhood(entity, outgoing, incoming);
    }

    public List<KgEntity> getProcsForTable(String tableName, int limit, @Nullable String tag,
        String collection) {
        int safeLim = Math.min(Math.max(limit, 1), MAX_LIMIT);
        UUID collectionId = findCollectionId(collection);

        String sql =
            """
                SELECT e.id,
                       e.collection_id,
                       c.name AS collection,
                       r.subject AS name,
                       e.kind,
                       e.tags,
                       e.description,
                       e.metadata
                FROM relationships r
                JOIN collections c ON r.collection_id = c.id
                LEFT JOIN entities e
                       ON e.collection_id = r.collection_id AND e.name = r.subject AND (CAST(:tag AS text) IS NULL OR :tag = ANY(e.tags))
                WHERE r.collection_id = :collectionId
                  AND r.predicate = 'references_table'
                  AND lower(r.object) = lower(:tableName)
                """
                + tc(tag, "r") + """
                    ORDER BY e.kind, r.subject
                    LIMIT :limit
                    """;

        Map<String, Object> params = new HashMap<>();
        params.put("collectionId", collectionId);
        params.put("tableName", tableName);
        params.put("limit", safeLim);
        if (tag != null) {
            params.put("tag", tag);
        }

        return jdbcClient.sql(sql).params(params).query(entityRowMapper(false)).list();
    }

    public List<KgCall> getCalls(String name, @Nullable String direction, int limit,
        @Nullable String tag, String collection) {
        int safeLim = Math.min(Math.max(limit, 1), MAX_LIMIT);
        UUID collectionId = findCollectionId(collection);
        String dir = direction != null ? direction.toLowerCase() : "callers";

        String callersSql =
            """
                SELECT r.subject AS name, e.kind, e.description, 'caller' AS relation_type
                FROM relationships r
                LEFT JOIN entities e ON e.collection_id = r.collection_id AND e.name = r.subject AND (CAST(:tag AS text) IS NULL OR :tag = ANY(e.tags))
                WHERE r.collection_id = :collectionId AND r.predicate = 'calls' AND lower(r.object) = lower(:name)
                """
                + tc(tag, "r") + """
                    ORDER BY e.kind, r.subject
                    LIMIT :limit
                    """;

        String calleesSql =
            """
                SELECT r.object AS name, e.kind, e.description, 'callee' AS relation_type
                FROM relationships r
                LEFT JOIN entities e ON e.collection_id = r.collection_id AND e.name = r.object AND (CAST(:tag AS text) IS NULL OR :tag = ANY(e.tags))
                WHERE r.collection_id = :collectionId AND r.predicate = 'calls' AND lower(r.subject) = lower(:name)
                """
                + tc(tag, "r") + """
                    ORDER BY e.kind, r.object
                    LIMIT :limit
                    """;

        Map<String, Object> params = new HashMap<>();
        params.put("collectionId", collectionId);
        params.put("name", name);
        params.put("limit", safeLim);
        if (tag != null) {
            params.put("tag", tag);
        }

        if ("callers".equals(dir)) {
            return jdbcClient.sql(callersSql).params(params).query(new KgCallMapper()).list();
        } else if ("callees".equals(dir)) {
            return jdbcClient.sql(calleesSql).params(params).query(new KgCallMapper()).list();
        } else if ("both".equals(dir)) {
            String bothSql =
                """
                    SELECT name, kind, description, relation_type FROM (
                        SELECT r.subject AS name, e.kind, e.description, 'caller' AS relation_type, r.collection_id, r.tags
                        FROM relationships r
                        LEFT JOIN entities e ON e.collection_id = r.collection_id AND e.name = r.subject AND (CAST(:tag AS text) IS NULL OR :tag = ANY(e.tags))
                        WHERE r.collection_id = :collectionId AND r.predicate = 'calls' AND lower(r.object) = lower(:name)
                        UNION ALL
                        SELECT r.object AS name, e.kind, e.description, 'callee' AS relation_type, r.collection_id, r.tags
                        FROM relationships r
                        LEFT JOIN entities e ON e.collection_id = r.collection_id AND e.name = r.object AND (CAST(:tag AS text) IS NULL OR :tag = ANY(e.tags))
                        WHERE r.collection_id = :collectionId AND r.predicate = 'calls' AND lower(r.subject) = lower(:name)
                    ) AS calls
                    WHERE 1=1 """
                    + tc(tag) + """
                        ORDER BY kind, name
                        LIMIT :limit
                        """;
            return jdbcClient.sql(bothSql).params(params).query(new KgCallMapper()).list();
        } else {
            throw new IllegalArgumentException("Invalid direction: " + dir);
        }
    }

    public List<KgWalk> getFkWalk(String tableName, int maxHops, @Nullable String tag,
        String collection) {
        if (maxHops < 1) {
            return Collections.emptyList();
        }
        int hops = Math.min(maxHops, 5);
        UUID collectionId = findCollectionId(collection);

        String sql =
            """
                WITH RECURSIVE walk(node, depth, path, visited) AS (
                    SELECT lower(:tableName)::text, 0, ARRAY[:tableName::text], ARRAY[lower(:tableName)::text]
                    UNION ALL
                    SELECT
                      CASE WHEN lower(r.subject) = w.node THEN lower(r.object) ELSE lower(r.subject) END,
                      w.depth + 1,
                      w.path || (CASE WHEN lower(r.subject)=w.node THEN '→ '||r.object ELSE '← '||r.subject END),
                      w.visited || (CASE WHEN lower(r.subject)=w.node THEN lower(r.object) ELSE lower(r.subject) END)
                    FROM relationships r, walk w
                    WHERE r.collection_id = :collectionId
                      AND r.predicate = 'fk_to'
                      AND (lower(r.subject) = w.node OR lower(r.object) = w.node)
                      AND w.depth < :hops
                      AND NOT (CASE WHEN lower(r.subject)=w.node THEN lower(r.object) ELSE lower(r.subject) END = ANY(w.visited))
                """
                + tc(tag, "r") + """
                    )
                    SELECT depth, path
                    FROM walk
                    WHERE depth > 0
                    ORDER BY depth, path
                    LIMIT 200
                    """;

        Map<String, Object> params = new HashMap<>();
        params.put("collectionId", collectionId);
        params.put("tableName", tableName);
        params.put("hops", hops);
        if (tag != null) {
            params.put("tag", tag);
        }

        return jdbcClient.sql(sql).params(params).query(new KgWalkMapper()).list();
    }

    public boolean relationshipPredicateExists(String predicate, List<String> collections) {
        if (predicate == null || predicate.isBlank() || collections == null
            || collections.isEmpty()) {
            return false;
        }
        Optional<Integer> count = jdbcClient.sql("""
            SELECT 1 FROM relationships r
            JOIN collections c ON r.collection_id = c.id
            WHERE lower(r.predicate) = lower(:predicate)
              AND c.name IN (:collections)
            LIMIT 1
            """).param("predicate", predicate.trim()).param("collections", collections)
            .query(Integer.class).optional();
        return count.isPresent();
    }

    // --- RowMapper factories ---

    KgEntityMapper entityRowMapper(boolean includeSimilarity) {
        return new KgEntityMapper(objectMapper, includeSimilarity);
    }

    KgRelationshipMapper relationshipRowMapper() {
        return new KgRelationshipMapper(objectMapper);
    }

    /**
     * Maps a neighbor row, resolving direction and counterpart from the endpoint ids against the
     * start node's ids. Comparing the start NAME to subject/object instead would report every edge
     * between two same-named nodes (module "ADS" ─has_connector→ connector "ADS") as a self-loop.
     */
    private static RowMapper<KgNeighborEdges.Edge> neighborEdgeRowMapper(Set<UUID> startIds) {
        return (rs, rowNum) -> {
            String subject = rs.getString("subject");
            String object = rs.getString("object");
            String subjectKind = rs.getString("subject_kind");
            String objectKind = rs.getString("object_kind");
            UUID subjectId = rs.getObject("subject_id", UUID.class);
            UUID objectId = rs.getObject("object_id", UUID.class);

            Direction direction;
            String counterpart;
            String counterpartKind;
            if (subjectId != null && subjectId.equals(objectId)) {
                direction = Direction.SELF;
                counterpart = subject;
                counterpartKind = subjectKind;
            } else if (subjectId != null && startIds.contains(subjectId)) {
                direction = Direction.OUTGOING;
                counterpart = object;
                counterpartKind = objectKind;
            } else {
                direction = Direction.INCOMING;
                counterpart = subject;
                counterpartKind = subjectKind;
            }
            return new KgNeighborEdges.Edge(subject, rs.getString("predicate"), object,
                rs.getString("description"), direction, counterpart, counterpartKind);
        };
    }
}
