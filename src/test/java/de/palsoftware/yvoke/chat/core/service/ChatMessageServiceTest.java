package de.palsoftware.yvoke.chat.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import de.palsoftware.yvoke.chat.core.ChatProperties;
import de.palsoftware.yvoke.chat.core.model.Conversation;
import de.palsoftware.yvoke.chat.core.model.ConversationSetting;
import de.palsoftware.yvoke.chat.core.model.Message;
import de.palsoftware.yvoke.chat.core.repository.ConversationRepository;
import de.palsoftware.yvoke.chat.core.repository.MessageRepository;
import de.palsoftware.yvoke.rag.core.model.AgenticRequest;
import de.palsoftware.yvoke.rag.core.model.RagResult;
import de.palsoftware.yvoke.rag.core.service.RagService;
import de.palsoftware.yvoke.rag.prompt.Playbook;
import de.palsoftware.yvoke.rag.prompt.PlaybookService;
import de.palsoftware.yvoke.rag.prompt.SystemPromptService;
import de.palsoftware.yvoke.rag.retrieval.RetrievalLogRepository;
import de.palsoftware.yvoke.shared.user.service.UserService;
import de.palsoftware.yvoke.tag.core.repository.TagRepository;
import org.springframework.core.task.AsyncTaskExecutor;


public class ChatMessageServiceTest {
    private MessageRepository messageRepository;
    private ChatConversationService chatConversationService;
    private RagService ragService;
    private RetrievalLogRepository retrievalLogRepository;
    private PlaybookService playbookService;
    private SystemPromptService systemPromptService;
    private PlatformTransactionManager transactionManager;
    private AsyncTaskExecutor taskExecutor;
    private ChatCancellationService chatCancellationService;
    private ChatMessageService chatMessageService;

    @BeforeEach
    public void setUp() {
        messageRepository = mock(MessageRepository.class);
        chatConversationService = mock(ChatConversationService.class);
        ragService = mock(RagService.class);
        retrievalLogRepository = mock(RetrievalLogRepository.class);
        playbookService = mock(PlaybookService.class);
        systemPromptService = mock(SystemPromptService.class);
        transactionManager = mock(PlatformTransactionManager.class);
        taskExecutor = mock(AsyncTaskExecutor.class);
        chatCancellationService = mock(ChatCancellationService.class);

        chatMessageService =
            new ChatMessageService(messageRepository, mock(ConversationRepository.class),
                chatConversationService, ragService, retrievalLogRepository, playbookService,
                systemPromptService, transactionManager, taskExecutor, chatCancellationService,
                mock(de.palsoftware.yvoke.chat.orchestration.OrchestrationService.class));
    }

    @Test
    public void testSendMessageAgenticMode() {
        UUID conversationId = UUID.randomUUID();
        UUID assistantMessageId = UUID.randomUUID();
        Map<String, Object> settings = new HashMap<>();
        settings.put(ConversationSetting.MODEL.getValue(), "gemini-3.1-flash-lite");

        Conversation conv =
            new Conversation(conversationId, null, "My Chat", settings, null, null, List.of());
        when(chatConversationService.verifyOwnership(conversationId, false)).thenReturn(conv);

        UUID searchId = UUID.randomUUID();
        // Distinct non-zero token counts so the [DONE] JSON assertion locks the field mapping
        // (prompt/completion/total/cached/thought must not be swapped) — MNT-17.
        RagResult ragResult = new RagResult(List.of(UUID.randomUUID()), List.of(), null,
            List.of(searchId), 11, 22, 33, 4, 5);

        Playbook pb = new Playbook("test-playbook", "Title", "Desc", "", List.of(), false,
            Instant.now(), Instant.now(), false);
        when(playbookService.getPlaybook("test-playbook")).thenReturn(Optional.of(pb));

        doAnswer(inv -> {
            Consumer<String> ragSink = inv.getArgument(1);
            ragSink.accept("🔧 *Calling tool:* oim_search");
            ragSink.accept("Answer text [chunk_id=abc].");
            return ragResult;
        }).when(ragService).generateAgenticAnswer(
            argThat((AgenticRequest req) -> req.query().equals("User query")), any());

        List<String> results = new ArrayList<>();
        ChatMessageService.PreparedChat prepared =
            chatMessageService.prepare(conversationId, "User query", "test-playbook");
        chatMessageService.stream(prepared, assistantMessageId, results::add);

        assertThat(results).containsSequence("🔧 *Calling tool:* oim_search",
            "Answer text [chunk_id=abc].");
        JsonNode done = parseDoneEvent(results.get(results.size() - 1));
        assertThat(done.get("messageId").asText()).isEqualTo(assistantMessageId.toString());
        assertThat(done.get("promptTokens").asInt()).isEqualTo(11);
        assertThat(done.get("completionTokens").asInt()).isEqualTo(22);
        assertThat(done.get("totalTokens").asInt()).isEqualTo(33);
        assertThat(done.get("cachedTokens").asInt()).isEqualTo(4);
        assertThat(done.get("thoughtTokens").asInt()).isEqualTo(5);

        verify(retrievalLogRepository).updateMessageId(searchId, assistantMessageId);
    }

