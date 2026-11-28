package de.palsoftware.yvoke.rag.prompt;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SystemPromptRepository {

    /**
     * V17 stays non-destructive during the expand phase, so retired {@code CHAT} rows can coexist
     * in {@code system_prompts}. Read paths bind {@link SystemPromptType#allowedDbValues()} so enum
     * mapping never fails on legacy rows and adding a new enum constant stays single-sourced.
     */
    private static final String ALLOWED_TYPES_PREDICATE = "upper(btrim(type)) IN (:allowedTypes)";

    private final JdbcClient jdbcClient;

    public SystemPromptRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<SystemPrompt> findAll() {
        return jdbcClient
                .sql(
                        "SELECT name, type, system_prompt, description, created_at, updated_at, area "
                                + "FROM system_prompts WHERE "
                                + ALLOWED_TYPES_PREDICATE
                                + " ORDER BY name ASC")
                .param("allowedTypes", SystemPromptType.allowedDbValues())
                .query((rs, rowNum) -> mapRow(rs))
                .list();
    }

    public List<SystemPrompt> findByType(SystemPromptType type) {
        // upper(btrim(type)), not type = :type. This column has two producers that disagree on
        // case: the corpus importer in yvoke-exports writes KG/SUMMARIZE, older builds of this
        // class wrote lower case. A case-sensitive comparison silently returned nothing for
        // whichever spelling it was not asking about, and an empty list reads as "none are
        // configured".
        return jdbcClient
                .sql(
                        "SELECT name, type, system_prompt, description, created_at, updated_at, area FROM system_prompts WHERE upper(btrim(type)) = upper(btrim(:type)) ORDER BY name ASC")
                .param("type", type.dbValue())
                .query((rs, rowNum) -> mapRow(rs))
                .list();
    }

    public Optional<SystemPrompt> findByName(String name) {
        return jdbcClient
                .sql(
                        "SELECT name, type, system_prompt, description, created_at, updated_at, area "
                                + "FROM system_prompts WHERE name = :name AND "
                                + ALLOWED_TYPES_PREDICATE)
                .param("name", name)
                .param("allowedTypes", SystemPromptType.allowedDbValues())
                .query((rs, rowNum) -> mapRow(rs))
                .optional();
    }

    /**
     * Unfiltered lookup of a stored prompt's name, area, and whether its type is an active
     * ingestion type. Returns legacy {@code CHAT} rows as {@code ingestionType = false} so write
     * guards enforce cross-area ownership and refuse silent overwrites.
     */
    public Optional<StoredPromptIdentity> findStoredIdentity(String name) {
        return jdbcClient
                .sql(
                        """
                        SELECT name, area, upper(btrim(type)) IN (:allowedTypes) AS ingestion_type
                        FROM system_prompts
                        WHERE name = :name
                        """)
                .param("name", name)
                .param("allowedTypes", SystemPromptType.allowedDbValues())
                .query((rs, rowNum) ->
                        new StoredPromptIdentity(
                                rs.getString("name"),
                                rs.getString("area"),
                                rs.getBoolean("ingestion_type")))
                .optional();
    }

    private static SystemPrompt mapRow(ResultSet rs) throws SQLException {
        Timestamp cat = rs.getTimestamp("created_at");
        Timestamp uat = rs.getTimestamp("updated_at");
        return new SystemPrompt(
                rs.getString("name"),
                SystemPromptType.fromString(rs.getString("type")),
                rs.getString("system_prompt"),
                rs.getString("description"),
                cat != null ? cat.toInstant() : Instant.now(),
                uat != null ? uat.toInstant() : Instant.now(),
                false,
                rs.getString("area"));
    }

    public void upsert(
            String name,
            SystemPromptType type,
            String systemPrompt,
            String description,
            String area) {
        int updated =
                jdbcClient
                        .sql(
                                """
                                INSERT INTO system_prompts (name, type, system_prompt, description, area)
                                VALUES (:name, :type, :systemPrompt, :description, :area)
                                ON CONFLICT (name) DO UPDATE SET
                                    type = EXCLUDED.type,
                                    system_prompt = EXCLUDED.system_prompt,
                                    description = EXCLUDED.description,
                                    area = EXCLUDED.area,
                                    updated_at = CURRENT_TIMESTAMP
                                WHERE upper(btrim(system_prompts.type)) IN (:allowedTypes)
                                """)
                        .param("name", name)
                        .param("type", type.dbValue())
                        .param("systemPrompt", systemPrompt)
                        .param("description", description)
                        .param("area", area)
                        .param("allowedTypes", SystemPromptType.allowedDbValues())
                        .update();
        if (updated == 0) {
            throw new IllegalArgumentException(
                    "System prompt '"
                            + name
                            + "' is reserved by a retired prompt and cannot be overwritten.");
        }
    }

    public void delete(String name) {
        jdbcClient
                .sql("DELETE FROM system_prompts WHERE name = :name")
                .param("name", name)
                .update();
    }
}
