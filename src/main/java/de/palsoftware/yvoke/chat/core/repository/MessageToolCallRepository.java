package de.palsoftware.yvoke.chat.core.repository;

import de.palsoftware.yvoke.chat.core.model.ToolCallRecord;
import de.palsoftware.yvoke.shared.config.JdbcMappers;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MessageToolCallRepository {

    private final JdbcClient jdbcClient;
    private final JdbcTemplate jdbcTemplate;

    public MessageToolCallRepository(JdbcClient jdbcClient, JdbcTemplate jdbcTemplate) {
        this.jdbcClient = jdbcClient;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Batch-inserts tool call trace records for a message. No-op if {@code records} is null or
     * empty.
     */
    public void insertAll(UUID messageId, List<ToolCallRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }

        String sql =
            """
                INSERT INTO message_tool_calls (id, message_id, seq, tool_call_id, tool_name, arguments, result, is_error, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, COALESCE(?, CURRENT_TIMESTAMP))
                """;

        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                ToolCallRecord record = records.get(i);
                ps.setObject(1, record.id() != null ? record.id() : UUID.randomUUID());
                ps.setObject(2, messageId);
                ps.setInt(3, record.seq());
                ps.setString(4, record.toolCallId());
                ps.setString(5, record.toolName());
                ps.setString(6, record.arguments());
                ps.setString(7, record.result());
                ps.setBoolean(8, record.isError());
                ps.setTimestamp(9,
                    record.createdAt() != null ? Timestamp.from(record.createdAt()) : null);
            }

            @Override
            public int getBatchSize() {
                return records.size();
            }
        });
    }

    /**
     * Retrieves all tool calls for the specified message ordered by sequence ascending.
     */
    public List<ToolCallRecord> findByMessageId(UUID messageId) {
        if (messageId == null) {
            return Collections.emptyList();
        }

        String sql =
            """
                SELECT id, message_id, seq, tool_call_id, tool_name, arguments, result, is_error, created_at
                FROM message_tool_calls
                WHERE message_id = :messageId
                ORDER BY seq ASC
                """;

        return jdbcClient.sql(sql).param("messageId", messageId)
            .query(MessageToolCallRepository::mapRow).list();
    }

    private static ToolCallRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ToolCallRecord(rs.getObject("id", UUID.class),
            rs.getObject("message_id", UUID.class), rs.getInt("seq"), rs.getString("tool_call_id"),
            rs.getString("tool_name"), rs.getString("arguments"), rs.getString("result"),
            rs.getBoolean("is_error"), JdbcMappers.toInstant(rs.getObject("created_at")));
    }
}