    @Test
    public void testSendMessageWithPlaybook() {
        UUID conversationId = UUID.randomUUID();
        UUID assistantMessageId = UUID.randomUUID();
        Map<String, Object> settings = new HashMap<>();
        settings.put(ConversationSetting.MODEL.getValue(), "gemini-3.1-flash-lite");

        Conversation conv =
            new Conversation(conversationId, null, "My Chat", settings, null, null, List.of());
        when(chatConversationService.verifyOwnership(conversationId, false)).thenReturn(conv);

        UUID searchId = UUID.randomUUID();
        RagResult ragResult = new RagResult(List.of(UUID.randomUUID()), List.of(searchId));

        Playbook mockPlaybook = new Playbook("oim-explain-table", "Title", "Desc",
            "Table Playbook Content", List.of(), false, Instant.now(), Instant.now(), false);
        when(playbookService.getPlaybook("oim-explain-table"))
            .thenReturn(Optional.of(mockPlaybook));

        doAnswer(inv -> {
            Consumer<String> ragSink = inv.getArgument(1);
            ragSink.accept("Result");
            return ragResult;
        }).when(ragService).generateAgenticAnswer(argThat((AgenticRequest req) -> req.query()
            .equals("Table Playbook Content\n\n---\n\nUser query")), any());

        List<String> results = new ArrayList<>();
        ChatMessageService.PreparedChat prepared =
            chatMessageService.prepare(conversationId, "User query", "oim-explain-table");
        chatMessageService.stream(prepared, assistantMessageId, results::add);

        assertThat(results).contains("Result");

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository, times(2)).save(messageCaptor.capture());

