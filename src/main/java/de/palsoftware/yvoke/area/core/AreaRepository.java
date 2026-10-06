package de.palsoftware.yvoke.area.core;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The {@code areas} table. Membership is not stored here: each member table carries its own
 * {@code area} column with a foreign key to {@code areas(name)}, so a rename carries over to every
 * member and an area that still has members cannot be deleted, both enforced by the database.
 */
@Repository
public class AreaRepository {

    private static final String COLUMNS = """
        name, title, description, prototype, default_system_prompt, default_playbook,
        default_profile, created_at, updated_at""";

    private final JdbcClient jdbcClient;

    public AreaRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<Area> findAll() {
        return jdbcClient.sql("SELECT " + COLUMNS + " FROM areas ORDER BY name ASC")
            .query((rs, rowNum) -> mapRow(rs)).list();
    }

    public Optional<Area> findByName(String name) {
        return jdbcClient.sql("SELECT " + COLUMNS + " FROM areas WHERE name = :name")
            .param("name", name).query((rs, rowNum) -> mapRow(rs)).optional();
    }

    /** Lists the area names alone, for the area selects on the member admin forms. */
    public List<String> findAllNames() {
        return jdbcClient.sql("SELECT name FROM areas ORDER BY name ASC").query(String.class)
            .list();
    }

    public void upsert(Area area) {
        jdbcClient.sql("""
            INSERT INTO areas (name, title, description, prototype, default_system_prompt,
                               default_playbook, default_profile)
            VALUES (:name, :title, :description, :prototype, :defaultSystemPrompt,
                    :defaultPlaybook, :defaultProfile)
            ON CONFLICT (name) DO UPDATE SET
                title = EXCLUDED.title,
                description = EXCLUDED.description,
                prototype = EXCLUDED.prototype,
                default_system_prompt = EXCLUDED.default_system_prompt,
                default_playbook = EXCLUDED.default_playbook,
                default_profile = EXCLUDED.default_profile,
                updated_at = CURRENT_TIMESTAMP
            """).param("name", area.name()).param("title", area.title())
            .param("description", area.description()).param("prototype", area.prototype())
            .param("defaultSystemPrompt", area.defaultSystemPrompt())
            .param("defaultPlaybook", area.defaultPlaybook())
            .param("defaultProfile", area.defaultProfile()).update();
    }

    /** Renames an area; {@code ON UPDATE CASCADE} carries the new name to every member. */
    public void rename(String oldName, String newName) {
        jdbcClient.sql(
            "UPDATE areas SET name = :newName, updated_at = CURRENT_TIMESTAMP WHERE name = :oldName")
            .param("oldName", oldName).param("newName", newName).update();
    }

    /** Deletes an area; refused by the database while the area still has members. */
    public void delete(String name) {
        jdbcClient.sql("DELETE FROM areas WHERE name = :name").param("name", name).update();
    }

    private static Area mapRow(ResultSet rs) throws SQLException {
        Timestamp cat = rs.getTimestamp("created_at");
        Timestamp uat = rs.getTimestamp("updated_at");
        return new Area(rs.getString("name"), rs.getString("title"), rs.getString("description"),
            rs.getBoolean("prototype"), rs.getString("default_system_prompt"),
            rs.getString("default_playbook"), rs.getString("default_profile"),
            cat != null ? cat.toInstant() : Instant.now(),
            uat != null ? uat.toInstant() : Instant.now());
    }
}
