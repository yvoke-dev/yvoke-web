package de.palsoftware.yvoke.rag.prompt;

import de.palsoftware.yvoke.shared.config.JdbcMappers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PlaybookRepository {

    private final JdbcClient jdbcClient;

    public PlaybookRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<Playbook> findAll() {
        return jdbcClient.sql(
            "SELECT name, title, description, template_text, tools, code_execution, target_agent, created_at, updated_at FROM playbooks ORDER BY name ASC")
            .query((rs, rowNum) -> {
                Timestamp cat = rs.getTimestamp("created_at");
                Timestamp uat = rs.getTimestamp("updated_at");
                List<String> tools = JdbcMappers.arrayToStringList(rs, "tools");
                String targetAgent = rs.getString("target_agent");
                if (targetAgent == null || targetAgent.isBlank()) {
                    targetAgent = "specialist";
                }
                return new Playbook(rs.getString("name"), rs.getString("title"),
                    rs.getString("description"), rs.getString("template_text"), tools,
                    rs.getBoolean("code_execution"), targetAgent,
                    cat != null ? cat.toInstant() : Instant.now(),
                    uat != null ? uat.toInstant() : Instant.now());
            }).list();
    }

    public Optional<Playbook> findByName(String name) {
        return jdbcClient.sql(
            "SELECT name, title, description, template_text, tools, code_execution, target_agent, created_at, updated_at FROM playbooks WHERE name = :name")
            .param("name", name).query((rs, rowNum) -> {
                Timestamp cat = rs.getTimestamp("created_at");
                Timestamp uat = rs.getTimestamp("updated_at");
                List<String> tools = JdbcMappers.arrayToStringList(rs, "tools");
                String targetAgent = rs.getString("target_agent");
                if (targetAgent == null || targetAgent.isBlank()) {
                    targetAgent = "specialist";
                }
                return new Playbook(rs.getString("name"), rs.getString("title"),
                    rs.getString("description"), rs.getString("template_text"), tools,
                    rs.getBoolean("code_execution"), targetAgent,
                    cat != null ? cat.toInstant() : Instant.now(),
                    uat != null ? uat.toInstant() : Instant.now());
            }).optional();
    }

    public void upsert(String name, String title, String description, String templateText,
        List<String> tools, boolean codeExecution) {
        upsert(name, title, description, templateText, tools, codeExecution, "specialist");
    }

    public void upsert(String name, String title, String description, String templateText,
        List<String> tools, boolean codeExecution, String targetAgent) {
        String agent = targetAgent != null && !targetAgent.isBlank() ? targetAgent : "specialist";
        jdbcClient
            .sql(
                """
                    INSERT INTO playbooks (name, title, description, template_text, tools, code_execution, target_agent)
                    VALUES (:name, :title, :description, :templateText, :tools, :codeExecution, :targetAgent)
                    ON CONFLICT (name) DO UPDATE SET
                        title = EXCLUDED.title,
                        description = EXCLUDED.description,
                        template_text = EXCLUDED.template_text,
                        tools = EXCLUDED.tools,
                        code_execution = EXCLUDED.code_execution,
                        target_agent = EXCLUDED.target_agent,
                        updated_at = CURRENT_TIMESTAMP
                    """)
            .param("name", name).param("title", title).param("description", description)
            .param("templateText", templateText)
            .param("tools", tools != null ? tools.toArray(new String[0]) : new String[0])
            .param("codeExecution", codeExecution).param("targetAgent", agent).update();
    }

    public void delete(String name) {
        jdbcClient.sql("DELETE FROM playbooks WHERE name = :name").param("name", name).update();
    }
}
