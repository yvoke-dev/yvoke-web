package de.palsoftware.yvoke.chat.web;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.palsoftware.yvoke.chat.core.service.ChatMessageService;
import de.palsoftware.yvoke.chat.core.service.ChatCancellationService;
import de.palsoftware.yvoke.shared.web.GenerationConcurrencyLimiter;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Assertions;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

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

    /**
     * An empty submit must be answered by the terminal {@code [DONE]} and nothing else — no user
     * row, no permit, no generation — and it is the guard's POSITION in the method that enforces
     * that. It sits above {@code prepare()} and above {@code tryAcquire()}, and each ordering
     * prevents a different failure.
     *
     * <p>
     * Below {@code prepare()}: an empty send — Enter on an empty composer, a client retry, a
     * double-submit — would persist an empty {@code user} message. That row is not inert.
     * {@code getPriorHistory} replays every user message into the next turn's prompt, so the empty
     * question is re-sent to the model for the rest of the conversation, and if it is the
     * conversation's first row {@code saveUserMessage} also fires {@code autoTitle}, naming the
     * conversation the empty string. Neither is visible to the user; both are permanent.
     *
     * <p>
     * Below {@code tryAcquire()}: this branch returns BEFORE the try/finally that owns
     * {@code release()}, so a permit taken here is never given back. The semaphore has no timeout
     * and no reaper, so every blank submit would permanently shrink global generation capacity
     * until all users get 429 and only a restart recovers it. {@code
     * generationPermitIsReleasedOnEveryTerminalPath} cannot see that — it only sends non-blank
     * content, so it never enters this branch.
     *
     * <p>
     * The limiter is deliberately left unstubbed: it must not be consulted at all, so there is
     * nothing to stub.
     */
    @Test
    void blankContentEndsTheStreamWithoutPreparingOrAcquiringAPermit() throws Exception {
        ChatMessageService chatMessageService = mock(ChatMessageService.class);
        ChatCancellationService chatCancellationService = mock(ChatCancellationService.class);
        AsyncTaskExecutor sseExecutor = mock(AsyncTaskExecutor.class);
        GenerationConcurrencyLimiter limiter = mock(GenerationConcurrencyLimiter.class);

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ChatSseController(chatMessageService,
            chatCancellationService, sseExecutor, limiter)).build();

        String body =
            mockMvc.perform(post("/chat/" + UUID.randomUUID() + "/send").param("content", "   "))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        // One SSE event, the terminal one, and nothing before it.
        assertThat(body).isEqualTo("data:[DONE]\n\n");

        verify(chatMessageService, never()).prepare(any(UUID.class), any(), any());
        verify(limiter, never()).tryAcquire();
        verify(limiter, never()).release();
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
        Assertions.assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatusCode());

        // No slot acquired to release, and no generation started.
        verify(sseExecutor, never()).submit(any(Runnable.class));
        verify(limiter, never()).release();
    }

    /**
     * The permit is acquired on the request thread and returned only by the {@code finally} block.
     * The semaphore has no timeout and no reaper, so a permit that is not returned is lost for the
     * life of the process: every leak permanently shrinks capacity until all users get 429 and only
     * a restart recovers. Moving the release out of {@code finally} into the success branch is the
     * natural-looking refactor that breaks this, and it otherwise compiles and passes — the
     * existing 429 test only asserts the negative case (release is *not* called when nothing was
     * acquired).
     */
    @Test
    void generationPermitIsReleasedOnEveryTerminalPath() {
        for (Runnable streamBehaviour : List.<Runnable>of(() -> {
        }, () -> {
            throw new RuntimeException("boom");
        }, () -> {
            throw new CancellationException();
        })) {
            ChatMessageService chatMessageService = mock(ChatMessageService.class);
            ChatCancellationService chatCancellationService = mock(ChatCancellationService.class);
            AsyncTaskExecutor sseExecutor = mock(AsyncTaskExecutor.class);
            when(sseExecutor.submit(any(Runnable.class))).thenAnswer(invocation -> {
                invocation.getArgument(0, Runnable.class).run();
                return null;
            });
            when(chatMessageService.prepare(any(UUID.class), anyString(), any()))
                .thenReturn(mock(ChatMessageService.PreparedChat.class));
            Mockito.doAnswer(inv -> {
                streamBehaviour.run();
                return null;
            }).when(chatMessageService).stream(any(), any(), any());

            GenerationConcurrencyLimiter limiter = allowingLimiter();
            new ChatSseController(chatMessageService, chatCancellationService, sseExecutor, limiter)
                .sendMessageStream(UUID.randomUUID(), "hello", null);

            verify(limiter).tryAcquire();
            verify(limiter).release();
            verify(chatCancellationService).deregister(any(UUID.class), any(Thread.class));
        }
    }

    /**
     * A failed token write must say <em>what</em> failed and <em>where</em>. Every write failure
     * used to unwind as a bare {@code ClientGoneException}, which asserts a cause ("the client
     * disconnected") that is only one of the possibilities: Spring wraps any
     * non-{@code IOException} thrown by the emitter's write path into
     * {@code IllegalStateException}, so a transport fault with a live client arrives here too and
     * gets mislabelled. Since {@code ChatMessageService.stream}'s generic catch swallows it and
     * logs the chain, the exception itself is the only place the diagnosis can live — and the token
     * position is what separates a failure at the response-commit (token #0) from one mid-answer.
     */
    @Test
    void aFailedTokenWriteNamesItsPositionAndPreservesTheCause() {
        ChatMessageService chatMessageService = mock(ChatMessageService.class);
        ChatCancellationService chatCancellationService = mock(ChatCancellationService.class);
        AsyncTaskExecutor sseExecutor = mock(AsyncTaskExecutor.class);
        when(sseExecutor.submit(any(Runnable.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        });
        when(chatMessageService.prepare(any(UUID.class), anyString(), any()))
            .thenReturn(mock(ChatMessageService.PreparedChat.class));

        // Two tokens go through (no handler is installed outside a real async dispatch, so they are
        // buffered rather than written), then the task completes the emitter — after which every
        // further write is refused, exactly as a mid-stream transport failure would be.
        AtomicReference<Consumer<String>> sinkRef = new AtomicReference<>();
        doAnswer(invocation -> {
            Consumer<String> sink = invocation.getArgument(2);
            sink.accept("S");
            sink.accept("S");
            sinkRef.set(sink);
            return null;
        }).when(chatMessageService).stream(any(), any(), any());

        new ChatSseController(chatMessageService, chatCancellationService, sseExecutor,
            allowingLimiter()).sendMessageStream(UUID.randomUUID(), "hello", null);

        assertThatThrownBy(() -> sinkRef.get().accept("E")).hasMessageContaining("token #2")
            .hasCauseInstanceOf(IllegalStateException.class);
    }

    /**
     * {@code WebMvcConfig.configureAsyncSupport} sets the MVC async default to 60 s
     * ({@code setDefaultTimeout(60000)}), which is right for an ordinary request and fatal for a
     * generation. An agentic turn on this corpus routinely runs minutes — several tool round trips,
     * a rerank, a thinking model — and the 600 000 ms passed to this emitter is the ONLY thing
     * overriding that default for the streaming endpoint.
     *
     * <p>
     * Construct it as {@code new SseEmitter()} and the timeout falls back to the MVC default, at
     * which point every answer that takes longer than a minute dies in {@code onTimeout}: the
     * future is cancelled mid-generation, the emitter completes, and the user is left with a
     * truncated answer and no error — {@code ChatMessageService.stream} never reaches
     * {@code saveAssistantMessage}, so nothing is persisted either. Short questions keep working,
     * so the regression arrives as an unreproducible "long questions cut off" report rather than as
     * a failed build.
     *
     * <p>
     * No other test can see it. Every test here drives the emitter through mocks that finish
     * instantly, and the one SSE integration test in the whole {@code it-tests} profile
     * ({@code ChatAsyncControllerIT.testStreamingModeUnaffected}) streams a scripted reply in
     * milliseconds. Nothing anywhere is slow enough to observe a timeout, so the value has to be
     * asserted directly.
     */
    @Test
    void theEmitterOutlivesTheSixtySecondMvcAsyncDefault() {
        ChatMessageService chatMessageService = mock(ChatMessageService.class);
        ChatCancellationService chatCancellationService = mock(ChatCancellationService.class);
        AsyncTaskExecutor sseExecutor = mock(AsyncTaskExecutor.class);
        when(chatMessageService.prepare(any(UUID.class), anyString(), any()))
            .thenReturn(mock(ChatMessageService.PreparedChat.class));

        SseEmitter emitter = new ChatSseController(chatMessageService, chatCancellationService,
            sseExecutor, allowingLimiter()).sendMessageStream(UUID.randomUUID(), "hello", null);

        // Ten minutes, deliberately an order of magnitude above WebMvcConfig's 60 s default.
        assertThat(emitter.getTimeout()).isEqualTo(600_000L);
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

        Mockito.doThrow(new RuntimeException("Test stream failure")).when(chatMessageService)
            .stream(any(), any(), any());

        ChatCancellationService chatCancellationService = mock(ChatCancellationService.class);
        ChatSseController controller = new ChatSseController(chatMessageService,
            chatCancellationService, sseExecutor, allowingLimiter());

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        MvcResult result = mockMvc
            .perform(MockMvcRequestBuilders.post("/chat/" + UUID.randomUUID() + "/send")
                .param("content", "hello"))
            .andExpect(MockMvcResultMatchers.status().isOk()).andReturn();

        String content = result.getResponse().getContentAsString();
        Assertions.assertTrue(
            content.contains("data:[ERROR] The assistant could not complete this response."),
            "Response should contain the generic [ERROR] message");
        // The raw exception detail must never be echoed to the client (SEC-17).
        Assertions.assertFalse(content.contains("Test stream failure"),
            "Response must not leak the raw exception message");
        Assertions.assertTrue(content.contains("data:[DONE]"),
            "Response should be completed with [DONE]");
    }
}
