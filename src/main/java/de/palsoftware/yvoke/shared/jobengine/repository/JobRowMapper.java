package de.palsoftware.yvoke.shared.jobengine.repository;

import de.palsoftware.yvoke.shared.jobengine.model.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.shared.config.JdbcMappers;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.RowMapper;

public class JobRowMapper implements RowMapper<IngestionJob> {
    private static final Logger log = LoggerFactory.getLogger(JobRowMapper.class);
    private final ObjectMapper objectMapper;

    public JobRowMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public IngestionJob mapRow(ResultSet rs, int rowNum) throws SQLException {
        JobCounts counts = null;
        int docCount = rs.getInt("doc_count");
        boolean docNull = rs.wasNull();
        int chunkCount = rs.getInt("chunk_count");
        int entityCount = rs.getInt("entity_count");
        int edgeCount = rs.getInt("edge_count");
        int jsonObjectCount = rs.getInt("json_object_count");
        int skippedEntityCount = rs.getInt("skipped_entity_count");
        int skippedEdgeCount = rs.getInt("skipped_edge_count");
        if (!docNull) {
            counts = new JobCounts(docCount, chunkCount, entityCount, edgeCount, jsonObjectCount,
                skippedEntityCount, skippedEdgeCount);
        }

        String settingsStr = rs.getString("settings_text");
        Map<String, Object> settings = Collections.emptyMap();
        if (settingsStr != null && !settingsStr.isBlank()) {
            try {
                settings = objectMapper.readValue(settingsStr,
                    new TypeReference<Map<String, Object>>() {});
            } catch (JsonProcessingException e) {
                log.warn("Failed to deserialize settings JSONB: {}", settingsStr, e);
            }
        }

        List<String> tags = JdbcMappers.arrayToStringList(rs, "tags");

        return new IngestionJob(rs.getObject("id", UUID.class), rs.getString("kind"),
            rs.getString("source_ref"), tags, rs.getObject("collection_id", UUID.class),
            rs.getString("collection"), JobStatus.fromDbValue(rs.getString("status")),
            JobStep.fromDbValue(rs.getString("step")), rs.getInt("progress"), rs.getInt("attempts"),
            rs.getString("error"), counts, rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("started_at", OffsetDateTime.class),
            rs.getObject("finished_at", OffsetDateTime.class), settings, rs.getString("summary"));
    }
}
