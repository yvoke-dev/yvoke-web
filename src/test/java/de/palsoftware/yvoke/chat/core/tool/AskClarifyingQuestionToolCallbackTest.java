package de.palsoftware.yvoke.chat.core.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.rag.core.model.AgenticChatContext;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class AskClarifyingQuestionToolCallbackTest {

    private ObjectMapper objectMapper;
    private AskClarifyingQuestionToolCallback toolCallback;

    @BeforeEach
    public void setUp() {
        objectMapper = new ObjectMapper();
        toolCallback = new AskClarifyingQuestionToolCallback(objectMapper);
    }

    @Test
    public void testGetToolDefinition() {
        assertThat(toolCallback.getToolDefinition().name()).isEqualTo("ask_clarifying_question");
        assertThat(toolCallback.getToolDefinition().description()).contains(
            "If this tool replies that nobody was asked (external AI clients), ask the question in your reply instead.");
        assertThat(toolCallback.getToolDefinition().inputSchema()).contains("question")
            .contains("options");
    }

    @Test
    public void testCallWithContextAndOptions() {
        AgenticChatContext context = new AgenticChatContext();
        String jsonArguments = """
            {
              "question": "Which collection would you like to search?",
              "options": ["dev-docs", "prod-docs"]
            }
            """;

        String response = toolCallback.callWithContext(jsonArguments, context);

        // Exact: yvoke-desktop compares synced web history against this string
        // (CLARIFICATION_WEB_SUCCESS_MESSAGE), so a reworded reply breaks its rendering.
        assertThat(response)
            .isEqualTo("Clarifying question asked successfully. Waiting for user's response.");
        assertThat(context.getClarifyingQuestion())
            .isEqualTo("Which collection would you like to search?");
        assertThat(context.getClarifyingOptions()).containsExactly("dev-docs", "prod-docs");
    }

    @Test
    public void testCallWithContextWithoutOptions() {
        AgenticChatContext context = new AgenticChatContext();
        String jsonArguments = """
            {
              "question": "Please provide a timeframe."
            }
            """;

        String response = toolCallback.callWithContext(jsonArguments, context);

        assertThat(response)
            .isEqualTo("Clarifying question asked successfully. Waiting for user's response.");
        assertThat(context.getClarifyingQuestion()).isEqualTo("Please provide a timeframe.");
        assertThat(context.getClarifyingOptions()).isNull();
    }

    /**
     * The same tool has two entry points with genuinely different semantics. In-app, the agentic
     * loop calls {@code callWithContext} and the question is parked on the
     * {@code AgenticChatContext} for the UI to render. Over MCP there is no such context — the
     * {@code ToolCallback} contract only offers {@code call(String)}. A client that asks its own
     * user (yvoke-desktop, the Claude plugin) intercepts the call before it reaches the server, so
     * a call that does arrive was shown to nobody. The reply must say so: it used to report
     * success, and a model told that waits for an answer that never comes, believing it asked.
     *
     * <p>
     * That makes the {@code context == null} branch load-bearing rather than defensive. Removing it
     * (the obvious "this can't be null" cleanup — every other test in this file passes a real
     * context, so nothing here would notice) NPEs on the first setter. The NPE is then swallowed by
     * the method's own catch-all, which returns {@code "Error parsing arguments: …"} — so every
     * MCP-side clarifying question is reported to the model as a MALFORMED CALL. The model's only
     * sensible response to that is to retry with re-written arguments, which fails identically, on
     * a tool whose entire purpose is to stop and ask. Hence the exact-string assertion: neither a
     * claimed success nor a parsing failure is acceptable, whatever its wording.
     */
    @Test
    public void theMcpEntryPointSaysNobodyWasAsked() {
        String jsonArguments = """
            {
              "question": "Which kit version — 9.3.1 or 10.0?",
              "options": ["9.3.1", "10.0"]
            }
            """;

        String response = toolCallback.call(jsonArguments);

        assertThat(response).isEqualTo("Nobody was asked: the question was not shown to the user. "
            + "Ask the question in your reply and wait for the user's answer.");
        assertThat(response).doesNotContain("asked successfully");
        assertThat(response).doesNotContain("Error parsing arguments");
    }

    @Test
    public void testCallWithInvalidArguments() {
        AgenticChatContext context = new AgenticChatContext();
        String jsonArguments = "{invalid json}";

        String response = toolCallback.callWithContext(jsonArguments, context);

        assertThat(response).contains("Error parsing arguments");
        assertThat(context.getClarifyingQuestion()).isNull();
    }
}
