package de.palsoftware.yvoke.chat.core.service;

import de.palsoftware.yvoke.chat.core.model.Conversation;
import de.palsoftware.yvoke.chat.core.model.Feedback;
import de.palsoftware.yvoke.chat.core.model.Message;
import de.palsoftware.yvoke.chat.core.repository.ConversationRepository;
import de.palsoftware.yvoke.chat.core.repository.FeedbackRepository;
import de.palsoftware.yvoke.chat.core.repository.MessageRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import de.palsoftware.yvoke.chat.core.service.DesktopSyncService.NewMessage;
import de.palsoftware.yvoke.shared.user.model.User;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

public class DesktopSyncServiceTest {

    private ConversationRepository conversationRepository;
    private MessageRepository messageRepository;
    private FeedbackRepository feedbackRepository;
    private DesktopSyncService service;

    private final UUID currentUserId = UUID.randomUUID();
    private final User currentUser =
        new User(currentUserId, "entra-oid", "user@test.local", "Test User", Instant.now());

    @BeforeEach
    public void setUp() {
        conversationRepository = mock(ConversationRepository.class);
        messageRepository = mock(MessageRepository.class);
        feedbackRepository = mock(FeedbackRepository.class);
        service =
            new DesktopSyncService(conversationRepository, messageRepository, feedbackRepository);
    }

    private Conversation ownConversation(UUID id, String title) {
        return new Conversation(id, currentUserId, title, Map.of(), Instant.now(), Instant.now(),
            List.of());
    }

    private Conversation foreignConversation(UUID id) {
        return new Conversation(id, UUID.randomUUID(), "Foreign", Map.of(), Instant.now(),
            Instant.now(), List.of());
    }

    private static void assertStatus(Throwable t, HttpStatus status) {
        assertThat(t).isInstanceOf(ResponseStatusException.class);
        assertThat(((ResponseStatusException) t).getStatusCode()).isEqualTo(status);
    }

    // --- createConversation -------------------------------------------------

    @Test
    public void createConversationMarksSourceDesktop() {
        when(conversationRepository.findById(any())).thenAnswer(
            inv -> Optional.of(ownConversation(inv.getArgument(0), "New Conversation")));

        service.createConversation(currentUser, null, null);

        verify(conversationRepository).create(any(UUID.class), eq(currentUserId),
            eq("New Conversation"), eq(Map.of()), eq("desktop"));
    }

    // --- listConversations ---------------------------------------------------

    @Test
    public void listConversationsScopesToUserAndDesktopSource() {
        service.listConversations(currentUser, 50, 10);

        verify(conversationRepository).listByUserAndSource(currentUserId, "desktop", 50, 10);
    }

    // --- appendMessages -------------------------------------------------------

