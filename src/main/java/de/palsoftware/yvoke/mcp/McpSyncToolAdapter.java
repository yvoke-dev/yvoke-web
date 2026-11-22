package de.palsoftware.yvoke.mcp;

import static org.springframework.ai.mcp.McpToolUtils.toSyncToolSpecification;

import com.fasterxml.jackson.core.JsonProcessingException;
import de.palsoftware.yvoke.rag.core.McpToolHints;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.Nullable;
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

    /**
     * Carries {@link McpSchema.ToolAnnotations} resolved from {@link McpToolHints} on bean-scanned
     * {@code @Tool} methods whose Spring AI {@code MethodToolCallback} does not expose its
     * declaring class.
     */
    interface AnnotatedToolCallback extends ToolCallback {
        McpSchema.ToolAnnotations toolAnnotations();
    }

    static ToolCallback wrapWithHints(
            ToolCallback delegate, McpSchema.ToolAnnotations annotations) {
        return new AnnotatedToolCallback() {
            @Override
            public McpSchema.ToolAnnotations toolAnnotations() {
                return annotations;
            }

            @Override
            public ToolDefinition getToolDefinition() {
                return delegate.getToolDefinition();
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return delegate.getToolMetadata();
            }

            @Override
            public String call(String toolInput) {
                return delegate.call(toolInput);
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                return delegate.call(toolInput, toolContext);
            }
        };
    }

    static McpSchema.ToolAnnotations fromHints(McpToolHints hints) {
        return McpSchema.ToolAnnotations.builder()
                .readOnlyHint(hints.readOnly())
                .destructiveHint(hints.destructive())
                .idempotentHint(hints.idempotent())
                .openWorldHint(hints.openWorld())
                .build();
    }

    public static List<McpServerFeatures.SyncToolSpecification> adaptAll(
            List<ToolCallback> callbacks) {
        return callbacks.stream().map(McpSyncToolAdapter::adapt).toList();
    }

    public static McpServerFeatures.SyncToolSpecification adapt(ToolCallback callback) {
        String toolName = callback.getToolDefinition().name();
        McpSchema.ToolAnnotations annotations = resolveAnnotations(callback);
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
                            JsonProcessingException jpe = findJsonCause(e);
                            if (jpe != null) {
                                return McpToolUtils.invalidArgumentsError(toolName, jpe);
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
                        annotations,
                        baseTool.meta(),
                        baseTool.icons());
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(annotatedTool)
                .callHandler((exchange, request) ->
                        markErrorIfNeeded(base.callHandler().apply(exchange, request)))
                .build();
    }

    /**
     * Resolves {@link McpSchema.ToolAnnotations} from a declared {@link McpToolHints} marker. When
     * a standalone callback carries no marker (e.g. a bare test stub), falls back to conservative
     * MCP specification defaults ({@code readOnlyHint=false, destructiveHint=true,
     * idempotentHint=false, openWorldHint=true}) — never assuming read-only by default.
     */
    static McpSchema.ToolAnnotations resolveAnnotations(ToolCallback callback) {
        if (callback instanceof AnnotatedToolCallback annotated) {
            return annotated.toolAnnotations();
        }
        McpToolHints hints = callback.getClass().getAnnotation(McpToolHints.class);
        if (hints != null) {
            return fromHints(hints);
        }
        return McpSchema.ToolAnnotations.builder()
                .readOnlyHint(false)
                .destructiveHint(true)
                .idempotentHint(false)
                .openWorldHint(true)
                .build();
    }

    @Nullable
    private static JsonProcessingException findJsonCause(@Nullable Throwable e) {
        Throwable cur = e;
        for (int depth = 0; cur != null && depth < 8; depth++, cur = cur.getCause()) {
            if (cur instanceof JsonProcessingException jpe) {
                return jpe;
            }
        }
        return null;
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
