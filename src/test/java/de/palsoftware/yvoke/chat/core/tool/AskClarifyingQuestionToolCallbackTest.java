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

    @Test
    public void testCallWithInvalidArguments() {
        AgenticChatContext context = new AgenticChatContext();
        String jsonArguments = "{invalid json}";

        String response = toolCallback.callWithContext(jsonArguments, context);

        assertThat(response).contains("Error parsing arguments");
        assertThat(context.getClarifyingQuestion()).isNull();
    }
}
