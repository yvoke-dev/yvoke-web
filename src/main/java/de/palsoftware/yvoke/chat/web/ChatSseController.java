package de.palsoftware.yvoke.chat.web;

import de.palsoftware.yvoke.chat.core.service.ChatCancellationService;
import de.palsoftware.yvoke.chat.core.service.ChatConversationService;
import de.palsoftware.yvoke.chat.core.service.ChatMessageService;
import de.palsoftware.yvoke.chat.core.service.ChatMessageService.PreparedChat;
import de.palsoftware.yvoke.shared.web.GenerationConcurrencyLimiter;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
    private final ChatConversationService chatConversationService;
    private final ChatCancellationService chatCancellationService;
    private final AsyncTaskExecutor sseExecutor;
    private final GenerationConcurrencyLimiter concurrencyLimiter;

    public ChatSseController(ChatMessageService chatMessageService,
        ChatConversationService chatConversationService,
        ChatCancellationService chatCancellationService,
        @Qualifier("mvcTaskExecutor") AsyncTaskExecutor sseExecutor,
        GenerationConcurrencyLimiter concurrencyLimiter) {
        this.chatMessageService = chatMessageService;
        this.chatConversationService = chatConversationService;
        this.chatCancellationService = chatCancellationService;
        this.sseExecutor = sseExecutor;
        this.concurrencyLimiter = concurrencyLimiter;
    }

    /**
     * Signals that writing a token to the SSE response failed; used to unwind the streaming task.
     *
     * <p>
     * Named for the write, not for a diagnosis. A client that went away is only one of the causes:
     * Spring wraps every non-{@link IOException} thrown by the emitter's write path into
     * {@link IllegalStateException}, and an emitter that has already completed refuses writes the
     * same way — so a transport fault with a perfectly live client lands here too. Calling all of
     * them "client gone" sends the next reader looking for a disconnect that never happened.
     *
     * <p>
     * The message carries the token position because that is what separates a failure at the
     * response-commit (token #0 — the write that also emits the headers and triggers the security
     * header writers) from one mid-answer, and because {@code ChatMessageService.stream}'s generic
     * catch swallows this exception into a logged chain: the message is the only place the
     * diagnosis survives to the log.
     */
    private static final class SseWriteFailedException extends RuntimeException {
        SseWriteFailedException(int sentTokens, Throwable cause) {
            super("SSE write failed at token #" + sentTokens + ": " + cause, cause);
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

        // Verify conversation ownership before acquiring a concurrency permit (SEC-10)
        chatConversationService.verifyOwnership(id, false);

        // Global generation cap (PRF-15): acquire a slot on the request thread so we can still
        // reject with a clean 429 before the SSE response is committed. The slot is released when
        // the streaming task finishes (success, error, or cancellation), or immediately if
        // prepare() fails.
        if (!concurrencyLimiter.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "The assistant is at capacity right now. Please retry in a moment.");
        }

        // Validate + persist the user message on the request thread so authorization/validation
        // failures propagate as normal HTTP errors BEFORE the SSE response is committed. Only the
        // (long-running) generation streams asynchronously.
        AtomicBoolean permitReleased = new AtomicBoolean(false);
        Runnable releasePermitOnce = () -> {
            if (permitReleased.compareAndSet(false, true)) {
                concurrencyLimiter.release();
            }
        };

        PreparedChat prepared;
        try {
            prepared = chatMessageService.prepare(id, content, promptName);

            UUID assistantMessageId = UUID.randomUUID();

            AtomicReference<Future<?>> taskRef = new AtomicReference<>();
            AtomicBoolean started = new AtomicBoolean(false);
            AtomicBoolean cancelled = new AtomicBoolean(false);
            // Tokens written so far. Only ever read to describe a failure — the citation filter
            // emits one SSE event per character, so "which token" is the difference between "the
            // very first write, the one that commits the response" and "somewhere in the answer".
            AtomicInteger sentTokens = new AtomicInteger();

            Runnable cancelTask = () -> {
                cancelled.set(true);
                cancel(taskRef, started, releasePermitOnce);
            };
            emitter.onCompletion(cancelTask);
            emitter.onTimeout(() -> {
                log.warn("SSE timeout for conversation: {}", id);
                cancelTask.run();
                safeComplete(emitter);
            });
            emitter.onError(t -> {
                log.warn("SSE connection error for conversation {}: {}", id, t.getMessage());
                cancelTask.run();
            });

            Future<?> task = sseExecutor.submit(() -> {
                if (!started.compareAndSet(false, true)) {
                    return;
                }
                try {
                    chatCancellationService.register(id, Thread.currentThread());
                    chatMessageService.stream(prepared, assistantMessageId, token -> {
                        log.trace("SSE sending token to client: '{}'", token);
                        try {
                            emitter.send(token);
                        } catch (IOException | IllegalStateException e) {
                            // Emitter completed/closed, client disconnected, or the write path
                            // itself threw: unwind the stream carrying enough to tell those apart
                            // afterwards.
                            throw new SseWriteFailedException(sentTokens.get(), e);
                        }
                        sentTokens.incrementAndGet();
                    });
                    log.info("SSE stream completed successfully for conversation: {}", id);
                    safeComplete(emitter);
                } catch (CancellationException e) {
                    log.info("SSE stream cancelled by user for conversation: {}", id);
                    // Already stopped, just complete
                    safeComplete(emitter);
                } catch (SseWriteFailedException e) {
                    // Reached only when the in-band error write failed too, so the client really is
                    // gone. Logged with the cause chain so the level, not the wording, is what
                    // keeps an ordinary mid-answer disconnect quiet.
                    log.debug("SSE stream unwound for conversation {}: {}", id, e.getMessage(), e);
                    safeComplete(emitter);
                } catch (Throwable e) {
                    // The SSE response is already committed, so report the failure in-band and
                    // complete normally — completeWithError() here would make MVC try to write a
                    // JSON error over the committed event-stream response.
                    log.error("Error during streaming generation for conversation: {}", id, e);
                    try {
                        // Never echo the raw exception message to the client — it can leak SQL /
                        // provider / stack detail. The full cause is in the server log above
                        // (SEC-17).
                        emitter.send("[ERROR] The assistant could not complete this response.");
                        emitter.send("[DONE]");
                    } catch (Throwable ex) {
                        log.debug("Failed to send error event via SSE for conversation {}", id, ex);
                    }
                    safeComplete(emitter);
                } finally {
                    try {
                        releasePermitOnce.run();
                    } finally {
                        chatCancellationService.deregister(id, Thread.currentThread());
                    }
                }
            });
            taskRef.set(task);
            if (cancelled.get()) {
                cancel(taskRef, started, releasePermitOnce);
            }
        } catch (Throwable submitError) {
            releasePermitOnce.run();
            emitter.completeWithError(submitError);
            throw submitError;
        }

        return emitter;
    }

    private static void safeComplete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Throwable ignored) {
            // Emitter was already completed or closed.
        }
    }

    private static void cancel(AtomicReference<Future<?>> taskRef, AtomicBoolean started,
        Runnable releasePermitOnce) {
        Future<?> f = taskRef.get();
        if (f != null) {
            boolean cancelled = f.cancel(true);
            if (cancelled && started.compareAndSet(false, true)) {
                // Task was cancelled before the worker entered the lambda; finally will never run.
                releasePermitOnce.run();
            }
        }
    }
}
