package de.palsoftware.yvoke.chat.orchestration;

import de.palsoftware.yvoke.shared.config.JdbcMappers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;

@Repository
public class OrchestratorProfileRepository {

    private final JdbcClient jdbcClient;

    public OrchestratorProfileRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<OrchestratorProfile> findAll() {
        return jdbcClient
            .sql(
                """
                    SELECT name, max_review_rounds, max_specialist_calls, orchestrator_playbook, reviewer_playbook,
                           specialist_playbooks, orchestrator_model, orchestrator_thinking_level,
                           reviewer_model, reviewer_thinking_level, specialist_model, specialist_thinking_level,
                           prototype, created_at, updated_at
                    FROM orchestrator_profiles
                    ORDER BY name ASC
                    """)
            .query((rs, rowNum) -> mapRow(rs)).list();
    }

    public Optional<OrchestratorProfile> findByName(String name) {
        return jdbcClient
            .sql(
                """
                    SELECT name, max_review_rounds, max_specialist_calls, orchestrator_playbook, reviewer_playbook,
                           specialist_playbooks, orchestrator_model, orchestrator_thinking_level,
                           reviewer_model, reviewer_thinking_level, specialist_model, specialist_thinking_level,
                           prototype, created_at, updated_at
                    FROM orchestrator_profiles
                    WHERE name = :name
                    """)
            .param("name", name).query((rs, rowNum) -> mapRow(rs)).optional();
    }

    public void upsert(OrchestratorProfile profile) {
        jdbcClient
            .sql(
                """
                    INSERT INTO orchestrator_profiles (
                        name, max_review_rounds, max_specialist_calls, orchestrator_playbook, reviewer_playbook,
                        specialist_playbooks, orchestrator_model, orchestrator_thinking_level,
                        reviewer_model, reviewer_thinking_level, specialist_model, specialist_thinking_level,
                        prototype
                    ) VALUES (
                        :name, :maxReviewRounds, :maxSpecialistCalls, :orchestratorPlaybook, :reviewerPlaybook,
                        :specialistPlaybooks, :orchestratorModel, :orchestratorThinkingLevel,
                        :reviewerModel, :reviewerThinkingLevel, :specialistModel, :specialistThinkingLevel,
                        :prototype
                    ) ON CONFLICT (name) DO UPDATE SET
                        max_review_rounds = EXCLUDED.max_review_rounds,
                        max_specialist_calls = EXCLUDED.max_specialist_calls,
                        orchestrator_playbook = EXCLUDED.orchestrator_playbook,
                        reviewer_playbook = EXCLUDED.reviewer_playbook,
                        specialist_playbooks = EXCLUDED.specialist_playbooks,
                        orchestrator_model = EXCLUDED.orchestrator_model,
                        orchestrator_thinking_level = EXCLUDED.orchestrator_thinking_level,
                        reviewer_model = EXCLUDED.reviewer_model,
                        reviewer_thinking_level = EXCLUDED.reviewer_thinking_level,
                        specialist_model = EXCLUDED.specialist_model,
                        specialist_thinking_level = EXCLUDED.specialist_thinking_level,
                        prototype = EXCLUDED.prototype,
                        updated_at = CURRENT_TIMESTAMP
                    """)
            .param("name", profile.name()).param("maxReviewRounds", profile.maxReviewRounds())
            .param("maxSpecialistCalls", profile.maxSpecialistCalls())
            .param("orchestratorPlaybook", profile.orchestratorPlaybook())
            .param("reviewerPlaybook", profile.reviewerPlaybook())
            .param("specialistPlaybooks",
                profile.specialistPlaybooks() != null
                    ? profile.specialistPlaybooks().toArray(new String[0])
                    : new String[0])
            .param("orchestratorModel", profile.orchestratorModel())
            .param("orchestratorThinkingLevel", profile.orchestratorThinkingLevel())
            .param("reviewerModel", profile.reviewerModel())
            .param("reviewerThinkingLevel", profile.reviewerThinkingLevel())
            .param("specialistModel", profile.specialistModel())
            .param("specialistThinkingLevel", profile.specialistThinkingLevel())
            .param("prototype", profile.prototype()).update();
    }

    public void delete(String name) {
        jdbcClient.sql("DELETE FROM orchestrator_profiles WHERE name = :name").param("name", name)
            .update();
    }

    private OrchestratorProfile mapRow(ResultSet rs) throws SQLException {
        Timestamp cat = rs.getTimestamp("created_at");
        Timestamp uat = rs.getTimestamp("updated_at");
        List<String> specialistPlaybooks =
            JdbcMappers.arrayToStringList(rs, "specialist_playbooks");

        return new OrchestratorProfile(rs.getString("name"), rs.getInt("max_review_rounds"),
            rs.getInt("max_specialist_calls"), rs.getString("orchestrator_playbook"),
            rs.getString("reviewer_playbook"), specialistPlaybooks,
            rs.getString("orchestrator_model"), rs.getString("orchestrator_thinking_level"),
            rs.getString("reviewer_model"), rs.getString("reviewer_thinking_level"),
            rs.getString("specialist_model"), rs.getString("specialist_thinking_level"),
            rs.getBoolean("prototype"), cat != null ? cat.toInstant() : Instant.now(),
            uat != null ? uat.toInstant() : Instant.now());
    }
}
