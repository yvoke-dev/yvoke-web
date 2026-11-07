package de.palsoftware.yvoke.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.chat.core.service.PluginFeedbackService;
import de.palsoftware.yvoke.chat.core.service.PluginFeedbackService.PluginFeedbackRequest;
import de.palsoftware.yvoke.chat.core.service.PluginFeedbackService.PluginFeedbackResult;
import de.palsoftware.yvoke.mcp.ClientOnlyTools;
import de.palsoftware.yvoke.mcp.McpToolUtils;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Client-only MCP tool for recording answer ratings (thumbs-up {@code 1} / thumbs-down {@code -1})
 * and comments from the Yvoke plugin for Claude Code.
 */
@ClientOnlyTools
@Component
public class SubmitFeedbackTool {

    private static final String DESCRIPTION =
        "For Yvoke clients only: records a user's thumbs-up (1) or thumbs-down (-1) rating and "
            + "optional/required comment for an answered turn, persisting the turn and its "
            + "feedback for review in the Yvoke Admin Console.";

    private static final String TURN_ID_PARAM =
        "Unique client-generated identifier for the rated turn.";
    private static final String RATING_PARAM =
        "Rating value: 1 for positive (thumbs up) or -1 for negative (thumbs down).";
    private static final String COMMENT_PARAM =
        "Optional comment for positive feedback; required non-blank comment for negative feedback (-1).";
    private static final String QUESTION_PARAM = "The user's question text for the rated turn.";
    private static final String ANSWER_PARAM = "The assistant's answer text for the rated turn.";
    private static final String AREA_PARAM =
        "Optional knowledge area name active for the session (e.g. 'OIM').";
    private static final String MODE_PARAM =
        "Optional session execution mode: 'single' or 'multi'.";
    private static final String PLAYBOOK_PARAM =
        "Optional playbook name used for the turn (e.g. 'oim-full').";
    private static final String PLAYBOOK_VERSION_PARAM =
        "Optional positive integer version of the playbook used for the turn.";
    private static final String PROFILE_PARAM =
        "Optional multi-agent orchestrator profile name used for the turn.";
    private static final String CITED_IDS_PARAM =
        "Optional list of corpus chunk or document IDs cited in the answer.";
    private static final String MODEL_PARAM = "Optional model identifier that produced the answer.";
    private static final String CLIENT_PARAM =
        "Optional client identifier (defaults to 'claude-plugin').";
    private static final String PLUGIN_VERSION_PARAM =
        "Optional client plugin version string (e.g. '0.1.0').";

    record SubmitFeedbackResponse(String status, String feedbackId, String messageId,
        String conversationId, String turnId, int rating) {}

    private final PluginFeedbackService pluginFeedbackService;
    private final ObjectMapper objectMapper;

    public SubmitFeedbackTool(PluginFeedbackService pluginFeedbackService,
        ObjectMapper objectMapper) {
        this.pluginFeedbackService = pluginFeedbackService;
        this.objectMapper = objectMapper;
    }

    @McpTool(name = "submit_feedback", description = DESCRIPTION)
    @Tool(name = "submit_feedback", description = DESCRIPTION)
    public String submitFeedback(
        @McpToolParam(description = TURN_ID_PARAM, required = true)
        @ToolParam(description = TURN_ID_PARAM, required = true) String turnId,
        @McpToolParam(description = RATING_PARAM, required = true)
        @ToolParam(description = RATING_PARAM, required = true) Integer rating,
        @McpToolParam(description = COMMENT_PARAM, required = false)
        @ToolParam(description = COMMENT_PARAM, required = false) String comment,
        @McpToolParam(description = QUESTION_PARAM, required = true)
        @ToolParam(description = QUESTION_PARAM, required = true) String question,
        @McpToolParam(description = ANSWER_PARAM, required = true)
        @ToolParam(description = ANSWER_PARAM, required = true) String answer,
        @McpToolParam(description = AREA_PARAM, required = false)
        @ToolParam(description = AREA_PARAM, required = false) String area,
        @McpToolParam(description = MODE_PARAM, required = false)
        @ToolParam(description = MODE_PARAM, required = false) String mode,
        @McpToolParam(description = PLAYBOOK_PARAM, required = false)
        @ToolParam(description = PLAYBOOK_PARAM, required = false) String playbook,
        @McpToolParam(description = PLAYBOOK_VERSION_PARAM, required = false)
        @ToolParam(description = PLAYBOOK_VERSION_PARAM, required = false) Integer playbookVersion,
        @McpToolParam(description = PROFILE_PARAM, required = false)
        @ToolParam(description = PROFILE_PARAM, required = false) String profile,
        @McpToolParam(description = CITED_IDS_PARAM, required = false)
        @ToolParam(description = CITED_IDS_PARAM, required = false) List<String> citedIds,
        @McpToolParam(description = MODEL_PARAM, required = false)
        @ToolParam(description = MODEL_PARAM, required = false) String model,
        @McpToolParam(description = CLIENT_PARAM, required = false)
        @ToolParam(description = CLIENT_PARAM, required = false) String client,
        @McpToolParam(description = PLUGIN_VERSION_PARAM, required = false)
        @ToolParam(description = PLUGIN_VERSION_PARAM, required = false) String pluginVersion) {
        try {
            PluginFeedbackResult result = pluginFeedbackService.submitFeedback(
                new PluginFeedbackRequest(turnId, rating, comment, question, answer, area, mode,
                    playbook, playbookVersion, profile, citedIds, model, client, pluginVersion));
            SubmitFeedbackResponse response = new SubmitFeedbackResponse("ok",
                result.feedbackId().toString(), result.messageId().toString(),
                result.conversationId().toString(), result.turnId(), result.rating());
            return objectMapper.writeValueAsString(response);
        } catch (IllegalArgumentException | SecurityException e) {
            return "ERROR: " + e.getMessage();
        } catch (Exception e) {
            return McpToolUtils.toolError("submit_feedback", e);
        }
    }
}
