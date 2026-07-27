package de.palsoftware.yvoke.jsonobject.core.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.jsonobject.core.model.JsonSchema;
import de.palsoftware.yvoke.shared.config.JdbcMappers;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JsonSchemaRepository {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public JsonSchemaRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public Optional<JsonSchema> findByCollectionId(UUID collectionId, String tag) {
        String sql;
        if (tag == null) {
            sql = "SELECT id, collection_id, tag, schema_data, source, updated_at "
                + "FROM json_schemas WHERE collection_id = :collectionId AND tag IS NULL";
        } else {
            sql = "SELECT id, collection_id, tag, schema_data, source, updated_at "
                + "FROM json_schemas WHERE collection_id = :collectionId AND tag = :tag";
        }

        return jdbcClient.sql(sql).param("collectionId", collectionId).param("tag", tag)
            .query((rs, rowNum) -> {
                OffsetDateTime updatedAt =
                    rs.getTimestamp("updated_at") != null ? OffsetDateTime.ofInstant(
                        rs.getTimestamp("updated_at").toInstant(), ZoneId.systemDefault()) : null;
                return new JsonSchema(rs.getObject("id", UUID.class),
                    rs.getObject("collection_id", UUID.class), rs.getString("tag"),
                    JdbcMappers.jsonbToMap(rs, "schema_data", objectMapper), rs.getString("source"),
                    updatedAt);
            }).optional();
    }

    public void upsert(JsonSchema schema) {
        try {
            String dataJson = objectMapper.writeValueAsString(schema.schemaData());
            String sql =
                "INSERT INTO json_schemas (id, collection_id, tag, schema_data, source, updated_at) "
                    + "VALUES (:id, :collectionId, :tag, :schemaData::jsonb, :source, CURRENT_TIMESTAMP) "
                    + "ON CONFLICT (collection_id, tag) DO UPDATE SET "
                    + "schema_data = EXCLUDED.schema_data, " + "source = EXCLUDED.source, "
                    + "updated_at = CURRENT_TIMESTAMP";

            jdbcClient.sql(sql).param("id", schema.id())
                .param("collectionId", schema.collectionId()).param("tag", schema.tag())
                .param("schemaData", dataJson).param("source", schema.source()).update();
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize schema data", e);
        }
    }

    public void deleteByCollectionId(UUID collectionId) {
        jdbcClient.sql("DELETE FROM json_schemas WHERE collection_id = :collectionId")
            .param("collectionId", collectionId).update();
    }
}
