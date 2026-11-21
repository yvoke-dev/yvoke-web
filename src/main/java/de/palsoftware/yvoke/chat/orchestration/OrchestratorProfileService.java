package de.palsoftware.yvoke.chat.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.area.core.AreaService;
import de.palsoftware.yvoke.chat.core.ChatProperties;
import de.palsoftware.yvoke.chat.core.model.ThinkingLevel;
import de.palsoftware.yvoke.shared.validation.SlugNames;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProperties.RoleConfig;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProperties.RoleDefaults;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class OrchestratorProfileService {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorProfileService.class);

    private final OrchestratorProfileRepository repository;
    private final OrchestratorProperties properties;
    private final ObjectMapper objectMapper;
    private final AreaService areaService;
    private final ChatProperties chatProperties;

    @Autowired
    public OrchestratorProfileService(OrchestratorProfileRepository repository,
        OrchestratorProperties properties, AreaService areaService, ChatProperties chatProperties) {
        this.areaService = Objects.requireNonNull(areaService, "areaService must not be null");
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.chatProperties =
            Objects.requireNonNull(chatProperties, "chatProperties must not be null");
        this.objectMapper = new ObjectMapper().findAndRegisterModules();
    }

    public List<OrchestratorProfile> listAllProfiles() {
        return repository.findAll();
    }

    /**
     * The dropdown's options, mapped to a view record so the page never carries a profile's
     * per-role model configuration. Prototype profiles are included and flagged rather than
     * filtered out: the client decides what to show, and the conversation's own selected profile
     * must stay in the list even when prototype visibility is off, or the dropdown reads "Single
     * playbook" over a conversation that is still in multi-agent mode.
     */
    public List<OrchestratorProfileOption> listProfileOptions() {
        return repository.findAll().stream()
            .map(p -> new OrchestratorProfileOption(p.name(), p.prototype(), p.area())).toList();
    }

    public Optional<OrchestratorProfile> getProfile(String name) {
        return repository.findByName(name);
    }

    /**
     * Saves a profile into its area after validating bounds, allowed models, and thinking levels.
     */
    public void saveProfile(OrchestratorProfile profile) {
        Objects.requireNonNull(profile, "profile must not be null");
        SlugNames.requireValidName(profile.name(), "Orchestrator profile");
        String storedArea = areaService.requireArea(profile.area());
        int maxRoundsLimit = properties.resolvedMaxReviewRoundsLimit();
        if (profile.maxReviewRounds() < 1 || profile.maxReviewRounds() > maxRoundsLimit) {
            throw new IllegalArgumentException(
                "maxReviewRounds must be between 1 and " + maxRoundsLimit + ".");
        }
        int maxCallsLimit = properties.resolvedMaxSpecialistCallsLimit();
        if (profile.maxSpecialistCalls() < 1 || profile.maxSpecialistCalls() > maxCallsLimit) {
            throw new IllegalArgumentException(
                "maxSpecialistCalls must be between 1 and " + maxCallsLimit + ".");
        }
        validateOptionalModel("orchestratorModel", profile.orchestratorModel());
        validateOptionalModel("reviewerModel", profile.reviewerModel());
        validateOptionalModel("specialistModel", profile.specialistModel());
        String orchThinking = ThinkingLevel.normalizeOptional(profile.orchestratorThinkingLevel());
        String revThinking = ThinkingLevel.normalizeOptional(profile.reviewerThinkingLevel());
        String specThinking = ThinkingLevel.normalizeOptional(profile.specialistThinkingLevel());

        OrchestratorProfile normalized = new OrchestratorProfile(profile.name().trim(),
            profile.maxReviewRounds(), profile.maxSpecialistCalls(), profile.orchestratorPlaybook(),
            profile.reviewerPlaybook(), profile.specialistPlaybooks(),
            trimToNull(profile.orchestratorModel()), orchThinking,
            trimToNull(profile.reviewerModel()), revThinking, trimToNull(profile.specialistModel()),
            specThinking, profile.prototype(), profile.createdAt(), profile.updatedAt(),
            storedArea);
        repository.upsert(normalized);
    }

    private void validateOptionalModel(String fieldName, String model) {
        if (model == null || model.isBlank()) {
            return;
        }
        List<String> allowed = chatProperties.allowedModels();
        if (allowed == null || allowed.isEmpty()) {
            throw new IllegalStateException("app.chat.allowed-models must not be empty.");
        }
        String trimmed = model.trim();
        if (!allowed.contains(trimmed)) {
            throw new IllegalArgumentException(
                fieldName + " '" + trimmed + "' is not in app.chat.allowed-models.");
        }
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
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

    /**
     * Imports a profile file. The area named in the file wins; a file that names none goes into
     * {@code fallbackArea}, the area picked on the import form.
     */
    public OrchestratorProfile importProfileFromJson(String jsonContent, String fallbackArea) {
        try {
            JsonNode root = objectMapper.readTree(jsonContent);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("Invalid JSON: expected an object.");
            }
            OrchestratorProfile profile = objectMapper.treeToValue(root, OrchestratorProfile.class);
            if (profile.name() == null || profile.name().isBlank()) {
                throw new IllegalArgumentException("Invalid JSON: profile name is required.");
            }
            if (profile.area() == null || profile.area().isBlank()) {
                profile = profile.withArea(fallbackArea);
            }
            // Only omitted or explicit null JSON fields inherit configured defaults; explicit 0 or
            // negative values are preserved so saveProfile rejects them.
            boolean roundsOmitted =
                !root.has("maxReviewRounds") || root.get("maxReviewRounds").isNull();
            boolean callsOmitted =
                !root.has("maxSpecialistCalls") || root.get("maxSpecialistCalls").isNull();
            int rounds =
                roundsOmitted ? properties.resolvedMaxReviewRounds() : profile.maxReviewRounds();
            int calls = callsOmitted ? properties.resolvedMaxSpecialistCalls()
                : profile.maxSpecialistCalls();
            if (rounds != profile.maxReviewRounds() || calls != profile.maxSpecialistCalls()) {
                profile = new OrchestratorProfile(profile.name(), rounds, calls,
                    profile.orchestratorPlaybook(), profile.reviewerPlaybook(),
                    profile.specialistPlaybooks(), profile.orchestratorModel(),
                    profile.orchestratorThinkingLevel(), profile.reviewerModel(),
                    profile.reviewerThinkingLevel(), profile.specialistModel(),
                    profile.specialistThinkingLevel(), profile.prototype(), profile.createdAt(),
                    profile.updatedAt(), profile.area());
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
        if (dbProfile.isPresent()) {
            return resolve(dbProfile.get());
        }
        return properties.resolve(name);
    }

    public ResolvedProfile resolve(OrchestratorProfile p) {
        Objects.requireNonNull(p, "profile must not be null");
        RoleDefaults d = properties.defaults() != null ? properties.defaults()
            : new RoleDefaults(null, null, null);
        RoleConfig defaultOrch =
            d.orchestrator() != null ? d.orchestrator() : new RoleConfig(null, null);
        RoleConfig defaultRev = d.reviewer() != null ? d.reviewer() : new RoleConfig(null, null);
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
            p.specialistPlaybooks() != null ? p.specialistPlaybooks() : List.of(), orch, rev, spec,
            p.maxReviewRounds() > 0 ? p.maxReviewRounds() : properties.resolvedMaxReviewRounds(),
            p.maxSpecialistCalls() > 0 ? p.maxSpecialistCalls()
                : properties.resolvedMaxSpecialistCalls());
    }
}
