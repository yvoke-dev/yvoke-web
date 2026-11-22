package de.palsoftware.yvoke.mcp.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.mcp.McpToolUtils;
import de.palsoftware.yvoke.rag.core.ContextAwareToolCallback;
import de.palsoftware.yvoke.rag.core.McpToolHints;
import de.palsoftware.yvoke.rag.core.model.AgenticChatContext;
import java.util.Map;
import org.springframework.ai.tool.definition.ToolDefinition;

@McpToolHints(readOnly = true, destructive = false, idempotent = true)
public class ListDocumentsToolCallback implements ContextAwareToolCallback {

    private final ListDocumentsTool tool;
    private final ObjectMapper objectMapper;
    private final ToolDefinition definition;

    public ListDocumentsToolCallback(ListDocumentsTool tool, ObjectMapper objectMapper) {
        this.tool = tool;
        this.objectMapper = objectMapper;
        this.definition =
                new ToolDefinition() {
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
                              "description": "The collection to list (if not given by a playbook, call 'list_areas' to discover authorized areas and their collections)."
                            },
                            "kind": {
                              "type": "string",
                              "description": "Filter by document kind (e.g. 'hierarchical', 'confluence', 'standard', 'table', 'process'). When nothing is specified all kinds are considered."
                            },
                            "tag": {
                              "type": "string",
                              "description": "Scope to a single tag. REQUIRED when the collection has tags — results must come from exactly one tag; the error lists the valid values. Omit it only for a collection that has no tags."
                            },
                            "limit": {
                              "type": "integer",
                              "description": "Max documents to return (1–%d). When nothing is specified %d is used."
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
                        """
                                .formatted(
                                        ListDocumentsTool.MAX_LIMIT,
                                        ListDocumentsTool.DEFAULT_LIMIT);
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
        String collection;
        String kind;
        String tag;
        Integer limit;
        Integer offset;
        String query;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = objectMapper.readValue(jsonArguments, Map.class);
            if (args == null) {
                return "Error parsing arguments: expected a JSON object.";
            }
            collection = asString(args.get("collection"));
            kind = asString(args.get("kind"));
            tag = asString(args.get("tag"));
            limit = (args.get("limit") instanceof Number n) ? n.intValue() : null;
            offset = (args.get("offset") instanceof Number n) ? n.intValue() : null;
            query = asString(args.get("query"));
        } catch (JsonProcessingException | ClassCastException | IllegalArgumentException e) {
            return McpToolUtils.invalidArgumentsError("list_documents", e);
        }
        try {
            return tool.listDocuments(collection, kind, tag, limit, offset, query, context);
        } catch (Exception e) {
            return McpToolUtils.toolError("list_documents", e);
        }
    }

    private static String asString(Object raw) {
        return raw == null ? null : raw.toString();
    }
}
