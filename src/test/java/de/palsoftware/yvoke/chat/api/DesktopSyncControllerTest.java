package de.palsoftware.yvoke.chat.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import de.palsoftware.yvoke.chat.api.model.*;
import de.palsoftware.yvoke.chat.core.model.Conversation;
import de.palsoftware.yvoke.chat.core.service.DesktopSyncService;
import de.palsoftware.yvoke.chat.core.service.DesktopSyncService.NewMessage;
import de.palsoftware.yvoke.chat.core.model.Feedback;
import de.palsoftware.yvoke.chat.core.model.Message;
import de.palsoftware.yvoke.shared.user.model.User;
import de.palsoftware.yvoke.rag.prompt.SystemPrompt;
import de.palsoftware.yvoke.rag.prompt.SystemPromptService;
import de.palsoftware.yvoke.rag.prompt.SystemPromptType;
import de.palsoftware.yvoke.rag.prompt.Playbook;
import de.palsoftware.yvoke.rag.prompt.PlaybookService;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProperties;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProperties.Profile;
import de.palsoftware.yvoke.chat.orchestration.DesktopOrchestratorRunService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

class DesktopSyncControllerTest {

    private DesktopSyncService syncService;
    private SystemPromptService systemPromptService;
    private PlaybookService playbookService;
    private OrchestratorProperties orchestratorProperties;
    private DesktopOrchestratorRunService orchestratorRunService;
    private DesktopSyncController controller;

    private final User testUser =
        new User(UUID.randomUUID(), "entra-oid", "user@test.local", "Test User", Instant.now());

    @BeforeEach
    void setUp() {
        syncService = mock(DesktopSyncService.class);
        systemPromptService = mock(SystemPromptService.class);
        playbookService = mock(PlaybookService.class);
        orchestratorProperties = new OrchestratorProperties(2, 8, null,
            List.of(new Profile("OIM", "oim-orchestrator", "oim-orchestrator-reviewer",
                List.of("oim-access-governance", "oim-developer-api"), null, null, null)));
        orchestratorRunService = mock(DesktopOrchestratorRunService.class);
        controller = new DesktopSyncController(syncService, systemPromptService, playbookService,
            orchestratorProperties, orchestratorRunService);
    }

    @Test
    void recordOrchestratorRunDelegatesToServiceAndReturnsId() {
        UUID runId = UUID.randomUUID();
        OrchestratorRunRequest request = new OrchestratorRunRequest(UUID.randomUUID(),
            UUID.randomUUID(), "OIM", "done", null, 1, null, 100, 50, 150, 0, 0, null, List.of());
        when(orchestratorRunService.record(eq(testUser), any())).thenReturn(runId);

        Map<String, UUID> response = controller.recordOrchestratorRun(testUser, request);

        assertThat(response).containsEntry("id", runId);
        verify(orchestratorRunService).record(testUser, request);
    }

    @Test
    void listOrchestratorProfilesReturnsStructureFromProperties() {
        List<OrchestratorProfileDto> profiles = controller.listOrchestratorProfiles();

        assertThat(profiles).hasSize(1);
        OrchestratorProfileDto oim = profiles.get(0);
        assertThat(oim.name()).isEqualTo("OIM");
        assertThat(oim.orchestratorPlaybook()).isEqualTo("oim-orchestrator");
        assertThat(oim.reviewerPlaybook()).isEqualTo("oim-orchestrator-reviewer");
        assertThat(oim.specialistPlaybooks()).containsExactly("oim-access-governance",
            "oim-developer-api");
    }

    @Test
    void listOrchestratorProfilesReturnsEmptyWhenNoneConfigured() {
        controller = new DesktopSyncController(syncService, systemPromptService, playbookService,
            new OrchestratorProperties(null, null, null, null), orchestratorRunService);
        assertThat(controller.listOrchestratorProfiles()).isEmpty();
    }

