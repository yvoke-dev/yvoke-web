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
     * Two shapes of request arrive here. Submitting the comment form writes a row. Clicking a thumb
     * sends only a rating: if a row already exists the rating is updated and the stored comment
     * kept, otherwise nothing is persisted and the vote is shown as transient UI state (the comment
     * is what creates the row). Either way the caller is authorized for the message first, so an id
     * they don't own is rejected rather than reflected.
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
            feedback = chatFeedbackService.submitRatingPreservingComment(messageId, rating)
                .orElseGet(() -> new Feedback(null, messageId, rating, null, null, null));
        }

        model.addAttribute("messageId", messageId);
        model.addAttribute("feedback", feedback);

        return "chat/fragments/feedback-buttons";
    }
}
