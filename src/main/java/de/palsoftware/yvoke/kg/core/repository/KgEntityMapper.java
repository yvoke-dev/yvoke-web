package de.palsoftware.yvoke.kg.core.repository;

import de.palsoftware.yvoke.kg.core.model.KgEntity;


import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.shared.config.JdbcMappers;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;

public class KgEntityMapper implements RowMapper<KgEntity> {
    private final ObjectMapper objectMapper;
    private final boolean includeSimilarity;

    public KgEntityMapper(ObjectMapper objectMapper, boolean includeSimilarity) {
        this.objectMapper = objectMapper;
        this.includeSimilarity = includeSimilarity;
    }

    @Override
    public KgEntity mapRow(ResultSet rs, int rowNum) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        UUID collectionId = rs.getObject("collection_id", UUID.class);
        String collection = rs.getString("collection");
        String name = rs.getString("name");
        String kind = rs.getString("kind");
        List<String> tags = JdbcMappers.arrayToStringList(rs, "tags");
        String description = rs.getString("description");
        Map<String, Object> metadata = JdbcMappers.jsonbToMap(rs, "metadata", objectMapper);
        Double similarity = null;
        if (includeSimilarity) {
            similarity = rs.getDouble("similarity");
            if (rs.wasNull()) {
                similarity = null;
            }
        }
        return new KgEntity(id, collectionId, collection, name, kind, tags, description, metadata,
            similarity);
    }
}
