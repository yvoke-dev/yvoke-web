package de.palsoftware.yvoke.mcp.tools;

import de.palsoftware.yvoke.area.core.Area;
import de.palsoftware.yvoke.area.core.AreaService;
import de.palsoftware.yvoke.mcp.McpToolUtils;
import de.palsoftware.yvoke.rag.prompt.SystemPrompt;
import de.palsoftware.yvoke.rag.prompt.SystemPromptService;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * The base instructions, for clients that cannot call the desktop's {@code GET
 * /api/chat/v1/prompts/system/{name}} — the Claude plugin has only its MCP sign-in.
 *
 * <p>
 * They are served by this tool and deliberately NOT as the MCP server's {@code instructions}: those
 * reach every session that connects, including users' ordinary coding sessions, and a session that
 * asks for them here would get them twice.
 *
 * <p>
 * An unknown name is an {@code ERROR:} rather than the empty string the REST endpoint returns. The
 * desktop runs on without a prompt; the plugin must refuse to start a session that has none, and it
 * can only do that if the failure is visible.
 */
@Component
public class GetSystemPromptTool {

    private static final Logger log = LoggerFactory.getLogger(GetSystemPromptTool.class);

    private static final String DESCRIPTION =
        "Base instructions for a Yvoke session. Call this only when a Yvoke playbook skill tells "
            + "you to, and follow the text it returns before answering. Returns the instructions "
            + "as plain text.";

    private static final String NAME_PARAM =
        "Prompt name. Omit for 'default-chat', the instructions an administrator has made active.";

    private static final String AREA_PARAM =
        "Optional area name, e.g. 'OIM' (see list_areas). With no prompt name, returns that area's "
            + "default instructions.";

    private final SystemPromptService systemPromptService;
    private final AreaService areaService;

    public GetSystemPromptTool(SystemPromptService systemPromptService, AreaService areaService) {
        this.systemPromptService = systemPromptService;
        this.areaService = areaService;
    }

    @McpTool(name = "get_system_prompt", description = DESCRIPTION)
    @Tool(name = "get_system_prompt", description = DESCRIPTION)
    public String getSystemPrompt(
        @McpToolParam(description = NAME_PARAM, required = false)
        @ToolParam(description = NAME_PARAM, required = false) String name,
        @McpToolParam(description = AREA_PARAM, required = false)
        @ToolParam(description = AREA_PARAM, required = false) String area) {
        try {
            String requested;
            if (name != null && !name.isBlank()) {
                requested = name.trim();
            } else if (area != null && !area.isBlank()) {
                Optional<Area> found = areaService.findArea(area);
                if (found.isEmpty()) {
                    return "ERROR: area '" + area.trim() + "' does not exist.";
                }
                requested = areaDefault(found.get());
            } else {
                requested = SystemPromptService.DEFAULT_CHAT;
            }
            log.info("GetSystemPromptTool: fetching system prompt '{}'", requested);
            return systemPromptService.findChatPrompt(requested).map(SystemPrompt::systemPrompt)
                .filter(text -> !text.isBlank())
                .orElse("ERROR: system prompt '" + requested + "' does not exist.");
        } catch (Exception e) {
            return McpToolUtils.toolError("get_system_prompt", e);
        }
    }

    /**
     * The area's default prompt, or {@code default-chat} (D-16, the admin's active prompt) when it
     * sets none. As in {@code list_areas}, a default that is not one of the area's chat prompts is
     * no default.
     */
    private String areaDefault(Area area) {
        String preferred = area.defaultSystemPrompt();
        if (preferred != null && systemPromptService.findChatPrompt(preferred)
            .filter(p -> area.name().equals(p.area())).isPresent()) {
            return preferred;
        }
        return SystemPromptService.DEFAULT_CHAT;
    }
}
