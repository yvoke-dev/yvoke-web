package de.palsoftware.yvoke.shared.audit.repository;

import de.palsoftware.yvoke.shared.audit.model.AuditLog;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AuditLogRepository {

    private static final Logger log = LoggerFactory.getLogger(AuditLogRepository.class);

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public AuditLogRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public void log(String entraOid, String action, @Nullable String target,
        @Nullable Map<String, Object> detail) {
        UUID id = UUID.randomUUID();
        String detailJson = null;
        if (detail != null && !detail.isEmpty()) {
            try {
                detailJson = objectMapper.writeValueAsString(detail);
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize audit log details for action {}: {}", action, detail,
                    e);
            }
        }

        String sql = """
            INSERT INTO audit_log (id, entra_oid, action, target, detail, created_at)
            VALUES (:id, :entraOid, :action, :target, :detail::jsonb, CURRENT_TIMESTAMP)
            """;

        jdbcClient.sql(sql).param("id", id).param("entraOid", entraOid).param("action", action)
            .param("target", target).param("detail", detailJson).update();
        log.info("Audit Logged: id={}, action={}, target={}", id, action, target);
    }

    public List<AuditLog> listLogs(int limit, int offset) {
        String sql = """
            SELECT id, entra_oid, action, target, detail::text AS detail_text, created_at
            FROM audit_log
            ORDER BY created_at DESC, id ASC
            LIMIT :limit OFFSET :offset
            """;

        return jdbcClient.sql(sql).param("limit", limit).param("offset", offset)
            .query(new AuditLogRowMapper(objectMapper)).list();
    }

    public long countLogs() {
        return jdbcClient.sql("SELECT count(*) FROM audit_log").query(Long.class).single();
    }
}
