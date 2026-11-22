package de.palsoftware.yvoke.mcp;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.mcp.tools.GetSectionTool;
import de.palsoftware.yvoke.mcp.tools.ListDocumentsTool;
import de.palsoftware.yvoke.mcp.tools.SearchCorpusTool;
import de.palsoftware.yvoke.mcp.tools.VerifyCitationsTool;
import de.palsoftware.yvoke.shared.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;

class McpToolsConfigTest {

    @Test
    void sharedToolCallbacksFailsFastOnMissingToolBean() {
        McpToolsConfig config = new McpToolsConfig();
        ApplicationContext ctx = mock(ApplicationContext.class);
        ToolCallbackProvider clientOnly = ToolCallbackProvider.from();
        when(ctx.getBean(ObjectMapper.class)).thenReturn(new ObjectMapper());
        when(ctx.getBean(UserService.class)).thenReturn(mock(UserService.class));
        when(ctx.getBean(SearchCorpusTool.class))
                .thenThrow(new NoSuchBeanDefinitionException(SearchCorpusTool.class));

        assertThatThrownBy(() -> config.sharedToolCallbacks(ctx, 50, clientOnly))
                .isInstanceOf(NoSuchBeanDefinitionException.class);
    }

    @Test
    void sharedToolCallbacksFailsFastOnDuplicateToolNameWithClientOnly() {
        McpToolsConfig config = new McpToolsConfig();
        ApplicationContext ctx = mock(ApplicationContext.class);
        ToolCallback conflicting = mock(ToolCallback.class);
        when(conflicting.getToolDefinition())
                .thenReturn(
                        ToolDefinition.builder()
                                .name("search_corpus")
                                .description("dup")
                                .inputSchema("{}")
                                .build());
        ToolCallbackProvider clientOnly = ToolCallbackProvider.from(conflicting);

        when(ctx.getBean(ObjectMapper.class)).thenReturn(new ObjectMapper());
        when(ctx.getBean(UserService.class)).thenReturn(mock(UserService.class));
        when(ctx.getBean(SearchCorpusTool.class)).thenReturn(mock(SearchCorpusTool.class));
        when(ctx.getBean(GetSectionTool.class)).thenReturn(mock(GetSectionTool.class));
        when(ctx.getBean(ListDocumentsTool.class)).thenReturn(mock(ListDocumentsTool.class));
        when(ctx.getBean(VerifyCitationsTool.class)).thenReturn(mock(VerifyCitationsTool.class));

        assertThatThrownBy(() -> config.sharedToolCallbacks(ctx, 50, clientOnly))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate MCP tool name 'search_corpus'");
    }
}
