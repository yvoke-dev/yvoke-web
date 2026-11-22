package de.palsoftware.yvoke.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.chat.core.tool.AskClarifyingQuestionToolCallback;
import de.palsoftware.yvoke.mcp.tools.GetSectionTool;
import de.palsoftware.yvoke.mcp.tools.GetSectionToolCallback;
import de.palsoftware.yvoke.mcp.tools.ListDocumentsTool;
import de.palsoftware.yvoke.mcp.tools.ListDocumentsToolCallback;
import de.palsoftware.yvoke.mcp.tools.SearchCorpusTool;
import de.palsoftware.yvoke.mcp.tools.SearchCorpusToolCallback;
import de.palsoftware.yvoke.mcp.tools.VerifyCitationsTool;
import de.palsoftware.yvoke.mcp.tools.VerifyCitationsToolCallback;
import de.palsoftware.yvoke.rag.prompt.PlaybookRepository;
import de.palsoftware.yvoke.shared.user.service.UserService;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

@Configuration
public class McpToolsConfig {

    private static final Logger log = LoggerFactory.getLogger(McpToolsConfig.class);

    /**
     * Tool classes in {@code mcp.tools} registered by hand in {@link #sharedToolCallbacks} with
     * custom {@code ContextAwareToolCallback} schemas, so their scanned registrations are skipped
     * to keep a single source of truth per tool schema.
     */
    public static final Set<Class<?>> CALLBACK_PUBLISHED =
            Set.of(
                    SearchCorpusTool.class,
                    GetSectionTool.class,
                    ListDocumentsTool.class,
                    VerifyCitationsTool.class);

    /**
     * The tools both the in-app assistant ({@code RagService}, through its {@code
     * List<ToolCallback>} constructor argument) and MCP clients get. It starts from the client-only
     * names, so no shared registration, by hand or by scan, can put one of them in front of the
     * in-app assistant. Registration fails fast on missing beans, class-load errors, or duplicate
     * tool names rather than silently starting with a depleted tool catalogue.
     */
    @Bean
    public List<ToolCallback> sharedToolCallbacks(
            ApplicationContext applicationContext,
            @Value("${app.retrieval.max-limit}") int maxLimit,
            @Qualifier("clientOnlyToolCallbacks") ToolCallbackProvider clientOnlyToolCallbacks) {
        List<ToolCallback> callbacksList = new ArrayList<>();
        Set<String> registeredNames = new HashSet<>();
        for (ToolCallback clientOnly : clientOnlyToolCallbacks.getToolCallbacks()) {
            String name = clientOnly.getToolDefinition().name();
            if (!registeredNames.add(name)) {
                throw new IllegalStateException("Duplicate client-only MCP tool name: " + name);
            }
        }

        ObjectMapper objectMapper = applicationContext.getBean(ObjectMapper.class);
        UserService userService = applicationContext.getBean(UserService.class);
        List<ToolCallback> handRegistered =
                List.of(
                        new SearchCorpusToolCallback(
                                applicationContext.getBean(SearchCorpusTool.class),
                                objectMapper,
                                maxLimit,
                                userService),
                        new AskClarifyingQuestionToolCallback(objectMapper),
                        new GetSectionToolCallback(
                                applicationContext.getBean(GetSectionTool.class), objectMapper),
                        new ListDocumentsToolCallback(
                                applicationContext.getBean(ListDocumentsTool.class), objectMapper),
                        new VerifyCitationsToolCallback(
                                applicationContext.getBean(VerifyCitationsTool.class),
                                objectMapper));

        for (ToolCallback callback : handRegistered) {
            registerUnique(callback, registeredNames, callbacksList, "hand-registered");
        }

        for (Class<?> clazz : scanToolClasses()) {
            // Registered by hand above with a context-aware callback. Skipping the class keeps
            // exactly one source of truth for each tool's schema.
            if (CALLBACK_PUBLISHED.contains(clazz)
                    || clazz.isAnnotationPresent(ClientOnlyTools.class)) {
                continue;
            }
            registerBeanTools(applicationContext, clazz, registeredNames, callbacksList);
        }

        log.info("Total shared MCP Server Tools registered: {}", callbacksList.size());
        return callbacksList;
    }

