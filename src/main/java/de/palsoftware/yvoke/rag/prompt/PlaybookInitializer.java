package de.palsoftware.yvoke.rag.prompt;

import io.modelcontextprotocol.server.McpSyncServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class PlaybookInitializer {

    private static final Logger log = LoggerFactory.getLogger(PlaybookInitializer.class);

    private final PlaybookService playbookService;
    private final McpSyncServer mcpSyncServer;

    public PlaybookInitializer(PlaybookService playbookService,
        @Autowired(required = false) McpSyncServer mcpSyncServer) {
        this.playbookService = playbookService;
        this.mcpSyncServer = mcpSyncServer;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("Application is ready. Checking for dynamic MCP prompts/playbooks to register...");
        if (mcpSyncServer != null) {
            playbookService.registerAllPlaybooksWithMcp(mcpSyncServer);
        } else {
            log.info(
                "McpSyncServer is not present in context (disabled or in test context). Skipping dynamic prompt registration.");
        }
    }
}
