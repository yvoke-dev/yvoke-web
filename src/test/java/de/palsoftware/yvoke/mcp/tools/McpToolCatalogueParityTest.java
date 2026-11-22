package de.palsoftware.yvoke.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.chat.core.tool.AskClarifyingQuestionToolCallback;
import de.palsoftware.yvoke.jsonobject.core.repository.JsonObjectRepository;
import de.palsoftware.yvoke.kg.core.repository.KgGraphReadRepository;
import de.palsoftware.yvoke.mcp.ClientOnlyTools;
import de.palsoftware.yvoke.mcp.McpToolsConfig;
import de.palsoftware.yvoke.rag.core.McpToolHints;
import de.palsoftware.yvoke.shared.user.service.UserService;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
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

    @Test
    void toolDescriptionsDocumentCapsWildcardsDiscoveryAndClientOnlyGuards() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SearchCorpusToolCallback searchCb =
                new SearchCorpusToolCallback(
                        Mockito.mock(SearchCorpusTool.class),
                        mapper,
                        10,
                        20,
                        Mockito.mock(UserService.class));
        assertThat(searchCb.getToolDefinition().description()).contains("[chunk_id=");
        assertThat(searchCb.getToolDefinition().inputSchema())
                .contains("list_areas")
                .doesNotContain("around 10");

        ListDocumentsToolCallback listDocsCb =
                new ListDocumentsToolCallback(Mockito.mock(ListDocumentsTool.class), mapper);
        assertThat(listDocsCb.getToolDefinition().inputSchema())
                .contains("1–" + ListDocumentsTool.MAX_LIMIT)
                .contains("specified " + ListDocumentsTool.DEFAULT_LIMIT)
                .contains("list_areas")
                .contains("hierarchical")
                .contains("confluence")
                .doesNotContain("'manual'")
                .doesNotContain("'procedure'");

        Method queryMethod =
                QueryJsonObjectsTool.class.getMethod(
                        "queryJsonObjects",
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        Integer.class,
                        Integer.class,
                        Boolean.class,
                        String.class);
        ToolParam[] queryParams =
                Arrays.stream(queryMethod.getParameters())
                        .map(p -> p.getAnnotation(ToolParam.class))
                        .toArray(ToolParam[]::new);
        assertThat(queryParams[0].description()).contains("list_areas");
        assertThat(queryParams[3].description()).contains("*");
        assertThat(queryParams[4].description()).contains("1–" + JsonObjectRepository.MAX_LIMIT);
        assertThat(queryParams[7].description())
                .contains(String.valueOf(JsonObjectRepository.MAX_GROUPS));

        Method neighborsMethod =
                GetGraphNeighborsTool.class.getMethod(
                        "getGraphNeighbors",
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        String.class);
        assertThat(neighborsMethod.getAnnotation(Tool.class).description())
                .contains(String.valueOf(GetGraphNeighborsTool.MAX_EDGES));
        assertThat(neighborsMethod.getParameters()[1].getAnnotation(ToolParam.class).description())
                .contains("list_areas");

        Method searchGraphMethod =
                SearchGraphEntitiesTool.class.getMethod(
                        "searchGraphEntities",
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        Integer.class);
        assertThat(
                        searchGraphMethod
                                .getParameters()[1]
                                .getAnnotation(ToolParam.class)
                                .description())
                .contains("list_areas");
        assertThat(
                        searchGraphMethod
                                .getParameters()[4]
                                .getAnnotation(ToolParam.class)
                                .description())
                .contains(String.valueOf(SearchGraphEntitiesTool.DEFAULT_LIMIT))
                .contains(String.valueOf(KgGraphReadRepository.MAX_LIMIT));

        Method schemaMethod =
                GetJsonSchemaTool.class.getMethod("getJsonSchema", String.class, String.class);
        assertThat(schemaMethod.getAnnotation(Tool.class).description())
                .contains("inferred, manual, or imported");
        assertThat(schemaMethod.getParameters()[0].getAnnotation(ToolParam.class).description())
                .contains("list_areas");
        assertThat(schemaMethod.getParameters()[1].getAnnotation(ToolParam.class).description())
                .doesNotContain("DB-History");

        Method tocMethod = GetTocTool.class.getMethod("getToc", String.class, String.class);
        assertThat(tocMethod.getAnnotation(Tool.class).description())
                .contains(String.valueOf(GetTocTool.MAX_LINES));

        Method feedbackMethod =
                SubmitFeedbackTool.class.getMethod(
                        "submitFeedback",
                        String.class,
                        Integer.class,
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        Integer.class,
                        String.class,
                        List.class,
                        String.class,
                        String.class,
                        String.class);
        assertThat(feedbackMethod.getAnnotation(Tool.class).description())
                .contains("call this only when the Yvoke plugin tells you to");

        Method areasMethod = AreaTools.class.getMethod("listAreas");
        assertThat(areasMethod.getAnnotation(Tool.class).description())
                .contains("discover available areas and collection names");
    }

    @Test
    void everyPublishedToolDeclaresExplicitMcpToolHints() {
        List<Class<?>> callbacks =
                List.of(
                        SearchCorpusToolCallback.class,
                        ListDocumentsToolCallback.class,
                        GetSectionToolCallback.class,
                        VerifyCitationsToolCallback.class,
                        AskClarifyingQuestionToolCallback.class);
        for (Class<?> cb : callbacks) {
            assertThat(cb.getAnnotation(McpToolHints.class))
                    .as(cb.getSimpleName() + " must declare @McpToolHints")
                    .isNotNull();
        }
        for (Class<?> toolClass : TOOLS) {
            for (Method method : toolClass.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Tool.class)) {
                    McpToolHints hints =
                            method.isAnnotationPresent(McpToolHints.class)
                                    ? method.getAnnotation(McpToolHints.class)
                                    : toolClass.getAnnotation(McpToolHints.class);
                    assertThat(hints)
                            .as(
                                    toolClass.getSimpleName()
                                            + "#"
                                            + method.getName()
                                            + " must declare @McpToolHints")
                            .isNotNull();
                }
            }
        }
    }
}
