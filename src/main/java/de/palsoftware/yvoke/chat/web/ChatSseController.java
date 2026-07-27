package de.palsoftware.yvoke.chat.web;

import de.palsoftware.yvoke.chat.core.service.ChatMessageService;
import de.palsoftware.yvoke.chat.core.service.ChatMessageService.PreparedChat;
import de.palsoftware.yvoke.chat.core.service.ChatCancellationService;
import de.palsoftware.yvoke.shared.web.GenerationConcurrencyLimiter;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class ChatSseController {
    private static final Logger log = LoggerFactory.getLogger(ChatSseController.class);

    private final ChatMessageService chatMessageService;
    private final ChatCancellationService chatCancellationService;
    private final AsyncTaskExecutor sseExecutor;
    private final GenerationConcurrencyLimiter concurrencyLimiter;

    public ChatSseController(ChatMessageService chatMessageService,
        ChatCancellationService chatCancellationService,
        @Qualifier("mvcTaskExecutor") AsyncTaskExecutor sseExecutor,
        GenerationConcurrencyLimiter concurrencyLimiter) {
        this.chatMessageService = chatMessageService;
        this.chatCancellationService = chatCancellationService;
        this.sseExecutor = sseExecutor;
        this.concurrencyLimiter = concurrencyLimiter;
    }

    /**
     * Signals that the SSE client went away mid-stream; used to unwind the streaming task quietly.
     */
    private static final class ClientGoneException extends RuntimeException {
        ClientGoneException(Throwable cause) {
            super(cause);
        }
    }

    @PostMapping(value = "/chat/{id}/send", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessageStream(@PathVariable UUID id,
        @RequestParam("content") String content,
        @RequestParam(value = "promptName", required = false) String promptName) {
        log.info("SSE stream requested for conversation: {}, promptName: '{}'", id, promptName);
        log.debug("SSE stream query for conversation {}: '{}'", id, content);
        SseEmitter emitter = new SseEmitter(600_000L);

        if (content == null || content.isBlank()) {
            try {
                emitter.send("[DONE]");
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }

        UUID assistantMessageId = UUID.randomUUID();

        // Validate + persist the user message on the request thread so authorization/validation
        // failures propagate as normal HTTP errors BEFORE the SSE response is committed. Only the
        // (long-running) generation streams asynchronously.
        PreparedChat prepared = chatMessageService.prepare(id, content, promptName);

        // Global generation cap (PRF-15): acquire a slot on the request thread so we can still
        // reject
        // with a clean 429 before the SSE response is committed. The slot is released when the
        // streaming task finishes (success, error, or cancellation).
        if (!concurrencyLimiter.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "The assistant is at capacity right now. Please retry in a moment.");
        }

        AtomicReference<Future<?>> taskRef = new AtomicReference<>();

        emitter.onCompletion(() -> cancel(taskRef));
        emitter.onTimeout(() -> {
            log.warn("SSE timeout for conversation: {}", id);
            cancel(taskRef);
            emitter.complete();
        });
        emitter.onError(t -> {
            log.warn("SSE connection error for conversation {}: {}", id, t.getMessage());
            cancel(taskRef);
        });

        Future<?> task = sseExecutor.submit(() -> {
            chatCancellationService.register(id, Thread.currentThread());
            try {
                chatMessageService.stream(prepared, assistantMessageId, token -> {
                    log.trace("SSE sending token to client: '{}'", token);
                    try {
                        emitter.send(token);
                    } catch (IOException | IllegalStateException e) {
                        // Emitter completed/closed or client disconnected: unwind the stream.
                        throw new ClientGoneException(e);
                    }
                });
                log.info("SSE stream completed successfully for conversation: {}", id);
                emitter.complete();
            } catch (CancellationException e) {
                log.info("SSE stream cancelled by user for conversation: {}", id);
                // Already stopped, just complete
                emitter.complete();
            } catch (ClientGoneException e) {
                log.debug("SSE client disconnected mid-stream for conversation {}: {}", id,
                    e.getMessage());
            } catch (Exception e) {
                // The SSE response is already committed, so report the failure in-band and complete
                // normally — completeWithError() here would make MVC try to write a JSON error over
                // the committed event-stream response.
                log.error("Error during streaming generation for conversation: {}", id, e);
                try {
                    // Never echo the raw exception message to the client — it can leak SQL /
                    // provider
                    // / stack detail. The full cause is in the server log above (SEC-17).
                    emitter.send("[ERROR] The assistant could not complete this response.");
                    emitter.send("[DONE]");
                } catch (Exception ex) {
                    log.debug("Failed to send error event via SSE for conversation {}", id, ex);
                }
                emitter.complete();
            } finally {
                concurrencyLimiter.release();
                chatCancellationService.deregister(id, Thread.currentThread());
            }
        });

        taskRef.set(task);

        return emitter;
    }

    private static void cancel(AtomicReference<Future<?>> taskRef) {
        Future<?> f = taskRef.get();
        if (f != null) {
            f.cancel(true);
        }
    }
}
