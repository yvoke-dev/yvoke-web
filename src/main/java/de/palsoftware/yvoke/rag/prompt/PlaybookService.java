package de.palsoftware.yvoke.rag.prompt;

import de.palsoftware.yvoke.shared.config.CacheConfig;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class PlaybookService {

    private static final Logger log = LoggerFactory.getLogger(PlaybookService.class);

    private final PlaybookRepository playbookRepository;
    private final McpSyncServer mcpSyncServer;

    public PlaybookService(PlaybookRepository playbookRepository,
        @Autowired(required = false) McpSyncServer mcpSyncServer) {
        this.playbookRepository = playbookRepository;
        this.mcpSyncServer = mcpSyncServer;
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

    @Cacheable(cacheNames = CacheConfig.PLAYBOOKS, key = "#name",
        condition = "#name != null && !#name.isBlank()")
    public Optional<Playbook> getPlaybook(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }

        // Try database
        return playbookRepository.findByName(name.trim());
    }

    public void savePlaybook(String name, String title, String description, String templateText,
        List<String> tools, boolean codeExecution) {
        savePlaybook(name, title, description, templateText, tools, codeExecution, "specialist",
            false);
    }

    public void savePlaybook(String name, String title, String description, String templateText,
        List<String> tools, boolean codeExecution, String targetAgent) {
        savePlaybook(name, title, description, templateText, tools, codeExecution, targetAgent,
            false);
    }

    @CacheEvict(cacheNames = CacheConfig.PLAYBOOKS, key = "#name",
        condition = "#name != null && !#name.isBlank()")
    public void savePlaybook(String name, String title, String description, String templateText,
        List<String> tools, boolean codeExecution, String targetAgent, boolean prototype) {
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

        playbookRepository.upsert(name.trim(), title.trim(),
            description != null ? description.trim() : "", templateText.trim(), tools,
            codeExecution, agent, prototype);

        if (mcpSyncServer != null) {
            registerPlaybookWithMcp(name.trim());
        }
    }

    public Playbook importPlaybookFromMarkdown(String mdContent, String fallbackName) {
        Playbook parsed = PlaybookMarkdownParser.parseMarkdown(mdContent, fallbackName);
        savePlaybook(parsed.name(), parsed.title(), parsed.description(), parsed.templateText(),
            parsed.tools(), parsed.codeExecution(), parsed.targetAgent(), parsed.prototype());
        return getPlaybook(parsed.name()).orElse(parsed);
    }

    public String exportPlaybookToMarkdown(String name) {
        Playbook pb = getPlaybook(name)
            .orElseThrow(() -> new IllegalArgumentException("Playbook '" + name + "' not found."));
        return PlaybookMarkdownParser.toMarkdown(pb);
    }

    @CacheEvict(cacheNames = CacheConfig.PLAYBOOKS, key = "#name",
        condition = "#name != null && !#name.isBlank()")
    public void deletePlaybook(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Playbook name cannot be empty.");
        }

        playbookRepository.delete(name.trim());
        if (mcpSyncServer != null) {
            try {
                mcpSyncServer.notifyPromptsListChanged();
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

    private void registerPlaybookWithMcp(String name) {
        if (mcpSyncServer == null)
            return;
        getPlaybook(name).ifPresent(playbook -> {
            doRegister(playbook, mcpSyncServer);
            try {
                mcpSyncServer.notifyPromptsListChanged();
            } catch (Exception e) {
                log.debug("No MCP client connected yet to receive list change notification.");
            }
        });
    }

    private void doRegister(Playbook playbook, McpSyncServer server) {
        var promptSpec =
            McpSchema.Prompt.builder(playbook.name()).description(playbook.description())
                .meta(Map.of("tools", playbook.tools() != null ? playbook.tools() : List.of(),
                    "codeExecution", playbook.codeExecution(), "targetAgent",
                    playbook.targetAgent() != null ? playbook.targetAgent() : "specialist",
                    "prototype", playbook.prototype()))
                .build();

        var registration =
            new McpServerFeatures.SyncPromptSpecification(promptSpec, (exchange, request) -> {
                // Dynamic lookup on execution to support live database template updates
                Playbook current = getPlaybook(playbook.name()).orElse(playbook);
                return McpSchema.GetPromptResult
                    .builder(List.of(new McpSchema.PromptMessage(McpSchema.Role.USER,
                        McpSchema.TextContent.builder(current.templateText()).build())))
                    .description(current.description())
                    .meta(Map.of("tools", current.tools() != null ? current.tools() : List.of(),
                        "codeExecution", current.codeExecution(), "targetAgent",
                        current.targetAgent() != null ? current.targetAgent() : "specialist",
                        "prototype", current.prototype()))
                    .build();
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
