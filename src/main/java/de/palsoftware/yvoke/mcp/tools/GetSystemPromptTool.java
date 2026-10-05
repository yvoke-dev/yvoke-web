package de.palsoftware.yvoke.mcp.tools;

import de.palsoftware.yvoke.mcp.McpToolUtils;
import de.palsoftware.yvoke.rag.prompt.SystemPrompt;
import de.palsoftware.yvoke.rag.prompt.SystemPromptService;
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

    private final SystemPromptService systemPromptService;

    public GetSystemPromptTool(SystemPromptService systemPromptService) {
        this.systemPromptService = systemPromptService;
    }

    @McpTool(name = "get_system_prompt", description = DESCRIPTION)
    @Tool(name = "get_system_prompt", description = DESCRIPTION)
    public String getSystemPrompt(@McpToolParam(description = NAME_PARAM, required = false)
    @ToolParam(description = NAME_PARAM, required = false) String name) {
        String requested =
            (name == null || name.isBlank()) ? SystemPromptService.DEFAULT_CHAT : name.trim();
        log.info("GetSystemPromptTool: fetching system prompt '{}'", requested);
        try {
            return systemPromptService.findChatPrompt(requested).map(SystemPrompt::systemPrompt)
                .filter(text -> !text.isBlank())
                .orElse("ERROR: system prompt '" + requested + "' does not exist.");
        } catch (Exception e) {
            return McpToolUtils.toolError("get_system_prompt", e);
        }
    }
}
