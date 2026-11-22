package de.palsoftware.yvoke.mcp;

import static org.springframework.ai.mcp.McpToolUtils.toSyncToolSpecification;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Adapts Spring AI {@link ToolCallback}s into MCP {@link McpServerFeatures.SyncToolSpecification}s
 * with a uniform MCP error contract:
 *
 * <ul>
 *   <li>Any tool result whose text payload starts with {@code Error:} / {@code ERROR:} (raw or
 *       JSON-quoted) is returned with {@code isError = true} per the MCP specification, while
 *       keeping the exact text intact for existing clients and the in-app agent loop.
 *   <li>Uncaught exceptions from a {@link ToolCallback} are caught before Spring AI's default
 *       handler can forward raw {@code e.getMessage()} text to the MCP client: JSON parse failures
 *       become an actionable {@code "Error parsing arguments: …"} payload, and all other failures
 *       become a provider-neutral {@link McpToolUtils#toolError} payload — both with {@code isError
 *       = true}.
 * </ul>
 */
public final class McpSyncToolAdapter {

    private McpSyncToolAdapter() {}

    public static List<McpServerFeatures.SyncToolSpecification> adaptAll(
            List<ToolCallback> callbacks) {
        return callbacks.stream().map(McpSyncToolAdapter::adapt).toList();
    }

    public static McpServerFeatures.SyncToolSpecification adapt(ToolCallback callback) {
        String toolName = callback.getToolDefinition().name();
        ToolCallback guarded =
                new ToolCallback() {
                    @Override
                    public ToolDefinition getToolDefinition() {
                        return callback.getToolDefinition();
                    }

                    @Override
                    public ToolMetadata getToolMetadata() {
                        return callback.getToolMetadata();
                    }

                    @Override
                    public String call(String toolInput) {
                        return call(toolInput, null);
                    }

                    @Override
                    public String call(String toolInput, ToolContext toolContext) {
                        try {
                            return toolContext != null
                                    ? callback.call(toolInput, toolContext)
                                    : callback.call(toolInput);
                        } catch (Exception e) {
                            if (e instanceof JsonProcessingException
                                    || e.getCause() instanceof JsonProcessingException) {
                                Throwable cause =
                                        e instanceof JsonProcessingException ? e : e.getCause();
                                return McpToolUtils.invalidArgumentsError(toolName, cause);
                            }
                            return McpToolUtils.toolError(toolName, e);
                        }
                    }
                };
        McpServerFeatures.SyncToolSpecification base = toSyncToolSpecification(guarded);
        McpSchema.Tool baseTool = base.tool();
        McpSchema.Tool annotatedTool =
                new McpSchema.Tool(
                        baseTool.name(),
                        baseTool.title(),
                        baseTool.description(),
                        baseTool.inputSchema(),
                        baseTool.outputSchema(),
                        annotationsFor(toolName),
                        baseTool.meta(),
                        baseTool.icons());
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(annotatedTool)
                .callHandler((exchange, request) ->
                        markErrorIfNeeded(base.callHandler().apply(exchange, request)))
                .build();
    }

    static McpSchema.ToolAnnotations annotationsFor(String toolName) {
        boolean readOnly = !"submit_feedback".equals(toolName);
        return McpSchema.ToolAnnotations.builder()
                .readOnlyHint(readOnly)
                .destructiveHint(false)
                .idempotentHint(true)
                .openWorldHint(false)
                .build();
    }

    static McpSchema.CallToolResult markErrorIfNeeded(McpSchema.CallToolResult result) {
        if (result == null || Boolean.TRUE.equals(result.isError())) {
            return result;
        }
        boolean hasErrorText =
                result.content() != null
                        && result.content().stream()
                                .filter(McpSchema.TextContent.class::isInstance)
                                .map(McpSchema.TextContent.class::cast)
                                .anyMatch(tc -> McpToolUtils.isErrorResult(tc.text()));
        if (!hasErrorText) {
            return result;
        }
        return new McpSchema.CallToolResult(
                result.content(), true, result.structuredContent(), result.meta());
    }
}
