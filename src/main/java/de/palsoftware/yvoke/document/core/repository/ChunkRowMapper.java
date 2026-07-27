package de.palsoftware.yvoke.document.core.repository;

import de.palsoftware.yvoke.document.core.model.ChunkRow;


import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.shared.config.JdbcMappers;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;

public class ChunkRowMapper implements RowMapper<ChunkRow> {
    private final ObjectMapper objectMapper;
    private final String scoreColumn;

    public ChunkRowMapper(ObjectMapper objectMapper, String scoreColumn) {
        this.objectMapper = objectMapper;
        this.scoreColumn = scoreColumn;
    }

    @Override
    public ChunkRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        UUID documentId = rs.getObject("document_id", UUID.class);
        String text = rs.getString("text");

        List<String> headingPath = JdbcMappers.arrayToStringList(rs, "heading_path");

        String heading = rs.getString("heading");
        Integer depth = rs.getObject("depth", Integer.class);
        Integer sortOrder = rs.getObject("sort_order", Integer.class);
        String tag = rs.getString("tag");
        String documentTitle = rs.getString("document_title");
        String kind = rs.getString("kind");
        String collection = rs.getString("collection");

        Map<String, Object> metadata = JdbcMappers.jsonbToMap(rs, "metadata", objectMapper);

        double score = 0.0;
        if (scoreColumn != null) {
            score = rs.getDouble(scoreColumn);
        }

        return new ChunkRow(id, documentId, text, headingPath, heading, depth, sortOrder, tag,
            documentTitle, kind, collection, metadata, score);
    }
}
