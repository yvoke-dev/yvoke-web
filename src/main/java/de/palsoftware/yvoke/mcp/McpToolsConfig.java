package de.palsoftware.yvoke.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.chat.core.tool.AskClarifyingQuestionToolCallback;
import de.palsoftware.yvoke.mcp.tools.GetSectionTool;
import de.palsoftware.yvoke.mcp.tools.GetSectionToolCallback;
import de.palsoftware.yvoke.mcp.tools.SearchCorpusTool;
import de.palsoftware.yvoke.mcp.tools.SearchCorpusToolCallback;
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
     * {@code List<ToolCallback>} constructor argument) and MCP clients get.
     */
    @Bean
    public List<ToolCallback> sharedToolCallbacks(ApplicationContext applicationContext,
        @Value("${app.retrieval.max-limit}") int maxLimit) {
        List<ToolCallback> callbacksList = new ArrayList<>();
        Set<String> registeredNames = new HashSet<>(clientOnlyNames(applicationContext));

        try {
            SearchCorpusTool searchCorpusTool = applicationContext.getBean(SearchCorpusTool.class);
            ObjectMapper objectMapper = applicationContext.getBean(ObjectMapper.class);
            callbacksList
                .add(new SearchCorpusToolCallback(searchCorpusTool, objectMapper, maxLimit));
            registeredNames.add("search_corpus");
            log.info("Manually registered Context-Aware Tool callback: search_corpus");
        } catch (Exception e) {
            log.error("Failed to register custom SearchCorpusToolCallback", e);
        }

        try {
            ObjectMapper objectMapper = applicationContext.getBean(ObjectMapper.class);
            callbacksList.add(new AskClarifyingQuestionToolCallback(objectMapper));
            registeredNames.add("ask_clarifying_question");
            log.info("Manually registered Context-Aware Tool callback: ask_clarifying_question");
        } catch (Exception e) {
            log.error("Failed to register custom AskClarifyingQuestionToolCallback", e);
        }

        try {
            GetSectionTool getSectionTool = applicationContext.getBean(GetSectionTool.class);
            ObjectMapper objectMapper = applicationContext.getBean(ObjectMapper.class);
            callbacksList.add(new GetSectionToolCallback(getSectionTool, objectMapper));
            registeredNames.add("get_section");
            log.info("Manually registered Context-Aware Tool callback: get_section");
        } catch (Exception e) {
            log.error("Failed to register custom GetSectionToolCallback", e);
        }

        for (Class<?> clazz : scanToolClasses()) {
            // Both are registered by hand above with a context-aware callback. The
            // duplicate-name guard below would skip the scanned copy anyway, but that
            // leaves two sources of truth for one tool's schema; skipping the class keeps
            // exactly one.
            if (clazz.equals(SearchCorpusTool.class) || clazz.equals(GetSectionTool.class)
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

    /**
     * The names the client-only classes declare. The shared list starts from them, so a shared
     * class declaring one of the same names can never put it in front of the in-app assistant.
     */
    private static Set<String> clientOnlyNames(ApplicationContext applicationContext) {
        Set<String> names = new HashSet<>();
        for (Class<?> clazz : scanToolClasses()) {
            if (clazz.isAnnotationPresent(ClientOnlyTools.class)) {
                for (ToolCallback callback : ToolCallbacks
                    .from(applicationContext.getBean(clazz))) {
                    names.add(callback.getToolDefinition().name());
                }
            }
        }
        return names;
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
