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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doThrow;
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
import de.palsoftware.yvoke.chat.orchestration.OrchestrationService;
import de.palsoftware.yvoke.chat.orchestration.OrchestrationService.OrchestrationResult;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.AnnotatedElementUtils;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.security.access.AccessDeniedException;


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

        chatMessageService = new ChatMessageService(messageRepository,
            mock(ConversationRepository.class), chatConversationService, ragService,
            retrievalLogRepository, playbookService, systemPromptService, transactionManager,
            taskExecutor, chatCancellationService, mock(OrchestrationService.class));
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

    /**
     * The conversation title is written exactly once, on the opening question, and the count that
     * decides it is taken AFTER the user row has been saved — {@code countByConversationId} counts
     * every row in the conversation, so 1 means "the row I just wrote is the only one there is".
     * Loosen the comparison to {@code >= 1} (or move the count above the save and compare against
     * 0) and every later question silently renames the conversation: the sidebar entry a user has
     * been navigating by for weeks becomes whatever they last typed, and the original title is gone
     * — {@code updateTitle} is a plain UPDATE, so there is no undo and no history to recover it
     * from.
     *
     * <p>
     * The second half of the rule is which string is used. {@code autoTitle} receives
     * {@code userContent}, not the {@code modelContent} the same method has just assembled, and
     * those two differ precisely on a playbook's first use in a conversation — which IS the first
     * message. Pass the wrong one and every new conversation is titled with the opening 77
     * characters of a playbook template, so the whole sidebar collapses into rows of identical
     * text. That is why the playbook stubbed here deliberately carries a non-blank template.
     *
     * <p>
     * Nothing else in the suite reaches this branch: every other {@code prepare()} test leaves
     * {@code countByConversationId} unstubbed, the mock returns 0, and {@code autoTitle} is never
     * called — so the titling path is exercised only here.
     */
    @Test
    public void theTitleIsWrittenOnlyForTheVeryFirstMessageOfAConversation() {
        UUID firstTurn = UUID.randomUUID();
        UUID laterTurn = UUID.randomUUID();
        Map<String, Object> settings = new HashMap<>();
        settings.put(ConversationSetting.MODEL.getValue(), "gemini-3.1-flash-lite");
        when(chatConversationService.verifyOwnership(firstTurn, false)).thenReturn(
            new Conversation(firstTurn, null, "New Conversation", settings, null, null, List.of()));
        when(chatConversationService.verifyOwnership(laterTurn, false)).thenReturn(
            new Conversation(laterTurn, null, "New Conversation", settings, null, null, List.of()));

        // A non-blank template, so modelContent ("PB Template\n\n---\n\n…") differs from the raw
        // question and the title cannot accidentally be taken from the prompt sent to the model.
        Playbook pb = new Playbook("PB1", "Title", "Desc", "PB Template", List.of(), false,
            Instant.now(), Instant.now(), false);
        when(playbookService.getPlaybook("PB1")).thenReturn(Optional.of(pb));

        // The count is read after the user row is saved: 1 on the opening question, more later.
        when(messageRepository.countByConversationId(firstTurn)).thenReturn(1L);
        when(messageRepository.countByConversationId(laterTurn)).thenReturn(2L);

        chatMessageService.prepare(firstTurn, "What is a UNSAccount?", "PB1");
        chatMessageService.prepare(laterTurn, "And how is it provisioned?", "PB1");

        verify(chatConversationService).autoTitle(firstTurn, "What is a UNSAccount?");
        verify(chatConversationService, never()).autoTitle(eq(laterTurn), any());
    }

    @Test
    public void testPrepareRejectsUnauthorizedBeforeStreaming() {
        UUID conversationId = UUID.randomUUID();
        when(chatConversationService.verifyOwnership(conversationId, false))
            .thenThrow(new AccessDeniedException("Access denied"));

        Playbook pb = new Playbook("test-playbook", "Title", "Desc", "", List.of(), false,
            Instant.now(), Instant.now(), false);
        when(playbookService.getPlaybook("test-playbook")).thenReturn(Optional.of(pb));

        Assertions.assertThrows(AccessDeniedException.class,
            () -> chatMessageService.prepare(conversationId, "User query", "test-playbook"));

        // Authorization is enforced during the synchronous prepare() phase; generation must never
        // begin for an unauthorized request.
        verify(ragService, never()).generateAgenticAnswer(any(), any());
    }

    @Test
    public void testChatDisabledThrowsException() {
        ChatProperties disabledProperties =
            new ChatProperties(false, List.of("gemini-3.1-flash-lite"), true);

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
            taskExecutor, chatCancellationService, mock(OrchestrationService.class));

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

    /**
     * A Stop is not a failure, and {@code stream()} is the one chat path that separates the two by
     * exception type rather than by a persisted status: {@link ChatCancellationService} interrupts
     * the generating thread, the provider surfaces that interrupt as a
     * {@link CancellationException}, and the rethrow is what tells {@code ChatSseController} to
     * close the emitter and leave the turn unpersisted — the browser has already rendered its own
     * "[Generation stopped by user]" notice for the aborted fetch.
     *
     * <p>
     * Let a cancellation fall one line further into {@code catch (Exception e)} and three things go
     * wrong at once for a user who did nothing but press Stop. An assistant row is written with the
     * SEC-17 system-error notice as its body and status {@code error}, so the chat claims the
     * assistant crashed. That notice plus a terminal {@code [DONE]} carrying all-zero token counts
     * is pushed down the still-open SSE connection, on top of the client's own stop notice. And
     * because the fabricated row is neither {@code generating} nor blank, {@code getPriorHistory}
     * replays it to the model as genuine assistant history on every following turn.
     *
     * <p>
     * Nothing else would notice: {@code testStreamHandlesExceptionAndPersistsErrorMessage} throws a
     * plain {@link RuntimeException}, which is exactly what a cancellation becomes once the
     * dedicated catch is gone, so it stays green; and the cancellation coverage that does exist
     * ({@code testPrepareAndSubmitAsyncCancellationPersistsCancelledStatus}) exercises
     * {@code prepareAndSubmitAsync}, whose cancel handling is a separate block that persists a
     * {@code cancelled} row instead of rethrowing.
     */
    @Test
    public void aCancelledStreamingTurnPersistsNothingRatherThanAFailedAnswer() {
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

        // Partial text reaches the client, then the user presses Stop: the thread interrupt
        // surfaces out of the provider as a CancellationException mid-generation.
        doAnswer(inv -> {
            Consumer<String> ragSink = inv.getArgument(1);
            ragSink.accept("Half an answer");
            throw new CancellationException("stopped by user");
        }).when(ragService).generateAgenticAnswer(any(), any());

        List<String> results = new ArrayList<>();
        ChatMessageService.PreparedChat prepared =
            chatMessageService.prepare(conversationId, "User query", "test-playbook");

        Assertions.assertThrows(CancellationException.class,
            () -> chatMessageService.stream(prepared, assistantMessageId, results::add));

        // Only the user message written by prepare() exists — no assistant row was persisted at
        // all, so the stopped turn cannot come back as history or as a fake failed answer.
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository, times(1)).save(messageCaptor.capture());
        assertThat(messageCaptor.getValue().role()).isEqualTo("user");

        // The client saw the partial text and nothing else: no error notice, no terminal [DONE].
        assertThat(results).containsExactly("Half an answer");
        assertThat(results).noneMatch(s -> s.startsWith("[DONE]"));
        assertThat(results).noneMatch(s -> s.contains("could not complete this response"));
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

    /**
     * {@code resolveThinkingLevel} is the last line of defence for a value that is then handed
     * straight to the provider. Conversations created before the setting existed have no
     * {@code thinking-level} key at all, and the settings map is free-form jsonb that any
     * {@code /settings} post can leave holding a blank string — so both the missing and the blank
     * case are reachable in production, not theoretical.
     *
     * <p>
     * Drop the fallback and {@code AgenticRequest.thinkingLevel} carries {@code null} (or
     * {@code "   "}) into the Gemini call: the thinking budget is resolved from that string, so the
     * turn either fails outright at the provider or silently runs at whatever the unmapped default
     * is. Neither shows up as a settings problem — the user sees a generic system error, or an
     * answer that is quietly less considered than every other conversation's, with no way to tell
     * from the UI, which shows the same dropdown either way.
     *
     * <p>
     * The only existing coverage, {@code testThinkingLevelConfiguredAndPassedToRag}, sets
     * {@code thinking-level} to "high" explicitly, so it exercises the value-present branch and
     * stays green with the fallback deleted. The generateSync leg below is deliberate too: the
     * default has to survive all the way into the request the provider receives, not merely sit in
     * the {@code PreparedChat} record.
     */
    @Test
    public void aConversationWithNoThinkingLevelFallsBackToMedium() {
        UUID unsetLevel = UUID.randomUUID();
        UUID blankLevel = UUID.randomUUID();
        UUID assistantMessageId = UUID.randomUUID();

        // A conversation created before thinking-level existed: the key is simply not there.
        Map<String, Object> onlyModel = new HashMap<>();
        onlyModel.put(ConversationSetting.MODEL.getValue(), "gemini-3.1-flash-lite");
        when(chatConversationService.verifyOwnership(unsetLevel, false)).thenReturn(
            new Conversation(unsetLevel, null, "My Chat", onlyModel, null, null, List.of()));

        // …and the same conversation after a settings write left the key present but blank.
        Map<String, Object> blankSetting = new HashMap<>();
        blankSetting.put(ConversationSetting.MODEL.getValue(), "gemini-3.1-flash-lite");
        blankSetting.put(ConversationSetting.THINKING_LEVEL.getValue(), "   ");
        when(chatConversationService.verifyOwnership(blankLevel, false)).thenReturn(
            new Conversation(blankLevel, null, "My Chat", blankSetting, null, null, List.of()));

        Playbook pb = new Playbook("test-playbook", "Title", "Desc", "", List.of(), false,
            Instant.now(), Instant.now(), false);
        when(playbookService.getPlaybook("test-playbook")).thenReturn(Optional.of(pb));
        when(ragService.generateAgenticAnswer(any(), any()))
            .thenReturn(new RagResult(List.of(UUID.randomUUID()), List.of(UUID.randomUUID())));

        ChatMessageService.PreparedChat missing =
            chatMessageService.prepare(unsetLevel, "User query", "test-playbook");
        assertThat(missing.thinkingLevel()).isEqualTo("medium");

        ChatMessageService.PreparedChat blank =
            chatMessageService.prepare(blankLevel, "User query", "test-playbook");
        assertThat(blank.thinkingLevel()).isEqualTo("medium");

        // The default must reach the provider request, not just the prepared record.
        chatMessageService.generateSync(missing, assistantMessageId);
        verify(ragService).generateAgenticAnswer(
            argThat((AgenticRequest req) -> "medium".equals(req.thinkingLevel())), any());
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

    /**
     * S3.13: what the sink sees is EXACTLY what gets persisted. {@code stream()}'s wrapped sink
     * appends every token to the same buffer it forwards to the browser, so the tool-call banner,
     * the {@code <clarifying-question>} XML and the iteration-cap warning all land in
     * {@code messages.content} verbatim — no filtering, no stripping.
     *
     * <p>
     * The tempting edit is to filter the "chrome" out before persisting (it is, after all, not part
     * of the answer). Doing so breaks three things at once, none of which raises an error. A
     * reloaded thread and every rehydrated desktop copy would render a bare answer with no sign a
     * tool ever ran, so a user cannot tell a retrieved answer from an invented one. {@code thread
     * .js}'s {@code wrapToolCalls} needs the 🔧 marker PRESENT in the stored text in order to hide
     * it — strip it server-side and the client has nothing to match, which is the exact failure
     * mode CLAUDE.md records as leaking half a tool call into the visible answer. And the cap
     * warning is the only record anywhere that an answer was truncated by the iteration limit
     * rather than finished.
     *
     * <p>
     * Nothing in the suite looks at the persisted assistant content on this path:
     * {@code testSendMessageAgenticMode} asserts the banner reaches the SSE SINK,
     * {@code RagServiceAgenticTest} asserts it is absent from the MODEL-facing history, and
     * {@code testSendMessageWithPlaybook} captures the saved messages but inspects only the user
     * row. The three strings below are the literal forms production emits (RagService's cap notice
     * is reproduced verbatim), so a filter tuned to any one of them is caught.
     */
    @Test
    public void theToolBannerClarifyingXmlAndCapWarningAllSurviveIntoThePersistedAnswer() {
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

        String banner = "🔧 *Calling tool:* search_corpus";
        String clarifying = "<clarifying-question>\n  <question>Which version?</question>\n"
            + "</clarifying-question>";
        String capWarning = "\n\n⚠️ *[System: The maximum loop count of 8 iterations was hit. The "
            + "response was cut off to prevent an infinite loop.]*";

        RagResult ragResult = new RagResult(List.of(), List.of(), null, List.of(), 1, 2, 3, 0, 0);
        doAnswer(inv -> {
            Consumer<String> ragSink = inv.getArgument(1);
            ragSink.accept(banner);
            ragSink.accept("Answer text.");
            ragSink.accept(clarifying);
            ragSink.accept(capWarning);
            return ragResult;
        }).when(ragService).generateAgenticAnswer(any(AgenticRequest.class), any());

        ChatMessageService.PreparedChat prepared =
            chatMessageService.prepare(conversationId, "User query", "test-playbook");
        chatMessageService.stream(prepared, assistantMessageId, token -> {
        });

        ArgumentCaptor<Message> saved = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository, times(2)).save(saved.capture());
        Message assistant = saved.getAllValues().get(1);

        assertThat(assistant.role()).isEqualTo("assistant");
        assertThat(assistant.content())
            .as("the persisted answer must be byte-identical to what the user was streamed")
            .contains(banner).contains("Answer text.").contains(clarifying).contains(capWarning);
    }

    /**
     * {@code prepare()} is a sequence, not a bag of independent steps: it validates the playbook
     * BEFORE it writes the user row, so a rejected request leaves the conversation exactly as it
     * found it. Reorder those two — the natural-looking cleanup that groups the two
     * {@code playbookService.getPlaybook} lookups together, since {@code saveUserMessage} performs
     * one of its own — and a request that ends in a 400 still leaves a persisted user message
     * behind.
     *
     * <p>
     * That orphan is not cosmetic. It is replayed as history into every subsequent turn by
     * {@code getPriorHistory}, so the model keeps answering a question the user was told was
     * rejected; if it is the conversation's first row it also fires {@code autoTitle}, naming the
     * conversation after the failed send; and no assistant row will ever follow it, so the thread
     * renders a question hanging with no answer. The playbook catalogue is DB content that admins
     * edit and delete, so "the selected playbook no longer exists" is an ordinary event, not an
     * attack — a user with a stale page reproduces it on every send.
     *
     * <p>
     * {@code testPrepareThrowsIfPlaybookInvalid} asserts only the exception message, which the
     * reordered version still produces identically, so it cannot see this.
     */
    @Test
    public void anInvalidPlaybookIsRejectedBeforeTheQuestionIsPersisted() {
        UUID conversationId = UUID.randomUUID();
        Map<String, Object> settings = new HashMap<>();
        settings.put(ConversationSetting.MODEL.getValue(), "gemini-3.1-flash-lite");
        // Owned and otherwise perfectly valid: the ONLY thing wrong is the playbook, so a failure
        // here can only mean the validation moved.
        when(chatConversationService.verifyOwnership(conversationId, false)).thenReturn(
            new Conversation(conversationId, null, "My Chat", settings, null, null, List.of()));
        when(playbookService.getPlaybook("deleted-playbook")).thenReturn(Optional.empty());

        Assertions.assertThrows(IllegalArgumentException.class,
            () -> chatMessageService.prepare(conversationId, "User query", "deleted-playbook"));

        verify(messageRepository, never()).save(any());
        verify(chatConversationService, never()).autoTitle(any(), any());
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
     * S3/S2.6. No endpoint can REMOVE a settings key: clearing the orchestrator profile writes the
     * empty string, so {@code ""} — not absence — is what "multi-agent off" looks like in
     * {@code conversations.settings} for every conversation that ever had a profile. The
     * {@code isBlank()} half of the routing guard is the only thing that reads it that way.
     *
     * <p>
     * Relax it to a bare null check and those conversations run the orchestrator forever after. The
     * user sees no switch, no banner and no error — just a normal answer — while the turn takes the
     * orchestrated path, which is a different product: the selected playbook's template is never
     * prepended (that path saves the user message with a null playbook on purpose), the prompt is
     * the orchestrator's, and a multi-agent run costs a multiple of a single turn. Every cost view
     * then attributes that spend to a MAS profile the user had switched off.
     *
     * <p>
     * Nothing observes the off side today: {@code ChatControllerTest#updateOrchestratorProfile_
     * emptyName_clearsProfile} pins that clearing WRITES {@code ""}, and every
     * {@code prepareAndSubmitAsync} test here uses a conversation with no
     * {@code orchestrator-profile} key at all, so the guard's second clause never executes. The
     * assertions below are on outcomes rather than on routing arguments: the playbook template
     * really was prepended into the model query, and the persisted rows really do carry the
     * playbook, neither of which the orchestrated path produces.
     */
    @Test
    public void anEmptyOrchestratorProfileMeansOffAndStillRunsPlainRag() {
        UUID conversationId = UUID.randomUUID();
        Map<String, Object> settings = new HashMap<>();
        settings.put(ConversationSetting.MODEL.getValue(), "gemini-3.1-flash-lite");
        // Exactly what clearing the profile writes: the key stays, the value is the empty string.
        settings.put(ConversationSetting.ORCHESTRATOR_PROFILE.getValue(), "");
        Conversation conv =
            new Conversation(conversationId, null, "My Chat", settings, null, null, List.of());
        when(chatConversationService.verifyOwnership(conversationId, false)).thenReturn(conv);

        Playbook pb = new Playbook("PB1", "Title", "Desc", "PB1 Template", List.of(), false,
            Instant.now(), Instant.now(), false);
        when(playbookService.getPlaybook("PB1")).thenReturn(Optional.of(pb));

        OrchestrationService orchestrationService = mock(OrchestrationService.class);
        ChatMessageService service = new ChatMessageService(messageRepository,
            mock(ConversationRepository.class), chatConversationService, ragService,
            retrievalLogRepository, playbookService, systemPromptService, transactionManager,
            taskExecutor, chatCancellationService, orchestrationService);

        RagResult ragResult =
            new RagResult(List.of(UUID.randomUUID()), List.of(), null, List.of(), 11, 22, 33, 4, 5);
        doAnswer(inv -> {
            Consumer<String> sink = inv.getArgument(1);
            sink.accept("Plain answer");
            return ragResult;
        }).when(ragService).generateAgenticAnswer(any(AgenticRequest.class), any());

        UUID assistantMessageId =
            service.prepareAndSubmitAsync(conversationId, "Async Query", "PB1");

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskExecutor).submit(runnableCaptor.capture());
        runnableCaptor.getValue().run();

        // "" is OFF: the orchestrator was never consulted, not even to resolve the profile.
        verifyNoInteractions(orchestrationService);

        // ...and the plain path really ran, with the user's playbook template prepended — which the
        // orchestrated path deliberately never does.
        verify(ragService).generateAgenticAnswer(
            argThat(
                (AgenticRequest req) -> "PB1 Template\n\n---\n\nAsync Query".equals(req.query())),
            any());

        ArgumentCaptor<Message> saved = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(0).role()).isEqualTo("user");
        assertThat(saved.getAllValues().get(0).playbook())
            .as("the question is filed under the playbook the user chose").isEqualTo("PB1");
        assertThat(saved.getAllValues().get(1).role()).isEqualTo("assistant");
        assertThat(saved.getAllValues().get(1).status()).isEqualTo("generating");
        assertThat(saved.getAllValues().get(1).playbook()).isEqualTo("PB1");

        verify(messageRepository).updateContentAndStatus(eq(assistantMessageId), eq("Plain answer"),
            any(), any(), any(), any(), any(), any(), any(), eq("done"), any());
    }

    /**
     * The orchestrator profile is a per-conversation setting and this branch is the ONLY code that
     * acts on it: lose it and a conversation the user explicitly switched into multi-agent mode
     * quietly answers with plain single-agent RAG. Nothing throws, nothing logs a downgrade — the
     * answer simply arrives — so the regression is invisible in exactly the mode it destroys.
     *
     * <p>
     * The damage is not only "a different answer". The orchestrated path calls
     * {@code saveUserMessage} with a null playbook, so it deliberately does NOT prepend the
     * playbook template the plain path would; it mints the {@code agentRunId} that
     * {@code agent_runs} / {@code agent_steps} and the cost dashboard's profile attribution hang
     * off; and it lands the orchestrator's own token totals on the message. Falling back therefore
     * produces an answer with no agent trace, no MAS cost attribution, and a prompt the user never
     * configured — while the chat looks entirely normal.
     *
     * <p>
     * No existing test covers the routing. {@code ConversationAgentRunContextIT} invokes
     * {@code orchestrationService.runOrchestration} directly rather than through this service, and
     * every {@code prepareAndSubmitAsync} test in this file uses a conversation with no
     * {@code orchestrator-profile} setting, so all of them exercise the plain branch and stay green
     * if the orchestrated one disappears.
     */
    @Test
    public void aConversationWithAnOrchestratorProfileRunsTheOrchestratorAndNeverPlainRag() {
        UUID conversationId = UUID.randomUUID();
        Map<String, Object> settings = new HashMap<>();
        settings.put(ConversationSetting.MODEL.getValue(), "gemini-3.1-flash-lite");
        settings.put(ConversationSetting.ORCHESTRATOR_PROFILE.getValue(), "oim");
        Conversation conv =
            new Conversation(conversationId, null, "My Chat", settings, null, null, List.of());
        when(chatConversationService.verifyOwnership(conversationId, false)).thenReturn(conv);

        // A valid playbook is stubbed on purpose although the orchestrated path never looks one up:
        // a regression that drops the branch must then fail on having run plain RAG, not on
        // playbook validation inside prepare().
        Playbook pb = new Playbook("PB1", "Title", "Desc", "PB1 Template", List.of(), false,
            Instant.now(), Instant.now(), false);
        when(playbookService.getPlaybook("PB1")).thenReturn(Optional.of(pb));

        OrchestrationService orchestrationService = mock(OrchestrationService.class);
        ChatMessageService service = new ChatMessageService(messageRepository,
            mock(ConversationRepository.class), chatConversationService, ragService,
            retrievalLogRepository, playbookService, systemPromptService, transactionManager,
            taskExecutor, chatCancellationService, orchestrationService);

        UUID searchId = UUID.randomUUID();
        OrchestrationResult result = new OrchestrationResult("Orchestrated answer",
            List.of(UUID.randomUUID()), List.of(searchId), 11, 22, 33, 4, 5, "done");
        when(orchestrationService.runOrchestration(any(), any(), any(), any(), any(), any()))
            .thenReturn(result);

        UUID assistantMessageId =
            service.prepareAndSubmitAsync(conversationId, "Async Query", "PB1");

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskExecutor).submit(runnableCaptor.capture());
        runnableCaptor.getValue().run();

        // The orchestrator ran for this conversation's configured profile, on the user's raw
        // question with no playbook template prepended.
        verify(orchestrationService).runOrchestration(eq(conversationId), any(), eq("Async Query"),
            any(), any(), eq("oim"));
        // Single-agent RAG was never reached. never() is sound here because taskExecutor is a mock
        // and the submitted task is run inline above — nothing is pending on a real executor.
        verify(ragService, never()).generateAgenticAnswer(any(), any());

        verify(messageRepository).updateContentAndStatus(eq(assistantMessageId),
            eq("Orchestrated answer"), any(), any(), any(), any(), any(), any(), any(), eq("done"),
            any());
        verify(retrievalLogRepository).updateMessageId(searchId, assistantMessageId);
    }

    /**
     * S3.7, the half no test can currently see: the cancel catch clears the interrupt BEFORE it
     * writes.
     *
     * <p>
     * Cancellation reaches this service as a thread interrupt — {@code ChatCancellationService}
     * interrupts the generating thread and the in-flight provider call unwinds as a
     * {@link java.util.concurrent.CancellationException} — and a JDBC write issued on a thread
     * whose interrupt flag is still set can be refused outright by the driver or the pool. Delete
     * {@code Thread.interrupted()} (the obvious "why is this reading a flag it throws away?"
     * cleanup) and the status write is silently lost: the assistant row stays {@code generating}
     * FOREVER. The browser poll never reaches a terminal branch, the loader spins until the tab is
     * closed, and the row is eventually rewritten by the startup sweep as an interrupted-by-restart
     * error — so a deliberate Stop is finally reported to the user as a system fault, hours later.
     *
     * <p>
     * All four {@code Thread.interrupted()} calls in this class are unasserted today. The sibling
     * {@code testPrepareAndSubmitAsyncCancellationPersistsCancelledStatus} throws
     * {@code CancellationException} directly from a stub that never sets the flag, so the clear is
     * dead code from its point of view and deleting it keeps that test green. The only place the
     * worker is genuinely interrupted is
     * {@code ChatAsyncControllerIT#testStopCancelsAsyncGeneration}, where whether the write
     * actually fails depends on pgjdbc/Hikari socket timing — a flake, not a guard. Asserting the
     * flag STATE at the moment of the write is deterministic and needs no DB.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void aCancelledAsyncTurnWritesItsCancelledStatusWithTheInterruptFlagCleared() {
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

        // Reproduces the real shape of a Stop: the generating thread is interrupted, and the call
        // it was blocked in unwinds as a CancellationException — in that order, on that thread.
        when(ragService.generateAgenticAnswer(any(AgenticRequest.class), any(Consumer.class)))
            .thenAnswer(inv -> {
                Thread.currentThread().interrupt();
                throw new CancellationException("stopped by user");
            });

        AtomicBoolean interruptedDuringWrite = new AtomicBoolean(true);
        doAnswer(inv -> {
            interruptedDuringWrite.set(Thread.currentThread().isInterrupted());
            return null;
        }).when(messageRepository).updateContentAndStatus(any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any());

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        UUID assistantMessageId =
            chatMessageService.prepareAndSubmitAsync(conversationId, "Async Query", pbName);
        verify(taskExecutor).submit(runnableCaptor.capture());

        try {
            runnableCaptor.getValue().run();

            assertThat(interruptedDuringWrite)
                .as("the cancelled-status write must run on a thread whose interrupt flag has been "
                    + "cleared, or the JDBC update can be refused and the row stays 'generating'")
                .isFalse();
            verify(messageRepository).updateContentAndStatus(eq(assistantMessageId), any(), any(),
                any(), any(), any(), any(), any(), any(), eq("cancelled"), any());
        } finally {
            // This test deliberately interrupts the JUnit thread; leave it clean for the next test.
            Thread.interrupted();
        }
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

    /**
     * Cancel and failure were the same persisted status until {@code cancelled} was introduced, and
     * the orchestrated path is the second, easily-forgotten place that distinction has to be made:
     * {@code testPrepareAndSubmitAsyncCancellationPersistsCancelledStatus} pins the plain RAG
     * branch of {@code prepareAndSubmitAsync}, and nothing anywhere reaches
     * {@code submitOrchestratedAsync}'s own cancel block — {@code ConversationAgentRunContextIT}
     * calls {@code orchestrationService.runOrchestration} directly and never goes through this
     * service method.
     *
     * <p>
     * Persist {@code "error"} here and {@code ChatAsyncController} answers the browser poll with
     * {@code status: "error"} for a run the user deliberately stopped — the controller reports
     * {@code error} and {@code cancelled} as themselves and maps everything else to {@code done},
     * so the string written here IS the client contract. The user then gets the generic
     * system-error treatment wrapped around the server-authored stop marker: exactly the inversion
     * of the incident that created {@link ChatMessageService#STATUS_CANCELLED}, where a run killed
     * by an HTTP 429 was captioned "[Generation stopped by user]". Cost and agent-run reporting
     * also lose the ability to tell abandoned multi-agent runs from genuine faults.
     *
     * <p>
     * The content matters as much as the status: unlike the plain async path, the orchestrated one
     * deliberately discards the partial output (the per-agent trace lives in {@code agent_steps},
     * not in the message) and writes the stop marker alone, so a half-finished multi-agent draft is
     * never presented in the bubble as the final answer.
     */
    @Test
    public void anOrchestratedRunStoppedByTheUserIsPersistedAsCancelledNotError() {
        UUID conversationId = UUID.randomUUID();
        Map<String, Object> settings = new HashMap<>();
        settings.put(ConversationSetting.MODEL.getValue(), "gemini-3.1-flash-lite");
        settings.put(ConversationSetting.ORCHESTRATOR_PROFILE.getValue(), "oim");
        Conversation conv =
            new Conversation(conversationId, null, "My Chat", settings, null, null, List.of());
        when(chatConversationService.verifyOwnership(conversationId, false)).thenReturn(conv);

        OrchestrationService orchestrationService = mock(OrchestrationService.class);
        ChatMessageService service = new ChatMessageService(messageRepository,
            mock(ConversationRepository.class), chatConversationService, ragService,
            retrievalLogRepository, playbookService, systemPromptService, transactionManager,
            taskExecutor, chatCancellationService, orchestrationService);

        when(orchestrationService.runOrchestration(any(), any(), any(), any(), any(), any()))
            .thenThrow(new CancellationException("stopped by user"));

        UUID assistantMessageId =
            service.prepareAndSubmitAsync(conversationId, "Async Query", "PB1");

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskExecutor).submit(runnableCaptor.capture());
        runnableCaptor.getValue().run();

        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> statusCaptor = ArgumentCaptor.forClass(String.class);
        verify(messageRepository).updateContentAndStatus(eq(assistantMessageId),
            contentCaptor.capture(), any(), any(), any(), any(), any(), any(), any(),
            statusCaptor.capture(), any());

        // Asserted as the literal, not as STATUS_CANCELLED: this exact string is what
        // ChatAsyncController and the browser poll key off, so changing the constant's value is
        // itself a client-visible change and must fail here.
        assertThat(statusCaptor.getValue()).isEqualTo("cancelled");
        assertThat(contentCaptor.getValue()).isEqualTo("*[Generation stopped by user]*");
        verify(chatCancellationService).deregister(eq(conversationId), any());
    }

    /**
     * A reviewer-rejected answer is still DELIVERED. {@code OrchestrationService} ends a run that
     * ran out of review rounds with {@code status = "delivered_flagged"} and appends a flag note to
     * the content, and {@code OrchestrationResult} carries that status out of the class — but this
     * service deliberately drops it and writes {@code messages.status = "done"} for every
     * successful orchestration, flagged or not. The user's only signal is the note inside the
     * content; the verdict itself lives in {@code agent_runs} for the admin trace.
     *
     * <p>
     * Both halves are load-bearing and both are one edit away. Propagating {@code result.status()}
     * into {@code messages.status} — the obvious "why is this field being thrown away?" fix — puts
     * a value in the column that {@code ChatAsyncController}'s poll does not know: its terminal
     * branch reports {@code error} and {@code cancelled} as themselves and maps everything else to
     * {@code done}, a deliberately closed vocabulary, so the browser would keep working while the
     * persisted status quietly stopped meaning what every other reader assumes. The far worse edit
     * is treating a non-{@code "done"} orchestration result as a failure and routing it to the
     * catch block: the user then gets the generic system-error notice instead of a complete answer
     * that a reviewer merely flagged — a delivered answer replaced by "the assistant could not
     * complete this response", with the real answer discarded.
     *
     * <p>
     * The content assertion is the other half of the contract: the flag note is appended by
     * {@code OrchestrationService}, not by this service, so persisting {@code result.content()}
     * verbatim is what puts the caveat in front of the user at all. Strip or rewrite it here and
     * the flagged answer becomes indistinguishable from an approved one.
     *
     * <p>
     * Nothing covers this today: {@code OrchestrationServiceTest.rejectedTwice_deliversFlagged}
     * stops at the {@code OrchestrationResult} and never reaches persistence, and every
     * orchestrated test in this file stubs either an approved run, a cancellation or a throw — the
     * flagged-but-successful path is the one combination none of them produce.
     */
    @Test
    public void aFlaggedOrchestrationResultStillPersistsTheMessageAsDone() {
        UUID conversationId = UUID.randomUUID();
        Map<String, Object> settings = new HashMap<>();
        settings.put(ConversationSetting.MODEL.getValue(), "gemini-3.1-flash-lite");
        settings.put(ConversationSetting.ORCHESTRATOR_PROFILE.getValue(), "oim");
        Conversation conv =
            new Conversation(conversationId, null, "My Chat", settings, null, null, List.of());
        when(chatConversationService.verifyOwnership(conversationId, false)).thenReturn(conv);

        OrchestrationService orchestrationService = mock(OrchestrationService.class);
        ChatMessageService service = new ChatMessageService(messageRepository,
            mock(ConversationRepository.class), chatConversationService, ragService,
            retrievalLogRepository, playbookService, systemPromptService, transactionManager,
            taskExecutor, chatCancellationService, orchestrationService);

        String flaggedContent = "The kit ships 9.3.1.\n\n> ⚠️ Delivered with reservations.";
        OrchestrationResult flagged = new OrchestrationResult(flaggedContent, List.of(), List.of(),
            11, 22, 33, 4, 5, "delivered_flagged");
        when(orchestrationService.runOrchestration(any(), any(), any(), any(), any(), any()))
            .thenReturn(flagged);

        UUID assistantMessageId =
            service.prepareAndSubmitAsync(conversationId, "Async Query", null);

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskExecutor).submit(runnableCaptor.capture());
        runnableCaptor.getValue().run();

        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> statusCaptor = ArgumentCaptor.forClass(String.class);
        verify(messageRepository).updateContentAndStatus(eq(assistantMessageId),
            contentCaptor.capture(), any(), any(), any(), any(), any(), any(), any(),
            statusCaptor.capture(), any());

        // Asserted as the literal: this exact string is the client contract ChatAsyncController's
        // poll keys off, and the run's own "delivered_flagged" is deliberately not it.
        assertThat(statusCaptor.getValue()).isEqualTo("done");
        assertThat(flagged.status()).as("the run really was flagged; the message status is not")
            .isEqualTo("delivered_flagged");
        assertThat(contentCaptor.getValue())
            .as("the flag note is the user's ONLY signal — it must survive verbatim")
            .isEqualTo(flaggedContent);
        assertThat(contentCaptor.getValue()).doesNotContain("System Error");
        verify(chatCancellationService).deregister(eq(conversationId), any());
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

    /**
     * An exception message from an orchestrated run is arbitrary third-party text, and this catch
     * is the last place it can reach the chat. The genai SDK's {@code ApiException.getMessage()} is
     * {@code String.format("%d %s. %s", code, status, message)} over provider-supplied fields, and
     * a failure deeper in the run can carry a bearer token, an {@code api_key=} query parameter or
     * a stack-trace fragment. Writing it into {@code messages.content} publishes it to the browser
     * AND into every later turn's history, since a non-blank, non-{@code generating} assistant row
     * is replayed to the model by {@code getPriorHistory} (SEC-17).
     *
     * <p>
     * The assertion is an exact equality rather than a {@code contains} on purpose: the
     * orchestrated path writes the notice ALONE, with no partial-output prefix, because a
     * multi-agent run's intermediate drafts are not an answer. That makes the "be helpful and
     * append the cause" regression — the single most likely edit to this block — fail here instead
     * of shipping.
     *
     * <p>
     * The sibling {@code testPrepareAndSubmitAsyncFailure} pins only the plain RAG branch, whose
     * catch block is a different one that deliberately DOES prefix the partial text, so it stays
     * green no matter what this block writes; no other test reaches the orchestrated path at all.
     */
    @Test
    public void anOrchestratedRunThatFailsPersistsTheGenericNoticeWithoutProviderDetail() {
        UUID conversationId = UUID.randomUUID();
        Map<String, Object> settings = new HashMap<>();
        settings.put(ConversationSetting.MODEL.getValue(), "gemini-3.1-flash-lite");
        settings.put(ConversationSetting.ORCHESTRATOR_PROFILE.getValue(), "oim");
        Conversation conv =
            new Conversation(conversationId, null, "My Chat", settings, null, null, List.of());
        when(chatConversationService.verifyOwnership(conversationId, false)).thenReturn(conv);

        OrchestrationService orchestrationService = mock(OrchestrationService.class);
        ChatMessageService service = new ChatMessageService(messageRepository,
            mock(ConversationRepository.class), chatConversationService, ragService,
            retrievalLogRepository, playbookService, systemPromptService, transactionManager,
            taskExecutor, chatCancellationService, orchestrationService);

        when(orchestrationService.runOrchestration(any(), any(), any(), any(), any(), any()))
            .thenThrow(new RuntimeException("429 quota key=abc"));

        UUID assistantMessageId =
            service.prepareAndSubmitAsync(conversationId, "Async Query", "PB1");

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskExecutor).submit(runnableCaptor.capture());
        runnableCaptor.getValue().run();

        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> statusCaptor = ArgumentCaptor.forClass(String.class);
        verify(messageRepository).updateContentAndStatus(eq(assistantMessageId),
            contentCaptor.capture(), any(), any(), any(), any(), any(), any(), any(),
            statusCaptor.capture(), any());

        assertThat(contentCaptor.getValue())
            .isEqualTo("⚠️ *[System Error: the assistant could not complete this response.]*");
        assertThat(contentCaptor.getValue()).doesNotContain("429").doesNotContain("quota")
            .doesNotContain("key=abc");
        assertThat(statusCaptor.getValue()).isEqualTo("error");
        verify(chatCancellationService).deregister(eq(conversationId), any());
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
     * {@code getPriorHistory} inspects {@code status} in exactly ONE place — the assistant branch's
     * {@code "generating"} check — so every other terminal status is replayed to the model on every
     * later turn of the conversation. That is not a theoretical case: the async path persists
     * {@code partial + GENERIC_ERROR_TEXT} as {@code error} and {@code partial + "*[Generation
     * stopped by user]*"} as {@code cancelled}, and the orchestrated path persists the bare notice
     * for both — all four always have content, so all four come back as prior "answers".
     *
     * <p>
     * This is the rule the neighbouring SEC-17 tests silently depend on: the reason the failure
     * notice must be generic is precisely that it is not only shown to the user, it is fed back to
     * the model as its own previous turn, where a provider message carrying a bearer token or an
     * {@code api_key=} fragment would be re-uploaded on every subsequent question and could be
     * paraphrased into an answer. {@code anOrchestratedRunThatFailsPersistsTheGenericNoticeWithout
     * ProviderDetail} states that dependency in its javadoc and cannot test it.
     *
     * <p>
     * The likely regression is a tidy-up in the other direction: reading the guard as "only replay
     * finished answers" and rewriting it as {@code if (!"done".equalsIgnoreCase(msg.status()))
     * continue;}. That looks strictly safer and is not. Dropping the row leaves the user turn above
     * it with nothing after it, so the model receives two (or three) consecutive user messages —
     * the shape most providers reject or silently merge — and the follow-up "why did that fail?" or
     * "try again" arrives with no trace of what was attempted, including the partial answer the
     * next turn is usually meant to continue from. Either behaviour is defensible; changing it
     * silently is not, because nothing else in the system reads {@code messages.status} on this
     * path.
     *
     * <p>
     * {@code testGetPriorHistoryExcludesGeneratingAndEmpty} covers only {@code generating} and the
     * blank-content case, and every other test in this file builds history from {@code "done"}
     * rows, so the whole non-{@code done} terminal vocabulary is unasserted.
     */
    @Test
    public void anErrorOrCancelledAssistantMessageIsStillReplayedIntoHistory() {
        UUID conversationId = UUID.randomUUID();

        Message question = new Message(UUID.randomUUID(), conversationId, "user", "Hello",
            List.of(), List.of(), Instant.now());
        Message failed = new Message(UUID.randomUUID(), conversationId, "assistant",
            "Partial draft.\n\n⚠️ *[System Error: the assistant could not complete this response.]*",
            null, List.of(), List.of(), Instant.now(), null, null, null, null, null, "error");
        Message stopped = new Message(UUID.randomUUID(), conversationId, "assistant",
            "*[Generation stopped by user]*", null, List.of(), List.of(), Instant.now(), null, null,
            null, null, null, "cancelled");

        when(messageRepository.findByConversationId(conversationId, 100, 0))
            .thenReturn(List.of(question, failed, stopped));

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

        assertThat(prep.history()).as("a failed or stopped turn is history, not a hole").hasSize(3);
        assertThat(prep.history().get(0).role()).isEqualTo("user");
        assertThat(prep.history().get(1).role()).isEqualTo("assistant");
        assertThat(prep.history().get(1).content()).isEqualTo(failed.content());
        assertThat(prep.history().get(2).role()).isEqualTo("assistant");
        assertThat(prep.history().get(2).content()).isEqualTo("*[Generation stopped by user]*");
    }

    /**
     * Asserts the final SSE line carries the MNT-17 named-JSON {@code [DONE]} payload and returns
     * it parsed. Fails (not just returns) if the line is not {@code [DONE] {…valid JSON…}}.
     */
    /**
     * S3: "MUST NOT leave a message {@code generating} after a restart". The sweep's SQL is pinned
     * elsewhere, but nothing pins that anything ever CALLS it: {@code ChatThreadRenderingIT}
     * invokes {@code messageRepository.resetGeneratingMessages()} directly, so deleting or
     * re-targeting the {@code @EventListener(ApplicationReadyEvent.class)} on
     * {@code onApplicationReady} leaves the whole suite green while the sweep never runs in
     * production. Nothing else invokes it — the annotation IS the wiring.
     *
     * <p>
     * Every message in flight when the process stops then stays {@code generating} forever: the
     * bubble renders the loader, the browser poll re-arms every 3 s against a worker that no longer
     * exists, and {@code getPriorHistory} skips {@code generating} assistant rows — so the user's
     * question is silently dropped from the model's view of the conversation for good. A
     * {@code ./redeploy.sh} during any long orchestrated run reproduces it.
     *
     * <p>
     * The event type is asserted rather than merely the annotation's presence, because the two
     * plausible substitutes both break it: {@code ContextRefreshedEvent} fires before the
     * application is ready (and repeats for a child context), and it is the event a "make it run
     * earlier" edit reaches for. The merged-annotation lookup is used so the {@code classes =}
     * alias is resolved the same way Spring resolves it.
     *
     * <p>
     * The second half is why the method has a try/catch at all: a sweep that throws must not take
     * the application down with it. Let the exception escape and a transient DB hiccup at boot
     * turns a stale-row cleanup into a failed startup — the stale rows are a cosmetic problem, a
     * container that will not start is not.
     */
    @Test
    public void theStartupSweepIsWiredToApplicationReadyAndCannotItselfAbortStartup()
        throws Exception {
        EventListener wiring = AnnotatedElementUtils.findMergedAnnotation(
            ChatMessageService.class.getMethod("onApplicationReady"), EventListener.class);

        assertThat(wiring).as("nothing else invokes the sweep — the annotation IS the wiring")
            .isNotNull();
        assertThat(wiring.value()).as("ApplicationReadyEvent: the sweep needs a live DataSource")
            .containsExactly(ApplicationReadyEvent.class);

        doThrow(new RuntimeException("the connection pool is not up yet")).when(messageRepository)
            .resetGeneratingMessages();
        chatMessageService.onApplicationReady();
        verify(messageRepository).resetGeneratingMessages();
    }

    /**
     * S6 Data: {@code agent_runs.message_id} is the USER message id on the web path, and it is this
     * service — not {@code OrchestrationService} — that decides it. Every chat entry point
     * attributes spend to the persisted question, so that a {@code GROUP BY message_id} over
     * {@code llm_call_logs} compares like with like; the orchestrated path is the one place where
     * the wrong id is also a LEGAL id, because it writes the {@code generating} assistant
     * placeholder before delegating, so the FK would happily accept it and nothing would fail.
     *
     * <p>
     * Pass the placeholder instead and every multi-agent turn's cost splits across two ids: the
     * nested agents' calls (which inherit {@code messageId} through {@code LlmCallContextHolder} in
     * {@code runAgent}) land on the answer while the same conversation's plain turns land on the
     * question, so per-message cost views under-report one and invent the other, and the join from
     * {@code agent_runs} to the question that caused the run stops working. This is exactly the
     * failure the plain async path already guards against — and the guard there cannot see this
     * branch at all.
     *
     * <p>
     * The assertion is against the id of the user row this call actually persisted, captured from
     * the repository, rather than against an id the test supplied: the existing orchestrated tests
     * all match the argument with {@code any()}, and {@code ConversationAgentRunContextIT} calls
     * {@code runOrchestration} directly with an id it chose itself, so neither can observe which of
     * the two ids this method hands over.
     */
    @Test
    public void anOrchestratedRunIsFiledUnderTheUserMessageIdNotTheAssistantPlaceholder() {
        UUID conversationId = UUID.randomUUID();
        Map<String, Object> settings = new HashMap<>();
        settings.put(ConversationSetting.MODEL.getValue(), "gemini-3.1-flash-lite");
        settings.put(ConversationSetting.ORCHESTRATOR_PROFILE.getValue(), "oim");
        when(chatConversationService.verifyOwnership(conversationId, false)).thenReturn(
            new Conversation(conversationId, null, "My Chat", settings, null, null, List.of()));

        OrchestrationService orchestrationService = mock(OrchestrationService.class);
        ChatMessageService service = new ChatMessageService(messageRepository,
            mock(ConversationRepository.class), chatConversationService, ragService,
            retrievalLogRepository, playbookService, systemPromptService, transactionManager,
            taskExecutor, chatCancellationService, orchestrationService);
        when(orchestrationService.runOrchestration(any(), any(), any(), any(), any(), any()))
            .thenReturn(new OrchestrationResult("Orchestrated answer", List.of(), List.of(), 11, 22,
                33, 4, 5, "done"));

        UUID assistantMessageId =
            service.prepareAndSubmitAsync(conversationId, "Async Query", null);
        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskExecutor).submit(runnableCaptor.capture());
        runnableCaptor.getValue().run();

        ArgumentCaptor<Message> saved = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository, times(2)).save(saved.capture());
        Message userRow = saved.getAllValues().get(0);
        assertThat(userRow.role()).as("the first row written is the question").isEqualTo("user");

        ArgumentCaptor<UUID> delegatedMessageId = ArgumentCaptor.forClass(UUID.class);
        verify(orchestrationService).runOrchestration(eq(conversationId),
            delegatedMessageId.capture(), eq("Async Query"), any(), any(), eq("oim"));

        assertThat(delegatedMessageId.getValue())
            .as("agent_runs.message_id and llm_call_logs.message_id point at the QUESTION")
            .isEqualTo(userRow.id());
        assertThat(delegatedMessageId.getValue())
            .as("the assistant placeholder exists and would satisfy the FK — that is the trap")
            .isNotEqualTo(assistantMessageId);
    }

    private static JsonNode parseDoneEvent(String line) {
        assertThat(line).startsWith("[DONE] {");
        try {
            return new ObjectMapper().readTree(line.substring("[DONE] ".length()));
        } catch (Exception e) {
            throw new AssertionError("[DONE] line is not valid JSON: " + line, e);
        }
    }
}
