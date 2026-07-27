package de.palsoftware.yvoke.document.core.repository;

import de.palsoftware.yvoke.document.core.model.DocumentRow;


import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.shared.config.JdbcMappers;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;

class DocumentRowMapper implements RowMapper<DocumentRow> {

    private final ObjectMapper objectMapper;

    public DocumentRowMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public DocumentRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        UUID collectionId = rs.getObject("collection_id", UUID.class);
        String collection = rs.getString("collection");
        String kind = rs.getString("kind");
        String title = rs.getString("title");
        String ingestionStatus = rs.getString("ingestion_status");

        Timestamp ts = rs.getTimestamp("created_at");
        Instant createdAt = ts != null ? ts.toInstant() : null;

        Map<String, Object> metadata = JdbcMappers.jsonbToMap(rs, "metadata", objectMapper);
        List<String> tags = JdbcMappers.arrayToStringList(rs, "tags");

        return new DocumentRow(id, collectionId, collection, kind, title, metadata, ingestionStatus,
            tags, createdAt);
    }
}
