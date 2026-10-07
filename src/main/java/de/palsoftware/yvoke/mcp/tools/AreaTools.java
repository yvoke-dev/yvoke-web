package de.palsoftware.yvoke.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.area.core.AreaService;
import de.palsoftware.yvoke.area.core.AreaWithMembers;
import de.palsoftware.yvoke.mcp.McpToolUtils;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * The areas, for the Yvoke plugin's setup band: each area with its defaults and the names of its
 * members. Read from the tables on every call, so a new area or a moved item shows at once.
 *
 * <p>
 * A default is reported only when it is a member the client can pick (see {@link AreaWithMembers});
 * anything else would start a session on an item the setup band does not list.
 */
@Component
public class AreaTools {

    private static final String DESCRIPTION =
        "For Yvoke clients only: call this only when a Yvoke playbook skill or the Yvoke plugin "
            + "tells you to. Lists the Yvoke areas (knowledge bases such as OIM) as a JSON array of "
            + "{name, title, description, prototype, defaultSystemPrompt, defaultPlaybook, "
            + "defaultProfile, systemPrompts, collections, playbooks, profiles}, where the last "
            + "four are the names of the area's members. It does not answer questions about the "
            + "knowledge base.";

    /** One area as {@code list_areas} returns it. */
    record AreaDto(String name, String title, String description, boolean prototype,
        String defaultSystemPrompt, String defaultPlaybook, String defaultProfile,
        List<String> systemPrompts, List<String> collections, List<String> playbooks,
        List<String> profiles) {

        static AreaDto from(AreaWithMembers a) {
            return new AreaDto(a.area().name(), a.area().title(), a.area().description(),
                a.area().prototype(), a.defaultSystemPrompt(), a.defaultPlaybook(),
                a.defaultProfile(), a.members().systemPrompts(), a.members().collections(),
                a.members().playbooks(), a.members().profiles());
        }
    }

    private final AreaService areaService;
    private final ObjectMapper objectMapper;

    public AreaTools(AreaService areaService, ObjectMapper objectMapper) {
        this.areaService = areaService;
        this.objectMapper = objectMapper;
    }

    @McpTool(name = "list_areas", description = DESCRIPTION)
    @Tool(name = "list_areas", description = DESCRIPTION)
    public String listAreas() {
        try {
            return objectMapper.writeValueAsString(
                areaService.listAreasWithMembers().stream().map(AreaDto::from).toList());
        } catch (Exception e) {
            return McpToolUtils.toolError("list_areas", e);
        }
    }
}