    @Test
    public void appendMessagesReturnsIdsInOrderAndTouchesConversation() {
        UUID conversationId = UUID.randomUUID();
        when(conversationRepository.findById(conversationId))
            .thenReturn(Optional.of(ownConversation(conversationId, "Custom title")));

        List<UUID> ids = service.appendMessages(conversationId, currentUser, List.of(
            new NewMessage("user", "What is the Person table?", null, null, null, null, null),
            new NewMessage("assistant", "The Person table is...", 1200, 350, 1550, null, null)));

        assertThat(ids).hasSize(2).doesNotContainNull().doesNotHaveDuplicates();

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues().get(0).id()).isEqualTo(ids.get(0));
        assertThat(captor.getAllValues().get(0).role()).isEqualTo("user");
        assertThat(captor.getAllValues().get(1).id()).isEqualTo(ids.get(1));
        assertThat(captor.getAllValues().get(1).completionTokens()).isEqualTo(350);
        verify(conversationRepository).touch(conversationId);
        verify(conversationRepository, never()).updateTitle(any(), anyString());
    }

    @Test
    public void appendMessagesAutoTitlesDefaultTitledConversation() {
        UUID conversationId = UUID.randomUUID();
        when(conversationRepository.findById(conversationId))
            .thenReturn(Optional.of(ownConversation(conversationId, "New Conversation")));

        service
            .appendMessages(conversationId, currentUser,
                List.of(
                    new NewMessage("user", "Explain   the PersonWantsOrg table", null, null, null,
                        null, null),
                    new NewMessage("assistant", "Sure...", null, null, null, null, null)));

        verify(conversationRepository).updateTitle(conversationId,
            "Explain the PersonWantsOrg table");
    }

    @Test
    public void appendMessagesRejectsEmptyBatch() {
        UUID conversationId = UUID.randomUUID();
        when(conversationRepository.findById(conversationId))
            .thenReturn(Optional.of(ownConversation(conversationId, "t")));

        assertThatThrownBy(() -> service.appendMessages(conversationId, currentUser, List.of()))
            .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
    }

    @Test
    public void appendMessagesRejectsInvalidRole() {
        UUID conversationId = UUID.randomUUID();
        when(conversationRepository.findById(conversationId))
            .thenReturn(Optional.of(ownConversation(conversationId, "t")));

        assertThatThrownBy(() -> service.appendMessages(conversationId, currentUser,
            List.of(new NewMessage("system", "x", null, null, null, null, null))))
            .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
        verify(messageRepository, never()).save(any());
    }

    @Test
    public void appendMessagesRejectsBlankContent() {
        UUID conversationId = UUID.randomUUID();
        when(conversationRepository.findById(conversationId))
            .thenReturn(Optional.of(ownConversation(conversationId, "t")));

        assertThatThrownBy(() -> service.appendMessages(conversationId, currentUser,
            List.of(new NewMessage("user", "  ", null, null, null, null, null))))
            .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
    }

    @Test
    public void appendMessagesToForeignConversationIsDenied() {
        UUID conversationId = UUID.randomUUID();
        when(conversationRepository.findById(conversationId))
            .thenReturn(Optional.of(foreignConversation(conversationId)));

        assertThatThrownBy(() -> service.appendMessages(conversationId, currentUser,
            List.of(new NewMessage("user", "x", null, null, null, null, null))))
            .isInstanceOf(AccessDeniedException.class);
        verify(messageRepository, never()).save(any());
    }

    @Test
    public void appendMessagesToUnknownConversationYields404() {
        UUID conversationId = UUID.randomUUID();
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.appendMessages(conversationId, currentUser,
            List.of(new NewMessage("user", "x", null, null, null, null, null))))
            .satisfies(t -> assertStatus(t, HttpStatus.NOT_FOUND));
    }

    // --- feedback --------------------------------------------------------------

    @Test
    public void negativeFeedbackWithoutCommentIsRejected() {
        assertThatThrownBy(() -> service.submitFeedback(UUID.randomUUID(), currentUser, -1, null))
            .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.submitFeedback(UUID.randomUUID(), currentUser, -1, "   "))
            .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
        verify(feedbackRepository, never()).upsert(any(), anyInt(), any());
    }

    @Test
    public void negativeFeedbackWithCommentIsUpserted() {
        UUID messageId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(new Message(messageId,
            conversationId, "assistant", "answer", null, null, null, null, null, null)));
        when(conversationRepository.findById(conversationId))
            .thenReturn(Optional.of(ownConversation(conversationId, "t")));
        when(feedbackRepository.findByMessageId(messageId))
            .thenReturn(Optional.of(new Feedback(UUID.randomUUID(), messageId, -1, "wrong citation",
                Instant.now(), Instant.now())));

        Feedback feedback =
            service.submitFeedback(messageId, currentUser, -1, "  wrong citation  ");

        verify(feedbackRepository).upsert(messageId, -1, "wrong citation");
        assertThat(feedback.rating()).isEqualTo(-1);
    }

    @Test
    public void positiveFeedbackWithoutCommentIsAccepted() {
        UUID messageId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(new Message(messageId,
            conversationId, "assistant", "answer", null, null, null, null, null, null)));
        when(conversationRepository.findById(conversationId))
            .thenReturn(Optional.of(ownConversation(conversationId, "t")));
        when(feedbackRepository.findByMessageId(messageId)).thenReturn(Optional
            .of(new Feedback(UUID.randomUUID(), messageId, 1, null, Instant.now(), Instant.now())));

        service.submitFeedback(messageId, currentUser, 1, null);

        verify(feedbackRepository).upsert(messageId, 1, null);
    }

    @Test
    public void invalidRatingIsRejected() {
        assertThatThrownBy(() -> service.submitFeedback(UUID.randomUUID(), currentUser, null, "c"))
            .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.submitFeedback(UUID.randomUUID(), currentUser, 0, "c"))
            .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.submitFeedback(UUID.randomUUID(), currentUser, 5, "c"))
            .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
    }

    @Test
    public void feedbackOnUnknownMessageYields404() {
        UUID messageId = UUID.randomUUID();
        when(messageRepository.findById(messageId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submitFeedback(messageId, currentUser, 1, null))
            .satisfies(t -> assertStatus(t, HttpStatus.NOT_FOUND));
    }

    @Test
    public void feedbackOnForeignMessageIsDenied() {
        UUID messageId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(new Message(messageId,
            conversationId, "assistant", "answer", null, null, null, null, null, null)));
        when(conversationRepository.findById(conversationId))
            .thenReturn(Optional.of(foreignConversation(conversationId)));

        assertThatThrownBy(() -> service.submitFeedback(messageId, currentUser, 1, null))
            .isInstanceOf(AccessDeniedException.class);
        verify(feedbackRepository, never()).upsert(any(), anyInt(), any());
    }

    // --- delete / title ----------------------------------------------------------

    @Test
    public void deleteVerifiesOwnership() {
        UUID conversationId = UUID.randomUUID();
        when(conversationRepository.findById(conversationId))
            .thenReturn(Optional.of(foreignConversation(conversationId)));

        assertThatThrownBy(() -> service.deleteConversation(conversationId, currentUser))
            .isInstanceOf(AccessDeniedException.class);
        verify(conversationRepository, never()).delete(any());
    }

    @Test
    public void updateTitleRejectsBlank() {
        UUID conversationId = UUID.randomUUID();
        when(conversationRepository.findById(conversationId))
            .thenReturn(Optional.of(ownConversation(conversationId, "t")));

        assertThatThrownBy(() -> service.updateTitle(conversationId, currentUser, " "))
            .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
    }

    @Test
    public void updateConversationAppliesUpdatesAndMapsCamelCase() {
        UUID conversationId = UUID.randomUUID();
        when(conversationRepository.findById(conversationId))
            .thenReturn(Optional.of(ownConversation(conversationId, "t")));

        Map<String, Object> settings =
            Map.of("thinkingLevel", "high", "chatPrompt", "playbook-x", "model", "claude-3-5");

        service.updateConversation(conversationId, currentUser, "Updated Title", settings);

        verify(conversationRepository).updateTitle(conversationId, "Updated Title");

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(conversationRepository).updateSettings(eq(conversationId), captor.capture());

        Map<String, Object> mapped = captor.getValue();
        assertThat(mapped).containsEntry("thinking-level", "high");
        assertThat(mapped).containsEntry("chat-prompt", "playbook-x");
        assertThat(mapped).containsEntry("model", "claude-3-5");
        assertThat(mapped).doesNotContainKey("thinkingLevel");
        assertThat(mapped).doesNotContainKey("chatPrompt");
    }
}
