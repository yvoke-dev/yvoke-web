package de.palsoftware.yvoke.mcp.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.llm.core.context.LlmCallContextHolder;
import de.palsoftware.yvoke.mcp.McpToolUtils;
import de.palsoftware.yvoke.rag.core.ContextAwareToolCallback;
import de.palsoftware.yvoke.rag.core.McpToolHints;
import de.palsoftware.yvoke.rag.core.model.AgenticChatContext;
import de.palsoftware.yvoke.shared.user.model.User;
import de.palsoftware.yvoke.shared.user.service.UserService;
import java.util.Map;
import java.util.UUID;
import org.springframework.ai.tool.definition.ToolDefinition;

@McpToolHints(readOnly = true, destructive = false, idempotent = true)
public class SearchCorpusToolCallback implements ContextAwareToolCallback {

    private final SearchCorpusTool tool;
    private final ObjectMapper objectMapper;
    private final UserService userService;
    private final ToolDefinition definition;

    /**
     * @param defaultLimit {@code app.retrieval.default-limit}, woven into the schema text so the
     *     advertised default always tracks configuration.
     * @param maxLimit {@code app.retrieval.max-limit}, woven into the schema text the MODEL reads.
     *     It must be the same number {@code HybridSearch} clamps to: the description used to
     *     hardcode "max 200" (a constant that no longer exists) while the server clamped to 20, so
     *     a specialist asking for 100 got 20 rows, was told the result was capped and to raise the
     *     limit, raised it, and got 20 again — a loop the model cannot escape because the advice it
     *     is given is impossible to follow.
     */
    public SearchCorpusToolCallback(
            SearchCorpusTool tool,
            ObjectMapper objectMapper,
            int defaultLimit,
            int maxLimit,
            UserService userService) {
        this.tool = tool;
        this.objectMapper = objectMapper;
        this.userService = userService;
        this.definition =
                new ToolDefinition() {
                    @Override
                    public String name() {
                        return "search_corpus";
                    }

                    @Override
                    public String description() {
                        return "Hybrid search (semantic + full-text) the corpus in a collection. Requires a 'query'. Do not use this tool to list/catalog available documents or for browsing-only hierarchical document kinds (use 'list_documents' instead). Returns matching text chunks as markdown, each with breadcrumbs (heading path), passage/document IDs (cite with [chunk_id=<id>]), and relevance scores.";
                    }

                    @Override
                    public String inputSchema() {
                        return """
                        {
                          "type": "object",
                          "properties": {
                            "query": {
                              "type": "string",
                              "description": "Natural-language query for hybrid (semantic + keyword) search. Phrasing as a question or concise statement works best."
                            },
                            "collection": {
                              "type": "string",
                              "description": "The collection to search (if not given by a playbook, call 'list_areas' to discover authorized areas and their collections)."
                            },
                            "tag": {
                              "type": "string",
                              "description": "Scope to a single tag. REQUIRED when the collection has tags — results must come from exactly one tag; the error lists the valid values. Omit it only for a collection that has no tags."
                            },
                            "limit": {
                              "type": "integer",
                              "description": "Max chunks to return (1–%d). Omit to use the server default (%d). A full page means the result is CAPPED and more may match: raise this (max %d) or narrow the query. To read a whole named section rather than its top-ranked chunks, use 'get_section'."
                            }
                          },
                          "required": ["query", "collection"]
                        }
                        """
                                .formatted(maxLimit, defaultLimit, maxLimit);
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
        String query;
        String collection;
        String tag;
        Integer limit;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = objectMapper.readValue(jsonArguments, Map.class);
            if (args == null) {
                return "Error parsing arguments: expected a JSON object.";
            }
            query = asString(args.get("query"));
            collection = asString(args.get("collection"));
            tag = asString(args.get("tag"));
            limit = (args.get("limit") instanceof Number n) ? n.intValue() : null;
        } catch (JsonProcessingException | ClassCastException | IllegalArgumentException e) {
            return McpToolUtils.invalidArgumentsError("search_corpus", e);
        }

        // Cost attribution must NEVER come from caller/model tool arguments: in-app runs already
        // hold a trusted server-side LlmCallContextHolder; external MCP requests derive userId
        // strictly from the authenticated principal.
        LlmCallContextHolder.Context outerCtx = LlmCallContextHolder.get();
        boolean installedMcpContext = false;
        try {
            if (outerCtx == null) {
                UUID userId = userService.getCurrentUser().map(User::id).orElse(null);
                LlmCallContextHolder.set(null, null, null, userId, "mcp", "mcp");
                installedMcpContext = true;
            }
            return tool.searchCorpus(query, collection, tag, limit, context);
        } catch (Exception e) {
            return McpToolUtils.toolError("search_corpus", e);
        } finally {
            if (installedMcpContext) {
                LlmCallContextHolder.clear();
            }
        }
    }

    private static String asString(Object raw) {
        return raw == null ? null : raw.toString();
    }
}
