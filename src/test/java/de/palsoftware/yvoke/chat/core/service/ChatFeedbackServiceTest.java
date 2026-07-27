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
