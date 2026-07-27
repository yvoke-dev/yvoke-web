package de.palsoftware.yvoke.jsonobject.core.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.jsonobject.core.model.JsonObject;
import de.palsoftware.yvoke.shared.config.JdbcMappers;
import jakarta.annotation.Nullable;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JsonObjectRepository {

    /**
     * Maximum number of distinct groups a grouped count returns, matching the cap
     * {@code getDistinctValues} already applies. Public because the caller must know it to tell a
     * complete grouping from a truncated one and to label the difference — a silently capped
     * grouping reads as the whole picture.
     */
    public static final int MAX_GROUPS = 100;

    private final JdbcClient jdbcClient;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JsonObjectRepository(JdbcClient jdbcClient, JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void save(JsonObject jsonObject) {
        try {
            String dataJson = objectMapper.writeValueAsString(jsonObject.data());
            jdbcClient.sql(
                "INSERT INTO json_objects (id, collection_id, data, source_file, tags, created_at) "
                    + "VALUES (:id, :collectionId, :data::jsonb, :sourceFile, :tags::text[], :createdAt)")
                .param("id", jsonObject.id()).param("collectionId", jsonObject.collectionId())
                .param("data", dataJson).param("sourceFile", jsonObject.sourceFile())
                .param("tags", jsonObject.tags() != null ? jsonObject.tags().toArray(new String[0])
                    : new String[0])
                .param("createdAt", jsonObject.createdAt()).update();
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize json data", e);
        }
    }

    public void saveBatch(List<JsonObject> objects) {
        if (objects.isEmpty())
            return;

        jdbcTemplate.batchUpdate(
            "INSERT INTO json_objects (id, collection_id, data, source_file, tags, created_at) "
                + "VALUES (?, ?, ?::jsonb, ?, ?, ?)",
            new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    JsonObject obj = objects.get(i);
                    ps.setObject(1, obj.id());
                    ps.setObject(2, obj.collectionId());
                    try {
                        ps.setString(3, objectMapper.writeValueAsString(obj.data()));
                    } catch (JsonProcessingException e) {
                        throw new SQLException("Failed to serialize json data", e);
                    }
                    ps.setString(4, obj.sourceFile());
                    String[] tagsArr =
                        obj.tags() != null ? obj.tags().toArray(new String[0]) : new String[0];
                    ps.setArray(5, ps.getConnection().createArrayOf("text", tagsArr));
                    ps.setTimestamp(6,
                        obj.createdAt() != null ? Timestamp.from(obj.createdAt().toInstant())
                            : null);
                }

                @Override
                public int getBatchSize() {
                    return objects.size();
                }
            });
    }

    public Optional<JsonObject> findById(UUID id) {
        return jdbcClient.sql(
            "SELECT j.id, j.collection_id, c.name as collection_name, j.data, j.source_file, j.tags, j.created_at "
                + "FROM json_objects j " + "JOIN collections c ON j.collection_id = c.id "
                + "WHERE j.id = :id")
            .param("id", id).query((rs, rowNum) -> {
                OffsetDateTime createdAt =
                    rs.getTimestamp("created_at") != null ? OffsetDateTime.ofInstant(
                        rs.getTimestamp("created_at").toInstant(), ZoneId.systemDefault()) : null;
                return new JsonObject(rs.getObject("id", UUID.class),
                    rs.getObject("collection_id", UUID.class), rs.getString("collection_name"),
                    JdbcMappers.jsonbToMap(rs, "data", objectMapper), rs.getString("source_file"),
                    getTagsList(rs, "tags"), createdAt);
            }).optional();
    }

    public Optional<JsonObject> findByJsonField(UUID collectionId, String fieldPath, String value) {
        String querySql =
            "SELECT j.id, j.collection_id, c.name as collection_name, j.data, j.source_file, j.tags, j.created_at "
                + "FROM json_objects j " + "JOIN collections c ON j.collection_id = c.id "
                + "WHERE j.collection_id = :collectionId "
                + "AND j.data #>> string_to_array(:fieldPath, '.') = :value " + "LIMIT 1";
        return jdbcClient.sql(querySql).param("collectionId", collectionId)
            .param("fieldPath", fieldPath).param("value", value).query((rs, rowNum) -> {
                OffsetDateTime createdAt =
                    rs.getTimestamp("created_at") != null ? OffsetDateTime.ofInstant(
                        rs.getTimestamp("created_at").toInstant(), ZoneId.systemDefault()) : null;
                return new JsonObject(rs.getObject("id", UUID.class),
                    rs.getObject("collection_id", UUID.class), rs.getString("collection_name"),
                    JdbcMappers.jsonbToMap(rs, "data", objectMapper), rs.getString("source_file"),
                    getTagsList(rs, "tags"), createdAt);
            }).optional();
    }

    /**
     * Resolves, in a SINGLE query, which of {@code values} already exist for {@code fieldPath} in
     * the collection — returning a value → id map. This replaces the per-object existence probe
     * that made bulk import O(N×M) (PRF-02). When several stored rows share a value, an arbitrary
     * one wins (matching the previous {@code LIMIT 1} probe semantics).
     */
    public Map<String, UUID> findIdsByJsonField(UUID collectionId, String fieldPath,
        List<String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        String sql = "SELECT j.data #>> string_to_array(:fieldPath, '.') AS uval, j.id "
            + "FROM json_objects j " + "WHERE j.collection_id = :collectionId "
            + "AND j.data #>> string_to_array(:fieldPath, '.') IN (:values)";
        Map<String, UUID> result = new java.util.HashMap<>();
        jdbcClient.sql(sql).param("collectionId", collectionId).param("fieldPath", fieldPath)
            .param("values", values).query((rs, rowNum) -> {
                result.putIfAbsent(rs.getString("uval"), rs.getObject("id", UUID.class));
                return null;
            }).list();
        return result;
    }

    public void updateBatch(List<JsonObject> objects) {
        if (objects.isEmpty())
            return;

        jdbcTemplate.batchUpdate(
            "UPDATE json_objects SET data = ?::jsonb, source_file = ?, tags = ?, created_at = ? "
                + "WHERE id = ?",
            new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    JsonObject obj = objects.get(i);
                    try {
                        ps.setString(1, objectMapper.writeValueAsString(obj.data()));
                    } catch (JsonProcessingException e) {
                        throw new SQLException("Failed to serialize json data", e);
                    }
                    ps.setString(2, obj.sourceFile());
                    String[] tagsArr =
                        obj.tags() != null ? obj.tags().toArray(new String[0]) : new String[0];
                    ps.setArray(3, ps.getConnection().createArrayOf("text", tagsArr));
                    ps.setTimestamp(4,
                        obj.createdAt() != null ? Timestamp.from(obj.createdAt().toInstant())
                            : null);
                    ps.setObject(5, obj.id());
                }

                @Override
                public int getBatchSize() {
                    return objects.size();
                }
            });
    }

    public List<JsonObject> findByCollectionId(UUID collectionId, int limit, int offset) {
        return findByCollectionId(collectionId, null, limit, offset);
    }

    public List<JsonObject> findByCollectionId(UUID collectionId, @Nullable List<String> tags,
        int limit, int offset) {
        String querySql =
            "SELECT j.id, j.collection_id, c.name as collection_name, j.data, j.source_file, j.tags, j.created_at "
                + "FROM json_objects j " + "JOIN collections c ON j.collection_id = c.id "
                + "WHERE j.collection_id = :collectionId " + tagsFilter(tags)
                + "ORDER BY j.created_at DESC " + "LIMIT :limit OFFSET :offset";
        var client = jdbcClient.sql(querySql).param("collectionId", collectionId)
            .param("limit", limit).param("offset", offset);
        if (tags != null && !tags.isEmpty()) {
            client = client.param("tags", tags.toArray(new String[0]));
        }
        return client.query((rs, rowNum) -> {
            OffsetDateTime createdAt =
                rs.getTimestamp("created_at") != null
                    ? OffsetDateTime.ofInstant(rs.getTimestamp("created_at").toInstant(),
                        ZoneId.systemDefault())
                    : null;
            return new JsonObject(rs.getObject("id", UUID.class),
                rs.getObject("collection_id", UUID.class), rs.getString("collection_name"),
                JdbcMappers.jsonbToMap(rs, "data", objectMapper), rs.getString("source_file"),
                getTagsList(rs, "tags"), createdAt);
        }).list();
    }

    public long countByCollectionId(UUID collectionId) {
        return countByCollectionId(collectionId, null);
    }

    public long countByCollectionId(UUID collectionId, @Nullable List<String> tags) {
        String querySql =
            "SELECT COUNT(*) FROM json_objects j WHERE j.collection_id = :collectionId"
                + tagsFilter(tags);
        var client = jdbcClient.sql(querySql).param("collectionId", collectionId);
        if (tags != null && !tags.isEmpty()) {
            client = client.param("tags", tags.toArray(new String[0]));
        }
        return client.query(Long.class).single();
    }

    public List<JsonObject> search(UUID collectionId, String searchText, int limit, int offset) {
        return search(collectionId, searchText, null, limit, offset);
    }

    public List<JsonObject> search(UUID collectionId, String searchText,
        @Nullable List<String> tags, int limit, int offset) {
        String querySql =
            "SELECT j.id, j.collection_id, c.name as collection_name, j.data, j.source_file, j.tags, j.created_at "
                + "FROM json_objects j " + "JOIN collections c ON j.collection_id = c.id "
                + "WHERE j.collection_id = :collectionId " + "AND j.data::text ILIKE :searchText "
                + tagsFilter(tags) + "ORDER BY j.created_at DESC " + "LIMIT :limit OFFSET :offset";
        var client = jdbcClient.sql(querySql).param("collectionId", collectionId)
            .param("searchText", "%" + searchText + "%").param("limit", limit)
            .param("offset", offset);
        if (tags != null && !tags.isEmpty()) {
            client = client.param("tags", tags.toArray(new String[0]));
        }
        return client.query((rs, rowNum) -> {
            OffsetDateTime createdAt =
                rs.getTimestamp("created_at") != null
                    ? OffsetDateTime.ofInstant(rs.getTimestamp("created_at").toInstant(),
                        ZoneId.systemDefault())
                    : null;
            return new JsonObject(rs.getObject("id", UUID.class),
                rs.getObject("collection_id", UUID.class), rs.getString("collection_name"),
                JdbcMappers.jsonbToMap(rs, "data", objectMapper), rs.getString("source_file"),
                getTagsList(rs, "tags"), createdAt);
        }).list();
    }

    public long countSearch(UUID collectionId, String searchText) {
        return countSearch(collectionId, searchText, null);
    }

    public long countSearch(UUID collectionId, String searchText, @Nullable List<String> tags) {
        String querySql =
            "SELECT COUNT(*) FROM json_objects j " + "WHERE j.collection_id = :collectionId "
                + "AND j.data::text ILIKE :searchText" + tagsFilter(tags);
        var client = jdbcClient.sql(querySql).param("collectionId", collectionId)
            .param("searchText", "%" + searchText + "%");
        if (tags != null && !tags.isEmpty()) {
            client = client.param("tags", tags.toArray(new String[0]));
        }
        return client.query(Long.class).single();
    }

    public List<JsonObject> queryByJsonPath(UUID collectionId, String jsonPath, int limit,
        int offset) {
        return queryByJsonPath(collectionId, jsonPath, null, limit, offset);
    }

    public List<JsonObject> queryByJsonPath(UUID collectionId, String jsonPath,
        @Nullable List<String> tags, int limit, int offset) {
        String querySql =
            "SELECT j.id, j.collection_id, c.name as collection_name, j.data, j.source_file, j.tags, j.created_at "
                + "FROM json_objects j " + "JOIN collections c ON j.collection_id = c.id "
                + "WHERE j.collection_id = :collectionId " +
                // The @? operator (not the jsonb_path_exists function) so the GIN index on data
                // is usable; ?? is the JDBC escape for a literal ? (PgJDBC unescapes it).
                "AND j.data @?? :jsonPath::jsonpath " + tagsFilter(tags)
                + "ORDER BY j.created_at DESC " + "LIMIT :limit OFFSET :offset";
        var client = jdbcClient.sql(querySql).param("collectionId", collectionId)
            .param("jsonPath", jsonPath).param("limit", limit).param("offset", offset);
        if (tags != null && !tags.isEmpty()) {
            client = client.param("tags", tags.toArray(new String[0]));
        }
        return client.query((rs, rowNum) -> {
            OffsetDateTime createdAt =
                rs.getTimestamp("created_at") != null
                    ? OffsetDateTime.ofInstant(rs.getTimestamp("created_at").toInstant(),
                        ZoneId.systemDefault())
                    : null;
            return new JsonObject(rs.getObject("id", UUID.class),
                rs.getObject("collection_id", UUID.class), rs.getString("collection_name"),
                JdbcMappers.jsonbToMap(rs, "data", objectMapper), rs.getString("source_file"),
                getTagsList(rs, "tags"), createdAt);
        }).list();
    }

    public long countByJsonPath(UUID collectionId, String jsonPath) {
        return countByJsonPath(collectionId, jsonPath, null);
    }

    public long countByJsonPath(UUID collectionId, String jsonPath, @Nullable List<String> tags) {
        String querySql =
            "SELECT COUNT(*) FROM json_objects j " + "WHERE j.collection_id = :collectionId "
                + "AND j.data @?? :jsonPath::jsonpath" + tagsFilter(tags);
        var client = jdbcClient.sql(querySql).param("collectionId", collectionId).param("jsonPath",
            jsonPath);
        if (tags != null && !tags.isEmpty()) {
            client = client.param("tags", tags.toArray(new String[0]));
        }
        return client.query(Long.class).single();
    }

    public Map<String, Long> countGroupedByJsonPath(UUID collectionId, String jsonPath,
        String groupByField, @Nullable List<String> tags) {
        // The right side of ->> cannot be parameterized, so the field is concatenated. The charset
        // strip below keeps that concatenation safe — but stripping alone used to be silently
        // WRONG: "Customer.name" became "Customername", a key no row carries, so GROUP BY returned
        // a single (null) bucket whose count equalled the unfiltered total and read like a real
        // breakdown. A field that needs sanitising was never a valid top-level key, so divergence
        // is fatal rather than quietly corrected. Callers should reject it earlier with a usable
        // message; this is the backstop that keeps any other caller honest.
        String safeField = groupByField.replaceAll("[^a-zA-Z0-9_\\-]", "");
        if (!safeField.equals(groupByField)) {
            throw new IllegalArgumentException("groupBy field '" + groupByField
                + "' is not a plain top-level key: grouping is only supported on a single "
                + "top-level field name (letters, digits, '_' and '-').");
        }

        String querySql =
            "SELECT j.data->>'" + safeField + "' AS group_key, COUNT(*) AS group_count "
                + "FROM json_objects j " + "WHERE j.collection_id = :collectionId ";

        if (jsonPath != null && !jsonPath.isBlank()) {
            querySql += "AND j.data @?? :jsonPath::jsonpath ";
        }
        // Cap the number of GROUPS, not the rows scanned. groupBy accepts any top-level key, and
        // high-cardinality ones are legal: 'name' on the DB-History content tag has 4,008 distinct
        // values, which used to come back as a single ~4,000-row markdown table. One extra row is
        // fetched so the caller can tell "exactly at the cap" from "there is more" without paying
        // for a second COUNT(DISTINCT). Ordering by count first makes a truncated result the TOP
        // groups, which is the useful half — provided the caller labels it as partial.
        querySql += tagsFilter(tags) + "GROUP BY j.data->>'" + safeField + "' "
            + "ORDER BY group_count DESC LIMIT " + (MAX_GROUPS + 1);

        var client = jdbcClient.sql(querySql).param("collectionId", collectionId);

        if (jsonPath != null && !jsonPath.isBlank()) {
            client = client.param("jsonPath", jsonPath);
        }
        if (tags != null && !tags.isEmpty()) {
            client = client.param("tags", tags.toArray(new String[0]));
        }

        Map<String, Long> result = new java.util.LinkedHashMap<>();
        client.query((rs, rowNum) -> {
            String key = rs.getString("group_key");
            long count = rs.getLong("group_count");
            result.put(key != null ? key : "(null)", count);
            return null;
        }).list();
        return result;
    }

    public List<JsonObject> queryByContainment(UUID collectionId, Map<String, Object> filter,
        int limit, int offset) {
        return queryByContainment(collectionId, filter, null, limit, offset);
    }

    public List<JsonObject> queryByContainment(UUID collectionId, Map<String, Object> filter,
        @Nullable List<String> tags, int limit, int offset) {
        try {
            String filterJson = objectMapper.writeValueAsString(filter);
            String querySql =
                "SELECT j.id, j.collection_id, c.name as collection_name, j.data, j.source_file, j.tags, j.created_at "
                    + "FROM json_objects j " + "JOIN collections c ON j.collection_id = c.id "
                    + "WHERE j.collection_id = :collectionId " + "AND j.data @> :filter::jsonb "
                    + tagsFilter(tags) + "ORDER BY j.created_at DESC "
                    + "LIMIT :limit OFFSET :offset";
            var client = jdbcClient.sql(querySql).param("collectionId", collectionId)
                .param("filter", filterJson).param("limit", limit).param("offset", offset);
            if (tags != null && !tags.isEmpty()) {
                client = client.param("tags", tags.toArray(new String[0]));
            }
            return client.query((rs, rowNum) -> {
                OffsetDateTime createdAt =
                    rs.getTimestamp("created_at") != null ? OffsetDateTime.ofInstant(
                        rs.getTimestamp("created_at").toInstant(), ZoneId.systemDefault()) : null;
                return new JsonObject(rs.getObject("id", UUID.class),
                    rs.getObject("collection_id", UUID.class), rs.getString("collection_name"),
                    JdbcMappers.jsonbToMap(rs, "data", objectMapper), rs.getString("source_file"),
                    getTagsList(rs, "tags"), createdAt);
            }).list();
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize filter map", e);
        }
    }

    public void deleteByCollectionId(UUID collectionId) {
        jdbcClient.sql("DELETE FROM json_objects WHERE collection_id = :collectionId")
            .param("collectionId", collectionId).update();
    }

    /**
     * Tag-aware removal for one collection: deletes only json objects whose sole tag is {@code tag}
     * and detaches the tag from objects shared with other tags. Objects that never carried the tag
     * are untouched.
     */
    public void removeTagAndPurgeOrphans(UUID collectionId, String tag) {
        jdbcClient.sql("""
            DELETE FROM json_objects
            WHERE collection_id = :collectionId AND :tag = ANY(tags) AND cardinality(tags) = 1
            """).param("collectionId", collectionId).param("tag", tag).update();
        jdbcClient.sql("""
            UPDATE json_objects
            SET tags = array_remove(tags, :tag)
            WHERE collection_id = :collectionId AND :tag = ANY(tags)
            """).param("collectionId", collectionId).param("tag", tag).update();
    }

    public void deleteById(UUID id) {
        jdbcClient.sql("DELETE FROM json_objects WHERE id = :id").param("id", id).update();
    }

    public List<String> getDistinctValues(UUID collectionId, String fieldName) {
        return jdbcClient
            .sql("SELECT DISTINCT j.data->>:fieldName " + "FROM json_objects j "
                + "WHERE j.collection_id = :collectionId " + "AND j.data->>:fieldName IS NOT NULL "
                + "ORDER BY 1 " + "LIMIT 100")
            .param("collectionId", collectionId).param("fieldName", fieldName).query(String.class)
            .list();
    }

    private String tagsFilter(@Nullable List<String> tags) {
        return (tags != null && !tags.isEmpty()) ? " AND j.tags && :tags::text[] " : "";
    }

    private List<String> getTagsList(ResultSet rs, String columnName) throws SQLException {
        return JdbcMappers.arrayToStringList(rs, columnName);
    }
}