        List<Message> savedMessages = messageCaptor.getAllValues();
        assertThat(savedMessages.get(0).role()).isEqualTo("user");
        assertThat(savedMessages.get(0).content()).isEqualTo("User query");
        assertThat(savedMessages.get(0).playbook()).isEqualTo("oim-explain-table");
    }

    @Test
    public void testSendMessageWithEmptyPlaybook() {
        UUID conversationId = UUID.randomUUID();
        UUID assistantMessageId = UUID.randomUUID();
        Map<String, Object> settings = new HashMap<>();
        settings.put(ConversationSetting.MODEL.getValue(), "gemini-3.1-flash-lite");

        Conversation conv =
            new Conversation(conversationId, null, "My Chat", settings, null, null, List.of());
        when(chatConversationService.verifyOwnership(conversationId, false)).thenReturn(conv);

        UUID searchId = UUID.randomUUID();
        RagResult ragResult = new RagResult(List.of(UUID.randomUUID()), List.of(searchId));

        Playbook mockBlankPlaybook = new Playbook("oim-explain-table", "Title", "Desc", "   ",
            List.of(), false, Instant.now(), Instant.now(), false);
        when(playbookService.getPlaybook("oim-explain-table"))
            .thenReturn(Optional.of(mockBlankPlaybook));

        doAnswer(inv -> {
            Consumer<String> ragSink = inv.getArgument(1);
            ragSink.accept("Result");
            return ragResult;
        }).when(ragService).generateAgenticAnswer(
            argThat((AgenticRequest req) -> req.query().equals("User query")), any());

        List<String> results = new ArrayList<>();
        ChatMessageService.PreparedChat prepared =
            chatMessageService.prepare(conversationId, "User query", "oim-explain-table");
        chatMessageService.stream(prepared, assistantMessageId, results::add);

        assertThat(results).contains("Result");

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository, times(2)).save(messageCaptor.capture());

        List<Message> savedMessages = messageCaptor.getAllValues();
        assertThat(savedMessages.get(0).role()).isEqualTo("user");
        assertThat(savedMessages.get(0).content()).isEqualTo("User query");
        assertThat(savedMessages.get(0).playbook()).isEqualTo("oim-explain-table");
    }

    @Test
    public void testPrepareRejectsUnauthorizedBeforeStreaming() {
        UUID conversationId = UUID.randomUUID();
        when(chatConversationService.verifyOwnership(conversationId, false)).thenThrow(
            new org.springframework.security.access.AccessDeniedException("Access denied"));

        Playbook pb = new Playbook("test-playbook", "Title", "Desc", "", List.of(), false,
            Instant.now(), Instant.now(), false);
        when(playbookService.getPlaybook("test-playbook")).thenReturn(Optional.of(pb));

        org.junit.jupiter.api.Assertions.assertThrows(
            org.springframework.security.access.AccessDeniedException.class,
            () -> chatMessageService.prepare(conversationId, "User query", "test-playbook"));

        // Authorization is enforced during the synchronous prepare() phase; generation must never
        // begin for an unauthorized request.
        verify(ragService, never()).generateAgenticAnswer(any(), any());
    }

    @Test
    public void testChatDisabledThrowsException() {
        ChatProperties disabledProperties =
            new ChatProperties(false, java.util.List.of("gemini-3.1-flash-lite"), true);

        ChatConversationService disabledConversationService =
            new ChatConversationService(mock(ConversationRepository.class), mock(UserService.class),
                disabledProperties, mock(TagRepository.class));

        PlaybookService mockPlaybookService = mock(PlaybookService.class);
        Playbook pb = new Playbook("test-playbook", "Title", "Desc", "", List.of(), false,
            Instant.now(), Instant.now(), false);
        when(mockPlaybookService.getPlaybook("test-playbook")).thenReturn(Optional.of(pb));

        ChatMessageService disabledMessageService = new ChatMessageService(messageRepository,
            mock(ConversationRepository.class), disabledConversationService, ragService,
            retrievalLogRepository, mockPlaybookService, systemPromptService, transactionManager,
            taskExecutor, chatCancellationService,
            mock(de.palsoftware.yvoke.chat.orchestration.OrchestrationService.class));

        Assertions.assertThrows(IllegalStateException.class, () -> {
            disabledMessageService.prepare(UUID.randomUUID(), "query", "test-playbook");
        });
    }

    @Test
    public void testStreamHandlesExceptionAndPersistsErrorMessage() {
        UUID conversationId = UUID.randomUUID();
        UUID assistantMessageId = UUID.randomUUID();
        Map<String, Object> settings = new HashMap<>();
        settings.put(ConversationSetting.MODEL.getValue(), "gemini-3.1-flash-lite");

        Conversation conv =
            new Conversation(conversationId, null, "My Chat", settings, null, null, List.of());
        when(chatConversationService.verifyOwnership(conversationId, false)).thenReturn(conv);

        Playbook pb = new Playbook("test-playbook", "Title", "Desc", "", List.of(), false,
            Instant.now(), Instant.now(), false);
        when(playbookService.getPlaybook("test-playbook")).thenReturn(Optional.of(pb));

        when(ragService.generateAgenticAnswer(any(), any()))
            .thenThrow(new RuntimeException("Simulated Gemini Failure"));

        List<String> results = new ArrayList<>();
        ChatMessageService.PreparedChat prepared =
            chatMessageService.prepare(conversationId, "User query", "test-playbook");

        chatMessageService.stream(prepared, assistantMessageId, results::add);

        // A generic error is streamed; the raw exception message must NEVER reach the client
        // (SEC-17).
        assertThat(results)
            .contains("\n\n⚠️ *[System Error: the assistant could not complete this response.]*");
        assertThat(results).noneMatch(s -> s.contains("Simulated Gemini Failure"));
        JsonNode done = parseDoneEvent(results.get(results.size() - 1));
        assertThat(done.get("messageId").asText()).isEqualTo(assistantMessageId.toString());
        assertThat(done.get("promptTokens").asInt()).isZero();

        // The persisted assistant message must also be free of the raw exception detail.
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository, times(2)).save(messageCaptor.capture());
        Message assistantMessage = messageCaptor.getAllValues().stream()
            .filter(m -> "assistant".equals(m.role())).findFirst().orElseThrow();
        assertThat(assistantMessage.content()).contains("could not complete this response");
        assertThat(assistantMessage.content()).doesNotContain("Simulated Gemini Failure");
    }

    @Test
    public void testGenerateSyncHandlesExceptionAndPersistsErrorMessage() {
        UUID conversationId = UUID.randomUUID();
        UUID assistantMessageId = UUID.randomUUID();
        Map<String, Object> settings = new HashMap<>();
        settings.put(ConversationSetting.MODEL.getValue(), "gemini-3.1-flash-lite");

        Conversation conv =
            new Conversation(conversationId, null, "My Chat", settings, null, null, List.of());
        when(chatConversationService.verifyOwnership(conversationId, false)).thenReturn(conv);

        Playbook pb = new Playbook("test-playbook", "Title", "Desc", "", List.of(), false,
            Instant.now(), Instant.now(), false);
        when(playbookService.getPlaybook("test-playbook")).thenReturn(Optional.of(pb));

        when(ragService.generateAgenticAnswer(any(), any()))
            .thenThrow(new RuntimeException("Simulated Gemini Failure"));

        ChatMessageService.PreparedChat prepared =
            chatMessageService.prepare(conversationId, "User query", "test-playbook");

        Message returnedMessage = chatMessageService.generateSync(prepared, assistantMessageId);

        // The returned message carries a generic error only — no raw exception detail (SEC-17).
        assertThat(returnedMessage.content()).contains("could not complete this response");
        assertThat(returnedMessage.content()).doesNotContain("Simulated Gemini Failure");

        // Verify that the message was also saved to the repository
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository, times(2)).save(messageCaptor.capture());
        Message assistantMessage = messageCaptor.getAllValues().stream()
            .filter(m -> "assistant".equals(m.role()) && m.id().equals(assistantMessageId))
            .findFirst().orElseThrow();
        assertThat(assistantMessage.content()).doesNotContain("Simulated Gemini Failure");
    }

    @Test
    public void testThinkingLevelConfiguredAndPassedToRag() {
        UUID conversationId = UUID.randomUUID();
        UUID assistantMessageId = UUID.randomUUID();
        Map<String, Object> settings = new HashMap<>();
        settings.put(ConversationSetting.MODEL.getValue(), "gemini-3.1-flash-lite");
        settings.put(ConversationSetting.THINKING_LEVEL.getValue(), "high");

        Conversation conv =
            new Conversation(conversationId, null, "My Chat", settings, null, null, List.of());
        when(chatConversationService.verifyOwnership(conversationId, false)).thenReturn(conv);

        Playbook pb = new Playbook("test-playbook", "Title", "Desc", "", List.of(), false,
            Instant.now(), Instant.now(), false);
        when(playbookService.getPlaybook("test-playbook")).thenReturn(Optional.of(pb));

        RagResult ragResult = new RagResult(List.of(UUID.randomUUID()), List.of(UUID.randomUUID()));
        when(ragService.generateAgenticAnswer(any(), any())).thenReturn(ragResult);

        ChatMessageService.PreparedChat prepared =
            chatMessageService.prepare(conversationId, "User query", "test-playbook");

        assertThat(prepared.thinkingLevel()).isEqualTo("high");

        chatMessageService.generateSync(prepared, assistantMessageId);

        verify(ragService).generateAgenticAnswer(
            argThat((AgenticRequest req) -> "high".equals(req.thinkingLevel())), any());
    }

    @Test
    public void testPlaybookSequenceDeduplication() {
        UUID conversationId = UUID.randomUUID();
        Map<String, Object> settings = new HashMap<>();
        settings.put(ConversationSetting.MODEL.getValue(), "gemini-3.1-flash-lite");
        Conversation conv =
            new Conversation(conversationId, null, "My Chat", settings, null, null, List.of());
        when(chatConversationService.verifyOwnership(conversationId, false)).thenReturn(conv);

        Playbook pb1 = new Playbook("PB1", "Playbook 1", "Desc", "PB1 Template", List.of(), false,
            Instant.now(), Instant.now(), false);
        Playbook pb2 = new Playbook("PB2", "Playbook 2", "Desc", "PB2 Template", List.of(), false,
            Instant.now(), Instant.now(), false);
        when(playbookService.getPlaybook("PB1")).thenReturn(Optional.of(pb1));
        when(playbookService.getPlaybook("PB2")).thenReturn(Optional.of(pb2));

        // Message 1 (PB1) -> should prepend
        when(messageRepository.findByConversationId(conversationId, 100, 0)).thenReturn(List.of());
        ChatMessageService.PreparedChat prep1 =
            chatMessageService.prepare(conversationId, "Message 1", "PB1");
        assertThat(prep1.modelContent()).isEqualTo("PB1 Template\n\n---\n\nMessage 1");

        Message msg1 = new Message(UUID.randomUUID(), conversationId, "user", "Message 1", "PB1",
            List.of(), List.of(), Instant.now());
        Message msg1Ans = new Message(UUID.randomUUID(), conversationId, "assistant", "Answer 1",
            List.of(), List.of(), Instant.now());

        // Message 2 (PB1) -> should not prepend
        when(messageRepository.findByConversationId(conversationId, 100, 0))
            .thenReturn(List.of(msg1, msg1Ans));
        ChatMessageService.PreparedChat prep2 =
            chatMessageService.prepare(conversationId, "Message 2", "PB1");
        assertThat(prep2.modelContent()).isEqualTo("Message 2");

        Message msg2 = new Message(UUID.randomUUID(), conversationId, "user", "Message 2", "PB1",
            List.of(), List.of(), Instant.now());
        Message msg2Ans = new Message(UUID.randomUUID(), conversationId, "assistant", "Answer 2",
            List.of(), List.of(), Instant.now());

        // Message 3 (PB1) -> should not prepend
        when(messageRepository.findByConversationId(conversationId, 100, 0))
            .thenReturn(List.of(msg1, msg1Ans, msg2, msg2Ans));
        ChatMessageService.PreparedChat prep3 =
            chatMessageService.prepare(conversationId, "Message 3", "PB1");
        assertThat(prep3.modelContent()).isEqualTo("Message 3");

        Message msg3 = new Message(UUID.randomUUID(), conversationId, "user", "Message 3", "PB1",
            List.of(), List.of(), Instant.now());
        Message msg3Ans = new Message(UUID.randomUUID(), conversationId, "assistant", "Answer 3",
            List.of(), List.of(), Instant.now());

        // Message 4 (PB2) -> should prepend (first PB2)
        when(messageRepository.findByConversationId(conversationId, 100, 0))
            .thenReturn(List.of(msg1, msg1Ans, msg2, msg2Ans, msg3, msg3Ans));
        ChatMessageService.PreparedChat prep4 =
            chatMessageService.prepare(conversationId, "Message 4", "PB2");
        assertThat(prep4.modelContent()).isEqualTo("PB2 Template\n\n---\n\nMessage 4");

        Message msg4 = new Message(UUID.randomUUID(), conversationId, "user", "Message 4", "PB2",
            List.of(), List.of(), Instant.now());
        Message msg4Ans = new Message(UUID.randomUUID(), conversationId, "assistant", "Answer 4",
            List.of(), List.of(), Instant.now());

        // Message 5 (PB2) -> should not prepend
        when(messageRepository.findByConversationId(conversationId, 100, 0))
            .thenReturn(List.of(msg1, msg1Ans, msg2, msg2Ans, msg3, msg3Ans, msg4, msg4Ans));
        ChatMessageService.PreparedChat prep5 =
            chatMessageService.prepare(conversationId, "Message 5", "PB2");
        assertThat(prep5.modelContent()).isEqualTo("Message 5");

        Message msg5 = new Message(UUID.randomUUID(), conversationId, "user", "Message 5", "PB2",
            List.of(), List.of(), Instant.now());
        Message msg5Ans = new Message(UUID.randomUUID(), conversationId, "assistant", "Answer 5",
            List.of(), List.of(), Instant.now());

        // Message 6 (PB1) -> should not prepend (already used in Message 1)
        when(messageRepository.findByConversationId(conversationId, 100, 0)).thenReturn(
            List.of(msg1, msg1Ans, msg2, msg2Ans, msg3, msg3Ans, msg4, msg4Ans, msg5, msg5Ans));
        ChatMessageService.PreparedChat prep6 =
            chatMessageService.prepare(conversationId, "Message 6", "PB1");
        assertThat(prep6.modelContent()).isEqualTo("Message 6");

        Message msg6 = new Message(UUID.randomUUID(), conversationId, "user", "Message 6", "PB1",
            List.of(), List.of(), Instant.now());
        Message msg6Ans = new Message(UUID.randomUUID(), conversationId, "assistant", "Answer 6",
            List.of(), List.of(), Instant.now());

        // Message 7 (PB2) -> should not prepend (already used in Message 4)
        when(messageRepository.findByConversationId(conversationId, 100, 0))
            .thenReturn(List.of(msg1, msg1Ans, msg2, msg2Ans, msg3, msg3Ans, msg4, msg4Ans, msg5,
                msg5Ans, msg6, msg6Ans));
        ChatMessageService.PreparedChat prep7 =
            chatMessageService.prepare(conversationId, "Message 7", "PB2");
        assertThat(prep7.modelContent()).isEqualTo("Message 7");

        // Verify full history reconstruction for prep7
        assertThat(prep7.history()).hasSize(12);
        assertThat(prep7.history().get(0).content()).isEqualTo("PB1 Template\n\n---\n\nMessage 1");
        assertThat(prep7.history().get(1).content()).isEqualTo("Answer 1");
        assertThat(prep7.history().get(2).content()).isEqualTo("Message 2");
        assertThat(prep7.history().get(3).content()).isEqualTo("Answer 2");
        assertThat(prep7.history().get(4).content()).isEqualTo("Message 3");
        assertThat(prep7.history().get(5).content()).isEqualTo("Answer 3");
        assertThat(prep7.history().get(6).content()).isEqualTo("PB2 Template\n\n---\n\nMessage 4");
        assertThat(prep7.history().get(7).content()).isEqualTo("Answer 4");
        assertThat(prep7.history().get(8).content()).isEqualTo("Message 5");
        assertThat(prep7.history().get(9).content()).isEqualTo("Answer 5");
        assertThat(prep7.history().get(10).content()).isEqualTo("Message 6");
        assertThat(prep7.history().get(11).content()).isEqualTo("Answer 6");
    }

    @Test
    public void testPrepareThrowsIfPlaybookNotSelected() {
        UUID conversationId = UUID.randomUUID();
        IllegalArgumentException exception = Assertions.assertThrows(IllegalArgumentException.class,
            () -> chatMessageService.prepare(conversationId, "User query", null));
        assertThat(exception.getMessage())
            .isEqualTo("A playbook must be selected before asking a question.");
    }

    @Test
    public void testPrepareThrowsIfPlaybookInvalid() {
        UUID conversationId = UUID.randomUUID();
        when(playbookService.getPlaybook("invalid-pb")).thenReturn(Optional.empty());
        IllegalArgumentException exception = Assertions.assertThrows(IllegalArgumentException.class,
            () -> chatMessageService.prepare(conversationId, "User query", "invalid-pb"));
        assertThat(exception.getMessage()).isEqualTo("The selected playbook is invalid.");
    }

    @Test
    public void testPrepareAndSubmitAsyncSuccess() throws Exception {
        UUID conversationId = UUID.randomUUID();
        String query = "Async Query";
        String pbName = "PB1";

        Map<String, Object> settings = new HashMap<>();
        settings.put(ConversationSetting.MODEL.getValue(), "gemini-3.1-flash-lite");
        Conversation conv =
            new Conversation(conversationId, null, "My Chat", settings, null, null, List.of());
        when(chatConversationService.verifyOwnership(conversationId, false)).thenReturn(conv);

        Playbook pb = new Playbook(pbName, "Title", "Desc", "PB1 Template", List.of(), false,
            Instant.now(), Instant.now(), false);
        when(playbookService.getPlaybook(pbName)).thenReturn(Optional.of(pb));

        RagResult mockRagResult =
            new RagResult(List.of(UUID.randomUUID()), List.of(), null, List.of(), 10, 20, 30, 0, 0);

        when(ragService.generateAgenticAnswer(any(AgenticRequest.class), any(Consumer.class)))
            .thenAnswer(invocation -> {
                Consumer<String> sink = invocation.getArgument(1);
                sink.accept("Async response text");
                return mockRagResult;
            });

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);

        UUID assistantMessageId =
            chatMessageService.prepareAndSubmitAsync(conversationId, query, pbName);
        assertThat(assistantMessageId).isNotNull();

        verify(taskExecutor).submit(runnableCaptor.capture());
        Runnable capturedTask = runnableCaptor.getValue();

        // Run the captured runnable synchronously
        capturedTask.run();

        verify(chatCancellationService).register(argThat(id -> id.equals(conversationId)),
            any(Thread.class));
        verify(messageRepository).updateContentAndStatus(
            argThat(id -> id.equals(assistantMessageId)),
            argThat(content -> content.equals("Async response text")), any(List.class),
            any(List.class), any(Integer.class), any(Integer.class), any(Integer.class),
            any(Integer.class), any(Integer.class), argThat(status -> status.equals("done")),
            any(String.class));
        verify(chatCancellationService).deregister(eq(conversationId), any());
    }

    @Test
    public void testPrepareAndSubmitAsyncFailure() throws Exception {
        UUID conversationId = UUID.randomUUID();
        String query = "Async Query";
        String pbName = "PB1";

        Map<String, Object> settings = new HashMap<>();
        settings.put(ConversationSetting.MODEL.getValue(), "gemini-3.1-flash-lite");
        Conversation conv =
            new Conversation(conversationId, null, "My Chat", settings, null, null, List.of());
        when(chatConversationService.verifyOwnership(conversationId, false)).thenReturn(conv);

        Playbook pb = new Playbook(pbName, "Title", "Desc", "PB1 Template", List.of(), false,
            Instant.now(), Instant.now(), false);
        when(playbookService.getPlaybook(pbName)).thenReturn(Optional.of(pb));

        when(ragService.generateAgenticAnswer(any(AgenticRequest.class), any(Consumer.class)))
            .thenThrow(new RuntimeException("LLM failure"));

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);

        UUID assistantMessageId =
            chatMessageService.prepareAndSubmitAsync(conversationId, query, pbName);
        assertThat(assistantMessageId).isNotNull();

        verify(taskExecutor).submit(runnableCaptor.capture());
        Runnable capturedTask = runnableCaptor.getValue();

        // Run the captured runnable synchronously
        capturedTask.run();

        verify(chatCancellationService).register(argThat(id -> id.equals(conversationId)),
            any(Thread.class));
        verify(messageRepository).updateContentAndStatus(
            argThat(id -> id.equals(assistantMessageId)),
            argThat(content -> content.contains("could not complete this response")
                && !content.contains("LLM failure")),
            any(List.class), any(List.class), any(Integer.class), any(Integer.class),
            any(Integer.class), any(Integer.class), any(Integer.class),
            argThat(status -> status.equals("error")), any(String.class));
        verify(chatCancellationService).deregister(eq(conversationId), any());
    }

    /**
     * A user Stop and a provider failure both wrote status "error", so nothing downstream could
     * tell them apart — which is how a run that died on an HTTP 429 came to be captioned
     * "[Generation stopped by user]" in the chat.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void testPrepareAndSubmitAsyncCancellationPersistsCancelledStatus() throws Exception {
        UUID conversationId = UUID.randomUUID();
        String pbName = "PB1";

        Map<String, Object> settings = new HashMap<>();
        settings.put(ConversationSetting.MODEL.getValue(), "gemini-3.1-flash-lite");
        Conversation conv =
            new Conversation(conversationId, null, "My Chat", settings, null, null, List.of());
        when(chatConversationService.verifyOwnership(conversationId, false)).thenReturn(conv);

        Playbook pb = new Playbook(pbName, "Title", "Desc", "PB1 Template", List.of(), false,
            Instant.now(), Instant.now(), false);
        when(playbookService.getPlaybook(pbName)).thenReturn(Optional.of(pb));

        when(ragService.generateAgenticAnswer(any(AgenticRequest.class), any(Consumer.class)))
            .thenThrow(new CancellationException("stopped by user"));

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        UUID assistantMessageId =
            chatMessageService.prepareAndSubmitAsync(conversationId, "Async Query", pbName);
        verify(taskExecutor).submit(runnableCaptor.capture());
        runnableCaptor.getValue().run();

        verify(messageRepository).updateContentAndStatus(
            argThat(id -> id.equals(assistantMessageId)),
            argThat(content -> content.contains("Generation stopped by user")), any(List.class),
            any(List.class), any(Integer.class), any(Integer.class), any(Integer.class),
            any(Integer.class), any(Integer.class), argThat(status -> status.equals("cancelled")),
            any());
    }

    @Test
    public void testGetMessageStatus() {
        UUID messageId = UUID.randomUUID();
        Message mockMsg = new Message(messageId, UUID.randomUUID(), "assistant", "content", null,
            List.of(), List.of(), Instant.now(), 0, 0, 0, 0, 0, "done");
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(mockMsg));

        Optional<Message> status = chatMessageService.getMessageStatus(messageId);
        assertThat(status).isPresent();
        assertThat(status.get().status()).isEqualTo("done");
    }

    @Test
    public void testGetPriorHistoryExcludesGeneratingAndEmpty() {
        UUID conversationId = UUID.randomUUID();

        Message userMsg = new Message(UUID.randomUUID(), conversationId, "user", "Hello", List.of(),
            List.of(), Instant.now());
        Message generatingMsg = new Message(UUID.randomUUID(), conversationId, "assistant", "",
            null, List.of(), List.of(), Instant.now(), null, null, null, null, null, "generating");
        Message doneMsg = new Message(UUID.randomUUID(), conversationId, "assistant", "World", null,
            List.of(), List.of(), Instant.now(), null, null, null, null, null, "done");

        when(messageRepository.findByConversationId(conversationId, 100, 0))
            .thenReturn(List.of(userMsg, generatingMsg, doneMsg));

        // Stub playbook service for the mock playbook pb Name "PB1"
        Playbook pb = new Playbook("PB1", "Title", "Desc", "PB1 Template", List.of(), false,
            Instant.now(), Instant.now(), false);
        when(playbookService.getPlaybook("PB1")).thenReturn(Optional.of(pb));
        Map<String, Object> settings = new HashMap<>();
        settings.put(ConversationSetting.MODEL.getValue(), "gemini-3.1-flash-lite");
        Conversation conv =
            new Conversation(conversationId, null, "My Chat", settings, null, null, List.of());
        when(chatConversationService.verifyOwnership(conversationId, false)).thenReturn(conv);

        ChatMessageService.PreparedChat prep =
            chatMessageService.prepare(conversationId, "Query", "PB1");
        assertThat(prep.history()).hasSize(2);
        assertThat(prep.history().get(0).role()).isEqualTo("user");
        assertThat(prep.history().get(1).role()).isEqualTo("assistant");
        assertThat(prep.history().get(1).content()).isEqualTo("World");
    }

    /**
     * Asserts the final SSE line carries the MNT-17 named-JSON {@code [DONE]} payload and returns
     * it parsed. Fails (not just returns) if the line is not {@code [DONE] {…valid JSON…}}.
     */
    private static JsonNode parseDoneEvent(String line) {
        assertThat(line).startsWith("[DONE] {");
        try {
            return new ObjectMapper().readTree(line.substring("[DONE] ".length()));
        } catch (Exception e) {
            throw new AssertionError("[DONE] line is not valid JSON: " + line, e);
        }
    }
}
