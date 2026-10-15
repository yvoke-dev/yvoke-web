package de.palsoftware.yvoke.rag.core.websearch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.rag.core.ContextAwareToolCallback;
import de.palsoftware.yvoke.rag.core.model.AgenticChatContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * In-app chat tool callback for {@code WebSearch}.
 *
 * <p>
 * Deliberately not a Spring {@code @Component}: {@code RagService} registers this callback in its
 * chat tool registry while {@code McpToolsConfig} does not publish it on {@code /mcp}.
 *
 * <p>
 * The input schema exposes only {@code query} so model-initiated domain widening is
 * unrepresentable; domain and path-prefix scoping is strictly enforced by {@link WebSearchService}.
 */
public class WebSearchToolCallback implements ContextAwareToolCallback {

    public static final String TOOL_NAME = "WebSearch";

    private final WebSearchService webSearchService;
    private final ObjectMapper objectMapper;
    private final ToolDefinition definition;

    public WebSearchToolCallback(WebSearchService webSearchService, ObjectMapper objectMapper) {
        this.webSearchService = webSearchService;
        this.objectMapper = objectMapper;
        this.definition = new ToolDefinition() {
            @Override
            public String name() {
                return TOOL_NAME;
            }

            @Override
            public String description() {
                return "Search administrator-approved public vendor domains (such as official "
                    + "support knowledge base articles, defect advisories, and community threads) "
                    + "when the local corpus does not contain the answer or when the user asks "
                    + "about external KB numbers, defect IDs, or hotfix advisories. Cite any web "
                    + "result you use as a standard markdown link [Title](https://...).";
            }

            @Override
            public String inputSchema() {
                return """
                    {
                      "type": "object",
                      "properties": {
                        "query": {
                          "type": "string",
                          "description": "Focused search query (e.g. product name, version, KB or defect number, error message, or module)."
                        }
                      },
                      "required": ["query"],
                      "additionalProperties": false
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
        if (jsonArguments == null || jsonArguments.isBlank()) {
            return "Error: 'query' is required for WebSearch.";
        }

        Map<String, Object> args;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(jsonArguments, Map.class);
            args = parsed;
        } catch (Exception e) {
            return "Error parsing arguments: " + e.getMessage();
        }

        Object rawQuery = args != null ? args.get("query") : null;
        String query = rawQuery instanceof String s ? s.trim() : null;
        if (query == null || query.isEmpty()) {
            return "Error: 'query' is required for WebSearch.";
        }

        if (webSearchService == null || !webSearchService.isAvailable()) {
            return "Error: WebSearch is not enabled in this deployment.";
        }

        List<WebSearchResult> results = webSearchService.search(query);
        Map<String, Object> responsePayload = new LinkedHashMap<>();
        responsePayload.put("query", query);
        responsePayload.put("results", results);
        try {
            return objectMapper.writeValueAsString(responsePayload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize WebSearch results", e);
        }
    }
}
