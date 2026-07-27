package de.palsoftware.yvoke.shared.user.repository;

import de.palsoftware.yvoke.shared.user.model.*;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {
    private final JdbcClient jdbcClient;
    private final UserRowMapper userRowMapper = new UserRowMapper();

    public UserRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void upsert(String entraOid, String email, String displayName) {
        String sql = """
            INSERT INTO users (id, entra_oid, email, display_name, last_seen_at, updated_at)
            VALUES (:id, :entraOid, :email, :displayName, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (entra_oid) DO UPDATE
            SET email = EXCLUDED.email,
                display_name = EXCLUDED.display_name,
                last_seen_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            """;
        jdbcClient.sql(sql).param("id", UUID.randomUUID()).param("entraOid", entraOid)
            .param("email", email).param("displayName", displayName).update();
    }

    public Optional<User> findByEntraOid(String entraOid) {
        String sql = """
            SELECT id, entra_oid, email, display_name, last_seen_at
            FROM users
            WHERE entra_oid = :entraOid
            """;
        return jdbcClient.sql(sql).param("entraOid", entraOid).query(userRowMapper).optional();
    }

    public Optional<User> findById(UUID id) {
        String sql = """
            SELECT id, entra_oid, email, display_name, last_seen_at
            FROM users
            WHERE id = :id
            """;
        return jdbcClient.sql(sql).param("id", id).query(userRowMapper).optional();
    }
}
