package de.palsoftware.yvoke.chat.core.service;

import de.palsoftware.yvoke.chat.core.model.Feedback;
import de.palsoftware.yvoke.chat.core.model.Message;
import de.palsoftware.yvoke.chat.core.repository.FeedbackRepository;
import de.palsoftware.yvoke.chat.core.repository.MessageRepository;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ChatFeedbackService {
    private final FeedbackRepository feedbackRepository;
    private final MessageRepository messageRepository;
    private final ChatConversationService chatConversationService;

    public ChatFeedbackService(FeedbackRepository feedbackRepository,
        MessageRepository messageRepository, ChatConversationService chatConversationService) {
        this.feedbackRepository = feedbackRepository;
        this.messageRepository = messageRepository;
        this.chatConversationService = chatConversationService;
    }

    /**
     * Records a rating, keeping any comment already stored.
     *
     * <p>
     * Exists so the rating buttons don't have to round-trip the user's comment back to the server
     * to avoid erasing it — it used to ride in the request URL, which put free text into access
     * logs.
     *
     * <p>
     * A bare first rating (no row yet, so no comment to preserve) is stored with a null comment
     * rather than discarded. It used to return empty here and the caller rendered the vote as
     * transient UI state, which meant the user clicked the thumb, watched it light up, and lost it
     * on the next page load — an interface showing a vote it had not saved. It also made the
     * satisfaction ratio incomparable across surfaces, because the desktop API stores exactly this
     * case ({@code DesktopSyncService.submitFeedback} requires a comment only for a NEGATIVE
     * rating), so one figure was computed over two different collection rules. Note the
     * comment-in-the-URL problem above is unrelated: it concerned free text reaching access logs,
     * not whether a bare rating is persisted.
     *
     * <p>
     * Authorizes as a write, so an admin cannot re-rate another user's answer.
     */
    public Optional<Feedback> submitRatingPreservingComment(UUID messageId, int rating) {
        resolveOwnedMessage(messageId, false);
        String existingComment =
            feedbackRepository.findByMessageId(messageId).map(Feedback::comment).orElse(null);
        feedbackRepository.upsert(messageId, rating, existingComment);
        return feedbackRepository.findByMessageId(messageId);
    }

    public void submitFeedback(UUID messageId, int rating, String comment) {
        resolveOwnedMessage(messageId, false);
        feedbackRepository.upsert(messageId, rating, comment);
    }

    public Optional<Feedback> getFeedback(UUID messageId) {
        resolveOwnedMessage(messageId, true);
        return feedbackRepository.findByMessageId(messageId);
    }

    /**
     * Every feedback row for a conversation, keyed by message id, in a single query.
     *
     * <p>
     * The thread view renders a feedback widget per assistant message. Resolving those one id at a
     * time costs three statements each — message lookup, ownership lookup, feedback select — so a
     * long thread became dozens of round-trips. Ownership is checked once for the conversation with
     * reads allowed, matching {@link #getFeedback}: an admin viewing another user's thread still
     * sees its ratings.
     *
     * <p>
     * {@code message_feedback} is unique per {@code message_id}, so the collector cannot see a
     * duplicate key.
     */
    public Map<UUID, Feedback> getFeedbackByConversation(UUID conversationId) {
        chatConversationService.checkChatEnabled();
        chatConversationService.verifyOwnership(conversationId, true);
        return feedbackRepository.findByConversationId(conversationId).stream()
            .collect(Collectors.toMap(Feedback::messageId, Function.identity()));
    }

    /**
     * {@code readOnlyAllowed=false} for writes, so the carve-outs in
     * {@code verifyConversationOwnership} (ROLE_ADMIN, "public"-tagged conversations) grant reads
     * only — an admin may view another user's thread but not rate it, matching every other chat
     * write.
     */
    private Message resolveOwnedMessage(UUID messageId, boolean readOnlyAllowed) {
        chatConversationService.checkChatEnabled();
        Message message = messageRepository.findById(messageId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Message not found: " + messageId));
        chatConversationService.verifyOwnership(message.conversationId(), readOnlyAllowed);
        return message;
    }
}
