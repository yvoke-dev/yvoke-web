package de.palsoftware.yvoke.chat.api;

import de.palsoftware.yvoke.chat.api.model.*;
import de.palsoftware.yvoke.chat.core.model.Conversation;
import de.palsoftware.yvoke.chat.core.service.DesktopSyncService;
import de.palsoftware.yvoke.chat.core.service.DesktopSyncService.NewMessage;
import de.palsoftware.yvoke.chat.core.model.Feedback;
import de.palsoftware.yvoke.chat.core.model.Message;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProfile;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProfileService;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProperties;
import de.palsoftware.yvoke.chat.orchestration.DesktopOrchestratorRunService;
import de.palsoftware.yvoke.shared.user.model.User;
import de.palsoftware.yvoke.rag.prompt.SystemPrompt;
import de.palsoftware.yvoke.rag.prompt.SystemPromptService;
import de.palsoftware.yvoke.rag.prompt.PlaybookService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/chat/v1")
public class DesktopSyncController {

    private final DesktopSyncService syncService;
    private final SystemPromptService systemPromptService;
    private final PlaybookService playbookService;
    private final OrchestratorProperties orchestratorProperties;
    private final OrchestratorProfileService orchestratorProfileService;
    private final DesktopOrchestratorRunService orchestratorRunService;

    @Autowired
    public DesktopSyncController(DesktopSyncService syncService,
        SystemPromptService systemPromptService, PlaybookService playbookService,
        OrchestratorProperties orchestratorProperties,
        OrchestratorProfileService orchestratorProfileService,
        DesktopOrchestratorRunService orchestratorRunService) {
        this.syncService = syncService;
        this.systemPromptService = systemPromptService;
        this.playbookService = playbookService;
        this.orchestratorProperties = orchestratorProperties;
        this.orchestratorProfileService = orchestratorProfileService;
        this.orchestratorRunService = orchestratorRunService;
    }

    public DesktopSyncController(DesktopSyncService syncService,
        SystemPromptService systemPromptService, PlaybookService playbookService,
        OrchestratorProperties orchestratorProperties,
        DesktopOrchestratorRunService orchestratorRunService) {
        this(syncService, systemPromptService, playbookService, orchestratorProperties, null,
            orchestratorRunService);
    }

    @GetMapping("/playbooks")
    public List<PlaybookDto> listPlaybooks() {
        return playbookService.listSpecializedPlaybooks().stream().map(PlaybookDto::from).toList();
    }

    /**
     * Multi-agent profiles (knowledge bases) available for orchestrator mode. The desktop renders
     * these in its profile dropdown; playbook <em>content</em> is fetched separately via MCP
     * prompts.
     */
    @GetMapping("/orchestrator/profiles")
    public List<OrchestratorProfileDto> listOrchestratorProfiles() {
        if (orchestratorProfileService != null) {
            List<OrchestratorProfile> profiles = orchestratorProfileService.listAllProfiles();
            if (!profiles.isEmpty()) {
                return profiles.stream().map(OrchestratorProfileDto::fromDbProfile).toList();
            }
        }
        List<OrchestratorProperties.Profile> profiles = orchestratorProperties.profiles();
        if (profiles == null) {
            return List.of();
        }
        return profiles.stream().map(OrchestratorProfileDto::from).toList();
    }

    /**
     * Records a completed multi-agent run (the desktop runs orchestration locally). The final
     * answer must already be persisted via {@code /messages}; {@code messageId} links this run to
     * it.
     */
    @PostMapping("/orchestrator/runs")
    public Map<String, UUID> recordOrchestratorRun(User user,
        @RequestBody OrchestratorRunRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
        }
        return Map.of("id", orchestratorRunService.record(user, request));
    }

    @GetMapping("/prompts/system/{name}")
    public ResponseEntity<Map<String, String>> getSystemPrompt(@PathVariable String name) {
        String systemPromptText =
            systemPromptService.getPrompt(name).map(SystemPrompt::systemPrompt).orElseGet(() -> {
                if ("default-chat".equals(name)) {
                    String activeName = systemPromptService.getDefaultChatPromptName();
                    return systemPromptService.getPrompt(activeName).map(SystemPrompt::systemPrompt)
                        .orElse("");
                }
                return "";
            });
        return ResponseEntity.ok(Map.of("systemPrompt", systemPromptText));
    }

    @GetMapping("/conversations")
    public List<ConversationDto> listConversations(User user,
        @RequestParam(name = "limit", defaultValue = "100") int limit,
        @RequestParam(name = "offset", defaultValue = "0") int offset) {
        return syncService.listConversations(user, limit, offset).stream()
            .map(ConversationDto::from).toList();
    }

    @PostMapping("/conversations")
    public ResponseEntity<ConversationDto> createConversation(User user,
        @Valid @RequestBody(required = false) CreateConversationRequest request) {
        String title = request != null ? request.title() : null;
        Map<String, Object> settings = request != null ? request.settings() : null;
        Conversation conversation = syncService.createConversation(user, title, settings);
        return ResponseEntity.status(HttpStatus.CREATED).body(ConversationDto.from(conversation));
    }

    @PatchMapping("/conversations/{id}")
    public ResponseEntity<Void> updateConversation(User user, @PathVariable UUID id,
        @Valid @RequestBody UpdateConversationRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
        }
        syncService.updateConversation(id, user, request.title(), request.settings());
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/conversations/{id}")
    public ResponseEntity<Void> deleteConversation(User user, @PathVariable UUID id) {
        syncService.deleteConversation(id, user);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/conversations/{id}/messages")
    public List<MessageDto> getMessages(User user, @PathVariable UUID id,
        @RequestParam(name = "limit", defaultValue = "500") int limit,
        @RequestParam(name = "offset", defaultValue = "0") int offset) {
        List<Message> messages = syncService.getMessages(id, user, limit, offset);
        Map<UUID, Feedback> feedback = syncService.getFeedbackByMessageId(id, user);
        return messages.stream().map(m -> MessageDto.from(m, feedback.get(m.id()))).toList();
    }

    @PostMapping("/conversations/{id}/messages")
    public Map<String, List<UUID>> appendMessages(User user, @PathVariable UUID id,
        @Valid @RequestBody AppendMessagesRequest request) {
        if (request == null || request.messages() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "messages are required");
        }
        List<NewMessage> newMessages = request
            .messages().stream().map(m -> new NewMessage(m.role(), m.content(), m.promptTokens(),
                m.completionTokens(), m.totalTokens(), m.cachedTokens(), m.thoughtTokens()))
            .toList();
        return Map.of("ids", syncService.appendMessages(id, user, newMessages));
    }

    @PutMapping("/messages/{messageId}/feedback")
    public FeedbackDto submitFeedback(User user, @PathVariable UUID messageId,
        @RequestBody FeedbackRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
        }
        Feedback feedback =
            syncService.submitFeedback(messageId, user, request.rating(), request.comment());
        return new FeedbackDto(feedback.messageId(), feedback.rating(), feedback.comment());
    }
}
