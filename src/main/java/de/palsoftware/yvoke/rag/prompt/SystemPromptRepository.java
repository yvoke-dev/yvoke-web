package de.palsoftware.yvoke.rag.prompt;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SystemPromptRepository {

    private final JdbcClient jdbcClient;

    public SystemPromptRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<SystemPrompt> findAll() {
        return jdbcClient.sql(
            "SELECT name, type, system_prompt, description, created_at, updated_at FROM system_prompts ORDER BY name ASC")
            .query((rs, rowNum) -> {
                Timestamp cat = rs.getTimestamp("created_at");
                Timestamp uat = rs.getTimestamp("updated_at");
                return new SystemPrompt(rs.getString("name"),
                    SystemPromptType.fromString(rs.getString("type")),
                    rs.getString("system_prompt"), rs.getString("description"),
                    cat != null ? cat.toInstant() : Instant.now(),
                    uat != null ? uat.toInstant() : Instant.now(), false);
            }).list();
    }

    public List<SystemPrompt> findByType(SystemPromptType type) {
        // upper(type), not type = :type. This column has two producers that disagree on case: the
        // corpus importer in yvoke-exports writes CHAT/KG/SUMMARIZE, older builds of this class
        // wrote lower case. A case-sensitive comparison silently returned nothing for whichever
        // spelling it was not asking about, and an empty list reads as "none are configured".
        return jdbcClient.sql(
            "SELECT name, type, system_prompt, description, created_at, updated_at FROM system_prompts WHERE upper(type) = upper(:type) ORDER BY name ASC")
            .param("type", type.dbValue()).query((rs, rowNum) -> {
                Timestamp cat = rs.getTimestamp("created_at");
                Timestamp uat = rs.getTimestamp("updated_at");
                return new SystemPrompt(rs.getString("name"),
                    SystemPromptType.fromString(rs.getString("type")),
                    rs.getString("system_prompt"), rs.getString("description"),
                    cat != null ? cat.toInstant() : Instant.now(),
                    uat != null ? uat.toInstant() : Instant.now(), false);
            }).list();
    }

    public Optional<SystemPrompt> findByName(String name) {
        return jdbcClient.sql(
            "SELECT name, type, system_prompt, description, created_at, updated_at FROM system_prompts WHERE name = :name")
            .param("name", name).query((rs, rowNum) -> {
                Timestamp cat = rs.getTimestamp("created_at");
                Timestamp uat = rs.getTimestamp("updated_at");
                return new SystemPrompt(rs.getString("name"),
                    SystemPromptType.fromString(rs.getString("type")),
                    rs.getString("system_prompt"), rs.getString("description"),
                    cat != null ? cat.toInstant() : Instant.now(),
                    uat != null ? uat.toInstant() : Instant.now(), false);
            }).optional();
    }

    public void upsert(String name, SystemPromptType type, String systemPrompt,
        String description) {
        jdbcClient.sql("""
            INSERT INTO system_prompts (name, type, system_prompt, description)
            VALUES (:name, :type, :systemPrompt, :description)
            ON CONFLICT (name) DO UPDATE SET
                type = EXCLUDED.type,
                system_prompt = EXCLUDED.system_prompt,
                description = EXCLUDED.description,
                updated_at = CURRENT_TIMESTAMP
            """).param("name", name).param("type", type.dbValue())
            .param("systemPrompt", systemPrompt).param("description", description).update();
    }

    public void delete(String name) {
        jdbcClient.sql("DELETE FROM system_prompts WHERE name = :name").param("name", name)
            .update();
    }
}
