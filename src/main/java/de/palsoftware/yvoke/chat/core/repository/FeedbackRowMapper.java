package de.palsoftware.yvoke.chat.core.repository;

import de.palsoftware.yvoke.chat.core.model.Feedback;


import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;

public class FeedbackRowMapper implements RowMapper<Feedback> {
    @Override
    public Feedback mapRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp createdTs = rs.getTimestamp("created_at");
        Timestamp updatedTs = rs.getTimestamp("updated_at");

        return new Feedback(rs.getObject("id", UUID.class), rs.getObject("message_id", UUID.class),
            rs.getInt("rating"), rs.getString("comment"),
            createdTs != null ? createdTs.toInstant() : null,
            updatedTs != null ? updatedTs.toInstant() : null);
    }
}
