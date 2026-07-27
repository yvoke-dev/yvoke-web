package de.palsoftware.yvoke.kg.core.repository;

import de.palsoftware.yvoke.kg.core.model.KgRelationship;


import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.shared.config.JdbcMappers;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;

public class KgRelationshipMapper implements RowMapper<KgRelationship> {
    private final ObjectMapper objectMapper;

    public KgRelationshipMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public KgRelationship mapRow(ResultSet rs, int rowNum) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        UUID collectionId = rs.getObject("collection_id", UUID.class);
        String collection = rs.getString("collection");
        String subject = rs.getString("subject");
        String predicate = rs.getString("predicate");
        String object = rs.getString("object");
        UUID subjectId = rs.getObject("subject_id", UUID.class);
        UUID objectId = rs.getObject("object_id", UUID.class);
        List<String> tags = JdbcMappers.arrayToStringList(rs, "tags");
        String description = rs.getString("description");
        Map<String, Object> metadata = JdbcMappers.jsonbToMap(rs, "metadata", objectMapper);
        return new KgRelationship(id, collectionId, collection, subject, predicate, object,
            subjectId, objectId, tags, description, metadata);
    }
}