    @Test
    void createConversationReturns201WithDto() {
        UUID id = UUID.randomUUID();
        Conversation conversation = new Conversation(id, UUID.randomUUID(), "Title",
            Map.of("model", "sonnet"), Instant.now(), Instant.now(), List.of());
        when(syncService.createConversation(testUser, "Title", Map.of("model", "sonnet")))
            .thenReturn(conversation);

        ResponseEntity<ConversationDto> response = controller.createConversation(testUser,
            new CreateConversationRequest("Title", Map.of("model", "sonnet")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().id()).isEqualTo(id);
        assertThat(response.getBody().settings()).containsEntry("model", "sonnet");
    }

    @Test
    void getMessagesMergesFeedbackIntoDtos() {
        UUID conversationId = UUID.randomUUID();
        UUID m1 = UUID.randomUUID();
        UUID m2 = UUID.randomUUID();
        when(syncService.getMessages(conversationId, testUser, 500, 0)).thenReturn(List.of(
            new Message(m1, conversationId, "user", "q", null, null, Instant.now(), null, null,
                null),
            new Message(m2, conversationId, "assistant", "a", null, null, Instant.now(), 100, 50,
                150)));
        when(syncService.getFeedbackByMessageId(conversationId, testUser)).thenReturn(Map.of(m2,
            new Feedback(UUID.randomUUID(), m2, -1, "bad answer", Instant.now(), Instant.now())));

        List<MessageDto> dtos = controller.getMessages(testUser, conversationId, 500, 0);

        assertThat(dtos).hasSize(2);
        assertThat(dtos.get(0).feedbackRating()).isNull();
        assertThat(dtos.get(1).feedbackRating()).isEqualTo(-1);
        assertThat(dtos.get(1).feedbackComment()).isEqualTo("bad answer");
        assertThat(dtos.get(1).completionTokens()).isEqualTo(50);
    }

    @Test
    void appendMessagesMapsDtosAndReturnsIds() {
        UUID conversationId = UUID.randomUUID();
        List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID());
        when(syncService.appendMessages(eq(conversationId), eq(testUser), any())).thenReturn(ids);

        Map<String, List<UUID>> response = controller.appendMessages(testUser, conversationId,
            new AppendMessagesRequest(
                List.of(new NewMessageDto("user", "q", null, null, null, null, null),
                    new NewMessageDto("assistant", "a", 10, 20, 30, null, null))));

        assertThat(response).containsEntry("ids", ids);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<NewMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(syncService).appendMessages(eq(conversationId), eq(testUser), captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue().get(1).totalTokens()).isEqualTo(30);
    }

    @Test
    void appendMessagesWithoutBodyYields400() {
        assertThatThrownBy(() -> controller.appendMessages(testUser, UUID.randomUUID(),
            new AppendMessagesRequest(null))).isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void submitFeedbackMapsToDto() {
        UUID messageId = UUID.randomUUID();
        when(syncService.submitFeedback(messageId, testUser, 1, null)).thenReturn(
            new Feedback(UUID.randomUUID(), messageId, 1, null, Instant.now(), Instant.now()));

        FeedbackDto dto =
            controller.submitFeedback(testUser, messageId, new FeedbackRequest(1, null));

        assertThat(dto.messageId()).isEqualTo(messageId);
        assertThat(dto.rating()).isEqualTo(1);
    }

    @Test
    void deleteConversationReturns204() {
        UUID id = UUID.randomUUID();

        ResponseEntity<Void> response = controller.deleteConversation(testUser, id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(syncService).deleteConversation(id, testUser);
    }

    @Test
    void getSystemPromptReturnsPromptFromService() {
        SystemPrompt prompt =
            new SystemPrompt("custom-prompt", SystemPromptType.CHAT, "Hello System", "desc");
        when(systemPromptService.getPrompt("custom-prompt")).thenReturn(Optional.of(prompt));

        ResponseEntity<Map<String, String>> response = controller.getSystemPrompt("custom-prompt");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("systemPrompt", "Hello System");
    }

    @Test
    void getSystemPromptFallsBackForDefaultChat() {
        when(systemPromptService.getPrompt("default-chat")).thenReturn(Optional.empty());
        when(systemPromptService.getDefaultChatPromptName()).thenReturn("active-chat");
        SystemPrompt fallback =
            new SystemPrompt("active-chat", SystemPromptType.CHAT, "Hello Fallback", "desc");
        when(systemPromptService.getPrompt("active-chat")).thenReturn(Optional.of(fallback));

        ResponseEntity<Map<String, String>> response = controller.getSystemPrompt("default-chat");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("systemPrompt", "Hello Fallback");
    }

    @Test
    void updateConversationPassesTitleAndSettings() {
        UUID id = UUID.randomUUID();
        UpdateConversationRequest request =
            new UpdateConversationRequest("New Title", Map.of("model", "haiku"));

        ResponseEntity<Void> response = controller.updateConversation(testUser, id, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(syncService).updateConversation(id, testUser, "New Title", Map.of("model", "haiku"));
    }

    @Test
    void listPlaybooksReturnsListFromService() {
        Playbook playbook = new Playbook("test", "Test Playbook", "Description", "template",
            List.of("tool1"), true, Instant.now(), Instant.now());
        when(playbookService.listSpecializedPlaybooks()).thenReturn(List.of(playbook));

        List<PlaybookDto> playbooks = controller.listPlaybooks();

        assertThat(playbooks).hasSize(1);
        assertThat(playbooks.get(0).name()).isEqualTo("test");
        assertThat(playbooks.get(0).tools()).containsExactly("tool1");
        assertThat(playbooks.get(0).codeExecution()).isTrue();
        assertThat(playbooks.get(0).targetAgent()).isEqualTo("specialist");
    }
}
