package de.palsoftware.yvoke.rag.prompt;

import de.palsoftware.yvoke.area.core.AreaService;
import de.palsoftware.yvoke.shared.config.CacheConfig;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

@Service
public class SystemPromptService {

    private final SystemPromptRepository systemPromptRepository;
    private final AreaService areaService;
    private final CacheManager cacheManager;

    @Autowired
    public SystemPromptService(SystemPromptRepository systemPromptRepository,
        AreaService areaService, CacheManager cacheManager) {
        this.areaService = Objects.requireNonNull(areaService, "areaService must not be null");
        this.systemPromptRepository = Objects.requireNonNull(systemPromptRepository,
            "systemPromptRepository must not be null");
        this.cacheManager = Objects.requireNonNull(cacheManager, "cacheManager must not be null");
    }

    /** For callers outside the container; creates a concurrent map cache manager. */
    SystemPromptService(SystemPromptRepository systemPromptRepository, AreaService areaService) {
        this(systemPromptRepository, areaService,
            new ConcurrentMapCacheManager(CacheConfig.SYSTEM_PROMPTS));
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
        SystemPrompt prompt = systemPromptRepository.findByName(name.trim())
            .orElseThrow(() -> new IllegalArgumentException(
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

    public Optional<SystemPrompt> getPrompt(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }

        String trimmed = name.trim();
        Cache cache = cacheManager.getCache(CacheConfig.SYSTEM_PROMPTS);
        if (cache != null) {
            return cache.get(trimmed, () -> systemPromptRepository.findByName(trimmed));
        }
        return systemPromptRepository.findByName(trimmed);
    }

    @CacheEvict(cacheNames = CacheConfig.SYSTEM_PROMPTS, allEntries = true)
    public void savePrompt(String name, SystemPromptType type, String systemPrompt,
        String description, String area) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Prompt name cannot be empty.");
        }
        if (type == null) {
            throw new IllegalArgumentException("Prompt type cannot be empty.");
        }
        if (systemPrompt == null || systemPrompt.isBlank()) {
            throw new IllegalArgumentException("System prompt content cannot be empty.");
        }
        String storedArea = areaService.requireArea(area);
        systemPromptRepository.upsert(name.trim(), type, systemPrompt.trim(),
            description != null ? description.trim() : "", storedArea);
    }

    @CacheEvict(cacheNames = CacheConfig.SYSTEM_PROMPTS, allEntries = true)
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
        if (prompt.area() != null) {
            sb.append("area: ").append(prompt.area()).append("\n");
        }
        if (prompt.description() != null && !prompt.description().isBlank()) {
            sb.append("description: ").append(prompt.description()).append("\n");
        }
        sb.append("---\n\n");
        sb.append(prompt.systemPrompt() != null ? prompt.systemPrompt() : "");
        return sb.toString();
    }

    /**
     * Parses a Markdown system prompt into a {@link SystemPrompt} record without persisting it. The
     * area named in its frontmatter wins; a file that names none uses {@code fallbackArea}.
     */
    public static SystemPrompt parseMarkdown(String mdContent, String fallbackName,
        String fallbackArea) {
        if (mdContent == null || mdContent.isBlank()) {
            throw new IllegalArgumentException("System prompt content cannot be empty.");
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

        if (yamlFrontmatter.isBlank()) {
            throw new IllegalArgumentException(
                "YAML frontmatter in system prompt cannot be empty.");
        }

        Object loaded;
        try {
            loaded = new Yaml().load(yamlFrontmatter);
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed YAML frontmatter in system prompt.", e);
        }
        if (!(loaded instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(
                "YAML frontmatter in system prompt must be a mapping.");
        }

        String name = fallbackName != null ? fallbackName : "";
        if (map.get("name") != null) {
            name = String.valueOf(map.get("name")).trim();
        }
        String description = "";
        if (map.get("description") != null) {
            description = String.valueOf(map.get("description")).trim();
        }
        String area = fallbackArea;
        if (map.get("area") != null && !String.valueOf(map.get("area")).isBlank()) {
            area = String.valueOf(map.get("area")).trim();
        }

        Object typeObj = map.get("type");
        if (typeObj == null || String.valueOf(typeObj).isBlank()) {
            throw new IllegalArgumentException(
                "System prompt type cannot be empty in YAML frontmatter.");
        }
        SystemPromptType type = SystemPromptType.fromString(String.valueOf(typeObj).trim());

        return new SystemPrompt(name, type, body, description, Instant.now(), Instant.now(), false,
            area);
    }

    /**
     * Imports a prompt file. The area named in its frontmatter wins; a file that names none goes
     * into {@code fallbackArea}, the area picked on the import form.
     */
    @CacheEvict(cacheNames = CacheConfig.SYSTEM_PROMPTS, allEntries = true)
    public SystemPrompt importPromptFromMarkdown(String mdContent, String fallbackName,
        String fallbackArea) {
        SystemPrompt parsed = parseMarkdown(mdContent, fallbackName, fallbackArea);
        savePrompt(parsed.name(), parsed.type(), parsed.systemPrompt(), parsed.description(),
            parsed.area());
        return getPrompt(parsed.name()).orElse(parsed);
    }
}
