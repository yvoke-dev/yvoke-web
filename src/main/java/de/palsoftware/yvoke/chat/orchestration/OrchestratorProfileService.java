package de.palsoftware.yvoke.chat.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProperties.RoleConfig;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProperties.RoleDefaults;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class OrchestratorProfileService {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorProfileService.class);

    private final OrchestratorProfileRepository repository;
    private final OrchestratorProperties properties;
    private final ObjectMapper objectMapper;

    public OrchestratorProfileService(OrchestratorProfileRepository repository,
        OrchestratorProperties properties) {
        this.repository = repository;
        this.properties = properties;
        this.objectMapper = new ObjectMapper().findAndRegisterModules();
    }

    public List<OrchestratorProfile> listAllProfiles() {
        return repository.findAll();
    }

    public List<String> listProfileNames() {
        return repository.findAll().stream().map(OrchestratorProfile::name).toList();
    }

    public Optional<OrchestratorProfile> getProfile(String name) {
        return repository.findByName(name);
    }

    public void saveProfile(OrchestratorProfile profile) {
        repository.upsert(profile);
    }

    public void deleteProfile(String name) {
        repository.delete(name);
    }

    public String exportProfileToJson(String name) {
        OrchestratorProfile profile = getProfile(name).orElseThrow(
            () -> new IllegalArgumentException("Orchestrator profile '" + name + "' not found."));
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(profile);
        } catch (Exception e) {
            throw new IllegalStateException(
                "Failed to export orchestrator profile: " + e.getMessage(), e);
        }
    }

    public OrchestratorProfile importProfileFromJson(String jsonContent) {
        try {
            OrchestratorProfile profile =
                objectMapper.readValue(jsonContent, OrchestratorProfile.class);
            if (profile.name() == null || profile.name().isBlank()) {
                throw new IllegalArgumentException("Invalid JSON: profile name is required.");
            }
            saveProfile(profile);
            return getProfile(profile.name()).orElse(profile);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "Failed to parse orchestrator profile JSON: " + e.getMessage(), e);
        }
    }

    public ResolvedProfile resolve(String name) {
        Optional<OrchestratorProfile> dbProfile = repository.findByName(name);
        RoleDefaults d = properties.defaults() != null ? properties.defaults()
            : new RoleDefaults(null, null, null);

        if (dbProfile.isPresent()) {
            OrchestratorProfile p = dbProfile.get();
            RoleConfig defaultOrch =
                d.orchestrator() != null ? d.orchestrator() : new RoleConfig(null, null);
            RoleConfig defaultRev =
                d.reviewer() != null ? d.reviewer() : new RoleConfig(null, null);
            RoleConfig defaultSpec =
                d.specialist() != null ? d.specialist() : new RoleConfig(null, null);

            RoleConfig orch = new RoleConfig(
                p.orchestratorModel() != null && !p.orchestratorModel().isBlank()
                    ? p.orchestratorModel()
                    : defaultOrch.model(),
                p.orchestratorThinkingLevel() != null && !p.orchestratorThinkingLevel().isBlank()
                    ? p.orchestratorThinkingLevel()
                    : defaultOrch.thinkingLevel());
            RoleConfig rev = new RoleConfig(
                p.reviewerModel() != null && !p.reviewerModel().isBlank() ? p.reviewerModel()
                    : defaultRev.model(),
                p.reviewerThinkingLevel() != null && !p.reviewerThinkingLevel().isBlank()
                    ? p.reviewerThinkingLevel()
                    : defaultRev.thinkingLevel());
            RoleConfig spec = new RoleConfig(
                p.specialistModel() != null && !p.specialistModel().isBlank() ? p.specialistModel()
                    : defaultSpec.model(),
                p.specialistThinkingLevel() != null && !p.specialistThinkingLevel().isBlank()
                    ? p.specialistThinkingLevel()
                    : defaultSpec.thinkingLevel());

            return new ResolvedProfile(p.name(), p.orchestratorPlaybook(), p.reviewerPlaybook(),
                p.specialistPlaybooks(), orch, rev, spec,
                p.maxReviewRounds() > 0 ? p.maxReviewRounds()
                    : properties.resolvedMaxReviewRounds(),
                p.maxSpecialistCalls() > 0 ? p.maxSpecialistCalls()
                    : properties.resolvedMaxSpecialistCalls());
        }

        return properties.resolve(name);
    }
}
