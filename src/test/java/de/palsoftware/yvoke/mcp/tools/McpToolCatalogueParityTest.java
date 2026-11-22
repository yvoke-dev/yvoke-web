package de.palsoftware.yvoke.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import de.palsoftware.yvoke.mcp.ClientOnlyTools;
import de.palsoftware.yvoke.mcp.McpToolsConfig;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
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
 * {@link McpToolsConfig} publishes annotation-driven tool classes in {@code mcp.tools} via {@code
 * ToolCallbacks.from(bean)}, which reads {@code @Tool}/{@code @ToolParam} exclusively ({@code
 * spring.ai.mcp.server.annotation-scanner.enabled=false}), while classes in {@link
 * McpToolsConfig#CALLBACK_PUBLISHED} ship hand-written {@code ContextAwareToolCallback} schemas.
 *
 * <p>Because the MCP annotation scanner is disabled, {@code @McpTool}/{@code @McpToolParam} are
 * never read at runtime — both the MCP server and the in-app assistant read one catalogue from
 * {@code @Tool}/{@code @ToolParam}. This test forbids dead {@code @McpTool}/{@code @McpToolParam}
 * annotations across all {@code mcp.tools} classes and dead {@code @Tool}/{@code @ToolParam}
 * annotations on {@link McpToolsConfig#CALLBACK_PUBLISHED} classes.
 *
 * <p>Pure reflection and classpath metadata scanning: no Spring context, so it cannot affect the
 * TestContext cache.
 */
class McpToolCatalogueParityTest {

    private static final List<Class<?>> ALL_TOOLS = McpToolsConfig.scanToolClasses();

    private static final List<Class<?>> TOOLS =
            ALL_TOOLS.stream().filter(c -> !McpToolsConfig.CALLBACK_PUBLISHED.contains(c)).toList();

    @Test
    void mcpAnnotationScannerIsDisabledSoToolAnnotationsAreSingleSourceOfTruth() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/application.yml")) {
            assertThat(in).isNotNull();
            String yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(yaml)
                    .as(
                            "spring.ai.mcp.server.annotation-scanner.enabled must stay false so "
                                    + "McpToolsConfig (@Tool + hand-registered callbacks) is the "
                                    + "single catalogue for both MCP and in-app chat")
                    .containsPattern("annotation-scanner:\\s*\\n\\s*enabled:\\s*false");
        }
    }

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
    void noToolClassCarriesDeadMcpToolAnnotations() {
        List<String> deadAnnotations = new ArrayList<>();
        for (Class<?> tool : ALL_TOOLS) {
            for (Method m : tool.getDeclaredMethods()) {
                if (m.isAnnotationPresent(McpTool.class)) {
                    deadAnnotations.add(
                            tool.getSimpleName() + "." + m.getName() + " carries dead @McpTool");
                }
                for (Parameter p : m.getParameters()) {
                    if (p.isAnnotationPresent(McpToolParam.class)) {
                        deadAnnotations.add(
                                tool.getSimpleName()
                                        + "."
                                        + m.getName()
                                        + "("
                                        + p.getName()
                                        + ") carries dead @McpToolParam");
                    }
                }
            }
        }
        assertThat(deadAnnotations)
                .as(
                        "spring.ai.mcp.server.annotation-scanner.enabled=false means @McpTool and "
                                + "@McpToolParam are never read at runtime; annotate with @Tool / "
                                + "@ToolParam only")
                .isEmpty();
    }

    @Test
    void callbackPublishedToolClassesCarryNoDeadAnnotations() {
        assertThat(McpToolsConfig.CALLBACK_PUBLISHED)
                .containsExactlyInAnyOrder(
                        SearchCorpusTool.class,
                        GetSectionTool.class,
                        ListDocumentsTool.class,
                        VerifyCitationsTool.class);
        List<String> deadAnnotations = new ArrayList<>();
        for (Class<?> tool : McpToolsConfig.CALLBACK_PUBLISHED) {
            for (Method m : tool.getDeclaredMethods()) {
                if (m.isAnnotationPresent(McpTool.class) || m.isAnnotationPresent(Tool.class)) {
                    deadAnnotations.add(
                            tool.getSimpleName()
                                    + "."
                                    + m.getName()
                                    + " carries method-level @McpTool/@Tool");
                }
                for (Parameter p : m.getParameters()) {
                    if (p.isAnnotationPresent(McpToolParam.class)
                            || p.isAnnotationPresent(ToolParam.class)) {
                        deadAnnotations.add(
                                tool.getSimpleName()
                                        + "."
                                        + m.getName()
                                        + "("
                                        + p.getName()
                                        + ") carries @McpToolParam/@ToolParam");
                    }
                }
            }
        }
        assertThat(deadAnnotations)
                .as(
                        "CALLBACK_PUBLISHED classes ship hand-written ContextAwareToolCallback schemas; "
                                + "leaving @McpTool/@Tool/@McpToolParam/@ToolParam on the underlying bean leaves "
                                + "dead, unchecked schema annotations that never ship")
                .isEmpty();
    }

    @Test
    void everyClientOnlyToolsClassIsPublishedThroughTheComponentScan()
            throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(ClientOnlyTools.class));
        List<Class<?>> clientOnlyClasses = new ArrayList<>();
        for (BeanDefinition bd :
                scanner.findCandidateComponents("de.palsoftware.yvoke.mcp.tools")) {
            String className = bd.getBeanClassName();
            if (className != null) {
                clientOnlyClasses.add(Class.forName(className));
            }
        }
        assertThat(clientOnlyClasses).isNotEmpty().contains(ProfileTools.class);
        for (Class<?> clazz : clientOnlyClasses) {
            assertThat(clazz.isAnnotationPresent(Component.class))
                    .as(
                            clazz.getSimpleName()
                                    + " is @ClientOnlyTools so it must also carry @Component to be discovered by McpToolsConfig.scanToolClasses()")
                    .isTrue();
            assertThat(McpToolsConfig.CALLBACK_PUBLISHED)
                    .as(
                            clazz.getSimpleName()
                                    + " is @ClientOnlyTools and must not be skipped via CALLBACK_PUBLISHED")
                    .doesNotContain(clazz);
            assertThat(TOOLS)
                    .as(clazz.getSimpleName() + " must be present in annotation-published TOOLS")
                    .contains(clazz);
        }
    }
}
