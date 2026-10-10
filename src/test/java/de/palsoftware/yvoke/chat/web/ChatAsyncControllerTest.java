package de.palsoftware.yvoke.chat.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.palsoftware.yvoke.chat.api.model.MessageDto;
import de.palsoftware.yvoke.chat.api.model.MessageTraceDto;
import de.palsoftware.yvoke.chat.core.model.Message;
import de.palsoftware.yvoke.chat.core.model.MessageTrace;
import de.palsoftware.yvoke.chat.core.service.ChatConversationService;
import de.palsoftware.yvoke.chat.core.service.ChatMessageService;
import de.palsoftware.yvoke.chat.orchestration.AgentRun;
import de.palsoftware.yvoke.chat.orchestration.AgentStep;
import de.palsoftware.yvoke.shared.web.GenerationConcurrencyLimiter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

class ChatAsyncControllerTest {

    private static GenerationConcurrencyLimiter allowingLimiter() {
        GenerationConcurrencyLimiter limiter = mock(GenerationConcurrencyLimiter.class);
        when(limiter.tryAcquire()).thenReturn(true);
        return limiter;
    }

    private static Message messageWithStatus(UUID conversationId, String status) {
        return new Message(UUID.randomUUID(), conversationId, "assistant", "content", null,
            List.of(), List.of(), Instant.now(), null, null, null, null, null, status, null);
    }

    @Test
    void validationErrorPropagatesAndDoesNotSubmit() {
        ChatMessageService chatMessageService = mock(ChatMessageService.class);
        ChatConversationService chatConversationService = mock(ChatConversationService.class);
        when(chatMessageService.prepareAndSubmitAsync(any(UUID.class), anyString(), any(), any()))
            .thenThrow(new AccessDeniedException("Access denied to conversation"));

        ChatAsyncController controller =
            new ChatAsyncController(chatMessageService, chatConversationService, allowingLimiter());

        assertThrows(AccessDeniedException.class,
            () -> controller.sendMessageAsync(UUID.randomUUID(), "hello", null));
    }

    @Test
    void ownershipFailureRejectsBeforeAcquiringConcurrencyPermit() {
        ChatMessageService chatMessageService = mock(ChatMessageService.class);
        ChatConversationService chatConversationService = mock(ChatConversationService.class);
        GenerationConcurrencyLimiter limiter = mock(GenerationConcurrencyLimiter.class);
        doThrow(new AccessDeniedException("Forbidden")).when(chatConversationService)
            .verifyOwnership(any(UUID.class), eq(false));

        ChatAsyncController controller =
            new ChatAsyncController(chatMessageService, chatConversationService, limiter);

        assertThrows(AccessDeniedException.class,
            () -> controller.sendMessageAsync(UUID.randomUUID(), "hello", null));

        verify(limiter, never()).tryAcquire();
        verify(chatMessageService, never()).prepareAndSubmitAsync(any(), any(), any(), any());
    }

