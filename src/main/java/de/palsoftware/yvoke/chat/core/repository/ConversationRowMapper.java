package de.palsoftware.yvoke.chat.core.repository;

import de.palsoftware.yvoke.chat.core.model.Conversation;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.shared.config.JdbcMappers;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.RowMapper;

public class ConversationRowMapper implements RowMapper<Conversation> {
    private static final Logger log = LoggerFactory.getLogger(ConversationRowMapper.class);
    private final ObjectMapper objectMapper;

    public ConversationRowMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Conversation mapRow(ResultSet rs, int rowNum) throws SQLException {
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

        Timestamp createdTs = rs.getTimestamp("created_at");
        Timestamp updatedTs = rs.getTimestamp("updated_at");

        List<String> tags = JdbcMappers.arrayToStringList(rs, "tags");

        return new Conversation(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
            rs.getString("title"), settings, createdTs != null ? createdTs.toInstant() : null,
            updatedTs != null ? updatedTs.toInstant() : null, tags, rs.getString("source"));
    }
}

