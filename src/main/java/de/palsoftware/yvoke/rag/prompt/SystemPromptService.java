package de.palsoftware.yvoke.rag.prompt;

import de.palsoftware.yvoke.shared.config.CacheConfig;
import java.util.*;
import org.yaml.snakeyaml.Yaml;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class SystemPromptService {

    private final SystemPromptRepository systemPromptRepository;
    private final String defaultPromptName;
    private final de.palsoftware.yvoke.shared.config.repository.AppConfigRepository appConfigRepository;

    public SystemPromptService(SystemPromptRepository systemPromptRepository,
        @Value("${app.ai.rag.default-prompt-name}") String defaultPromptName,
        de.palsoftware.yvoke.shared.config.repository.AppConfigRepository appConfigRepository) {
        this.systemPromptRepository = systemPromptRepository;
        this.defaultPromptName = defaultPromptName;
        this.appConfigRepository = appConfigRepository;
    }

    @Cacheable(cacheNames = CacheConfig.APP_CONFIG, key = "'default-chat-prompt'")
    public String getDefaultChatPromptName() {
        return appConfigRepository.get("default-chat-prompt", defaultPromptName);
    }

    @CacheEvict(cacheNames = CacheConfig.APP_CONFIG, key = "'default-chat-prompt'")
    public void setDefaultChatPromptName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Default prompt name cannot be empty.");
        }
        appConfigRepository.set("default-chat-prompt", name.trim());
    }

    public List<SystemPrompt> listAllPrompts() {
        return systemPromptRepository.findAll();
    }

    public List<SystemPrompt> listPromptsByType(SystemPromptType type) {
        return systemPromptRepository.findByType(type);
    }

    @Cacheable(cacheNames = CacheConfig.SYSTEM_PROMPTS, key = "#name",
        condition = "#name != null && !#name.isBlank()")
    public Optional<SystemPrompt> getPrompt(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }

        // Try database
        return systemPromptRepository.findByName(name.trim());
    }

    @CacheEvict(cacheNames = CacheConfig.SYSTEM_PROMPTS, key = "#name",
        condition = "#name != null && !#name.isBlank()")
    public void savePrompt(String name, SystemPromptType type, String systemPrompt,
        String description) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Prompt name cannot be empty.");
        }
        if (type == null) {
            throw new IllegalArgumentException("Prompt type cannot be empty.");
        }
        if (systemPrompt == null || systemPrompt.isBlank()) {
            throw new IllegalArgumentException("System prompt content cannot be empty.");
        }
        systemPromptRepository.upsert(name.trim(), type, systemPrompt.trim(),
            description != null ? description.trim() : "");
    }

    @CacheEvict(cacheNames = CacheConfig.SYSTEM_PROMPTS, key = "#name",
        condition = "#name != null && !#name.isBlank()")
    public void deletePrompt(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Prompt name cannot be empty.");
        }
        systemPromptRepository.delete(name.trim());
    }

    public String exportPromptToMarkdown(String name) {
        SystemPrompt prompt = getPrompt(name).orElseThrow(
            () -> new IllegalArgumentException("System prompt '" + name + "' not found."));
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(prompt.name()).append("\n");
        sb.append("type: ").append(prompt.type().name()).append("\n");
        if (prompt.description() != null && !prompt.description().isBlank()) {
            sb.append("description: ").append(prompt.description()).append("\n");
        }
        sb.append("---\n\n");
        sb.append(prompt.systemPrompt() != null ? prompt.systemPrompt() : "");
        return sb.toString();
    }

    public SystemPrompt importPromptFromMarkdown(String mdContent, String fallbackName) {
        if (mdContent == null) {
            mdContent = "";
        }
        String yamlFrontmatter = "";
        String body = mdContent;

        if (mdContent.startsWith("---")) {
            int endIdx = mdContent.indexOf("---", 3);
            if (endIdx > 0) {
                yamlFrontmatter = mdContent.substring(3, endIdx).trim();
                body = mdContent.substring(endIdx + 3).trim();
            }
        }

        String name = fallbackName != null ? fallbackName : "";
        SystemPromptType type = SystemPromptType.CHAT;
        String description = "";

        if (!yamlFrontmatter.isBlank()) {
            try {
                Object loaded = new Yaml().load(yamlFrontmatter);
                if (loaded instanceof Map<?, ?> map) {
                    if (map.get("name") != null) {
                        name = String.valueOf(map.get("name")).trim();
                    }
                    if (map.get("description") != null) {
                        description = String.valueOf(map.get("description")).trim();
                    }
                    Object typeObj = map.get("type");
                    if (typeObj != null) {
                        String tStr = String.valueOf(typeObj).trim().toUpperCase();
                        try {
                            type = SystemPromptType.valueOf(tStr);
                        } catch (Exception ignored) {
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }

        savePrompt(name, type, body, description);
        return getPrompt(name).orElse(new SystemPrompt(name, type, body, description));
    }
}