    @Test
    void blankContentReturnsBadRequest() {
        ChatMessageService chatMessageService = mock(ChatMessageService.class);
        ChatConversationService chatConversationService = mock(ChatConversationService.class);
        ChatAsyncController controller =
            new ChatAsyncController(chatMessageService, chatConversationService, allowingLimiter());

        ResponseEntity<Map<String, String>> response =
            controller.sendMessageAsync(UUID.randomUUID(), "   ", null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(chatMessageService, never()).prepareAndSubmitAsync(any(), any(), any(), any());
    }

    @Test
    void successfulExecutionReturnsAccepted() {
        ChatMessageService chatMessageService = mock(ChatMessageService.class);
        ChatConversationService chatConversationService = mock(ChatConversationService.class);
        UUID conversationId = UUID.randomUUID();
        UUID expectedAssistantMessageId = UUID.randomUUID();
        String content = "Hello assistant";

        when(
            chatMessageService.prepareAndSubmitAsync(eq(conversationId), eq(content), any(), any()))
            .thenReturn(expectedAssistantMessageId);

        ChatAsyncController controller =
            new ChatAsyncController(chatMessageService, chatConversationService, allowingLimiter());
        ResponseEntity<Map<String, String>> response =
            controller.sendMessageAsync(conversationId, content, null);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals(expectedAssistantMessageId.toString(),
            response.getBody().get("assistantMessageId"));
        verify(chatMessageService).prepareAndSubmitAsync(eq(conversationId), eq(content), any(),
            any());
    }

    @Test
    void getMessageStatusVerifiesOwnershipAndReturnsStatus() {
        ChatMessageService chatMessageService = mock(ChatMessageService.class);
        ChatConversationService chatConversationService = mock(ChatConversationService.class);
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();

        Message generatingMessage = messageWithStatus(conversationId, "generating");
        Message errorMessage = messageWithStatus(conversationId, "error");
        Message doneMessage = messageWithStatus(conversationId, "done");

        ChatAsyncController controller =
            new ChatAsyncController(chatMessageService, chatConversationService, allowingLimiter());

        // 1. Test Generating
        when(chatMessageService.getMessageStatus(messageId))
            .thenReturn(Optional.of(generatingMessage));
        ResponseEntity<Map<String, Object>> responseGen =
            controller.getMessageStatus(conversationId, messageId);
        assertEquals(HttpStatus.OK, responseGen.getStatusCode());
        assertEquals("generating", responseGen.getBody().get("status"));
        verify(chatConversationService).verifyOwnership(conversationId, false);

        // 2. Test Error
        when(chatMessageService.getMessageStatus(messageId)).thenReturn(Optional.of(errorMessage));
        ResponseEntity<Map<String, Object>> responseErr =
            controller.getMessageStatus(conversationId, messageId);
        assertEquals(HttpStatus.OK, responseErr.getStatusCode());
        assertEquals("error", responseErr.getBody().get("status"));
        assertEquals(MessageDto.from(errorMessage, null), responseErr.getBody().get("message"));

        // 3. Test Done
        when(chatMessageService.getMessageStatus(messageId)).thenReturn(Optional.of(doneMessage));
        ResponseEntity<Map<String, Object>> responseDone =
            controller.getMessageStatus(conversationId, messageId);
        assertEquals(HttpStatus.OK, responseDone.getStatusCode());
        assertEquals("done", responseDone.getBody().get("status"));
        assertEquals(MessageDto.from(doneMessage, null), responseDone.getBody().get("message"));

        // 4. Test Not Found
        when(chatMessageService.getMessageStatus(messageId)).thenReturn(Optional.empty());
        ResponseEntity<Map<String, Object>> responseNotFound =
            controller.getMessageStatus(conversationId, messageId);
        assertEquals(HttpStatus.NOT_FOUND, responseNotFound.getStatusCode());
    }

    /**
     * A cancelled generation must reach the browser as cancelled. The terminal branch used to be a
     * bare {@code else} mapping every non-generating, non-error status to "done", which would have
     * dressed a user's Stop up as a finished answer complete with feedback buttons.
     */
    @Test
    void getMessageStatusReportsCancelledDistinctlyFromDoneAndError() {
        ChatMessageService chatMessageService = mock(ChatMessageService.class);
        ChatConversationService chatConversationService = mock(ChatConversationService.class);
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();

        Message cancelledMessage = messageWithStatus(conversationId, "cancelled");
        when(chatMessageService.getMessageStatus(messageId))
            .thenReturn(Optional.of(cancelledMessage));

        ChatAsyncController controller =
            new ChatAsyncController(chatMessageService, chatConversationService, allowingLimiter());

        ResponseEntity<Map<String, Object>> response =
            controller.getMessageStatus(conversationId, messageId);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("cancelled", response.getBody().get("status"));
        assertEquals(MessageDto.from(cancelledMessage, null), response.getBody().get("message"));
    }

    /**
     * {@code messages.status} is unconstrained TEXT with no CHECK constraint, so any value can end
     * up on a row — a status added by a future in-flight state, a value written by a path nobody
     * remembered updating, a typo. This endpoint is the browser's only account of how a generation
     * ended, and the client contract is that it NEVER invents status text for a message the server
     * has already authored: {@code pollTerminalDecision} branches on exactly
     * generating/error/cancelled/done. Echo an unrecognised value back and it matches no branch at
     * all — the poll re-arms via setTimeout forever with the loader interval never cleared, which
     * is the same family of failure that once presented an HTTP 429 to the user as "[Generation
     * stopped by user]".
     *
     * <p>
     * The fixed "done" default is what keeps the response vocabulary closed. Replacing it with
     * {@code message.status()} looks like a strict improvement ("report what the row actually
     * says") and passes every other test in this file, because they cover only the four values the
     * controller already names explicitly — generating, error, cancelled and a row literally stored
     * as "done", which is indistinguishable from the default.
     */
    @Test
    void anUnrecognisedTerminalStatusIsReportedAsDoneRatherThanEchoedBack() {
        ChatMessageService chatMessageService = mock(ChatMessageService.class);
        ChatConversationService chatConversationService = mock(ChatConversationService.class);
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();

        Message queuedMessage = messageWithStatus(conversationId, "queued");
        when(chatMessageService.getMessageStatus(messageId)).thenReturn(Optional.of(queuedMessage));

        ChatAsyncController controller =
            new ChatAsyncController(chatMessageService, chatConversationService, allowingLimiter());

        ResponseEntity<Map<String, Object>> response =
            controller.getMessageStatus(conversationId, messageId);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("done", response.getBody().get("status"));
        assertEquals(MessageDto.from(queuedMessage, null), response.getBody().get("message"));
        assertNotEquals("queued", response.getBody().get("status"),
            "an unrecognised status must not be echoed back to a client that has no branch for it: "
                + response.getBody().get("status"));
    }

    @Test
    void getMessageStatusRejectsMessageBelongingToAnotherConversation() {
        ChatMessageService chatMessageService = mock(ChatMessageService.class);
        ChatConversationService chatConversationService = mock(ChatConversationService.class);
        UUID conversationId = UUID.randomUUID();
        UUID otherConversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();

        // Caller owns `conversationId`, but the requested message lives in a different
        // conversation.
        // Without a cross-check this leaks another conversation's message content (SEC-08 / IDOR).
        Message foreignMessage = messageWithStatus(otherConversationId, "done");
        when(chatMessageService.getMessageStatus(messageId))
            .thenReturn(Optional.of(foreignMessage));

        ChatAsyncController controller =
            new ChatAsyncController(chatMessageService, chatConversationService, allowingLimiter());
        ResponseEntity<Map<String, Object>> response =
            controller.getMessageStatus(conversationId, messageId);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        // The foreign message's content must never be surfaced.
        verify(chatConversationService).verifyOwnership(conversationId, false);
    }

    @Test
    void getMessageStatusFailsIfOwnershipVerificationThrows() {
        ChatMessageService chatMessageService = mock(ChatMessageService.class);
        ChatConversationService chatConversationService = mock(ChatConversationService.class);
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();

        doThrow(new AccessDeniedException("Access denied")).when(chatConversationService)
            .verifyOwnership(any(UUID.class), anyBoolean());

        ChatAsyncController controller =
            new ChatAsyncController(chatMessageService, chatConversationService, allowingLimiter());

        assertThrows(AccessDeniedException.class,
            () -> controller.getMessageStatus(conversationId, messageId));
        verify(chatMessageService, never()).getMessageStatus(any());
    }

    @Test
    void returns429AndDoesNotSubmitWhenAtGlobalCapacity() {
        ChatMessageService chatMessageService = mock(ChatMessageService.class);
        ChatConversationService chatConversationService = mock(ChatConversationService.class);
        GenerationConcurrencyLimiter limiter = mock(GenerationConcurrencyLimiter.class);
        when(limiter.tryAcquire()).thenReturn(false);

        ChatAsyncController controller =
            new ChatAsyncController(chatMessageService, chatConversationService, limiter);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.sendMessageAsync(UUID.randomUUID(), "hello", null));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatusCode());
        assertEquals("The assistant is at capacity right now. Please retry in a moment.",
            ex.getReason());

