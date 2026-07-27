package de.palsoftware.yvoke.chat.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Per-run tool the orchestrator uses to delegate a sub-question to one specialist playbook. The
 * actual specialist run + trace recording is done by the supplied handler; this class validates the
 * requested playbook is part of the active profile's specialist set.
 */
public class CallSpecialistTool implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(CallSpecialistTool.class);

    private final ObjectMapper objectMapper;
    private final Set<String> specialistNames;
    private final BiFunction<String, String, String> handler;
    private final ToolDefinition definition;

    public CallSpecialistTool(ObjectMapper objectMapper, List<String> specialistNames,
        BiFunction<String, String, String> handler) {
        this.objectMapper = objectMapper;
        this.specialistNames = Set.copyOf(specialistNames);
        this.handler = handler;
        String enumJson = toJsonArray(objectMapper, specialistNames);
        this.definition = new ToolDefinition() {
            @Override
            public String name() {
                return "call_specialist";
            }

            @Override
            public String description() {
                return "Delegate a focused sub-question to one specialist playbook and get its "
                    + "grounded answer. Call this once per specialist you need; you may call it "
                    + "several times (sequentially) and re-call a specialist with a refined question. "
                    + "Available specialists: " + String.join(", ", specialistNames) + ".";
            }

            @Override
            public String inputSchema() {
                return """
                    {
                      "type": "object",
                      "properties": {
                        "playbook_name": {
                          "type": "string",
                          "enum": %s,
                          "description": "The specialist playbook to invoke."
                        },
                        "question": {
                          "type": "string",
                          "description": "The self-contained sub-question for that specialist."
                        }
                      },
                      "required": ["playbook_name", "question"]
                    }
                    """.formatted(enumJson);
            }
        };
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return this.definition;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String call(String jsonArguments) {
        Map<String, Object> args;
        try {
            args = objectMapper.readValue(jsonArguments, Map.class);
        } catch (Exception e) {
            // Only the parse belongs in here. The handler call below runs an entire nested
            // specialist — LLM requests included — and wrapping it in this try turned a provider
            // fault into "Error parsing arguments: 429 . ", a returned string that RagService then
            // logged as a successful tool call. The model can fix its own arguments; it cannot fix
            // a 429, so an infrastructure failure must propagate instead of being answered.
            log.error("Failed to parse call_specialist arguments: {}", jsonArguments, e);
            return "Error parsing arguments: " + e.getMessage();
        }

        String playbookName;
        String question;
        try {
            // Casts belong under the same guard as the parse: a non-string argument is a model
            // mistake the model can correct from the returned message, not an infrastructure
            // failure. Left outside, a ClassCastException escaped into RagService's generic
            // handler and became the opaque "tool could not be completed" string.
            playbookName = (String) args.get("playbook_name");
            question = (String) args.get("question");
        } catch (ClassCastException e) {
            return "Error: playbook_name and question must both be strings.";
        }
        if (playbookName == null || !specialistNames.contains(playbookName)) {
            return "Error: '" + playbookName + "' is not an available specialist. Choose one of: "
                + String.join(", ", specialistNames) + ".";
        }
        if (question == null || question.isBlank()) {
            return "Error: 'question' must not be empty.";
        }
        log.info("Orchestrator delegating to specialist '{}'", playbookName);
        return handler.apply(playbookName, question);
    }

    private static String toJsonArray(ObjectMapper objectMapper, List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception e) {
            return "[]";
        }
    }
}
