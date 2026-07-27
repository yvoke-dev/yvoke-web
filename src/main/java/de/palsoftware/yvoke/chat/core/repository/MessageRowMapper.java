package de.palsoftware.yvoke.chat.core.repository;

import de.palsoftware.yvoke.shared.config.JdbcMappers;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.RowMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.chat.core.model.Message;
import de.palsoftware.yvoke.rag.core.service.CitationVerifier.CitationCheckResult;

public class MessageRowMapper implements RowMapper<Message> {
    private static final Logger log = LoggerFactory.getLogger(MessageRowMapper.class);
    private final ObjectMapper objectMapper;

    public MessageRowMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Message mapRow(ResultSet rs, int rowNum) throws SQLException {
        String citationsStr = rs.getString("citations_text");
        List<CitationCheckResult> citations = Collections.emptyList();
        if (citationsStr != null && !citationsStr.isBlank()) {
            try {
                citations = objectMapper.readValue(citationsStr,
                    new TypeReference<List<CitationCheckResult>>() {});
            } catch (JsonProcessingException e) {
                log.warn("Failed to deserialize citations JSONB: {}", citationsStr, e);
            }
        }

        List<UUID> retrievedChunkIds = JdbcMappers.arrayToUuidList(rs, "retrieved_chunk_ids");

        Timestamp createdTs = rs.getTimestamp("created_at");
        Integer promptTokens =
            rs.getObject("prompt_tokens") != null ? rs.getInt("prompt_tokens") : null;
        Integer completionTokens =
            rs.getObject("completion_tokens") != null ? rs.getInt("completion_tokens") : null;
        Integer totalTokens =
            rs.getObject("total_tokens") != null ? rs.getInt("total_tokens") : null;

        Integer cachedTokens =
            rs.getObject("cached_tokens") != null ? rs.getInt("cached_tokens") : null;
        Integer thoughtTokens =
            rs.getObject("thought_tokens") != null ? rs.getInt("thought_tokens") : null;

        return new Message(rs.getObject("id", UUID.class),
            rs.getObject("conversation_id", UUID.class), rs.getString("role"),
            rs.getString("content"), rs.getString("playbook"), retrievedChunkIds, citations,
            createdTs != null ? createdTs.toInstant() : null, promptTokens, completionTokens,
            totalTokens, cachedTokens, thoughtTokens, rs.getString("status"),
            rs.getString("model"));
    }
}
