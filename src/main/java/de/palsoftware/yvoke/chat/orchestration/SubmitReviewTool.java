package de.palsoftware.yvoke.chat.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.rag.core.ContextAwareToolCallback;
import de.palsoftware.yvoke.rag.core.model.AgenticChatContext;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Per-run tool the review agent MUST call to deliver its verdict. Captures the verdict and halts
 * the reviewer's agentic loop.
 */
public class SubmitReviewTool implements ContextAwareToolCallback {

    private static final Logger log = LoggerFactory.getLogger(SubmitReviewTool.class);

    private final ObjectMapper objectMapper;
    private final Consumer<Verdict> onVerdict;
    private final ToolDefinition definition;

    public SubmitReviewTool(ObjectMapper objectMapper, Consumer<Verdict> onVerdict) {
        this.objectMapper = objectMapper;
        this.onVerdict = onVerdict;
        this.definition = new ToolDefinition() {
            @Override
            public String name() {
                return "submit_review";
            }

            @Override
            public String description() {
                return "Submit your final review verdict. You MUST call this exactly once as your "
                    + "last action. Set approved=true only if every claim in the answer is supported "
                    + "by the supplied evidence; otherwise approved=false with concrete feedback the "
                    + "orchestrator can act on.";
            }

            @Override
            public String inputSchema() {
                return """
                    {
                      "type": "object",
                      "properties": {
                        "approved": {
                          "type": "boolean",
                          "description": "true if the answer is fully grounded in the supplied evidence."
                        },
                        "feedback": {
                          "type": "string",
                          "description": "Concise reasoning; on rejection, what must be fixed."
                        },
                        "unsupported_claims": {
                          "type": "array",
                          "items": { "type": "string" },
                          "description": "Claims with NOTHING behind them in the supplied evidence. Use this only when no supplied source supports the claim — fixing one may require new research."
                        },
                        "citation_fixes": {
                          "type": "array",
                          "items": { "type": "string" },
                          "description": "Defects repairable from the evidence already supplied: a claim attached to the wrong source, an uncited claim a supplied source does support, or a duplicated reference entry. State the repair concretely, e.g. 'swap [5] to [4] on the SAP HCM structural profile claim'. These need no new research, so listing one here rather than under unsupported_claims is what stops the orchestrator re-running a search it does not need."
                        }
                      },
                      "required": ["approved", "feedback"]
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
            boolean approved = Boolean.TRUE.equals(args.get("approved"));
            String feedback = (String) args.getOrDefault("feedback", "");
            List<String> unsupported =
                (List<String>) args.getOrDefault("unsupported_claims", List.of());
            List<String> citationFixes =
                (List<String>) args.getOrDefault("citation_fixes", List.of());
            onVerdict.accept(new Verdict(approved, feedback, unsupported, citationFixes));
            if (context != null) {
                context.setHaltRequested(true);
            }
            log.info("Review verdict submitted: approved={}", approved);
            return "Verdict recorded.";
        } catch (Exception e) {
            log.error("Failed to parse submit_review arguments: {}", jsonArguments, e);
            return "Error parsing arguments: " + e.getMessage();
        }
    }
}
