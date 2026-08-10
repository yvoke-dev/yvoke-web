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

    /**
     * Upserts the caller's identity, preserving any column the caller could not supply.
     *
     * <p>
     * The COALESCE is load-bearing. This runs from two places with different claim availability:
     * {@code UserService.syncUser} on browser login reads the OIDC ID token, where {@code name} and
     * {@code preferred_username} are present by default; {@code UserService.getCurrentUser} runs on
     * EVERY bearer request (MCP, desktop API) and reads the ACCESS token, where those are Entra
     * *optional* claims that are absent unless the app registration adds them — and it passes what
     * it found straight through, nulls included. A plain {@code SET email = EXCLUDED.email} let one
     * MCP call blank what a browser login had stored, permanently until the next browser login, and
     * then again on the next MCP call. Nothing errors; it surfaces only as blank names in the cost
     * dashboard's user picker and top-users report. {@code last_seen_at} is deliberately NOT
     * coalesced — recording bearer traffic is this upsert's other job.
     */
    public void upsert(String entraOid, String email, String displayName) {
        String sql = """
            INSERT INTO users (id, entra_oid, email, display_name, last_seen_at, updated_at)
            VALUES (:id, :entraOid, :email, :displayName, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (entra_oid) DO UPDATE
            SET email = COALESCE(EXCLUDED.email, users.email),
                display_name = COALESCE(EXCLUDED.display_name, users.display_name),
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
