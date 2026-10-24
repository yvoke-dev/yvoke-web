package de.palsoftware.yvoke.chat.core.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.rag.core.ContextAwareToolCallback;
import de.palsoftware.yvoke.rag.core.model.AgenticChatContext;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.definition.ToolDefinition;

public class AskClarifyingQuestionToolCallback implements ContextAwareToolCallback {

    private static final Logger log =
        LoggerFactory.getLogger(AskClarifyingQuestionToolCallback.class);

    private final ObjectMapper objectMapper;
    private final ToolDefinition definition;

    public AskClarifyingQuestionToolCallback(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.definition = new ToolDefinition() {
            @Override
            public String name() {
                return "ask_clarifying_question";
            }

            @Override
            public String description() {
                return "CRITICAL: You MUST use this tool to ask a clarifying question whenever the user requests an entity, version, or tag that DOES NOT EXIST, or if parameters are missing/ambiguous. Do NOT provide alternatives in plain text; use this tool to present the available options to the user. If this tool replies that nobody was asked (external AI clients), ask the question in your reply instead.";
            }

            @Override
            public String inputSchema() {
                return """
                    {
                      "type": "object",
                      "properties": {
                        "question": {
                          "type": "string",
                          "description": "The clarifying question to ask the user."
                        },
                        "options": {
                          "type": "array",
                          "items": {
                            "type": "string"
                          },
                          "description": "Optional list of suggested, predefined answers for the user."
                        }
                      },
                      "required": ["question"]
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
    @SuppressWarnings("unchecked")
    public String callWithContext(String jsonArguments, AgenticChatContext context) {
        try {
            Map<String, Object> args = objectMapper.readValue(jsonArguments, Map.class);
            String question = (String) args.get("question");
            List<String> options = (List<String>) args.get("options");

            log.info("Executing tool ask_clarifying_question: question='{}', options={}", question,
                options);

            if (context == null) {
                // An MCP tools/call. Clients that ask their own user intercept the call before it
                // gets here, so reaching this line means nobody saw the question — and claiming
                // success made the model wait for an answer that would never come.
                return "Nobody was asked: the question was not shown to the user. "
                    + "Ask the question in your reply and wait for the user's answer.";
            }
            context.setClarifyingQuestion(question);
            context.setClarifyingOptions(options);

            // Exact text matters: yvoke-desktop compares synced web history against it.
            return "Clarifying question asked successfully. Waiting for user's response.";
        } catch (Exception e) {
            log.error("Failed to parse arguments for ask_clarifying_question: {}", jsonArguments,
                e);
            return "Error parsing arguments: " + e.getMessage();
        }
    }
}
