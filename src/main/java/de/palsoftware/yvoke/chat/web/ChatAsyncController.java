package de.palsoftware.yvoke.chat.web;

import de.palsoftware.yvoke.chat.core.model.Message;
import de.palsoftware.yvoke.chat.core.service.ChatConversationService;
import de.palsoftware.yvoke.chat.core.service.ChatMessageService;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatAsyncController {
    private static final Logger log = LoggerFactory.getLogger(ChatAsyncController.class);

    private final ChatMessageService chatMessageService;
    private final ChatConversationService chatConversationService;

    public ChatAsyncController(ChatMessageService chatMessageService,
        ChatConversationService chatConversationService) {
        this.chatMessageService = chatMessageService;
        this.chatConversationService = chatConversationService;
    }

    @PostMapping(value = "/chat/{id}/send-async", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> sendMessageAsync(@PathVariable UUID id,
        @RequestParam("content") String content,
        @RequestParam(value = "promptName", required = false) String promptName) {
        log.info("Async chat requested for conversation: {}, promptName: '{}'", id, promptName);
        if (content == null || content.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            UUID assistantMessageId =
                chatMessageService.prepareAndSubmitAsync(id, content, promptName);
            return ResponseEntity.accepted()
                .body(Map.of("assistantMessageId", assistantMessageId.toString()));
        } catch (Exception e) {
            log.error("Failed to prepare and submit async chat", e);
            throw e; // Let standard exception handler or Spring Security filter handle it (e.g.
                     // AccessDeniedException)
        }
    }

    @GetMapping(value = "/chat/{id}/messages/{messageId}/status",
        produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getMessageStatus(@PathVariable UUID id,
        @PathVariable UUID messageId) {
        // Verify ownership of the conversation first
        chatConversationService.verifyOwnership(id, false);

        Optional<Message> messageOpt = chatMessageService.getMessageStatus(messageId);
        if (messageOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Message message = messageOpt.get();
        // Ownership of the conversation is not enough: the message id is caller-supplied, so
        // confirm
        // the message actually belongs to this conversation before returning its content. Otherwise
        // a
        // user who owns conversation A could read any message from conversation B (SEC-08 / IDOR).
        // Answer 404 (not 403) so we don't confirm the foreign message even exists.
        if (!id.equals(message.conversationId())) {
            return ResponseEntity.notFound().build();
        }
        if ("generating".equals(message.status())) {
            return ResponseEntity.ok(Map.of("status", "generating"));
        }
        // Terminal statuses are reported as themselves. This was a bare `else` mapping everything
        // non-generating to "done", which would have presented a cancelled generation to the
        // browser as a finished answer — feedback buttons and all.
        String status = "done";
        if ("error".equals(message.status()) || "cancelled".equals(message.status())) {
            status = message.status();
        }
        return ResponseEntity.ok(Map.of("status", status, "message", message));
    }
}
