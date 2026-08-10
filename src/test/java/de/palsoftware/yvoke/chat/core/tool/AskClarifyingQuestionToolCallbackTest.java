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

        assertThat(response).contains("Clarifying question asked successfully");
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

        assertThat(response).contains("Clarifying question asked successfully");
        assertThat(context.getClarifyingQuestion()).isEqualTo("Please provide a timeframe.");
        assertThat(context.getClarifyingOptions()).isNull();
    }

    /**
     * The same tool has two entry points with genuinely different semantics. In-app, the agentic
     * loop calls {@code callWithContext} and the question is parked on the
     * {@code AgenticChatContext} for the UI to render. Over MCP there is no such context — the
     * {@code ToolCallback} contract only offers {@code call(String)} — so the tool is a no-op that
     * reports success: an external client asks its own user directly, and the model must be told
     * the call worked so it stops and waits instead of retrying or inventing an answer.
     *
     * <p>
     * That makes the {@code context != null} guard load-bearing rather than defensive. Removing it
     * (the obvious "this can't be null" cleanup — every other test in this file passes a real
     * context, so nothing here would notice) NPEs on the first setter. The NPE is then swallowed by
     * the method's own catch-all, which returns {@code "Error parsing arguments: …"} — so every
     * MCP-side clarifying question is reported to the model as a MALFORMED CALL. The model's only
     * sensible response to that is to retry with re-written arguments, which fails identically, on
     * a tool whose entire purpose is to stop and ask. Hence the exact-string assertion: any message
     * naming a parsing failure is the bug, whatever its wording.
     */
    @Test
    public void theMcpEntryPointReportsSuccessWithoutAContext() {
        String jsonArguments = """
            {
              "question": "Which kit version — 9.3.1 or 10.0?",
              "options": ["9.3.1", "10.0"]
            }
            """;

        String response = toolCallback.call(jsonArguments);

        assertThat(response)
            .isEqualTo("Clarifying question asked successfully. Waiting for user's response.");
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
