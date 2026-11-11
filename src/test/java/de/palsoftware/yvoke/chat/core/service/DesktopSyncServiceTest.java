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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import de.palsoftware.yvoke.rag.prompt.Playbook;
import de.palsoftware.yvoke.rag.prompt.PlaybookService;
import java.util.Set;

public class DesktopSyncServiceTest {

    private ConversationRepository conversationRepository;
    private MessageRepository messageRepository;
    private FeedbackRepository feedbackRepository;
    private PlaybookService playbookService;
    private DesktopSyncService service;

    private final UUID currentUserId = UUID.randomUUID();
    private final User currentUser =
        new User(currentUserId, "entra-oid", "user@test.local", "Test User", Instant.now());

    @BeforeEach
    public void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken("user", "pass", "ROLE_USER", "AREA_USER_*"));
        conversationRepository = mock(ConversationRepository.class);
        messageRepository = mock(MessageRepository.class);
        feedbackRepository = mock(FeedbackRepository.class);
        playbookService = mock(PlaybookService.class);
        service = new DesktopSyncService(conversationRepository, messageRepository,
            feedbackRepository, playbookService);
    }

    @AfterEach
    public void tearDown() {
        SecurityContextHolder.clearContext();
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

        verify(conversationRepository).listByUserAndSource(currentUserId, "desktop", 50, 10, null);
    }

    @Test
    public void listConversationsFiltersByAreaWhenRestricted() {
        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken("user", "pass", "ROLE_USER", "AREA_USER_SECURITY"));

        service.listConversations(currentUser, 50, 10);

        verify(conversationRepository).listByUserAndSource(currentUserId, "desktop", 50, 10,
            Set.of("security"));
    }

    @Test
    public void verifyOwnershipRejectsConversationFromUnauthorizedAreaWith404() {
        UUID conversationId = UUID.randomUUID();
        when(conversationRepository.findById(conversationId))
            .thenReturn(Optional.of(new Conversation(conversationId, currentUserId, "Restricted",
                Map.of(), Instant.now(), Instant.now(), List.of(), "desktop", "restricted-area")));
        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken("user", "pass", "ROLE_USER", "AREA_USER_OTHER"));

        assertThatThrownBy(() -> service.getMessages(conversationId, currentUser, 50, 0))
            .satisfies(t -> assertStatus(t, HttpStatus.NOT_FOUND));
    }

    @Test
    public void appendMessagesRejectsPlaybookFromUnauthorizedAreaWith400() {
        UUID conversationId = UUID.randomUUID();
        when(conversationRepository.findById(conversationId))
            .thenReturn(Optional.of(ownConversation(conversationId, "Title")));
        when(playbookService.getPlaybook("restricted-pb")).thenReturn(Optional
            .of(new Playbook("restricted-pb", "Restricted", "desc", "tmpl", List.of(), false,
                "specialist", false, Instant.now(), Instant.now(), false, "restricted-area")));
        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken("user", "pass", "ROLE_USER", "AREA_USER_OTHER"));

        assertThatThrownBy(() -> service.appendMessages(conversationId, currentUser,
            List.of(
                new NewMessage("user", "Hello", null, null, null, null, null, "restricted-pb", 1))))
            .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
    }

    @Test
    public void appendMessagesStampsConversationAreaIfAbsent() {
        UUID conversationId = UUID.randomUUID();
        when(conversationRepository.findById(conversationId))
            .thenReturn(Optional.of(ownConversation(conversationId, "Title")));
        when(playbookService.getPlaybook("sec-pb"))
            .thenReturn(Optional.of(new Playbook("sec-pb", "Security", "desc", "tmpl", List.of(),
                false, "specialist", false, Instant.now(), Instant.now(), false, "security")));
        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken("user", "pass", "ROLE_USER", "AREA_USER_SECURITY"));

        service.appendMessages(conversationId, currentUser,
            List.of(new NewMessage("user", "Hello", null, null, null, null, null, "sec-pb", 1)));

        verify(conversationRepository).updateAreaIfAbsent(conversationId, "security");
    }

    @Test
    public void appendMessagesRejectsPlaybookWhoseAreaDisagreesWithConversationArea() {
        UUID conversationId = UUID.randomUUID();
        when(conversationRepository.findById(conversationId))
            .thenReturn(Optional.of(new Conversation(conversationId, currentUserId, "Title",
                Map.of(), Instant.now(), Instant.now(), List.of(), "desktop", "OIM")));
        when(playbookService.getPlaybook("ping-pb"))
            .thenReturn(Optional.of(new Playbook("ping-pb", "Ping", "desc", "tmpl", List.of(),
                false, "specialist", false, Instant.now(), Instant.now(), false, "PingID")));

        assertThatThrownBy(() -> service.appendMessages(conversationId, currentUser,
            List.of(new NewMessage("user", "Hello", null, null, null, null, null, "ping-pb", 1))))
            .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
        verify(messageRepository, never()).save(any());
    }

    @Test
    public void appendMessagesRejectsMixedAreaPlaybooksInSameBatch() {
        UUID conversationId = UUID.randomUUID();
        when(conversationRepository.findById(conversationId))
            .thenReturn(Optional.of(ownConversation(conversationId, "Title")));
        when(playbookService.getPlaybook("oim-pb"))
            .thenReturn(Optional.of(new Playbook("oim-pb", "OIM", "desc", "tmpl", List.of(), false,
                "specialist", false, Instant.now(), Instant.now(), false, "OIM")));
        when(playbookService.getPlaybook("ping-pb"))
            .thenReturn(Optional.of(new Playbook("ping-pb", "Ping", "desc", "tmpl", List.of(),
                false, "specialist", false, Instant.now(), Instant.now(), false, "PingID")));

        assertThatThrownBy(() -> service.appendMessages(conversationId, currentUser,
            List.of(new NewMessage("user", "Q1", null, null, null, null, null, "oim-pb", 1),
                new NewMessage("user", "Q2", null, null, null, null, null, "ping-pb", 1))))
            .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
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

        Map<String, Object> settings = Map.of("thinkingLevel", "high", "model", "claude-3-5");

        service.updateConversation(conversationId, currentUser, "Updated Title", settings);

        verify(conversationRepository).updateTitle(conversationId, "Updated Title");

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(conversationRepository).updateSettings(eq(conversationId), captor.capture());

        Map<String, Object> mapped = captor.getValue();
        assertThat(mapped).containsEntry("thinking-level", "high");
        assertThat(mapped).containsEntry("model", "claude-3-5");
        assertThat(mapped).doesNotContainKey("thinkingLevel");
    }

    @Test
    public void appendMessagesPersistsPlaybookAndPlaybookVersionAndEnforcesInvariants() {
        UUID conversationId = UUID.randomUUID();
        when(conversationRepository.findById(conversationId))
            .thenReturn(Optional.of(ownConversation(conversationId, "Custom title")));

        service.appendMessages(conversationId, currentUser,
            List.of(
                new NewMessage("user", "Question", null, null, null, null, null, " oim-specialist ",
                    2),
                new NewMessage("assistant", "Answer", 10, 20, 30, null, null, "oim-specialist", 2),
                new NewMessage("user", "Orchestrated Q", null, null, null, null, null, null, 2),
                new NewMessage("assistant", "Orchestrated A", 10, 20, 30, null, null, "   ", 2)));

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository, times(4)).save(captor.capture());
        List<Message> saved = captor.getAllValues();

        assertThat(saved.get(0).playbook()).isEqualTo("oim-specialist");
        assertThat(saved.get(0).playbookVersion()).isEqualTo(2);
        assertThat(saved.get(1).playbook()).isEqualTo("oim-specialist");
        assertThat(saved.get(1).playbookVersion()).isEqualTo(2);
        assertThat(saved.get(2).playbook()).isNull();
        assertThat(saved.get(2).playbookVersion()).isNull();
        assertThat(saved.get(3).playbook()).isNull();
        assertThat(saved.get(3).playbookVersion()).isNull();
    }

    @Test
    public void appendMessagesRejectsInvalidPlaybookVersion() {
        UUID conversationId = UUID.randomUUID();
        when(conversationRepository.findById(conversationId))
            .thenReturn(Optional.of(ownConversation(conversationId, "Custom title")));

        assertThatThrownBy(() -> service.appendMessages(conversationId, currentUser,
            List.of(new NewMessage("user", "Question", null, null, null, null, null, "pb", 0))))
            .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> service.appendMessages(conversationId, currentUser,
            List.of(new NewMessage("user", "Question", null, null, null, null, null, null, 0))))
            .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> service.appendMessages(conversationId, currentUser,
            List.of(new NewMessage("user", "Question", null, null, null, null, null, "   ", 0))))
            .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> service.appendMessages(conversationId, currentUser,
            List.of(new NewMessage("user", "Question", null, null, null, null, null, "pb", -1))))
            .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));

        verify(messageRepository, never()).save(any());
    }

    // --- desktopVersion validation, normalization & immutability ------------

    @Test
    public void createConversationPreservesDesktopVersionVerbatimAndHandlesImmutableMapOf() {
        when(conversationRepository.findById(any())).thenAnswer(
            inv -> Optional.of(ownConversation(inv.getArgument(0), "New Conversation")));

        for (Map.Entry<String, String> entry : Map.of(" v1.3.1 ", "v1.3.1", "Version 1.3.1",
            "Version 1.3.1", "v", "v", "\t" + "1".repeat(256) + "\u2003", "1".repeat(256))
            .entrySet()) {
            clearInvocations(conversationRepository);
            when(conversationRepository.findById(any())).thenAnswer(
                inv -> Optional.of(ownConversation(inv.getArgument(0), "New Conversation")));

            service.createConversation(currentUser, "Test",
                Map.of("desktopVersion", entry.getKey(), "model", "claude-sonnet-4-6"));

            verify(conversationRepository).create(any(UUID.class), eq(currentUserId), eq("Test"),
                eq(Map.of("desktopVersion", entry.getValue(), "model", "claude-sonnet-4-6")),
                eq("desktop"));
        }
    }

    @Test
    public void createConversationOmitsBlankUnicodeWhitespaceOrNullDesktopVersion() {
        for (String blank : List.of("", "   ", "\u2003", "\t\n\u2003")) {
            clearInvocations(conversationRepository);
            when(conversationRepository.findById(any())).thenAnswer(
                inv -> Optional.of(ownConversation(inv.getArgument(0), "New Conversation")));

            service.createConversation(currentUser, "Test",
                Map.of("desktopVersion", blank, "model", "claude-sonnet-4-6"));

            verify(conversationRepository).create(any(UUID.class), eq(currentUserId), eq("Test"),
                eq(Map.of("model", "claude-sonnet-4-6")), eq("desktop"));
        }

        clearInvocations(conversationRepository);
        when(conversationRepository.findById(any())).thenAnswer(
            inv -> Optional.of(ownConversation(inv.getArgument(0), "New Conversation")));
        Map<String, Object> withNull = new HashMap<>();
        withNull.put("desktopVersion", null);
        withNull.put("model", "claude-sonnet-4-6");
        service.createConversation(currentUser, "Test", withNull);

        verify(conversationRepository).create(any(UUID.class), eq(currentUserId), eq("Test"),
            eq(Map.of("model", "claude-sonnet-4-6")), eq("desktop"));

        // Blank-only map produces Map.of()
        clearInvocations(conversationRepository);
        when(conversationRepository.findById(any())).thenAnswer(
            inv -> Optional.of(ownConversation(inv.getArgument(0), "New Conversation")));
        service.createConversation(currentUser, "Test", Map.of("desktopVersion", "\u2003"));
        verify(conversationRepository).create(any(UUID.class), eq(currentUserId), eq("Test"),
            eq(Map.of()), eq("desktop"));
    }

    @Test
    public void createConversationRejectsNonStringOrOversizedDesktopVersionWith400() {
        for (Object nonString : List.of(Integer.valueOf(123), Boolean.TRUE, List.of("1.3.1"),
            Map.of("v", "1.3.1"))) {
            assertThatThrownBy(() -> service.createConversation(currentUser, "Test",
                Map.of("desktopVersion", nonString)))
                .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST))
                .hasMessageContaining("Invalid desktopVersion: must be a string");
        }

        for (String oversized : List.of("1".repeat(257), " " + "1".repeat(257) + " ",
            "v".repeat(100_000))) {
            assertThatThrownBy(() -> service.createConversation(currentUser, "Test",
                Map.of("desktopVersion", oversized)))
                .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST))
                .hasMessageContaining("Invalid desktopVersion: exceeds maximum length");
        }

        verify(conversationRepository, never()).create(any(), any(), any(), any(), any());
    }

    @Test
    public void updateConversationPreservesOriginalDesktopVersionVerbatimOnFullSettingsPatch() {
        UUID conversationId = UUID.randomUUID();
        Conversation existing = new Conversation(conversationId, currentUserId, "Title",
            Map.of("desktopVersion", "1.3.0", "model", "claude-sonnet-4-6"), Instant.now(),
            Instant.now(), List.of(), "desktop", null);
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(existing));

        // 1. Full settings PATCH with overwrite attempt ("1.4.0") keeps "1.3.0"
        service.updateConversation(conversationId, currentUser, null,
            Map.of("model", "claude-opus-4-6", "desktopVersion", "1.4.0"));
        verify(conversationRepository).updateSettings(conversationId,
            Map.of("model", "claude-opus-4-6", "desktopVersion", "1.3.0"));

        // 2. Overwrite attempt alongside camelCase thinkingLevel keeps verbatim existing "v1.3.0"
        Conversation existingWithV = new Conversation(conversationId, currentUserId, "Title",
            Map.of("desktopVersion", "v1.3.0", "model", "claude-sonnet-4-6"), Instant.now(),
            Instant.now(), List.of(), "desktop", null);
        clearInvocations(conversationRepository);
        when(conversationRepository.findById(conversationId))
            .thenReturn(Optional.of(existingWithV));
        service.updateConversation(conversationId, currentUser, null,
            Map.of("desktopVersion", "1.4.0", "thinkingLevel", "high"));
        verify(conversationRepository).updateSettings(conversationId,
            Map.of("desktopVersion", "v1.3.0", "thinking-level", "high"));

        // 3. Omission of desktopVersion in full settings PATCH preserves existing version
        clearInvocations(conversationRepository);
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(existing));
        service.updateConversation(conversationId, currentUser, null,
            Map.of("model", "claude-opus-4-6"));
        verify(conversationRepository).updateSettings(conversationId,
            Map.of("desktopVersion", "1.3.0", "model", "claude-opus-4-6"));

        // 4. Null or empty settings updates title without calling updateSettings
        clearInvocations(conversationRepository);
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(existing));
        service.updateConversation(conversationId, currentUser, "Renamed", null);
        service.updateConversation(conversationId, currentUser, "Renamed", Map.of());
        verify(conversationRepository, times(2)).updateTitle(conversationId, "Renamed");
        verify(conversationRepository, never()).updateSettings(any(), any());
    }

    @Test
    public void updateConversationSkipsUpdateSettingsOnBlankOrIgnoredVersionOnlyPatchWithoutWipingModel() {
        UUID conversationId = UUID.randomUUID();
        Conversation unversioned = new Conversation(conversationId, currentUserId, "Title",
            Map.of("model", "claude-sonnet-4-6"), Instant.now(), Instant.now(), List.of(),
            "desktop", null);
        Conversation versioned = new Conversation(conversationId, currentUserId, "Title",
            Map.of("desktopVersion", "1.3.0", "model", "claude-sonnet-4-6"), Instant.now(),
            Instant.now(), List.of(), "desktop", null);

        Map<String, Object> nullVersionMap = new HashMap<>();
        nullVersionMap.put("desktopVersion", null);

        List<Map<String, Object>> blankPatches = List.of(Map.of("desktopVersion", ""),
            Map.of("desktopVersion", "   "), Map.of("desktopVersion", "\u2003"), nullVersionMap);

        for (Conversation target : List.of(unversioned, versioned)) {
            for (Map<String, Object> patch : blankPatches) {
                clearInvocations(conversationRepository);
                when(conversationRepository.findById(conversationId))
                    .thenReturn(Optional.of(target));

                service.updateConversation(conversationId, currentUser, "Renamed", patch);

                verify(conversationRepository).updateTitle(conversationId, "Renamed");
                verify(conversationRepository, never()).updateSettings(any(), any());
            }
        }

        // Ignored-overwrite version-only PATCH on already-versioned conversation
        for (String ignoredOverwrite : List.of("1.4.0", "v")) {
            clearInvocations(conversationRepository);
            when(conversationRepository.findById(conversationId))
                .thenReturn(Optional.of(versioned));

            service.updateConversation(conversationId, currentUser, "Renamed",
                Map.of("desktopVersion", ignoredOverwrite));

            verify(conversationRepository).updateTitle(conversationId, "Renamed");
            verify(conversationRepository, never()).updateSettings(any(), any());
        }
    }

    @Test
    public void updateConversationValidatesIncomingDesktopVersionBeforeUpdatingTitleOrSettings() {
        UUID conversationId = UUID.randomUUID();
        Conversation existing = new Conversation(conversationId, currentUserId, "Title",
            Map.of("desktopVersion", "1.3.0"), Instant.now(), Instant.now(), List.of(), "desktop",
            null);
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.updateConversation(conversationId, currentUser,
            "New Title", Map.of("desktopVersion", 123)))
            .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST))
            .hasMessageContaining("Invalid desktopVersion: must be a string");

        for (String oversized : List.of("1".repeat(257), " " + "1".repeat(257) + " ",
            "v".repeat(100_000))) {
            assertThatThrownBy(() -> service.updateConversation(conversationId, currentUser,
                "New Title", Map.of("desktopVersion", oversized)))
                .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST))
                .hasMessageContaining("Invalid desktopVersion: exceeds maximum length");
        }

        verify(conversationRepository, never()).updateTitle(any(), any());
        verify(conversationRepository, never()).updateSettings(any(), any());

        // Padded 256-char version is accepted on update backfill
        Conversation unversioned = new Conversation(conversationId, currentUserId, "Title",
            Map.of(), Instant.now(), Instant.now(), List.of(), "desktop", null);
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(unversioned));
        service.updateConversation(conversationId, currentUser, null,
            Map.of("desktopVersion", "\t" + "1".repeat(256) + "\u2003"));
        verify(conversationRepository).updateSettings(conversationId,
            Map.of("desktopVersion", "1".repeat(256)));
    }

    @Test
    public void updateConversationBackfillsDesktopVersionVerbatimWithoutWipingExistingSettings() {
        UUID conversationId = UUID.randomUUID();
        Conversation legacy = new Conversation(conversationId, currentUserId, "Legacy",
            Map.of("model", "claude-sonnet-4-6"), Instant.now(), Instant.now(), List.of(),
            "desktop", null);

        // 1. Version-only backfill PATCH merges desktopVersion into stored settings (preserving
        // "model")
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(legacy));
        service.updateConversation(conversationId, currentUser, null,
            Map.of("desktopVersion", "1.3.1"));
        verify(conversationRepository).updateSettings(conversationId,
            Map.of("model", "claude-sonnet-4-6", "desktopVersion", "1.3.1"));

        // 2. Version-only backfill PATCH with "Version 1.3.1" or "v1.3.1" preserves verbatim
        for (String verbatim : List.of("v1.3.1", "Version 1.3.1")) {
            clearInvocations(conversationRepository);
            when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(legacy));
            service.updateConversation(conversationId, currentUser, null,
                Map.of("desktopVersion", verbatim));
            verify(conversationRepository).updateSettings(conversationId,
                Map.of("model", "claude-sonnet-4-6", "desktopVersion", verbatim));
        }

        // 3. Version-only backfill PATCH when conversation.settings() is null does not NPE
        clearInvocations(conversationRepository);
        Conversation nullSettingsConv = new Conversation(conversationId, currentUserId, "Legacy",
            null, Instant.now(), Instant.now(), List.of(), "desktop", null);
        when(conversationRepository.findById(conversationId))
            .thenReturn(Optional.of(nullSettingsConv));
        service.updateConversation(conversationId, currentUser, null,
            Map.of("desktopVersion", "v"));
        verify(conversationRepository).updateSettings(conversationId,
            Map.of("desktopVersion", "v"));
    }
}
