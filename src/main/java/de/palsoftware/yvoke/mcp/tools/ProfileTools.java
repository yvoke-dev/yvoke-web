package de.palsoftware.yvoke.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.area.core.Area;
import de.palsoftware.yvoke.area.core.AreaService;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProfile;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProfileRepository;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProperties;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProperties.RoleConfig;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProperties.RoleDefaults;
import de.palsoftware.yvoke.mcp.ClientOnlyTools;
import de.palsoftware.yvoke.mcp.McpToolUtils;
import de.palsoftware.yvoke.shared.security.AreaAccessContext;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Multi-agent orchestrator profiles as client-only MCP tools for Yvoke clients. Reads directly from
 * {@code orchestrator_profiles} on every call and merges unset limits and per-role model/thinking
 * levels over {@link OrchestratorProperties}.
 */
@ClientOnlyTools
@Component
public class ProfileTools {

    private static final String LIST_DESCRIPTION =
        "For Yvoke clients only: call this only when the Yvoke plugin tells you to. Lists the "
            + "Yvoke multi-agent orchestrator profiles as a JSON array of {name, area, prototype, "
            + "orchestratorPlaybook, reviewerPlaybook, specialistPlaybooks, maxReviewRounds, "
            + "maxSpecialistCalls, orchestratorModel, orchestratorThinkingLevel, reviewerModel, "
            + "reviewerThinkingLevel, specialistModel, specialistThinkingLevel}. Pass area to list "
            + "only that area's profiles. It does not answer questions about the knowledge base.";

    private static final String GET_DESCRIPTION =
        "For Yvoke clients only: call this only when the Yvoke plugin tells you to, with the "
            + "profile name it gives. Returns that multi-agent orchestrator profile as a JSON "
            + "object {name, area, prototype, orchestratorPlaybook, reviewerPlaybook, "
            + "specialistPlaybooks, maxReviewRounds, maxSpecialistCalls, orchestratorModel, "
            + "orchestratorThinkingLevel, reviewerModel, reviewerThinkingLevel, specialistModel, "
            + "specialistThinkingLevel}.";

    private static final String AREA_PARAM =
        "Optional area name, e.g. \"OIM\" (see list_areas). Omit to list every area's profiles.";

    private static final String NAME_PARAM = "The orchestrator profile's name, e.g. \"OIM\".";

    private record ProfilePayload(String name, String area, boolean prototype,
        String orchestratorPlaybook, String reviewerPlaybook, List<String> specialistPlaybooks,
        int maxReviewRounds, int maxSpecialistCalls, String orchestratorModel,
        String orchestratorThinkingLevel, String reviewerModel, String reviewerThinkingLevel,
        String specialistModel, String specialistThinkingLevel) {}

    private final OrchestratorProfileRepository profileRepository;
    private final OrchestratorProperties properties;
    private final AreaService areaService;
    private final ObjectMapper objectMapper;

    public ProfileTools(OrchestratorProfileRepository profileRepository,
        OrchestratorProperties properties, AreaService areaService, ObjectMapper objectMapper) {
        this.profileRepository = profileRepository;
        this.properties = properties;
        this.areaService = areaService;
        this.objectMapper = objectMapper;
    }

    @McpTool(name = "list_profiles", description = LIST_DESCRIPTION)
    @Tool(name = "list_profiles", description = LIST_DESCRIPTION)
    public String listProfiles(@McpToolParam(description = AREA_PARAM, required = false)
    @ToolParam(description = AREA_PARAM, required = false) String area) {
        try {
            Predicate<OrchestratorProfile> inArea = profile -> true;
            if (area != null && !area.isBlank()) {
                Optional<String> stored = areaService.findArea(area).map(Area::name);
                inArea = profile -> stored.isPresent()
                    && stored.get().equals(blankToNull(profile.area()));
            }
            List<OrchestratorProfile> accessible = AreaAccessContext
                .filterByUserArea(profileRepository.findAll(), OrchestratorProfile::area);
            return objectMapper.writeValueAsString(
                accessible.stream().filter(inArea).map(this::toPayload).toList());
        } catch (Exception e) {
            return McpToolUtils.toolError("list_profiles", e);
        }
    }

    @McpTool(name = "get_profile", description = GET_DESCRIPTION)
    @Tool(name = "get_profile", description = GET_DESCRIPTION)
    public String getProfile(@McpToolParam(description = NAME_PARAM, required = true)
    @ToolParam(description = NAME_PARAM, required = true) String name) {
        if (name == null || name.isBlank()) {
            return "ERROR: 'name' is required for get_profile.";
        }
        String trimmed = name.trim();
        try {
            Optional<OrchestratorProfile> found = profileRepository.findByName(trimmed);
            if (found.isEmpty() || !AreaAccessContext.canAccessUserArea(found.get().area())) {
                return "ERROR: profile '" + trimmed + "' not found.";
            }
            return objectMapper.writeValueAsString(toPayload(found.get()));
        } catch (Exception e) {
            return McpToolUtils.toolError("get_profile", e);
        }
    }

    private ProfilePayload toPayload(OrchestratorProfile p) {
        RoleDefaults defaults = properties.defaults();
        RoleConfig defaultOrch = defaults == null ? null : defaults.orchestrator();
        RoleConfig defaultRev = defaults == null ? null : defaults.reviewer();
        RoleConfig defaultSpec = defaults == null ? null : defaults.specialist();

        List<String> specialists = p.specialistPlaybooks() == null ? List.of()
            : p.specialistPlaybooks().stream().filter(Objects::nonNull).map(String::trim)
                .filter(s -> !s.isEmpty()).toList();

        int maxReviewRounds =
            p.maxReviewRounds() >= 0 ? p.maxReviewRounds() : properties.resolvedMaxReviewRounds();
        int maxSpecialistCalls = p.maxSpecialistCalls() > 0 ? p.maxSpecialistCalls()
            : properties.resolvedMaxSpecialistCalls();

        return new ProfilePayload(blankToNull(p.name()), blankToNull(p.area()), p.prototype(),
            blankToNull(p.orchestratorPlaybook()), blankToNull(p.reviewerPlaybook()), specialists,
            maxReviewRounds, maxSpecialistCalls, resolveModel(p.orchestratorModel(), defaultOrch),
            resolveThinkingLevel(p.orchestratorThinkingLevel(), defaultOrch),
            resolveModel(p.reviewerModel(), defaultRev),
            resolveThinkingLevel(p.reviewerThinkingLevel(), defaultRev),
            resolveModel(p.specialistModel(), defaultSpec),
            resolveThinkingLevel(p.specialistThinkingLevel(), defaultSpec));
    }

    private static String resolveModel(String profileValue, RoleConfig defaultRole) {
        String candidate = blankToNull(profileValue);
        if (candidate != null) {
            return candidate;
        }
        return defaultRole == null ? null : blankToNull(defaultRole.model());
    }

    private static String resolveThinkingLevel(String profileValue, RoleConfig defaultRole) {
        String candidate = blankToNull(profileValue);
        if (candidate != null) {
            return candidate.toLowerCase(Locale.ROOT);
        }
        String fallback = defaultRole == null ? null : blankToNull(defaultRole.thinkingLevel());
        return fallback == null ? null : fallback.toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
