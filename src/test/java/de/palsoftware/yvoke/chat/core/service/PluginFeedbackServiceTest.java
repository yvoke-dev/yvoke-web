package de.palsoftware.yvoke.chat.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.area.core.Area;
import de.palsoftware.yvoke.area.core.AreaRepository;
import de.palsoftware.yvoke.area.core.AreaService;
import de.palsoftware.yvoke.chat.core.model.Conversation;
import de.palsoftware.yvoke.chat.core.model.Feedback;
import de.palsoftware.yvoke.chat.core.model.Message;
import de.palsoftware.yvoke.chat.core.repository.ConversationRepository;
import de.palsoftware.yvoke.chat.core.repository.FeedbackRepository;
import de.palsoftware.yvoke.chat.core.repository.MessageRepository;
import de.palsoftware.yvoke.chat.core.service.PluginFeedbackService.PluginFeedbackRequest;
import de.palsoftware.yvoke.chat.core.service.PluginFeedbackService.PluginFeedbackResult;
import de.palsoftware.yvoke.mcp.tools.SubmitFeedbackTool;
import de.palsoftware.yvoke.shared.user.model.User;
import de.palsoftware.yvoke.shared.user.service.UserService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class PluginFeedbackServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-11-02T12:00:00Z");

    private ConversationRepository conversationRepository;
    private MessageRepository messageRepository;
    private FeedbackRepository feedbackRepository;
    private UserService userService;
    private AreaRepository areaRepository;
    private PluginFeedbackService service;

    private final UUID userId = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private final User currentUser =
        new User(userId, "entra-oid-1", "alice@example.com", "Alice", FIXED_NOW);

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext()
            .setAuthentication(new UsernamePasswordAuthenticationToken("alice", "N/A",
                List.of(new SimpleGrantedAuthority("ROLE_USER"),
                    new SimpleGrantedAuthority("AREA_USER_OIM"))));

        conversationRepository = mock(ConversationRepository.class);
        messageRepository = mock(MessageRepository.class);
        feedbackRepository = mock(FeedbackRepository.class);
        userService = mock(UserService.class);
        areaRepository = mock(AreaRepository.class);

        when(userService.getCurrentUser()).thenReturn(Optional.of(currentUser));
        when(areaRepository.findAll()).thenReturn(
            List.of(new Area("OIM", "One Identity", null, false, null, null, null, null),
                new Area("PingID", "Ping Identity", null, false, null, null, null, null)));

        service = new PluginFeedbackService(conversationRepository, messageRepository,
            feedbackRepository, userService, new AreaService(areaRepository),
            Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void firstRatingCreatesConversationMessagesAndUpsertsFeedback() {
        String turnId = "turn-42";
        UUID expectedConvId = UUID.nameUUIDFromBytes(
            ("claude-plugin:conv:" + userId + ":" + turnId).getBytes(StandardCharsets.UTF_8));
        UUID expectedUserMsgId = UUID.nameUUIDFromBytes(
            ("claude-plugin:msg:user:" + userId + ":" + turnId).getBytes(StandardCharsets.UTF_8));
        UUID expectedAssistantMsgId =
            UUID.nameUUIDFromBytes(("claude-plugin:msg:assistant:" + userId + ":" + turnId)
                .getBytes(StandardCharsets.UTF_8));
        UUID storedFeedbackId = UUID.randomUUID();
        String fullChunkUuid = "550e8400-e29b-41d4-a716-446655440000";
        String dashlessUuid = "660e8400e29b41d4a716446655440001";
        String shortPrefix = "8f7c1a2b";

        when(conversationRepository.findById(expectedConvId)).thenReturn(Optional.empty());
        when(messageRepository.findById(expectedUserMsgId)).thenReturn(Optional.empty());
        when(feedbackRepository.findByMessageId(expectedAssistantMsgId))
            .thenReturn(Optional.of(new Feedback(storedFeedbackId, expectedAssistantMsgId, 1,
                "Helpful answer", FIXED_NOW, FIXED_NOW)));

        PluginFeedbackRequest request =
            new PluginFeedbackRequest("  turn-42  ", 1, "  Helpful answer  ",
                "How does PersonWantsOrg synchronization work in OIM when provisioning?",
                "PersonWantsOrg drives request-based provisioning [chunk_id=8f7c1a2b].", " oim ",
                "single", "oim-full", 2, null,
                List.of(fullChunkUuid, dashlessUuid, shortPrefix, fullChunkUuid, "   "),
                "claude-sonnet-4-5", "claude-plugin", "0.1.0");

        PluginFeedbackResult result = service.submitFeedback(request);

        assertThat(result.feedbackId()).isEqualTo(storedFeedbackId);
        assertThat(result.messageId()).isEqualTo(expectedAssistantMsgId);
        assertThat(result.conversationId()).isEqualTo(expectedConvId);
        assertThat(result.turnId()).isEqualTo("turn-42");
        assertThat(result.rating()).isEqualTo(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> settingsCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
        verify(conversationRepository).create(eq(expectedConvId), eq(userId), titleCaptor.capture(),
            settingsCaptor.capture(), eq("claude-plugin"), eq("OIM"));

        assertThat(titleCaptor.getValue()).hasSize(60)
            .isEqualTo("How does PersonWantsOrg synchronization work in OIM when pro");
        Map<String, Object> settings = settingsCaptor.getValue();
        assertThat(settings).containsEntry("turnId", "turn-42")
            .containsEntry("client", "claude-plugin").containsEntry("pluginVersion", "0.1.0")
            .containsEntry("mode", "single").containsEntry("playbook", "oim-full")
            .containsEntry("playbookVersion", 2).containsEntry("model", "claude-sonnet-4-5")
            .containsEntry("citedIds", List.of(fullChunkUuid, dashlessUuid, shortPrefix));

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository, times(2)).save(messageCaptor.capture());
        List<Message> savedMessages = messageCaptor.getAllValues();

        Message userMsg = savedMessages.get(0);
        assertThat(userMsg.id()).isEqualTo(expectedUserMsgId);
        assertThat(userMsg.conversationId()).isEqualTo(expectedConvId);
        assertThat(userMsg.role()).isEqualTo("user");
        assertThat(userMsg.playbook()).isEqualTo("oim-full");
        assertThat(userMsg.playbookVersion()).isEqualTo(2);
        assertThat(userMsg.createdAt()).isEqualTo(FIXED_NOW);

        Message assistantMsg = savedMessages.get(1);
        assertThat(assistantMsg.id()).isEqualTo(expectedAssistantMsgId);
        assertThat(assistantMsg.conversationId()).isEqualTo(expectedConvId);
        assertThat(assistantMsg.role()).isEqualTo("assistant");
        assertThat(assistantMsg.model()).isEqualTo("claude-sonnet-4-5");
        assertThat(assistantMsg.playbook()).isEqualTo("oim-full");
        assertThat(assistantMsg.playbookVersion()).isEqualTo(2);
        assertThat(assistantMsg.createdAt()).isEqualTo(FIXED_NOW.plusNanos(1_000_000));
        assertThat(userMsg.createdAt()).isBefore(assistantMsg.createdAt());
        assertThat(assistantMsg.retrievedChunkIds()).containsExactly(UUID.fromString(fullChunkUuid),
            UUID.fromString("660e8400-e29b-41d4-a716-446655440001"));
        assertThat(assistantMsg.citations()).hasSize(3);

        verify(feedbackRepository).upsert(expectedAssistantMsgId, 1, "Helpful answer");
    }

    @Test
    void reRatingSameTurnUpdatesFeedbackInPlaceWithoutDuplicatingConversationOrMessages() {
        String turnId = "turn-42";
        UUID expectedConvId = UUID.nameUUIDFromBytes(
            ("claude-plugin:conv:" + userId + ":" + turnId).getBytes(StandardCharsets.UTF_8));
        UUID expectedAssistantMsgId =
            UUID.nameUUIDFromBytes(("claude-plugin:msg:assistant:" + userId + ":" + turnId)
                .getBytes(StandardCharsets.UTF_8));

        Conversation existingConv = new Conversation(expectedConvId, userId, "Question", Map.of(),
            FIXED_NOW, FIXED_NOW, List.of(), "claude-plugin", "OIM");
        Message existingAssistant = new Message(expectedAssistantMsgId, expectedConvId, "assistant",
            "Answer", List.of(), List.of(), FIXED_NOW);

        when(conversationRepository.findById(expectedConvId)).thenReturn(Optional.of(existingConv));
        when(messageRepository.findById(expectedAssistantMsgId))
            .thenReturn(Optional.of(existingAssistant));

        PluginFeedbackRequest request = new PluginFeedbackRequest(turnId, -1,
            "Missed the QBM table reference", "Question", "Answer", "OIM", "single", "oim-full", 2,
            null, List.of(), "claude-sonnet-4-5", "claude-plugin", "0.1.0");

        PluginFeedbackResult result = service.submitFeedback(request);

        assertThat(result.conversationId()).isEqualTo(expectedConvId);
        assertThat(result.messageId()).isEqualTo(expectedAssistantMsgId);
        assertThat(result.rating()).isEqualTo(-1);

        verify(conversationRepository, never()).create(any(), any(), any(), any(), any(), any());
        verify(messageRepository, never()).save(any());
        verify(conversationRepository).touch(expectedConvId);
        verify(feedbackRepository).upsert(expectedAssistantMsgId, -1,
            "Missed the QBM table reference");
    }

    @Test
    void coercesPlaybookVersionToNullWhenPlaybookIsBlankOrNull() {
        String turnId = "turn-no-pb";
        when(conversationRepository.findById(any())).thenReturn(Optional.empty());
        when(messageRepository.findById(any())).thenReturn(Optional.empty());

        PluginFeedbackRequest request = new PluginFeedbackRequest(turnId, 1, null, "Question?",
            "Answer.", null, "multi", "   ", 3, "OIM", List.of(), null, null, null);

        service.submitFeedback(request);

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository, times(2)).save(messageCaptor.capture());
        for (Message m : messageCaptor.getAllValues()) {
            assertThat(m.playbook()).isNull();
            assertThat(m.playbookVersion()).isNull();
        }
    }

    @Test
    void rejectsUnauthenticatedCaller() {
        when(userService.getCurrentUser()).thenReturn(Optional.empty());

        PluginFeedbackRequest request = new PluginFeedbackRequest("turn-1", 1, null, "Q", "A",
            "OIM", "single", null, null, null, List.of(), null, null, null);

        assertThatThrownBy(() -> service.submitFeedback(request))
            .isInstanceOf(SecurityException.class)
            .hasMessage("Authenticated user is required to submit feedback.");
    }

    @Test
    void rejectsUserWithZeroAccessibleAreas() {
        SecurityContextHolder.getContext()
            .setAuthentication(new UsernamePasswordAuthenticationToken("alice", "N/A",
                List.of(new SimpleGrantedAuthority("SCOPE_mcp.read"))));

        PluginFeedbackRequest request = new PluginFeedbackRequest("turn-1", 1, null, "Q", "A", null,
            "single", null, null, null, List.of(), null, null, null);

        assertThatThrownBy(() -> service.submitFeedback(request))
            .isInstanceOf(SecurityException.class)
            .hasMessage("Access denied: user has no accessible areas.");
    }

    @Test
    void rejectsUnknownOrUnauthorizedAreaWithOpaqueError() {
        PluginFeedbackRequest unknownAreaReq = new PluginFeedbackRequest("turn-1", 1, null, "Q",
            "A", "SAP", "single", null, null, null, List.of(), null, null, null);

        assertThatThrownBy(() -> service.submitFeedback(unknownAreaReq))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("area 'SAP' does not exist.");

        PluginFeedbackRequest unauthorizedAreaReq = new PluginFeedbackRequest("turn-1", 1, null,
            "Q", "A", "PingID", "single", null, null, null, List.of(), null, null, null);

        assertThatThrownBy(() -> service.submitFeedback(unauthorizedAreaReq))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("area 'PingID' does not exist.");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\n"})
    void rejectsBlankRequiredFields(String blank) {
        assertThatThrownBy(() -> service.submitFeedback(new PluginFeedbackRequest(blank, 1, null,
            "Q", "A", "OIM", null, null, null, null, List.of(), null, null, null)))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("turnId is required.");

        assertThatThrownBy(() -> service.submitFeedback(new PluginFeedbackRequest("turn-1", 1, null,
            blank, "A", "OIM", null, null, null, null, List.of(), null, null, null)))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("question is required.");

        assertThatThrownBy(() -> service.submitFeedback(new PluginFeedbackRequest("turn-1", 1, null,
            "Q", blank, "OIM", null, null, null, null, List.of(), null, null, null)))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("answer is required.");
    }

    @ParameterizedTest
    @ValueSource(ints = {-2, 0, 2, 5})
    void rejectsInvalidRatings(int invalidRating) {
        assertThatThrownBy(
            () -> service.submitFeedback(new PluginFeedbackRequest("turn-1", invalidRating,
                "comment", "Q", "A", "OIM", null, null, null, null, List.of(), null, null, null)))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("rating must be 1 or -1.");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n"})
    void rejectsNegativeFeedbackWithoutComment(String blankComment) {
        assertThatThrownBy(() -> service.submitFeedback(new PluginFeedbackRequest("turn-1", -1,
            blankComment, "Q", "A", "OIM", null, null, null, null, List.of(), null, null, null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("comment is required for negative feedback (rating = -1).");
    }

    @Test
    void rejectsInvalidModeAndInvalidPlaybookVersion() {
        assertThatThrownBy(() -> service.submitFeedback(new PluginFeedbackRequest("turn-1", 1, null,
            "Q", "A", "OIM", "invalid-mode", null, null, null, List.of(), null, null, null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("mode must be 'single' or 'multi' when provided.");

        assertThatThrownBy(() -> service.submitFeedback(new PluginFeedbackRequest("turn-1", 1, null,
            "Q", "A", "OIM", "single", "oim-full", 0, null, List.of(), null, null, null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("playbookVersion must be >= 1 when provided.");
    }

    @Nested
    class SubmitFeedbackToolSpecs {

        private final ObjectMapper json = new ObjectMapper();
        private PluginFeedbackService mockedService;
        private SubmitFeedbackTool tool;

        @BeforeEach
        void setUpTool() {
            mockedService = mock(PluginFeedbackService.class);
            tool = new SubmitFeedbackTool(mockedService, json);
        }

        @Test
        void returnsOkJsonWithIdsWhenFeedbackSucceeds() throws Exception {
            UUID feedbackId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
            UUID messageId = UUID.fromString("11111111-2222-3333-4444-555555555555");
            UUID conversationId = UUID.fromString("99999999-8888-7777-6666-555555555555");

            when(mockedService.submitFeedback(any())).thenReturn(
                new PluginFeedbackResult(feedbackId, messageId, conversationId, "turn-99", 1));

            String raw = tool.submitFeedback("turn-99", 1, "Accurate citation", "What is QBM?",
                "QBM is the base module.", "OIM", "single", "oim-full", 2, null,
                List.of("8f7c1a2b"), "claude-sonnet-4-5", "claude-plugin", "0.1.0");

            JsonNode node = json.readTree(raw);
            assertThat(node.get("status").asText()).isEqualTo("ok");
            assertThat(node.get("feedbackId").asText()).isEqualTo(feedbackId.toString());
            assertThat(node.get("messageId").asText()).isEqualTo(messageId.toString());
            assertThat(node.get("conversationId").asText()).isEqualTo(conversationId.toString());
            assertThat(node.get("turnId").asText()).isEqualTo("turn-99");
            assertThat(node.get("rating").asInt()).isEqualTo(1);
        }

        @Test
        void returnsErrorStringWhenValidationOrSecurityFails() {
            when(mockedService.submitFeedback(any())).thenThrow(
                new IllegalArgumentException(
                    "comment is required for negative feedback (rating = -1)."),
                new SecurityException("Access denied: user has no accessible areas."));

            assertThat(tool.submitFeedback("turn-99", -1, "   ", "Q", "A", "OIM", "single", null,
                null, null, List.of(), null, "claude-plugin", "0.1.0"))
                .isEqualTo("ERROR: comment is required for negative feedback (rating = -1).");

            assertThat(tool.submitFeedback("turn-99", 1, null, "Q", "A", "OIM", "single", null,
                null, null, List.of(), null, "claude-plugin", "0.1.0"))
                .isEqualTo("ERROR: Access denied: user has no accessible areas.");
        }

        @Test
        void masksUnexpectedExceptionsViaMcpToolError() {
            when(mockedService.submitFeedback(any())).thenThrow(
                new IllegalStateException("jdbc:postgresql://secret-db-host:5432/yvoke"));

            String raw = tool.submitFeedback("turn-99", 1, null, "Q", "A", "OIM", "single", null,
                null, null, List.of(), null, "claude-plugin", "0.1.0");

            assertThat(raw)
                .isEqualTo("ERROR: the 'submit_feedback' tool failed to complete the request.")
                .doesNotContain("secret-db-host");
        }
    }
}
