package de.palsoftware.yvoke.rag.prompt;

import de.palsoftware.yvoke.area.core.AreaService;
import de.palsoftware.yvoke.shared.config.CacheConfig;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class PlaybookService {

    private static final Logger log = LoggerFactory.getLogger(PlaybookService.class);

    private final PlaybookRepository playbookRepository;
    /**
     * Resolved when a playbook changes, not at construction: the server is built from the tool
     * list, and a tool that reads playbooks would otherwise close a startup cycle. A failed tool
     * bean is only logged by {@code McpToolsConfig}, so the cycle would silently drop the tool.
     */
    private final ObjectProvider<McpSyncServer> mcpSyncServer;
    private final AreaService areaService;
    private final SystemPromptService systemPromptService;

    @Autowired
    public PlaybookService(PlaybookRepository playbookRepository,
        ObjectProvider<McpSyncServer> mcpSyncServer, AreaService areaService,
        SystemPromptService systemPromptService) {
        this.playbookRepository = playbookRepository;
        this.mcpSyncServer = mcpSyncServer;
        this.areaService = areaService;
        this.systemPromptService = systemPromptService;
    }

    /** For callers outside the container; {@code null} means no MCP server to notify. */
    public PlaybookService(PlaybookRepository playbookRepository, McpSyncServer mcpSyncServer,
        AreaService areaService) {
        this(playbookRepository, mcpSyncServer, areaService, null);
    }

    public PlaybookService(PlaybookRepository playbookRepository, McpSyncServer mcpSyncServer,
        AreaService areaService, SystemPromptService systemPromptService) {
        this(playbookRepository, new ObjectProvider<>() {
            @Override
            public McpSyncServer getObject() {
                return mcpSyncServer;
            }

            @Override
            public McpSyncServer getIfAvailable() {
                return mcpSyncServer;
            }
        }, areaService, systemPromptService);
    }

    public List<Playbook> listAllPlaybooks() {
        return playbookRepository.findAll();
    }

    public List<Playbook> listSpecializedPlaybooks() {
        return listAllPlaybooks().stream()
            .filter(p -> "specialist".equalsIgnoreCase(p.targetAgent()) || p.targetAgent() == null
                || (!"orchestrator".equalsIgnoreCase(p.targetAgent())
                    && !"reviewer".equalsIgnoreCase(p.targetAgent())))
            .toList();
    }

    @Cacheable(cacheNames = CacheConfig.PLAYBOOKS, key = "#name?.trim()",
        condition = "#name != null && !#name.isBlank()")
    public Optional<Playbook> getPlaybook(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }

        // Try database
        return playbookRepository.findByName(name.trim());
    }

    @CacheEvict(cacheNames = CacheConfig.PLAYBOOKS, key = "#name?.trim()",
        condition = "#name != null && !#name.isBlank()")
    public void savePlaybook(String name, String title, String description, String templateText,
        List<String> tools, boolean codeExecution, String targetAgent, boolean prototype,
        String area) {
        savePlaybook(name, title, description, templateText, tools, codeExecution, targetAgent,
            prototype, area, null);
    }

    @CacheEvict(cacheNames = CacheConfig.PLAYBOOKS, key = "#name?.trim()",
        condition = "#name != null && !#name.isBlank()")
    public void savePlaybook(String name, String title, String description, String templateText,
        List<String> tools, boolean codeExecution, String targetAgent, boolean prototype,
        String area, String systemPrompt) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Playbook name cannot be empty.");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Playbook title cannot be empty.");
        }
        if (templateText == null || templateText.isBlank()) {
            throw new IllegalArgumentException("Playbook template text cannot be empty.");
        }

        String agent =
            targetAgent != null && !targetAgent.isBlank() ? targetAgent.trim() : "specialist";
        String storedArea = areaService.requireArea(area);
        String prompt = null;
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            prompt =
                systemPromptService != null
                    ? systemPromptService.requirePrompt(systemPrompt.trim(), SystemPromptType.CHAT)
                        .name()
                    : systemPrompt.trim();
        }

        playbookRepository.upsert(name.trim(), title.trim(),
            description != null ? description.trim() : "", templateText.trim(), tools,
            codeExecution, agent, prototype, storedArea, prompt);

        McpSyncServer server = mcpSyncServer.getIfAvailable();
        if (server != null) {
            registerPlaybookWithMcp(name.trim(), server);
        }
    }

    /**
     * Imports a playbook file. The area named in its frontmatter wins; a file that names none goes
     * into {@code fallbackArea}, the area picked on the import form.
     */
    @CacheEvict(cacheNames = CacheConfig.PLAYBOOKS, key = "#result?.name()?.trim()",
        condition = "#result != null && #result.name() != null && !#result.name().isBlank()")
    public Playbook importPlaybookFromMarkdown(String mdContent, String fallbackName,
        String fallbackArea) {
        Playbook parsed = PlaybookMarkdownParser.parseMarkdown(mdContent, fallbackName);
        String area =
            parsed.area() != null && !parsed.area().isBlank() ? parsed.area() : fallbackArea;
        savePlaybook(parsed.name(), parsed.title(), parsed.description(), parsed.templateText(),
            parsed.tools(), parsed.codeExecution(), parsed.targetAgent(), parsed.prototype(), area,
            parsed.systemPrompt());
        return getPlaybook(parsed.name()).orElse(parsed);
    }

    public String exportPlaybookToMarkdown(String name) {
        Playbook pb = getPlaybook(name)
            .orElseThrow(() -> new IllegalArgumentException("Playbook '" + name + "' not found."));
        return PlaybookMarkdownParser.toMarkdown(pb);
    }

    @CacheEvict(cacheNames = CacheConfig.PLAYBOOKS, key = "#name?.trim()",
        condition = "#name != null && !#name.isBlank()")
    public void deletePlaybook(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Playbook name cannot be empty.");
        }

        playbookRepository.delete(name.trim());
        McpSyncServer server = mcpSyncServer.getIfAvailable();
        if (server != null) {
            try {
                server.notifyPromptsListChanged();
                log.info("Deleted playbook and notified MCP: {}", name);
            } catch (Exception e) {
                log.warn("Failed to notify prompt changes after delete for {}: {}", name,
                    e.getMessage());
            }
        }
    }

    public void registerAllPlaybooksWithMcp(McpSyncServer server) {
        if (server == null) {
            return;
        }
        List<Playbook> playbooks = listAllPlaybooks();
        log.info("Registering {} database playbooks with McpSyncServer", playbooks.size());
        for (Playbook playbook : playbooks) {
            doRegister(playbook, server);
        }
        try {
            server.notifyPromptsListChanged();
        } catch (Exception e) {
            log.debug("No MCP client connected yet to receive list change notification.");
        }
    }

    private void registerPlaybookWithMcp(String name, McpSyncServer server) {
        getPlaybook(name).ifPresent(playbook -> {
            doRegister(playbook, server);
            try {
                server.notifyPromptsListChanged();
            } catch (Exception e) {
                log.debug("No MCP client connected yet to receive list change notification.");
            }
        });
    }

    private void doRegister(Playbook playbook, McpSyncServer server) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("tools", playbook.tools() != null ? playbook.tools() : List.of());
        meta.put("codeExecution", playbook.codeExecution());
        meta.put("targetAgent",
            playbook.targetAgent() != null ? playbook.targetAgent() : "specialist");
        meta.put("prototype", playbook.prototype());
        if (playbook.systemPrompt() != null && !playbook.systemPrompt().isBlank()) {
            meta.put("systemPrompt", playbook.systemPrompt().trim());
        }

        var promptSpec = McpSchema.Prompt.builder(playbook.name())
            .description(playbook.description()).meta(meta).build();

        var registration =
            new McpServerFeatures.SyncPromptSpecification(promptSpec, (exchange, request) -> {
                // Dynamic lookup on execution to support live database template updates
                Playbook current = getPlaybook(playbook.name()).orElse(playbook);
                Map<String, Object> execMeta = new HashMap<>();
                execMeta.put("tools", current.tools() != null ? current.tools() : List.of());
                execMeta.put("codeExecution", current.codeExecution());
                execMeta.put("targetAgent",
                    current.targetAgent() != null ? current.targetAgent() : "specialist");
                execMeta.put("prototype", current.prototype());
                if (current.systemPrompt() != null && !current.systemPrompt().isBlank()) {
                    execMeta.put("systemPrompt", current.systemPrompt().trim());
                }
                return McpSchema.GetPromptResult
                    .builder(List.of(new McpSchema.PromptMessage(McpSchema.Role.USER,
                        McpSchema.TextContent.builder(current.templateText()).build())))
                    .description(current.description()).meta(execMeta).build();
            });

        try {
            server.addPrompt(registration);
            log.info("Successfully registered/updated prompt spec: {}", playbook.name());
        } catch (Exception e) {
            log.error("Failed to register prompt {} with McpSyncServer: {}", playbook.name(),
                e.getMessage());
        }
    }
}
