package de.palsoftware.yvoke.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.area.core.AreaService;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProfile;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProfileService;
import de.palsoftware.yvoke.chat.orchestration.ResolvedProfile;
import de.palsoftware.yvoke.mcp.ClientOnlyTools;
import de.palsoftware.yvoke.mcp.McpToolUtils;
import de.palsoftware.yvoke.shared.security.AreaAccessContext;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Multi-agent orchestrator profiles as client-only MCP tools for Yvoke clients. Reads directly from
 * {@code orchestrator_profiles} on every call via {@link OrchestratorProfileService} and resolves
 * effective review-round and specialist-call limits without exposing server-side model or
 * thinking-level bindings.
 */
@ClientOnlyTools
@Component
public class ProfileTools {

    private static final String LIST_DESCRIPTION =
        "For Yvoke clients only: call this only when the Yvoke plugin tells you to. Lists the "
            + "Yvoke multi-agent orchestrator profiles as a JSON array of {name, area, prototype, "
            + "orchestratorPlaybook, reviewerPlaybook, specialistPlaybooks, maxReviewRounds, "
            + "maxSpecialistCalls}. Pass area to list only that area's profiles. It does not "
            + "answer questions about the knowledge base.";

    private static final String GET_DESCRIPTION =
        "For Yvoke clients only: call this only when the Yvoke plugin tells you to, with the "
            + "profile name it gives. Returns that multi-agent orchestrator profile as a JSON "
            + "object {name, area, prototype, orchestratorPlaybook, reviewerPlaybook, "
            + "specialistPlaybooks, maxReviewRounds, maxSpecialistCalls}.";

    private static final String AREA_PARAM =
        "Optional area name, e.g. \"OIM\" (see list_areas). Omit to list every area's profiles.";

    private static final String NAME_PARAM = "The orchestrator profile's name, e.g. \"OIM\".";

    /**
     * Multi-agent profile exposed to MCP clients. As with {@code OrchestratorProfileDto}, per-role
     * model and thinking-level bindings are deliberately omitted: server profiles name server-side
     * provider deployments, whereas external clients bind their own models locally.
     */
    private record ProfilePayload(String name, String area, boolean prototype,
        String orchestratorPlaybook, String reviewerPlaybook, List<String> specialistPlaybooks,
        int maxReviewRounds, int maxSpecialistCalls) {

        static ProfilePayload from(OrchestratorProfile profile, ResolvedProfile resolved) {
            return new ProfilePayload(resolved.name(), profile.area(), profile.prototype(),
                resolved.orchestratorPlaybook(), resolved.reviewerPlaybook(),
                resolved.specialistPlaybooks(), resolved.maxReviewRounds(),
                resolved.maxSpecialistCalls());
        }
    }

    private final OrchestratorProfileService profileService;
    private final AreaService areaService;
    private final ObjectMapper objectMapper;

    public ProfileTools(OrchestratorProfileService profileService, AreaService areaService,
        ObjectMapper objectMapper) {
        this.profileService = profileService;
        this.areaService = areaService;
        this.objectMapper = objectMapper;
    }

    @McpTool(name = "list_profiles", description = LIST_DESCRIPTION)
    @Tool(name = "list_profiles", description = LIST_DESCRIPTION)
    public String listProfiles(@McpToolParam(description = AREA_PARAM, required = false)
    @ToolParam(description = AREA_PARAM, required = false) String area) {
        try {
            Predicate<OrchestratorProfile> inArea =
                McpToolUtils.inArea(areaService, area, OrchestratorProfile::area);
            List<OrchestratorProfile> accessible = AreaAccessContext
                .filterByUserArea(profileService.listAllProfiles(), OrchestratorProfile::area);
            return objectMapper.writeValueAsString(accessible.stream().filter(inArea)
                .map(p -> ProfilePayload.from(p, profileService.resolve(p))).toList());
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
        try {
            Optional<OrchestratorProfile> found = profileService.getProfile(name);
            if (found.isEmpty() || !AreaAccessContext.canAccessUserArea(found.get().area())) {
                return "ERROR: profile '" + name + "' not found.";
            }
            OrchestratorProfile profile = found.get();
            return objectMapper
                .writeValueAsString(ProfilePayload.from(profile, profileService.resolve(profile)));
        } catch (Exception e) {
            return McpToolUtils.toolError("get_profile", e);
        }
    }
}
