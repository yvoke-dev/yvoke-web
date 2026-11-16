package de.palsoftware.yvoke.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import de.palsoftware.yvoke.mcp.ClientOnlyTools;
import de.palsoftware.yvoke.mcp.McpToolsConfig;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

/**
 * {@link McpToolsConfig} publishes annotation-driven tool classes in {@code mcp.tools} via
 * {@code ToolCallbacks.from(bean)}, which reads {@code @Tool}/{@code @ToolParam}, while the classes
 * in {@link McpToolsConfig#CALLBACK_PUBLISHED} ship hand-written {@code ContextAwareToolCallback}
 * schemas instead. Annotation-published methods also carry {@code @McpTool}/{@code @McpToolParam}
 * alongside {@code @Tool}/{@code @ToolParam}; keeping both annotation sets in lockstep prevents
 * silent schema divergence across tool catalogues.
 *
 * <p>
 * This is pure reflection and classpath metadata scanning: no Spring context, so it cannot affect
 * the TestContext cache.
 */
class McpToolCatalogueParityTest {

    private static final List<Class<?>> TOOLS = McpToolsConfig.scanToolClasses().stream()
        .filter(c -> !McpToolsConfig.CALLBACK_PUBLISHED.contains(c)).toList();

    @Test
    void everyAnnotationPublishedToolClassDeclaresAtLeastOneTool() {
        assertThat(TOOLS).isNotEmpty();
        List<String> emptyTools = new ArrayList<>();
        for (Class<?> tool : TOOLS) {
            long count = 0;
            for (Method m : tool.getDeclaredMethods()) {
                if (m.isAnnotationPresent(Tool.class)) {
                    count++;
                }
            }
            if (count == 0) {
                emptyTools.add(tool.getSimpleName());
            }
        }
        assertThat(emptyTools)
            .as("every annotation-published tool class must declare at least one @Tool method")
            .isEmpty();
    }

    @Test
    void everyClientOnlyToolsClassIsPublishedThroughTheComponentScan()
        throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
            new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(ClientOnlyTools.class));
        List<Class<?>> clientOnlyClasses = new ArrayList<>();
        for (BeanDefinition bd : scanner
            .findCandidateComponents("de.palsoftware.yvoke.mcp.tools")) {
            String className = bd.getBeanClassName();
            if (className != null) {
                clientOnlyClasses.add(Class.forName(className));
            }
        }
        assertThat(clientOnlyClasses).isNotEmpty().contains(ProfileTools.class);
        for (Class<?> clazz : clientOnlyClasses) {
            assertThat(clazz.isAnnotationPresent(Component.class)).as(clazz.getSimpleName()
                + " is @ClientOnlyTools so it must also carry @Component to be discovered by McpToolsConfig.scanToolClasses()")
                .isTrue();
            assertThat(McpToolsConfig.CALLBACK_PUBLISHED)
                .as(clazz.getSimpleName()
                    + " is @ClientOnlyTools and must not be skipped via CALLBACK_PUBLISHED")
                .doesNotContain(clazz);
            assertThat(TOOLS)
                .as(clazz.getSimpleName() + " must be present in annotation-published TOOLS")
                .contains(clazz);
        }
    }

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
    void everyParameterIsDeclaredInBothCataloguesWithMatchingRequiredAndDescription() {
        List<String> mismatches = new ArrayList<>();
        for (Class<?> tool : TOOLS) {
            for (Method m : tool.getDeclaredMethods()) {
                if (!m.isAnnotationPresent(McpTool.class) && !m.isAnnotationPresent(Tool.class)) {
                    continue;
                }
                for (Parameter p : m.getParameters()) {
                    McpToolParam mcpParam = p.getAnnotation(McpToolParam.class);
                    ToolParam toolParam = p.getAnnotation(ToolParam.class);
                    boolean mcp = mcpParam != null;
                    boolean agentic = toolParam != null;
                    if (mcp != agentic) {
                        mismatches.add(tool.getSimpleName() + "." + m.getName() + "(" + p.getName()
                            + "): @McpToolParam=" + mcp + " but @ToolParam=" + agentic);
                        continue;
                    }
                    if (mcpParam != null) {
                        if (mcpParam.required() != toolParam.required()) {
                            mismatches
                                .add(tool.getSimpleName() + "." + m.getName() + "(" + p.getName()
                                    + "): required differs (@McpToolParam=" + mcpParam.required()
                                    + " vs @ToolParam=" + toolParam.required() + ")");
                        }
                        if (!mcpParam.description().equals(toolParam.description())) {
                            mismatches.add(tool.getSimpleName() + "." + m.getName() + "("
                                + p.getName()
                                + "): description differs between @McpToolParam and @ToolParam");
                        }
                    }
                }
            }
        }
        assertThat(mismatches).as(
            "parameters must match across @McpToolParam and @ToolParam in presence, required flag, and description")
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
