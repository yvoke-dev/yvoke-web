package de.palsoftware.yvoke.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.rag.core.ContextAwareToolCallback;
import de.palsoftware.yvoke.rag.core.model.AgenticChatContext;
import java.util.Map;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Hand-registered so {@code get_section} can see the conversation.
 *
 * <p>
 * The annotation-driven registration cannot: {@code ToolCallbacks.from(bean)} builds a callback
 * whose only entry point is {@code call(String)}, with nowhere to pass the
 * {@link AgenticChatContext}. Without it a section read cannot record the passages it rendered, so
 * a specialist that reads a section and then searches receives the same passage twice at full
 * length — and the agentic loop re-sends its whole transcript every turn, so the duplicate is
 * billed once per remaining turn rather than once.
 *
 * <p>
 * Because this replaces the scanned registration, {@code GetSectionTool} carries no
 * {@code @McpTool}/{@code @Tool} annotations any more and the schema below is the single
 * description both catalogues read — the same trade {@code SearchCorpusToolCallback} makes.
 * {@code McpToolCatalogueParityTest} requires a tool method to declare both catalogues or neither,
 * so dropping them in pairs keeps it satisfied rather than exempting anything.
 */
public class GetSectionToolCallback implements ContextAwareToolCallback {

    private final GetSectionTool tool;
    private final ObjectMapper objectMapper;
    private final ToolDefinition definition;

    public GetSectionToolCallback(GetSectionTool tool, ObjectMapper objectMapper) {
        this.tool = tool;
        this.objectMapper = objectMapper;
        this.definition = new ToolDefinition() {
            @Override
            public String name() {
                return "get_section";
            }

            @Override
            public String description() {
                return "Fetch the full text of a section, or of a whole document. Provide "
                    + "document_id (with an optional heading_path) or chunk_id; a chunk_id returns "
                    + "the whole section containing that chunk, not just the chunk. Every passage "
                    + "is preceded by its own _(id=...)_ marker - cite the passage you used with "
                    + "[chunk_id=<that id>], not the document.";
            }

            @Override
            public String inputSchema() {
                return """
                    {
                      "type": "object",
                      "properties": {
                        "document_id": {
                          "type": "string",
                          "description": "Document ID (full UUID). Combine with heading_path to read one section; omit heading_path to read the whole document."
                        },
                        "heading_path": {
                          "type": "string",
                          "description": "Section path, e.g. \\"Chapter > Section\\". When nothing is specified the full document is returned."
                        },
                        "chunk_id": {
                          "type": "string",
                          "description": "Chunk ID (full UUID). Returns the WHOLE section that contains this chunk, not the chunk alone."
                        }
                      }
                    }
                    """;
            }
        };
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return this.definition;
    }

    /** An external MCP client has no conversation, so nothing is suppressed for it. */
    @Override
    public String call(String jsonArguments) {
        return callWithContext(jsonArguments, null);
    }

    @Override
    public String callWithContext(String jsonArguments, AgenticChatContext context) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = objectMapper.readValue(jsonArguments, Map.class);
            return tool.getSection(asString(args.get("document_id")),
                asString(args.get("heading_path")), asString(args.get("chunk_id")), context);
        } catch (Exception e) {
            // A parse failure is the model's to correct, so it comes back as a tool result rather
            // than an exception — the model can fix its own arguments (CLAUDE.md § 6).
            return "Error parsing arguments: " + e.getMessage();
        }
    }

    private static String asString(Object raw) {
        return raw == null ? null : raw.toString();
    }
}
