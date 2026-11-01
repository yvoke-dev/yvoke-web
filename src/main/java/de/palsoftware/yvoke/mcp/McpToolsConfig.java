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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

@Configuration
public class McpToolsConfig {

    private static final Logger log = LoggerFactory.getLogger(McpToolsConfig.class);

    /**
     * The tools both the in-app assistant ({@code RagService}, through its
     * {@code List<ToolCallback>} constructor argument) and MCP clients get. It starts from the
     * client-only names, so no shared registration, by hand or by scan, can put one of them in
     * front of the in-app assistant.
     */
    @Bean
    public List<ToolCallback> sharedToolCallbacks(ApplicationContext applicationContext,
        @Value("${app.retrieval.max-limit}") int maxLimit,
        @Qualifier("clientOnlyToolCallbacks") ToolCallbackProvider clientOnlyToolCallbacks) {
        List<ToolCallback> callbacksList = new ArrayList<>();
        Set<String> registeredNames = new HashSet<>();
        for (ToolCallback clientOnly : clientOnlyToolCallbacks.getToolCallbacks()) {
            registeredNames.add(clientOnly.getToolDefinition().name());
        }

        try {
            SearchCorpusTool searchCorpusTool = applicationContext.getBean(SearchCorpusTool.class);
            ObjectMapper objectMapper = applicationContext.getBean(ObjectMapper.class);
            registerByHand(new SearchCorpusToolCallback(searchCorpusTool, objectMapper, maxLimit),
                registeredNames, callbacksList);
        } catch (Exception e) {
            log.error("Failed to register custom SearchCorpusToolCallback", e);
        }

        try {
            ObjectMapper objectMapper = applicationContext.getBean(ObjectMapper.class);
            registerByHand(new AskClarifyingQuestionToolCallback(objectMapper), registeredNames,
                callbacksList);
        } catch (Exception e) {
            log.error("Failed to register custom AskClarifyingQuestionToolCallback", e);
        }

        try {
            GetSectionTool getSectionTool = applicationContext.getBean(GetSectionTool.class);
            ObjectMapper objectMapper = applicationContext.getBean(ObjectMapper.class);
            registerByHand(new GetSectionToolCallback(getSectionTool, objectMapper),
                registeredNames, callbacksList);
        } catch (Exception e) {
            log.error("Failed to register custom GetSectionToolCallback", e);
        }

        try {
            ListDocumentsTool listDocumentsTool =
                applicationContext.getBean(ListDocumentsTool.class);
            ObjectMapper objectMapper = applicationContext.getBean(ObjectMapper.class);
            registerByHand(new ListDocumentsToolCallback(listDocumentsTool, objectMapper),
                registeredNames, callbacksList);
        } catch (Exception e) {
            log.error("Failed to register custom ListDocumentsToolCallback", e);
        }

        try {
            VerifyCitationsTool verifyCitationsTool =
                applicationContext.getBean(VerifyCitationsTool.class);
            ObjectMapper objectMapper = applicationContext.getBean(ObjectMapper.class);
            registerByHand(new VerifyCitationsToolCallback(verifyCitationsTool, objectMapper),
                registeredNames, callbacksList);
        } catch (Exception e) {
            log.error("Failed to register custom VerifyCitationsToolCallback", e);
        }

        for (Class<?> clazz : scanToolClasses()) {
            // Registered by hand above with a context-aware callback. The duplicate-name
            // guard below would skip the scanned copy anyway, but that leaves two sources
            // of truth for one tool's schema; skipping the class keeps exactly one.
            if (clazz.equals(SearchCorpusTool.class) || clazz.equals(GetSectionTool.class)
                || clazz.equals(ListDocumentsTool.class) || clazz.equals(VerifyCitationsTool.class)
                || clazz.isAnnotationPresent(ClientOnlyTools.class)) {
                continue;
            }
            register(applicationContext, clazz, registeredNames, callbacksList);
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
                register(applicationContext, clazz, registeredNames, callbacksList);
            }
        }
        log.info("Total client-only MCP Server Tools registered: {}", callbacksList.size());
        return ToolCallbackProvider.from(callbacksList);
    }

    // Classpath scanning rather than getBeansWithAnnotation on the entire ApplicationContext,
    // which would eagerly instantiate other unrelated beans and cause circular reference errors.
    private static List<Class<?>> scanToolClasses() {
        ClassPathScanningCandidateComponentProvider scanner =
            new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        List<Class<?>> classes = new ArrayList<>();
        for (BeanDefinition bd : scanner
            .findCandidateComponents("de.palsoftware.yvoke.mcp.tools")) {
            String className = bd.getBeanClassName();
            if (className == null) {
                continue;
            }
            try {
                classes.add(Class.forName(className));
            } catch (Exception e) {
                log.error("Failed to load MCP Tool candidate: {}", className, e);
            }
        }
        return classes;
    }

    private static void registerByHand(ToolCallback callback, Set<String> registeredNames,
        List<ToolCallback> callbacksList) {
        String name = callback.getToolDefinition().name();
        if (registeredNames.add(name)) {
            callbacksList.add(callback);
            log.info("Manually registered Context-Aware Tool callback: {}", name);
        } else {
            log.error("Skipped hand-registered tool callback '{}': the name is already taken",
                name);
        }
    }

    private static void register(ApplicationContext applicationContext, Class<?> clazz,
        Set<String> registeredNames, List<ToolCallback> callbacksList) {
        try {
            Object bean = applicationContext.getBean(clazz);
            for (ToolCallback callback : ToolCallbacks.from(bean)) {
                String name = callback.getToolDefinition().name();
                if (registeredNames.add(name)) {
                    callbacksList.add(callback);
                    log.info("Registered MCP Server Tool callback: {}", name);
                } else {
                    log.debug("Skipped duplicate MCP Server Tool registration: {}", name);
                }
            }
        } catch (Exception e) {
            log.error("Failed to register MCP Tool callback for candidate: {}", clazz.getName(), e);
        }
    }
}