    /**
     * The {@link ClientOnlyTools} classes, for MCP clients only. spring-ai's MCP server publishes
     * every {@code ToolCallbackProvider} bean alongside the {@code List<ToolCallback>} one, while
     * {@code RagService} takes only the list, so a tool here never reaches the in-app assistant or
     * the admin playbook editor's tool choices (Claude plugin P1-13, D-23).
     */
    @Bean
    public ToolCallbackProvider clientOnlyToolCallbacks(ApplicationContext applicationContext) {
        List<ToolCallback> callbacksList = new ArrayList<>();
        Set<String> registeredNames = new HashSet<>();
        for (Class<?> clazz : scanToolClasses()) {
            if (clazz.isAnnotationPresent(ClientOnlyTools.class)) {
                registerBeanTools(applicationContext, clazz, registeredNames, callbacksList);
            }
        }
        log.info("Total client-only MCP Server Tools registered: {}", callbacksList.size());
        return ToolCallbackProvider.from(callbacksList);
    }

    /**
     * Converts both tool catalogues into MCP {@link McpServerFeatures.SyncToolSpecification}s via
     * {@link McpSyncToolAdapter} so error responses set {@code isError = true} on the wire while
     * {@code RagService} continues receiving the exact {@code sharedToolCallbacks} instances.
     * Replaces Spring AI's {@code ToolCallbackConverterAutoConfiguration} ({@code
     * spring.ai.mcp.server.tool-callback-converter=false}).
     */
    @Bean
    @ConditionalOnProperty(
            prefix = "spring.ai.mcp.server",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    public List<McpServerFeatures.SyncToolSpecification> mcpSyncToolSpecifications(
            @Qualifier("sharedToolCallbacks") List<ToolCallback> sharedToolCallbacks,
            @Qualifier("clientOnlyToolCallbacks") ToolCallbackProvider clientOnlyToolCallbacks) {
        List<ToolCallback> all = new ArrayList<>(sharedToolCallbacks);
        all.addAll(Arrays.asList(clientOnlyToolCallbacks.getToolCallbacks()));
        return McpSyncToolAdapter.adaptAll(all);
    }

    /**
     * Wraps the auto-configured {@link WebMvcStreamableServerTransportProvider} so {@code
     * prompts/list} responses are filtered per caller against {@code AreaAccessContext} and the
     * live {@link PlaybookRepository}. {@code destroyMethod = ""} avoids double-closing the
     * underlying delegate bean on shutdown.
     */
    @Bean(destroyMethod = "")
    @Primary
    @ConditionalOnProperty(
            prefix = "spring.ai.mcp.server",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    public McpStreamableServerTransportProvider areaScopedMcpStreamableTransportProvider(
            WebMvcStreamableServerTransportProvider delegate,
            PlaybookRepository playbookRepository) {
        return new AreaScopedMcpStreamableTransportProvider(delegate, playbookRepository);
    }

    // Classpath scanning rather than getBeansWithAnnotation on the entire ApplicationContext,
    // which would eagerly instantiate other unrelated beans and cause circular reference errors.
    public static List<Class<?>> scanToolClasses() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        List<Class<?>> classes = new ArrayList<>();
        for (BeanDefinition bd :
                scanner.findCandidateComponents("de.palsoftware.yvoke.mcp.tools")) {
            String className = bd.getBeanClassName();
            if (className == null) {
                continue;
            }
            try {
                classes.add(Class.forName(className));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(
                        "Failed to load MCP tool candidate: " + className, e);
            }
        }
        classes.sort(Comparator.comparing(Class::getName));
        return classes;
    }

    private static void registerUnique(
            ToolCallback callback,
            Set<String> registeredNames,
            List<ToolCallback> callbacksList,
            String source) {
        String name = callback.getToolDefinition().name();
        if (!registeredNames.add(name)) {
            throw new IllegalStateException(
                    "Duplicate MCP tool name '" + name + "' (" + source + ")");
        }
        callbacksList.add(callback);
        log.info("Registered {} MCP tool callback: {}", source, name);
    }

    private static void registerBeanTools(
            ApplicationContext applicationContext,
            Class<?> clazz,
            Set<String> registeredNames,
            List<ToolCallback> callbacksList) {
        Object bean = applicationContext.getBean(clazz);
        for (ToolCallback callback : ToolCallbacks.from(bean)) {
            registerUnique(callback, registeredNames, callbacksList, clazz.getSimpleName());
        }
    }
}
