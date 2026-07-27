package de.palsoftware.yvoke.shared.audit.repository;

import de.palsoftware.yvoke.shared.audit.model.AuditLog;


import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.shared.config.JdbcMappers;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;

public class AuditLogRowMapper implements RowMapper<AuditLog> {

    private final ObjectMapper objectMapper;

    public AuditLogRowMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public AuditLog mapRow(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> detail = JdbcMappers.jsonbToMap(rs, "detail_text", objectMapper);

        return new AuditLog(rs.getObject("id", UUID.class), rs.getString("entra_oid"),
            rs.getString("action"), rs.getString("target"), detail,
            rs.getObject("created_at", OffsetDateTime.class));
    }
}
