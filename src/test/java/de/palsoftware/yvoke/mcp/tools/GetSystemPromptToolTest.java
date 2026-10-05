package de.palsoftware.yvoke.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.palsoftware.yvoke.rag.prompt.SystemPrompt;
import de.palsoftware.yvoke.rag.prompt.SystemPromptService;
import de.palsoftware.yvoke.rag.prompt.SystemPromptType;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code get_system_prompt} is how the Claude plugin (and its Chat/Cowork stubs) receive the base
 * instructions; they are deliberately NOT sent as MCP {@code instructions}, which would reach every
 * session that connects. The plugin fails closed on an {@code ERROR:} result, so an unknown name
 * must say so rather than return an empty prompt, as the desktop's REST endpoint does.
 */
class GetSystemPromptToolTest {

    private SystemPromptService systemPromptService;
    private GetSystemPromptTool tool;

    @BeforeEach
    void setUp() {
        systemPromptService = mock(SystemPromptService.class);
        tool = new GetSystemPromptTool(systemPromptService);
    }

    @Test
    void returnsThePromptTextAsIs() {
        when(systemPromptService.findChatPrompt("custom-chat")).thenReturn(Optional.of(
            new SystemPrompt("custom-chat", SystemPromptType.CHAT, "Line one.\nLine two.", "")));

        assertThat(tool.getSystemPrompt("custom-chat")).isEqualTo("Line one.\nLine two.");
    }

    @Test
    void withNoNameItAsksForDefaultChat() {
        when(systemPromptService.findChatPrompt("default-chat")).thenReturn(Optional
            .of(new SystemPrompt("active-chat", SystemPromptType.CHAT, "Cite every claim.", "")));

        assertThat(tool.getSystemPrompt(null)).isEqualTo("Cite every claim.");
        assertThat(tool.getSystemPrompt(" ")).isEqualTo("Cite every claim.");
    }

    @Test
    void anUnknownNameIsAnErrorNotAnEmptyPrompt() {
        when(systemPromptService.findChatPrompt("no-such-prompt")).thenReturn(Optional.empty());

        assertThat(tool.getSystemPrompt("no-such-prompt"))
            .isEqualTo("ERROR: system prompt 'no-such-prompt' does not exist.");
    }

    /** A stored prompt with no text would start a session with no instructions just as silently. */
    @Test
    void aPromptWithBlankTextIsAnError() {
        when(systemPromptService.findChatPrompt("default-chat")).thenReturn(
            Optional.of(new SystemPrompt("default-chat", SystemPromptType.CHAT, " ", "")));

        assertThat(tool.getSystemPrompt(null))
            .isEqualTo("ERROR: system prompt 'default-chat' does not exist.");
    }

    @Test
    void aServiceFailureIsReportedAsAToolError() {
        when(systemPromptService.findChatPrompt("default-chat"))
            .thenThrow(new IllegalStateException("db down"));

        assertThat(tool.getSystemPrompt(null))
            .isEqualTo("ERROR: the 'get_system_prompt' tool failed to complete the request.");
    }
}
