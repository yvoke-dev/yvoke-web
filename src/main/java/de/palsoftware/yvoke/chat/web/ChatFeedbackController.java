package de.palsoftware.yvoke.chat.web;

import de.palsoftware.yvoke.chat.core.service.ChatFeedbackService;
import de.palsoftware.yvoke.chat.core.model.Feedback;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Controller
@RequestMapping("/chat")
public class ChatFeedbackController {
    private static final Logger log = LoggerFactory.getLogger(ChatFeedbackController.class);

    private final ChatFeedbackService chatFeedbackService;

    public ChatFeedbackController(ChatFeedbackService chatFeedbackService) {
        this.chatFeedbackService = chatFeedbackService;
    }

    /**
     * Records feedback on an assistant message and returns the re-rendered widget for htmx to swap
     * in.
     *
     * <p>
     * Two shapes of request arrive here, and BOTH write a row. Submitting the comment form stores
     * rating and comment together. Clicking a thumb sends only a rating: an existing row is updated
     * with its stored comment kept, and where no row exists yet one is created with a null comment
     * — a bare thumb is never discarded, so the interface cannot show a vote the server did not
     * save. An empty result is therefore a failed write and throws, rather than being papered over
     * with a synthetic row that renders as if it had persisted. Either way the caller is authorized
     * for the message first, so an id they don't own is rejected rather than reflected.
     */
    @PostMapping("/message/{messageId}/feedback")
    public String submitFeedback(@PathVariable UUID messageId, @RequestParam("rating") int rating,
        @RequestParam(name = "comment", required = false) String comment, Model model) {
        // Not expressible as @Min/@Max: 0 sits inside the range but is not a vote. The repository
        // accepts -1..5, so without this a hand-crafted request could store a rating the admin
        // feedback dashboard never renders.
        if (rating != 1 && rating != -1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "rating must be 1 or -1, was: " + rating);
        }
        // The comment is the user's free text — log that feedback arrived, not what it said.
        log.info("Submitting feedback for message {}: rating={}", messageId, rating);

        Feedback feedback;
        if (comment != null && !comment.trim().isEmpty()) {
            chatFeedbackService.submitFeedback(messageId, rating, comment);
            // The fragment claims "Feedback saved" off any non-empty comment on this record, so it
            // must be the row that really is in the database. Rebuilding one from the request
            // params
            // would report success for a write that silently did nothing.
            feedback = chatFeedbackService.getFeedback(messageId)
                .orElseThrow(() -> new IllegalStateException(
                    "feedback for message " + messageId + " was not persisted"));
        } else {
            // Always persisted now, including a bare first rating — so an empty result means the
            // write did not land, and must surface rather than be papered over with a synthetic row
            // that renders as though it had. Matches the commented branch above.
            feedback = chatFeedbackService.submitRatingPreservingComment(messageId, rating)
                .orElseThrow(() -> new IllegalStateException(
                    "rating for message " + messageId + " was not persisted"));
        }

        model.addAttribute("messageId", messageId);
        model.addAttribute("feedback", feedback);

        return "chat/fragments/feedback-buttons";
    }
}