        verify(chatMessageService, never()).prepareAndSubmitAsync(any(), any(), any());
        verify(chatMessageService, never()).prepareAndSubmitAsync(any(), any(), any(), any());
        verify(limiter, never()).release();
    }

    @Test
    void concurrencyPermitReleasedIfPrepareAndSubmitThrowsSynchronously() {
        ChatMessageService chatMessageService = mock(ChatMessageService.class);
        ChatConversationService chatConversationService = mock(ChatConversationService.class);
        GenerationConcurrencyLimiter limiter = allowingLimiter();

        when(chatMessageService.prepareAndSubmitAsync(any(), any(), any(), any()))
            .thenThrow(new AccessDeniedException("Access denied to conversation"));

        ChatAsyncController controller =
            new ChatAsyncController(chatMessageService, chatConversationService, limiter);

        assertThrows(AccessDeniedException.class,
            () -> controller.sendMessageAsync(UUID.randomUUID(), "hello", null));

        verify(limiter).tryAcquire();
        verify(limiter).release();
    }

    @Test
    void concurrencyPermitReleasedWhenAsyncTaskCompletes() {
        ChatMessageService chatMessageService = mock(ChatMessageService.class);
        ChatConversationService chatConversationService = mock(ChatConversationService.class);
        GenerationConcurrencyLimiter limiter = allowingLimiter();
        UUID convId = UUID.randomUUID();
        UUID assistantMessageId = UUID.randomUUID();

        AtomicReference<Runnable> onCompleteRef = new AtomicReference<>();
        when(chatMessageService.prepareAndSubmitAsync(eq(convId), eq("hello"), any(), any()))
            .thenAnswer(inv -> {
                onCompleteRef.set(inv.getArgument(3));
                return assistantMessageId;
            });

        ChatAsyncController controller =
            new ChatAsyncController(chatMessageService, chatConversationService, limiter);

        ResponseEntity<Map<String, String>> response =
            controller.sendMessageAsync(convId, "hello", null);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        verify(limiter).tryAcquire();
        verify(limiter, never()).release();

        assertThat(onCompleteRef.get()).isNotNull();
        onCompleteRef.get().run();

        verify(limiter).release();
    }

    @Test
    void blankContentDoesNotAcquirePermit() {
        ChatMessageService chatMessageService = mock(ChatMessageService.class);
        ChatConversationService chatConversationService = mock(ChatConversationService.class);
        GenerationConcurrencyLimiter limiter = mock(GenerationConcurrencyLimiter.class);

        ChatAsyncController controller =
            new ChatAsyncController(chatMessageService, chatConversationService, limiter);

        ResponseEntity<Map<String, String>> response =
            controller.sendMessageAsync(UUID.randomUUID(), "   ", null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(limiter, never()).tryAcquire();
        verify(limiter, never()).release();
        verify(chatMessageService, never()).prepareAndSubmitAsync(any(), any(), any());
        verify(chatMessageService, never()).prepareAndSubmitAsync(any(), any(), any(), any());
    }

    @Test
    void testCrossConversationIdorReturns404() {
        ChatMessageService chatMessageService = mock(ChatMessageService.class);
        ChatConversationService chatConversationService = mock(ChatConversationService.class);
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();

        when(chatMessageService.getMessageTrace(conversationId, messageId))
            .thenReturn(Optional.empty());

        ChatAsyncController controller =
            new ChatAsyncController(chatMessageService, chatConversationService, allowingLimiter());

        ResponseEntity<MessageTraceDto> response =
            controller.getMessageTrace(conversationId, messageId);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        verify(chatConversationService).verifyOwnership(conversationId, true);
    }

    @Test
    void testZeroToolCallsReturns200WithEmptyList() {
        ChatMessageService chatMessageService = mock(ChatMessageService.class);
        ChatConversationService chatConversationService = mock(ChatConversationService.class);
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();

        MessageTrace trace = new MessageTrace("single", messageId, conversationId, "done",
            "content", "model", 10, 20, 30, 0, 0, List.of(), List.of(), null, null);
        when(chatMessageService.getMessageTrace(conversationId, messageId))
            .thenReturn(Optional.of(trace));

        ChatAsyncController controller =
            new ChatAsyncController(chatMessageService, chatConversationService, allowingLimiter());

        ResponseEntity<MessageTraceDto> response =
            controller.getMessageTrace(conversationId, messageId);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertThat(response.getBody()).isNotNull();
        MessageTraceDto dto = response.getBody();
        assertEquals("single", dto.mode());
        assertEquals(messageId, dto.messageId());
        assertEquals(conversationId, dto.conversationId());
        assertEquals("done", dto.status());
        assertThat(dto.toolCalls()).isNotNull().isEmpty();
        assertThat(dto.agentRun()).isNull();
        assertThat(dto.steps()).isNull();
        verify(chatConversationService).verifyOwnership(conversationId, true);
    }

    @Test
    void testGeneratingMessageTraceStatus() {
        ChatMessageService chatMessageService = mock(ChatMessageService.class);
        ChatConversationService chatConversationService = mock(ChatConversationService.class);
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();

        MessageTrace trace = new MessageTrace("single", messageId, conversationId, "generating", "",
            "model", null, null, null, null, null, List.of(), List.of(), null, null);
        when(chatMessageService.getMessageTrace(conversationId, messageId))
            .thenReturn(Optional.of(trace));

        ChatAsyncController controller =
            new ChatAsyncController(chatMessageService, chatConversationService, allowingLimiter());

        ResponseEntity<MessageTraceDto> response =
            controller.getMessageTrace(conversationId, messageId);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertThat(response.getBody()).isNotNull();
        MessageTraceDto dto = response.getBody();
        assertEquals("generating", dto.status());
        assertEquals(0, dto.tokens().total());
        verify(chatConversationService).verifyOwnership(conversationId, true);
    }

    @Test
    void testTraceUnauthorizedThrowsAccessDenied() {
        ChatMessageService chatMessageService = mock(ChatMessageService.class);
        ChatConversationService chatConversationService = mock(ChatConversationService.class);
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();

        doThrow(new AccessDeniedException("Forbidden")).when(chatConversationService)
            .verifyOwnership(conversationId, true);

        ChatAsyncController controller =
            new ChatAsyncController(chatMessageService, chatConversationService, allowingLimiter());

        assertThrows(AccessDeniedException.class,
            () -> controller.getMessageTrace(conversationId, messageId));
        verify(chatMessageService, never()).getMessageTrace(any(), any());
    }

    @Test
    void testMasModeTraceReturnsAgentRunAndSteps() {
        ChatMessageService chatMessageService = mock(ChatMessageService.class);
        ChatConversationService chatConversationService = mock(ChatConversationService.class);
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        AgentRun run = new AgentRun(runId, conversationId, messageId, "oim", "done", "{}", 1,
            "approved", 100, 200, 300, 40, 50, null, Instant.now(), Instant.now());
        AgentStep step1 = new AgentStep(UUID.randomUUID(), runId, 0, "orchestrator", 1, "plan",
            "gpt-4o", "medium", "input", "output", "[]", "ok", 50, 100, 150, 20, 25, Instant.now(),
            "ok", null);

        MessageTrace trace = new MessageTrace("mas", messageId, conversationId, "done",
            "mas content", "oim", 100, 200, 300, 40, 50, null, null, run, List.of(step1));
        when(chatMessageService.getMessageTrace(conversationId, messageId))
            .thenReturn(Optional.of(trace));

        ChatAsyncController controller =
            new ChatAsyncController(chatMessageService, chatConversationService, allowingLimiter());

        ResponseEntity<MessageTraceDto> response =
            controller.getMessageTrace(conversationId, messageId);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertThat(response.getBody()).isNotNull();
        MessageTraceDto dto = response.getBody();
        assertEquals("mas", dto.mode());
        assertEquals(messageId, dto.messageId());
        assertEquals(conversationId, dto.conversationId());
        assertEquals(300, dto.tokens().total());
        assertThat(dto.agentRun()).isNotNull();
        assertEquals(runId, dto.agentRun().id());
        assertEquals("oim", dto.agentRun().profileName());
        assertEquals(300, dto.agentRun().totalTokens());
        assertThat(dto.steps()).hasSize(1);
        assertEquals(step1.id(), dto.steps().get(0).id());
        assertEquals("orchestrator", dto.steps().get(0).role());
        assertEquals("gpt-4o", dto.steps().get(0).model());
        assertThat(dto.toolCalls()).isNull();
        verify(chatConversationService).verifyOwnership(conversationId, true);
    }
}
