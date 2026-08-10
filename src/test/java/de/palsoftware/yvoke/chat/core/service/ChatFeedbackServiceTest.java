package de.palsoftware.yvoke.chat.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import de.palsoftware.yvoke.chat.core.model.Feedback;
import de.palsoftware.yvoke.chat.core.model.Message;
import de.palsoftware.yvoke.chat.core.repository.FeedbackRepository;
import de.palsoftware.yvoke.chat.core.repository.MessageRepository;
import java.time.Instant;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ChatFeedbackServiceTest {
    private FeedbackRepository feedbackRepository;
    private MessageRepository messageRepository;
    private ChatConversationService chatConversationService;
    private ChatFeedbackService chatFeedbackService;

    @BeforeEach
    public void setUp() {
        feedbackRepository = mock(FeedbackRepository.class);
        messageRepository = mock(MessageRepository.class);
        chatConversationService = mock(ChatConversationService.class);

        chatFeedbackService =
            new ChatFeedbackService(feedbackRepository, messageRepository, chatConversationService);
    }

    @Test
    public void testSubmitFeedback() {
        UUID messageId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        Message message = new Message(messageId, conversationId, "assistant", "Response content",
            Collections.emptyList(), Collections.emptyList(), Instant.now());

        when(messageRepository.findById(messageId)).thenReturn(Optional.of(message));

        chatFeedbackService.submitFeedback(messageId, 5, "Good answer");

        verify(chatConversationService).verifyOwnership(conversationId, false);
        verify(feedbackRepository).upsert(messageId, 5, "Good answer");
    }

    /**
     * A thumbs-up with no comment MUST be persisted.
     *
     * <p>
     * It used to be discarded: this method returned empty when no row existed yet, and the caller
     * rendered the vote as transient UI state — so the user clicked the thumb, watched it light up,
     * and lost it on the next page load. Two consequences. The interface showed a vote it had not
     * saved, which is the part that matters to the person using it. And the desktop API stores
     * exactly this case ({@code DesktopSyncService.submitFeedback} demands a comment only for a
     * NEGATIVE rating), so one satisfaction ratio was being computed over two different collection
     * rules, systematically undercounting web positives.
     *
     * <p>
     * The stated reason for the old behaviour does not cover it: the comment-in-the-URL problem the
     * javadoc referred to was about free text reaching access logs, not about whether a bare rating
     * is stored.
     */
    @Test
    public void aBareThumbsUpIsPersistedRatherThanRenderedAsTransientUiState() {
        UUID messageId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        Message message = new Message(messageId, conversationId, "assistant", "Response content",
            Collections.emptyList(), Collections.emptyList(), Instant.now());
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(message));
        // No existing row: this is the user's FIRST interaction with the rating buttons.
        when(feedbackRepository.findByMessageId(messageId)).thenReturn(Optional.empty(), Optional
            .of(new Feedback(UUID.randomUUID(), messageId, 1, null, Instant.now(), Instant.now())));

        Optional<Feedback> result = chatFeedbackService.submitRatingPreservingComment(messageId, 1);

        verify(feedbackRepository).upsert(messageId, 1, null);
        assertThat(result).as("the caller must get back a stored row, not empty").isPresent();
        assertThat(result.get().rating()).isEqualTo(1);
    }

    /** The complement: an existing comment must survive a later change of vote. */
    @Test
    public void changingTheVoteKeepsTheCommentAlreadyStored() {
        UUID messageId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        Message message = new Message(messageId, conversationId, "assistant", "Response content",
            Collections.emptyList(), Collections.emptyList(), Instant.now());
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(message));
        when(feedbackRepository.findByMessageId(messageId))
            .thenReturn(Optional.of(new Feedback(UUID.randomUUID(), messageId, 1, "was helpful",
                Instant.now(), Instant.now())));

        chatFeedbackService.submitRatingPreservingComment(messageId, -1);

        verify(feedbackRepository).upsert(messageId, -1, "was helpful");
    }

    @Test
    public void testGetFeedback() {
        UUID messageId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        Message message = new Message(messageId, conversationId, "assistant", "Response content",
            Collections.emptyList(), Collections.emptyList(), Instant.now());
        Feedback mockFeedback = new Feedback(UUID.randomUUID(), messageId, 5, "Good answer",
            Instant.now(), Instant.now());

        when(messageRepository.findById(messageId)).thenReturn(Optional.of(message));
        when(feedbackRepository.findByMessageId(messageId)).thenReturn(Optional.of(mockFeedback));

        Optional<Feedback> result = chatFeedbackService.getFeedback(messageId);

        assertThat(result).isPresent();
        assertThat(result.get().rating()).isEqualTo(5);
        assertThat(result.get().comment()).isEqualTo("Good answer");

        verify(chatConversationService).verifyOwnership(conversationId, true);
    }
}
