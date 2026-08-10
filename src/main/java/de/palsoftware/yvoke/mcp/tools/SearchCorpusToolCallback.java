package de.palsoftware.yvoke.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.llm.core.context.LlmCallContextHolder;
import de.palsoftware.yvoke.rag.core.model.AgenticChatContext;
import de.palsoftware.yvoke.rag.core.ContextAwareToolCallback;
import java.util.Map;
import java.util.UUID;
import org.springframework.ai.tool.definition.ToolDefinition;

public class SearchCorpusToolCallback implements ContextAwareToolCallback {

    private final SearchCorpusTool tool;
    private final ObjectMapper objectMapper;
    private final ToolDefinition definition;

    /**
     * @param maxLimit {@code app.retrieval.max-limit}, woven into the schema text the MODEL reads.
     *        It must be the same number {@code HybridSearch} clamps to: the description used to
     *        hardcode "max 200" (a constant that no longer exists) while the server clamped to 20,
     *        so a specialist asking for 100 got 20 rows, was told the result was capped and to
     *        raise the limit, raised it, and got 20 again — a loop the model cannot escape because
     *        the advice it is given is impossible to follow.
     */
    public SearchCorpusToolCallback(SearchCorpusTool tool, ObjectMapper objectMapper,
        int maxLimit) {
        this.tool = tool;
        this.objectMapper = objectMapper;
        this.definition = new ToolDefinition() {
            @Override
            public String name() {
                return "search_corpus";
            }

            @Override
            public String description() {
                return "Hybrid search (semantic + full-text) the corpus in a collection. Requires a 'query'. Do not use this tool to list/catalog available documents or for browsing-only hierarchical document kinds (use 'list_documents' instead). Returns matching text chunks as markdown, each with breadcrumbs (heading path), citation IDs, and relevance scores.";
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
                          "description": "The collection to search."
                        },
                        "tag": {
                          "type": "string",
                          "description": "Scope to a single tag. REQUIRED when the collection has tags — results must come from exactly one tag; the error lists the valid values. Omit it only for a collection that has no tags."
                        },
                        "limit": {
                          "type": "integer",
                          "description": "Max chunks to return. Omit to use the server default (small — around 10). A full page means the result is CAPPED and more may match: raise this (max %d) or narrow the query. To read a whole named section rather than its top-ranked chunks, use 'get_section'."
                        },
                        "conversation_id": {
                          "type": "string",
                          "description": "Optional conversation ID (UUID) for attributing search/embedding costs."
                        },
                        "message_id": {
                          "type": "string",
                          "description": "Optional message ID (UUID) for attributing search/embedding costs."
                        },
                        "agent_run_id": {
                          "type": "string",
                          "description": "Optional agent run ID (UUID) for attributing search/embedding costs."
                        },
                        "user_id": {
                          "type": "string",
                          "description": "Optional user ID (UUID) for attributing search/embedding costs."
                        }
                      },
                      "required": ["query", "collection"]
                    }
                    """
                    .formatted(maxLimit);
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
        LlmCallContextHolder.Context outerCtx = LlmCallContextHolder.get();
        boolean setCustomContext = false;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = objectMapper.readValue(jsonArguments, Map.class);
            String query = (String) args.get("query");
            String collection = (String) args.get("collection");
            String tag = (String) args.get("tag");

            UUID convId = parseUuid(args.get("conversation_id"));
            if (convId == null)
                convId = parseUuid(args.get("conversationId"));
            if (convId == null && outerCtx != null)
                convId = outerCtx.conversationId();

            UUID msgId = parseUuid(args.get("message_id"));
            if (msgId == null)
                msgId = parseUuid(args.get("messageId"));
            if (msgId == null && outerCtx != null)
                msgId = outerCtx.messageId();

            UUID runId = parseUuid(args.get("agent_run_id"));
            if (runId == null)
                runId = parseUuid(args.get("agentRunId"));
            if (runId == null && outerCtx != null)
                runId = outerCtx.agentRunId();

            UUID userId = parseUuid(args.get("user_id"));
            if (userId == null)
                userId = parseUuid(args.get("userId"));
            if (userId == null && outerCtx != null)
                userId = outerCtx.userId();

            LlmCallContextHolder.set(convId, msgId, runId, userId,
                outerCtx != null ? outerCtx.source() : null,
                outerCtx != null ? outerCtx.role() : null);

            Integer limit = (args.get("limit") instanceof Number n) ? n.intValue() : null;
            return tool.searchCorpus(query, collection, tag, limit, context);
        } catch (Exception e) {
            return "Error parsing arguments: " + e.getMessage();
        } finally {
            if (outerCtx != null) {
                LlmCallContextHolder.set(outerCtx.conversationId(), outerCtx.messageId(),
                    outerCtx.agentRunId(), outerCtx.userId(), outerCtx.source(), outerCtx.role());
            } else {
                LlmCallContextHolder.clear();
            }
        }
    }

    private static UUID parseUuid(Object raw) {
        if (raw == null)
            return null;
        try {
            return UUID.fromString(raw.toString());
        } catch (Exception e) {
            return null;
        }
    }
}
