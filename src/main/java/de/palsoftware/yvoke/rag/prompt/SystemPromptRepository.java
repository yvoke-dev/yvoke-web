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
     * in {@code system_prompts}. Every read path filters to ingestion prompt types so enum mapping
     * never fails on legacy rows.
     */
    private static final String INGESTION_TYPE_FILTER = "upper(btrim(type)) IN ('KG', 'SUMMARIZE')";

    private final JdbcClient jdbcClient;

    public SystemPromptRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<SystemPrompt> findAll() {
        return jdbcClient
                .sql(
                        "SELECT name, type, system_prompt, description, created_at, updated_at, area "
                                + "FROM system_prompts WHERE "
                                + INGESTION_TYPE_FILTER
                                + " ORDER BY name ASC")
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
                                + INGESTION_TYPE_FILTER)
                .param("name", name)
                .query((rs, rowNum) -> mapRow(rs))
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
                        """)
                .param("name", name)
                .param("type", type.dbValue())
                .param("systemPrompt", systemPrompt)
                .param("description", description)
                .param("area", area)
                .update();
    }

    public void delete(String name) {
        jdbcClient
                .sql("DELETE FROM system_prompts WHERE name = :name")
                .param("name", name)
                .update();
    }
}
