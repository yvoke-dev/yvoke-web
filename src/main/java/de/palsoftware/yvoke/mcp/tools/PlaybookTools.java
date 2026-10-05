package de.palsoftware.yvoke.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.chat.api.model.PlaybookDto;
import de.palsoftware.yvoke.mcp.McpToolUtils;
import de.palsoftware.yvoke.rag.prompt.Playbook;
import de.palsoftware.yvoke.rag.prompt.PlaybookService;
import java.util.List;
import java.util.Optional;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * The playbook library as tools, for Claude clients. The Yvoke plugin's mod can call MCP tools but
 * cannot read MCP prompts, and in Claude Chat and Cowork a playbook skill tells the model to fetch
 * the playbook it stands for. Both read the table on every call, so unlike the prompt list a new or
 * deleted playbook shows at once.
 *
 * <p>
 * The list is the same selection and shape as the desktop's {@code GET /api/chat/v1/playbooks}
 * (orchestrator and reviewer playbooks left out, since a user cannot pick one), while
 * {@code get_playbook} resolves any name, as MCP {@code prompts/get} does.
 */
@Component
public class PlaybookTools {

    private static final String LIST_DESCRIPTION =
        "For Yvoke clients only: call this only when a Yvoke playbook skill or the Yvoke plugin "
            + "tells you to. Lists the Yvoke playbooks a user can pick, as a JSON array of "
            + "{name, title, description, tools, codeExecution, targetAgent, prototype}. "
            + "It does not answer questions about the knowledge base.";

    private static final String GET_DESCRIPTION =
        "For Yvoke clients only: call this only when a Yvoke playbook skill or the Yvoke plugin "
            + "tells you to, with the playbook name it gives. Returns that playbook as a JSON object "
            + "{name, title, description, tools, codeExecution, targetAgent, prototype, text}, "
            + "where text is the playbook's full instructions.";

    private static final String NAME_PARAM = "The playbook's name, e.g. \"oim-full\".";

    /** One playbook with its text: {@link PlaybookDto}'s fields plus {@code text}. */
    record PlaybookWithText(String name, String title, String description, List<String> tools,
        boolean codeExecution, String targetAgent, boolean prototype, String text) {

        static PlaybookWithText from(Playbook playbook) {
            PlaybookDto meta = PlaybookDto.from(playbook);
            return new PlaybookWithText(meta.name(), meta.title(), meta.description(),
                meta.tools(), meta.codeExecution(), meta.targetAgent(), meta.prototype(),
                playbook.templateText());
        }
    }

    private final PlaybookService playbookService;
    private final ObjectMapper objectMapper;

    /**
     * {@code @Lazy} breaks a startup cycle: {@code PlaybookService} needs the
     * {@code McpSyncServer}, which needs the tool list that {@code McpToolsConfig} builds from this
     * bean. Without it the bean fails to build and {@code McpToolsConfig} only logs that, so both
     * tools silently vanish from {@code tools/list}.
     */
    public PlaybookTools(@Lazy PlaybookService playbookService, ObjectMapper objectMapper) {
        this.playbookService = playbookService;
        this.objectMapper = objectMapper;
    }

    @McpTool(name = "list_playbooks", description = LIST_DESCRIPTION)
    @Tool(name = "list_playbooks", description = LIST_DESCRIPTION)
    public String listPlaybooks() {
        try {
            return objectMapper.writeValueAsString(playbookService.listSpecializedPlaybooks()
                .stream().map(PlaybookDto::from).toList());
        } catch (Exception e) {
            return McpToolUtils.toolError("list_playbooks", e);
        }
    }

    @McpTool(name = "get_playbook", description = GET_DESCRIPTION)
    @Tool(name = "get_playbook", description = GET_DESCRIPTION)
    public String getPlaybook(@McpToolParam(description = NAME_PARAM, required = true)
    @ToolParam(description = NAME_PARAM, required = true) String name) {
        if (name == null || name.isBlank()) {
            return "ERROR: 'name' is required for get_playbook.";
        }
        String trimmed = name.trim();
        try {
            Optional<Playbook> playbook = playbookService.getPlaybook(trimmed);
            if (playbook.isEmpty()) {
                return "ERROR: playbook '" + trimmed + "' not found.";
            }
            return objectMapper.writeValueAsString(PlaybookWithText.from(playbook.get()));
        } catch (Exception e) {
            return McpToolUtils.toolError("get_playbook", e);
        }
    }
}
