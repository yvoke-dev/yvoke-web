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

    @Bean
    public List<ToolCallback> mcpToolCallbacks(ApplicationContext applicationContext,
        @Value("${app.retrieval.max-limit}") int maxLimit) {
        List<ToolCallback> callbacksList = new ArrayList<>();
        Set<String> registeredNames = new HashSet<>();

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

        // Use classpath scanning to discover candidate beans in the tools package
        // This avoids calling getBeansWithAnnotation on the entire ApplicationContext,
        // which would eagerly instantiate other unrelated beans and cause circular reference
        // errors.
        ClassPathScanningCandidateComponentProvider scanner =
            new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));

        for (BeanDefinition bd : scanner
            .findCandidateComponents("de.palsoftware.yvoke.mcp.tools")) {
            try {
                String className = bd.getBeanClassName();
                if (className != null) {
                    Class<?> clazz = Class.forName(className);
                    // Both are registered by hand above with a context-aware callback. The
                    // duplicate-name guard below would skip the scanned copy anyway, but that
                    // leaves two sources of truth for one tool's schema; skipping the class keeps
                    // exactly one.
                    if (clazz.equals(SearchCorpusTool.class)
                        || clazz.equals(GetSectionTool.class)) {
                        continue;
                    }
                    Object bean = applicationContext.getBean(clazz);
                    ToolCallback[] callbacks = ToolCallbacks.from(bean);
                    for (ToolCallback callback : callbacks) {
                        String name = callback.getToolDefinition().name();
                        if (registeredNames.add(name)) {
                            callbacksList.add(callback);
                            log.info("Registered MCP Server Tool callback: {}", name);
                        } else {
                            log.debug("Skipped duplicate MCP Server Tool registration: {}", name);
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Failed to register MCP Tool callback for candidate: {}",
                    bd.getBeanClassName(), e);
            }
        }

        log.info("Total MCP Server Tools registered: {}", callbacksList.size());
        return callbacksList;
    }
}
