package de.palsoftware.yvoke.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.palsoftware.yvoke.area.core.Area;
import de.palsoftware.yvoke.area.core.AreaMembers;
import de.palsoftware.yvoke.area.core.AreaRepository;
import de.palsoftware.yvoke.area.core.AreaService;
import de.palsoftware.yvoke.rag.prompt.SystemPrompt;
import de.palsoftware.yvoke.rag.prompt.SystemPromptService;
import de.palsoftware.yvoke.rag.prompt.SystemPromptType;
import java.util.List;
import java.util.Map;
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
    private AreaRepository areaRepository;
    private GetSystemPromptTool tool;

    @BeforeEach
    void setUp() {
        systemPromptService = mock(SystemPromptService.class);
        areaRepository = mock(AreaRepository.class);
        when(areaRepository.findAll())
            .thenReturn(List.of(new Area("OIM", "OIM", null, false, null, null, null, null, null),
                new Area("PingID", "PingID", null, false, "ping-chat", null, null, null, null),
                new Area("Moved", "Moved", null, false, "oim-chat", null, null, null, null)));
        when(areaRepository.findAllMembers()).thenReturn(
            Map.of("OIM", new AreaMembers(List.of("oim-chat"), List.of(), List.of(), List.of()),
                "PingID", new AreaMembers(List.of("ping-chat"), List.of(), List.of(), List.of())));
        tool = new GetSystemPromptTool(systemPromptService, new AreaService(areaRepository));
    }

    @Test
    void returnsThePromptTextAsIs() {
        when(systemPromptService.findChatPrompt("custom-chat")).thenReturn(Optional.of(
            new SystemPrompt("custom-chat", SystemPromptType.CHAT, "Line one.\nLine two.", "")));

        assertThat(tool.getSystemPrompt("custom-chat", null)).isEqualTo("Line one.\nLine two.");
    }

    @Test
    void withNoNameItAsksForDefaultChat() {
        when(systemPromptService.findChatPrompt("default-chat")).thenReturn(Optional
            .of(new SystemPrompt("active-chat", SystemPromptType.CHAT, "Cite every claim.", "")));

        assertThat(tool.getSystemPrompt(null, null)).isEqualTo("Cite every claim.");
        assertThat(tool.getSystemPrompt(" ", null)).isEqualTo("Cite every claim.");
    }

    @Test
    void anUnknownNameIsAnErrorNotAnEmptyPrompt() {
        when(systemPromptService.findChatPrompt("no-such-prompt")).thenReturn(Optional.empty());

        assertThat(tool.getSystemPrompt("no-such-prompt", null))
            .isEqualTo("ERROR: system prompt 'no-such-prompt' does not exist.");
    }

    /** A stored prompt with no text would start a session with no instructions just as silently. */
    @Test
    void aPromptWithBlankTextIsAnError() {
        when(systemPromptService.findChatPrompt("default-chat")).thenReturn(
            Optional.of(new SystemPrompt("default-chat", SystemPromptType.CHAT, " ", "")));

        assertThat(tool.getSystemPrompt(null, null))
            .isEqualTo("ERROR: system prompt 'default-chat' does not exist.");
    }

    @Test
    void aServiceFailureIsReportedAsAToolError() {
        when(systemPromptService.findChatPrompt("default-chat"))
            .thenThrow(new IllegalStateException("db down"));

        assertThat(tool.getSystemPrompt(null, null))
            .isEqualTo("ERROR: the 'get_system_prompt' tool failed to complete the request.");
    }

    @Test
    void anAreaWithNoNameReturnsTheAreasDefaultPrompt() {
        when(systemPromptService.findChatPrompt("ping-chat"))
            .thenReturn(Optional.of(new SystemPrompt("ping-chat", SystemPromptType.CHAT,
                "Ping rules.", "", null, null, false, "PingID")));

        assertThat(tool.getSystemPrompt(null, " pingid ")).isEqualTo("Ping rules.");
    }

    /** D-16: an area that sets no default follows the admin's active chat prompt. */
    @Test
    void anAreaWithoutADefaultFallsBackToTheActivePrompt() {
        when(systemPromptService.findChatPrompt("default-chat")).thenReturn(Optional
            .of(new SystemPrompt("active-chat", SystemPromptType.CHAT, "Cite every claim.", "")));

        assertThat(tool.getSystemPrompt(null, "OIM")).isEqualTo("Cite every claim.");
    }

    /** As in list_areas, a default that has moved to another area is no default. */
    @Test
    void aDefaultFromAnotherAreaFallsBackToTheActivePrompt() {
        when(systemPromptService.findChatPrompt("oim-chat"))
            .thenReturn(Optional.of(new SystemPrompt("oim-chat", SystemPromptType.CHAT,
                "OIM rules.", "", null, null, false, "OIM")));
        when(systemPromptService.findChatPrompt("default-chat")).thenReturn(Optional
            .of(new SystemPrompt("active-chat", SystemPromptType.CHAT, "Cite every claim.", "")));

        assertThat(tool.getSystemPrompt(null, "Moved")).isEqualTo("Cite every claim.");
    }

    @Test
    void anUnknownAreaIsAnError() {
        assertThat(tool.getSystemPrompt(null, "SAP"))
            .isEqualTo("ERROR: area 'SAP' does not exist.");
    }

    @Test
    void aNameWinsOverAnArea() {
        when(systemPromptService.findChatPrompt("custom-chat")).thenReturn(
            Optional.of(new SystemPrompt("custom-chat", SystemPromptType.CHAT, "Custom.", "")));

        assertThat(tool.getSystemPrompt("custom-chat", "PingID")).isEqualTo("Custom.");
    }
}
