package de.palsoftware.yvoke.chat.web;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.palsoftware.yvoke.chat.core.service.ChatMessageService;
import de.palsoftware.yvoke.chat.core.service.ChatCancellationService;
import de.palsoftware.yvoke.shared.web.GenerationConcurrencyLimiter;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

class ChatSseControllerTest {

    private static GenerationConcurrencyLimiter allowingLimiter() {
        GenerationConcurrencyLimiter limiter = mock(GenerationConcurrencyLimiter.class);
        when(limiter.tryAcquire()).thenReturn(true);
        return limiter;
    }

    /**
     * Regression guard: validation/authorization runs synchronously on the request thread (via
     * {@code prepare()}) so failures propagate as normal HTTP errors. If setup were deferred into
     * the async streaming task, the SSE response would already be committed and the error would be
     * mangled (HttpMessageNotWritableException while writing a JSON body over text/event-stream).
     */
    @Test
    void validationErrorPropagatesAndDoesNotStartStreaming() {
        ChatMessageService chatMessageService = mock(ChatMessageService.class);
        ChatCancellationService chatCancellationService = mock(ChatCancellationService.class);
        AsyncTaskExecutor sseExecutor = mock(AsyncTaskExecutor.class);
        when(chatMessageService.prepare(any(UUID.class), anyString(), any()))
            .thenThrow(new AccessDeniedException("Access denied to conversation"));

        ChatSseController controller = new ChatSseController(chatMessageService,
            chatCancellationService, sseExecutor, allowingLimiter());

        assertThrows(AccessDeniedException.class,
            () -> controller.sendMessageStream(UUID.randomUUID(), "hello", null));

        // The async streaming task must never be submitted: the SSE response is never committed, so
        // Spring MVC can still write a clean HTTP error response.
        verify(sseExecutor, never()).submit(any(Runnable.class));
    }

    @Test
    void returns429AndDoesNotStreamWhenAtGlobalCapacity() {
        ChatMessageService chatMessageService = mock(ChatMessageService.class);
        ChatCancellationService chatCancellationService = mock(ChatCancellationService.class);
        AsyncTaskExecutor sseExecutor = mock(AsyncTaskExecutor.class);
        when(chatMessageService.prepare(any(UUID.class), anyString(), any()))
            .thenReturn(mock(ChatMessageService.PreparedChat.class));

        GenerationConcurrencyLimiter limiter = mock(GenerationConcurrencyLimiter.class);
        when(limiter.tryAcquire()).thenReturn(false); // at capacity

        ChatSseController controller = new ChatSseController(chatMessageService,
            chatCancellationService, sseExecutor, limiter);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.sendMessageStream(UUID.randomUUID(), "hello", null));
        org.junit.jupiter.api.Assertions.assertEquals(HttpStatus.TOO_MANY_REQUESTS,
            ex.getStatusCode());

        // No slot acquired to release, and no generation started.
        verify(sseExecutor, never()).submit(any(Runnable.class));
        verify(limiter, never()).release();
    }

    @Test
    void streamingExceptionPropagatesAsSseError() throws Exception {
        ChatMessageService chatMessageService = mock(ChatMessageService.class);
        AsyncTaskExecutor sseExecutor = mock(AsyncTaskExecutor.class);

        when(sseExecutor.submit(any(Runnable.class))).thenAnswer(invocation -> {
            Runnable r = invocation.getArgument(0);
            r.run();
            return null;
        });

        ChatMessageService.PreparedChat prepared = mock(ChatMessageService.PreparedChat.class);
        when(chatMessageService.prepare(any(UUID.class), anyString(), any())).thenReturn(prepared);

        org.mockito.Mockito.doThrow(new RuntimeException("Test stream failure"))
            .when(chatMessageService).stream(any(), any(), any());

        ChatCancellationService chatCancellationService = mock(ChatCancellationService.class);
        ChatSseController controller = new ChatSseController(chatMessageService,
            chatCancellationService, sseExecutor, allowingLimiter());

        org.springframework.test.web.servlet.MockMvc mockMvc =
            org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                .build();

        org.springframework.test.web.servlet.MvcResult result = mockMvc
            .perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/chat/" + UUID.randomUUID() + "/send").param("content", "hello"))
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andReturn();

        String content = result.getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(
            content.contains("data:[ERROR] The assistant could not complete this response."),
            "Response should contain the generic [ERROR] message");
        // The raw exception detail must never be echoed to the client (SEC-17).
        org.junit.jupiter.api.Assertions.assertFalse(content.contains("Test stream failure"),
            "Response must not leak the raw exception message");
        org.junit.jupiter.api.Assertions.assertTrue(content.contains("data:[DONE]"),
            "Response should be completed with [DONE]");
    }
}
