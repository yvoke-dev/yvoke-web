package de.palsoftware.yvoke.chat.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import de.palsoftware.yvoke.shared.api.ApiExceptionHandler;
import de.palsoftware.yvoke.shared.user.service.UserService;
import de.palsoftware.yvoke.shared.web.UserArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProfile;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProfileService;

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

    /**
     * The desktop's bootstrap makes two GETs against this controller, and both have to prefer the
     * live source and degrade rather than fail. Neither branch runs today: both existing profile
     * tests build the controller through the 5-arg constructor, which passes a NULL
     * {@code OrchestratorProfileService}, so the DB-first branch has never executed; and both
     * {@code getSystemPrompt} tests resolve a real prompt, so the empty-string branch has never
     * executed either.
     *
     * <p>
     * If DB profiles stopped being returned, the desktop would silently fall back to the
     * yml-configured set and run a DIFFERENT orchestrator/reviewer/specialist trio than the one an
     * admin edited in the UI — answers still arrive, nothing looks broken, and the only symptom is
     * that a playbook fix "did not take". The fallback is deliberately conditioned on an EMPTY db
     * list, so both directions are asserted here: a non-empty db list must WIN, an empty one must
     * fall through. And if an unknown prompt name started 404ing instead of returning
     * {@code {"systemPrompt":""}}, the desktop's prompt bootstrap would fail hard on a name it
     * merely has not heard of rather than running with no system prompt.
     */
    /**
     * A prototype profile is SENT to the desktop, flagged — never withheld.
     *
     * <p>
     * Filtering here would be the reflexive reading of "hidden from the user" and it breaks two
     * things at once. The desktop owns the setting ({@code showPrototypePlaybooks}), so a server
     * that filters makes the setting inert for profiles while it still works for playbooks — the
     * user ticks the box and half the feature responds. And {@code AppCore} resolves the profile a
     * thread is bound to from this same list, so a withheld profile makes an already-configured
     * thread fall back to single-agent with nothing to say why.
     *
     * <p>
     * The yml fallback reports {@code false}: those profiles predate the flag and have nowhere to
     * carry one, and defaulting them to hidden would empty the desktop's dropdown in exactly the
     * deployment that has no database rows.
     */
    @Test
    void aPrototypeProfileReachesTheDesktopFlaggedRatherThanWithheld() {
        OrchestratorProfileService profileService = mock(OrchestratorProfileService.class);
        DesktopSyncController dbBacked = new DesktopSyncController(syncService, systemPromptService,
            playbookService, orchestratorProperties, profileService, orchestratorRunService);

        when(profileService.listAllProfiles()).thenReturn(List.of(
            new OrchestratorProfile("OIM", 2, 8, "oim-orch", "oim-rev", List.of("spec"), null, null,
                null, null, null, null, false, null, null),
            new OrchestratorProfile("OIM - Browsing", 2, 8, "oim-orch", "oim-rev", List.of("spec"),
                null, null, null, null, null, null, true, null, null)));

        assertThat(dbBacked.listOrchestratorProfiles())
            .extracting(OrchestratorProfileDto::name, OrchestratorProfileDto::prototype)
            .containsExactly(tuple("OIM", false), tuple("OIM - Browsing", true));

        when(profileService.listAllProfiles()).thenReturn(List.of());

        assertThat(dbBacked.listOrchestratorProfiles())
            .as("a yml-configured profile has no flag to carry, so it is not a prototype")
            .extracting(OrchestratorProfileDto::prototype).containsOnly(false);
    }

    @Test
    void orchestratorProfilesComeFromTheDatabaseAndAnUnknownPromptDegradesToEmpty() {
        OrchestratorProfileService profileService = mock(OrchestratorProfileService.class);
        DesktopSyncController dbBacked = new DesktopSyncController(syncService, systemPromptService,
            playbookService, orchestratorProperties, profileService, orchestratorRunService);

        when(profileService.listAllProfiles())
            .thenReturn(List.of(new OrchestratorProfile("OIM", 2, 8, "oim-orchestrator-edited",
                "oim-orchestrator-reviewer-edited", List.of("oim-access-governance-edited"), null,
                null, null, null, null, null, false, null, null)));

        assertThat(dbBacked.listOrchestratorProfiles())
            .as("the admin-edited profile wins over the yml one of the same name")
            .extracting(OrchestratorProfileDto::orchestratorPlaybook)
            .containsExactly("oim-orchestrator-edited");

        when(profileService.listAllProfiles()).thenReturn(List.of());

        assertThat(dbBacked.listOrchestratorProfiles())
            .as("an EMPTY db list, and only that, falls back to the configured profiles")
            .extracting(OrchestratorProfileDto::orchestratorPlaybook)
            .containsExactly("oim-orchestrator");

        when(systemPromptService.getPrompt("no-such-prompt")).thenReturn(Optional.empty());

        ResponseEntity<Map<String, String>> unknown = dbBacked.getSystemPrompt("no-such-prompt");
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(unknown.getBody()).containsEntry("systemPrompt", "");
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

    /**
     * SEC-18 lives in two halves and only one of them is visible from a unit test that calls the
     * controller method directly: the bounds are DECLARED on {@code AppendMessagesRequest} /
     * {@code NewMessageDto}, but they are only ENFORCED because the handler parameter carries
     * {@code @Valid}. Drop that annotation and the constraints stay in the source, keep reading as
     * a guard in review, and do nothing — the batch flows straight into
     * {@code DesktopSyncService.appendMessages}, which is {@code @Transactional} and inserts the
     * whole list in one transaction, so an unbounded desktop-sync post becomes an unbounded server
     * -side allocation plus a long write transaction on the messages table. This is a bearer-token
     * endpoint reachable by any synced desktop client, so "our own client would never send that" is
     * not a control.
     *
     * <p>
     * Exercised through MockMvc because the enforcement point is Spring's argument resolution, not
     * the method body: every other test in this class invokes {@code controller.appendMessages(..)}
     * as a plain Java call, which bypasses validation entirely and therefore passes with or without
     * {@code @Valid}. The cascade is asserted too ({@code List<@Valid NewMessageDto>}) because a
     * single 1 MB-plus message is the other shape of the same attack, and the element-level bound
     * only fires if the list annotation is honoured.
     */
    @Test
    void anOversizedBatchIsRejectedBeforeReachingTheService() throws Exception {
        UserService userService = mock(UserService.class);
        when(userService.getCurrentUser()).thenReturn(Optional.of(testUser));
        // The real resolver + the real JSON error advice, so the boundary behaves as in production.
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setCustomArgumentResolvers(new UserArgumentResolver(userService))
            .setControllerAdvice(new ApiExceptionHandler()).build();

        StringBuilder oversizedBatch = new StringBuilder("{\"messages\":[");
        for (int i = 0; i <= 500; i++) {
            if (i > 0) {
                oversizedBatch.append(',');
            }
            oversizedBatch.append("{\"role\":\"user\",\"content\":\"m").append(i).append("\"}");
        }
        oversizedBatch.append("]}");

        mockMvc
            .perform(post("/api/chat/v1/conversations/{id}/messages", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(oversizedBatch.toString()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error", containsString("messages")))
            .andExpect(jsonPath("$.error", containsString("500")));

        // The per-message bound: one element over @Size(max = 1_000_000) fails the same way.
        String oversizedContent = "x".repeat(1_000_001);
        mockMvc
            .perform(post("/api/chat/v1/conversations/{id}/messages", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"messages\":[{\"role\":\"user\",\"content\":\"" + oversizedContent
                    + "\"}]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error", containsString("content")));

        verify(syncService, never()).appendMessages(any(), any(), any());
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
