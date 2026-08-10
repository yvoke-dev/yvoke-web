package de.palsoftware.yvoke.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Every tool in {@code mcp.tools} is published twice: {@code @McpTool}/{@code @McpToolParam} to
 * external MCP clients, and {@code @Tool}/{@code @ToolParam} to the in-app agentic catalogue. The
 * two are hand-maintained, sit on the same method, and nothing ties them together — so a parameter
 * annotated for one catalogue and not the other silently exists for one kind of caller and not the
 * other, with no compile or startup error.
 *
 * <p>
 * This is pure reflection: no Spring context, so it cannot affect the TestContext cache.
 */
class McpToolCatalogueParityTest {

    private static final List<Class<?>> TOOLS =
        List.of(GetGraphNeighborsTool.class, GetJsonSchemaTool.class, GetSectionTool.class,
            GetTocTool.class, ListDocumentsTool.class, QueryJsonObjectsTool.class,
            SearchCorpusTool.class, SearchGraphEntitiesTool.class, VerifyCitationsTool.class);

    @Test
    void everyToolMethodDeclaresBothCatalogues() {
        List<String> mismatches = new ArrayList<>();
        for (Class<?> tool : TOOLS) {
            for (Method m : tool.getDeclaredMethods()) {
                boolean mcp = m.isAnnotationPresent(McpTool.class);
                boolean agentic = m.isAnnotationPresent(Tool.class);
                if (mcp != agentic) {
                    mismatches.add(tool.getSimpleName() + "." + m.getName() + ": @McpTool=" + mcp
                        + " but @Tool=" + agentic);
                }
            }
        }
        assertThat(mismatches).as("a tool must be published to both catalogues or neither")
            .isEmpty();
    }

    @Test
    void everyParameterIsDeclaredInBothCatalogues() {
        List<String> mismatches = new ArrayList<>();
        for (Class<?> tool : TOOLS) {
            for (Method m : tool.getDeclaredMethods()) {
                if (!m.isAnnotationPresent(McpTool.class) && !m.isAnnotationPresent(Tool.class)) {
                    continue;
                }
                for (Parameter p : m.getParameters()) {
                    boolean mcp = p.isAnnotationPresent(McpToolParam.class);
                    boolean agentic = p.isAnnotationPresent(ToolParam.class);
                    if (mcp != agentic) {
                        mismatches.add(tool.getSimpleName() + "." + m.getName() + "(" + p.getName()
                            + "): @McpToolParam=" + mcp + " but @ToolParam=" + agentic);
                    }
                }
            }
        }
        assertThat(mismatches)
            .as("a parameter exposed to one catalogue but not the other is invisible to half the "
                + "callers — e.g. an agent that cannot paginate a tool an MCP client can")
            .isEmpty();
    }

    @Test
    void bothCataloguesAgreeOnNameAndDescription() {
        List<String> mismatches = new ArrayList<>();
        for (Class<?> tool : TOOLS) {
            for (Method m : tool.getDeclaredMethods()) {
                McpTool mcp = m.getAnnotation(McpTool.class);
                Tool agentic = m.getAnnotation(Tool.class);
                if (mcp == null || agentic == null) {
                    continue;
                }
                if (!mcp.name().equals(agentic.name())) {
                    mismatches.add(tool.getSimpleName() + ": name '" + mcp.name() + "' vs '"
                        + agentic.name() + "'");
                }
                if (!mcp.description().equals(agentic.description())) {
                    mismatches.add(tool.getSimpleName() + " (" + mcp.name()
                        + "): descriptions differ between the two catalogues");
                }
            }
        }
        assertThat(mismatches).as("the two catalogues must describe the same contract").isEmpty();
    }
}
