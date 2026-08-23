package de.palsoftware.yvoke.rag.prompt;

import de.palsoftware.yvoke.shared.config.CacheConfig;
import java.util.*;
import org.yaml.snakeyaml.Yaml;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import de.palsoftware.yvoke.shared.config.repository.AppConfigRepository;

@Service
public class SystemPromptService {

    private final SystemPromptRepository systemPromptRepository;
    private final String defaultPromptName;
    private final AppConfigRepository appConfigRepository;

    public SystemPromptService(SystemPromptRepository systemPromptRepository,
        @Value("${app.ai.rag.default-prompt-name}") String defaultPromptName,
        AppConfigRepository appConfigRepository) {
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

    /**
     * The prompt named, or a failure that says what to fix.
     *
     * <p>
     * The silent alternative — {@code getPrompt(name).orElse(null)} — is what let a whole
     * collection be ingested with no summarize prompt at all: the job reported success, the
     * summaries came back as "Here is a summary of the section:", and the defect only surfaced much
     * later as content an agent read out of a {@code get_toc}. A prompt a job was told to use and
     * cannot find is a configuration error, so it is raised as one, at the point the name is known.
     *
     * <p>
     * The TYPE is checked as well as the name. Prompts share one flat namespace, so nothing stops a
     * CHAT prompt being selected where a SUMMARIZE one is meant — that would "resolve" and then
     * quietly instruct the summarizer to answer questions and cite sources. The message lists the
     * valid names rather than just rejecting, because the caller is an operator who picked from a
     * list that has since changed.
     */
    public SystemPrompt requirePrompt(String name, SystemPromptType expectedType) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("No " + expectedType
                + " system prompt was specified. Available: " + availableNames(expectedType));
        }
        SystemPrompt prompt = getPrompt(name).orElseThrow(() -> new IllegalArgumentException(
            "System prompt '" + name.trim() + "' does not exist. Available " + expectedType
                + " prompts: " + availableNames(expectedType)));
        if (expectedType != null && prompt.type() != expectedType) {
            throw new IllegalArgumentException("System prompt '" + prompt.name() + "' is of type "
                + prompt.type() + ", but a " + expectedType + " prompt is required. Available: "
                + availableNames(expectedType));
        }
        return prompt;
    }

    /** Valid names for an error message; deliberately sorted so the text is stable in tests. */
    private String availableNames(SystemPromptType type) {
        if (type == null) {
            return "(no type given)";
        }
        List<String> names =
            listPromptsByType(type).stream().map(SystemPrompt::name).sorted().toList();
        return names.isEmpty() ? "(none registered)" : String.join(", ", names);
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
