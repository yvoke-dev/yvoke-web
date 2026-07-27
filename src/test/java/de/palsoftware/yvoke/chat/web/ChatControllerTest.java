package de.palsoftware.yvoke.chat.web;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.security.access.AccessDeniedException;

import de.palsoftware.yvoke.chat.core.model.Conversation;
import de.palsoftware.yvoke.chat.core.model.ConversationSidebar;
import de.palsoftware.yvoke.chat.core.model.Feedback;
import de.palsoftware.yvoke.chat.core.model.Message;
import de.palsoftware.yvoke.chat.core.service.ChatCancellationService;
import de.palsoftware.yvoke.chat.core.service.ChatConversationService;
import de.palsoftware.yvoke.chat.core.service.ChatFeedbackService;
import de.palsoftware.yvoke.chat.core.service.ChatMessageService;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProfile;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProfileService;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProperties;
import de.palsoftware.yvoke.rag.prompt.PlaybookService;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.web.server.ResponseStatusException;

class ChatControllerTest {

    private ChatConversationService conversationService;
    private ChatMessageService messageService;
    private ChatFeedbackService feedbackService;
    private ChatCancellationService cancellationService;
    private PlaybookService playbookService;
    private OrchestratorProperties orchestratorProperties;
    private OrchestratorProfileService orchestratorProfileService;
    private ChatController controller;

    @BeforeEach
    void setUp() {
        conversationService = mock(ChatConversationService.class);
        messageService = mock(ChatMessageService.class);
        feedbackService = mock(ChatFeedbackService.class);
        cancellationService = mock(ChatCancellationService.class);
        playbookService = mock(PlaybookService.class);
        orchestratorProperties = mock(OrchestratorProperties.class);
        orchestratorProfileService = mock(OrchestratorProfileService.class);

        controller = new ChatController(conversationService, messageService, feedbackService,
            cancellationService, playbookService, orchestratorProperties,
            orchestratorProfileService);
    }

    @Test
    void updateOrchestratorProfile_validDbProfile_updatesSettingsSuccessfully() {
        UUID conversationId = UUID.randomUUID();
        Conversation conv = mock(Conversation.class);
        when(conv.settings()).thenReturn(Collections.emptyMap());
        when(conversationService.getConversation(conversationId)).thenReturn(Optional.of(conv));

        OrchestratorProfile dbProfile = mock(OrchestratorProfile.class);
        when(orchestratorProfileService.getProfile("OIM")).thenReturn(Optional.of(dbProfile));
        when(orchestratorProperties.profile("OIM")).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> controller.updateOrchestratorProfile(conversationId, "OIM"));

        verify(conversationService).updateSettings(eq(conversationId), any());
    }

    @Test
    void updateOrchestratorProfile_validPropertyProfile_updatesSettingsSuccessfully() {
        UUID conversationId = UUID.randomUUID();
        Conversation conv = mock(Conversation.class);
        when(conv.settings()).thenReturn(Collections.emptyMap());
        when(conversationService.getConversation(conversationId)).thenReturn(Optional.of(conv));

        OrchestratorProperties.Profile propProfile = mock(OrchestratorProperties.Profile.class);
        when(orchestratorProfileService.getProfile("yaml-profile")).thenReturn(Optional.empty());
        when(orchestratorProperties.profile("yaml-profile")).thenReturn(Optional.of(propProfile));

        assertDoesNotThrow(
            () -> controller.updateOrchestratorProfile(conversationId, "yaml-profile"));

        verify(conversationService).updateSettings(eq(conversationId), any());
    }

    @Test
    void updateOrchestratorProfile_unknownProfile_throwsBadRequest() {
        UUID conversationId = UUID.randomUUID();
        when(orchestratorProfileService.getProfile("Unknown")).thenReturn(Optional.empty());
        when(orchestratorProperties.profile("Unknown")).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.updateOrchestratorProfile(conversationId, "Unknown"));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void stopGeneration_verifiesOwnershipBeforeStopping() {
        UUID conversationId = UUID.randomUUID();

        controller.stopGeneration(conversationId);

        // Ownership must be verified BEFORE the generation is stopped (SEC-10).
        InOrder inOrder = Mockito.inOrder(conversationService, cancellationService);
        inOrder.verify(conversationService).verifyOwnership(conversationId, false);
        inOrder.verify(cancellationService).stop(conversationId);
    }

    @Test
    void stopGeneration_deniedForNonOwner_doesNotStop() {
        UUID conversationId = UUID.randomUUID();
        doThrow(new AccessDeniedException("not your conversation")).when(conversationService)
            .verifyOwnership(conversationId, false);

        assertThrows(AccessDeniedException.class, () -> controller.stopGeneration(conversationId));

        verify(cancellationService, never()).stop(any());
    }

    @Test
    void updateOrchestratorProfile_emptyName_clearsProfile() {
        UUID conversationId = UUID.randomUUID();
        Conversation conv = mock(Conversation.class);
        when(conv.settings()).thenReturn(Map.of("orchestrator-profile", "OIM"));
        when(conversationService.getConversation(conversationId)).thenReturn(Optional.of(conv));

        assertDoesNotThrow(() -> controller.updateOrchestratorProfile(conversationId, "  "));

        verify(conversationService).updateSettings(eq(conversationId), any());
    }

    /**
     * The thread view used to resolve feedback one message at a time, and each of those calls cost
     * three statements (message lookup, ownership lookup, feedback select) — so a 20-message thread
     * issued ~60 queries just to render the thumbs widgets. It must use the conversation-scoped
     * batch query instead.
     */
    @Test
    void threadView_fetchesFeedbackInOneBatchNotPerMessage() {
        UUID conversationId = UUID.randomUUID();
        UUID firstAnswerId = UUID.randomUUID();
        UUID secondAnswerId = UUID.randomUUID();
        Conversation conv = new Conversation(conversationId, UUID.randomUUID(), "T", Map.of(),
            Instant.now(), Instant.now(), List.of());
        when(conversationService.getConversation(conversationId)).thenReturn(Optional.of(conv));
        when(conversationService.buildSidebar()).thenReturn(new ConversationSidebar(List.of(),
            Map.of(), List.of(), Map.of(), List.of(), 0, List.of(), UUID.randomUUID()));
        when(messageService.getMessages(conversationId))
            .thenReturn(List.of(assistantMessage(firstAnswerId, conversationId),
                userMessage(conversationId), assistantMessage(secondAnswerId, conversationId)));
        Feedback onFirstAnswer =
            new Feedback(UUID.randomUUID(), firstAnswerId, -1, "wrong", null, null);
        when(feedbackService.getFeedbackByConversation(conversationId))
            .thenReturn(Map.of(firstAnswerId, onFirstAnswer));

        Model model = new ConcurrentModel();
        String view = controller.threadView(conversationId, model);

        assertEquals("chat/thread", view);
        verify(feedbackService).getFeedbackByConversation(conversationId);
        verify(feedbackService, never()).getFeedback(any());
        assertEquals(Map.of(firstAnswerId, onFirstAnswer), model.getAttribute("feedbacks"));
    }

    private static Message assistantMessage(UUID id, UUID conversationId) {
        return new Message(id, conversationId, "assistant", "An answer.", List.of(), List.of(),
            Instant.now());
    }

    private static Message userMessage(UUID conversationId) {
        return new Message(UUID.randomUUID(), conversationId, "user", "A question.", List.of(),
            List.of(), Instant.now());
    }
}
