package de.palsoftware.yvoke.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.rag.core.ContextAwareToolCallback;
import de.palsoftware.yvoke.rag.core.model.AgenticChatContext;
import java.util.Map;
import org.springframework.ai.tool.definition.ToolDefinition;

public class ListDocumentsToolCallback implements ContextAwareToolCallback {

    private final ListDocumentsTool tool;
    private final ObjectMapper objectMapper;
    private final ToolDefinition definition;

    public ListDocumentsToolCallback(ListDocumentsTool tool, ObjectMapper objectMapper) {
        this.tool = tool;
        this.objectMapper = objectMapper;
        this.definition = new ToolDefinition() {
            @Override
            public String name() {
                return "list_documents";
            }

            @Override
            public String description() {
                return "List (catalog) the documents in a collection. Use to discover which documents exist, optionally filtering by an approximate title via 'query' (e.g. a process-chain or config-namespace name). The output displays the current page and total count (e.g., 'showing 1-100 of 150'); use the 'offset' parameter to fetch subsequent pages if the total count exceeds the limit. Returns a markdown table with columns: document_id, kind, title, chunk count.";
            }

            @Override
            public String inputSchema() {
                return """
                    {
                      "type": "object",
                      "properties": {
                        "collection": {
                          "type": "string",
                          "description": "The collection to list."
                        },
                        "kind": {
                          "type": "string",
                          "description": "Filter by document kind (e.g. 'manual', 'table', 'procedure'). When nothing is specified all kinds are considered."
                        },
                        "tag": {
                          "type": "string",
                          "description": "Scope to a single tag. REQUIRED when the collection has tags — results must come from exactly one tag; the error lists the valid values. Omit it only for a collection that has no tags."
                        },
                        "limit": {
                          "type": "integer",
                          "description": "Max documents to return. When nothing is specified 100 is used."
                        },
                        "offset": {
                          "type": "integer",
                          "description": "Pagination offset. When nothing is specified 0 is used. To fetch subsequent pages, set offset to (offset + limit) of the previous request."
                        },
                        "query": {
                          "type": "string",
                          "description": "Fuzzy document-title filter: finds documents by approximate title (e.g. a process-chain or config-namespace name). Matches case-insensitive substrings and close trigram matches, ranked best-first. When nothing is specified all titles are considered."
                        }
                      },
                      "required": ["collection"]
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
            String collection = asString(args.get("collection"));
            String kind = asString(args.get("kind"));
            String tag = asString(args.get("tag"));
            Integer limit = (args.get("limit") instanceof Number n) ? n.intValue() : null;
            Integer offset = (args.get("offset") instanceof Number n) ? n.intValue() : null;
            String query = asString(args.get("query"));
            return tool.listDocuments(collection, kind, tag, limit, offset, query, context);
        } catch (Exception e) {
            return "Error parsing arguments: " + e.getMessage();
        }
    }

    private static String asString(Object raw) {
        return raw == null ? null : raw.toString();
    }
}
