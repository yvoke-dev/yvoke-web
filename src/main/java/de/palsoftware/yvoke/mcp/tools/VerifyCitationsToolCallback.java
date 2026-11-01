package de.palsoftware.yvoke.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.rag.core.ContextAwareToolCallback;
import de.palsoftware.yvoke.rag.core.model.AgenticChatContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.definition.ToolDefinition;

public class VerifyCitationsToolCallback implements ContextAwareToolCallback {

    private final VerifyCitationsTool tool;
    private final ObjectMapper objectMapper;
    private final ToolDefinition definition;

    public VerifyCitationsToolCallback(VerifyCitationsTool tool, ObjectMapper objectMapper) {
        this.tool = tool;
        this.objectMapper = objectMapper;
        this.definition = new ToolDefinition() {
            @Override
            public String name() {
                return "verify_citations";
            }

            @Override
            public String description() {
                return VerifyCitationsTool.DESCRIPTION;
            }

            @Override
            public String inputSchema() {
                return """
                    {
                      "type": "object",
                      "properties": {
                        "citations": {
                          "type": "array",
                          "items": {
                            "type": "string"
                          },
                          "description": "List of citation tokens to verify, e.g. \\"[chunk_id=8f7c1a2b-3c4d-5e6f-7a8b-9c0d1e2f3a4b]\\", \\"[document_id=e3b0c442-98fc-1c14-9afb-f4c8996fb924]\\", a bare full chunk uuid, or \\"[1]\\". Ids must be complete: a truncated 8-character id cannot be checked."
                        }
                      },
                      "required": ["citations"]
                    }
                    """;
            }
        };
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return this.definition;
    }

    @Override
    public String call(String jsonArguments) {
        return callWithContext(jsonArguments, null);
    }

    @Override
    public String callWithContext(String jsonArguments, AgenticChatContext context) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = objectMapper.readValue(jsonArguments, Map.class);
            List<String> citations = extractCitations(args.get("citations"));
            return tool.verifyCitations(citations, context);
        } catch (Exception e) {
            return "Error parsing arguments: " + e.getMessage();
        }
    }

    private static List<String> extractCitations(Object citationsObj) {
        if (citationsObj instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item != null) {
                    result.add(item.toString());
                }
            }
            return result;
        }
        return Collections.emptyList();
    }
}
