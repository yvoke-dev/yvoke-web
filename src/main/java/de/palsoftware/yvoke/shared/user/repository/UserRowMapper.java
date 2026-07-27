package de.palsoftware.yvoke.shared.user.repository;

import de.palsoftware.yvoke.shared.user.model.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;

public class UserRowMapper implements RowMapper<User> {
    @Override
    public User mapRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp lastSeenTs = rs.getTimestamp("last_seen_at");
        return new User(rs.getObject("id", UUID.class), rs.getString("entra_oid"),
            rs.getString("email"), rs.getString("display_name"),
            lastSeenTs != null ? lastSeenTs.toInstant() : null);
    }
}
