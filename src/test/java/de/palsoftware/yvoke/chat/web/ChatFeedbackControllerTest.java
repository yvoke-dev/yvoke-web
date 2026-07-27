package de.palsoftware.yvoke.chat.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.palsoftware.yvoke.chat.core.model.Feedback;
import de.palsoftware.yvoke.chat.core.service.ChatFeedbackService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.web.server.ResponseStatusException;

/**
 * The feedback fragment claims "Feedback saved ✅" whenever the {@code feedback} model attribute
 * carries a non-empty comment, so the controller must only ever hand it a record that came back
 * from the database — never one rebuilt from the request. These tests pin that, plus the fact that
 * the rating-only branch (the request the UI's thumbs buttons actually send) is authorized even
 * though it deliberately persists nothing.
 */
@ExtendWith(MockitoExtension.class)
class ChatFeedbackControllerTest {

    private static final String FRAGMENT = "chat/fragments/feedback-buttons";

    @Mock
    private ChatFeedbackService chatFeedbackService;

    @InjectMocks
    private ChatFeedbackController controller;

    @Test
    void doesNotClaimFeedbackWasSavedWhenTheWriteDidNotLand() {
        UUID messageId = UUID.randomUUID();
        // The upsert reported success but the row is not there. The controller used to rebuild a
        // Feedback from the request params here, rendering "Feedback saved" over nothing.
        when(chatFeedbackService.getFeedback(messageId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.submitFeedback(messageId, -1, "Answer was wrong.",
            new ConcurrentModel())).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(messageId.toString());
    }

    @Test
    void rendersThePersistedRowAfterSubmittingAComment() {
        UUID messageId = UUID.randomUUID();
        Feedback persisted = new Feedback(UUID.randomUUID(), messageId, -1, "Answer was wrong.",
            Instant.now(), Instant.now());
        when(chatFeedbackService.getFeedback(messageId)).thenReturn(Optional.of(persisted));
        Model model = new ConcurrentModel();

        String view = controller.submitFeedback(messageId, -1, "Answer was wrong.", model);

        verify(chatFeedbackService).submitFeedback(messageId, -1, "Answer was wrong.");
        assertThat(view).isEqualTo(FRAGMENT);
        assertThat(model.getAttribute("feedback")).isSameAs(persisted);
    }

    @Test
    void authorizesTheMessageBeforeEchoingBackARatingWithNoComment() {
        UUID messageId = UUID.randomUUID();
        when(chatFeedbackService.submitRatingPreservingComment(messageId, 1))
            .thenReturn(Optional.empty());
        Model model = new ConcurrentModel();

        String view = controller.submitFeedback(messageId, 1, null, model);

        // Nothing is persisted for a first bare rating (by design), but the service call is what
        // authorizes the caller — otherwise the endpoint reflects state for any id it is handed.
        verify(chatFeedbackService).submitRatingPreservingComment(messageId, 1);
        verify(chatFeedbackService, never()).submitFeedback(any(), anyInt(), any());
        assertThat(view).isEqualTo(FRAGMENT);
        assertThat(((Feedback) model.getAttribute("feedback")).rating()).isEqualTo(1);
    }

    @Test
    void treatsAWhitespaceOnlyCommentAsNoComment() {
        UUID messageId = UUID.randomUUID();
        when(chatFeedbackService.submitRatingPreservingComment(messageId, -1))
            .thenReturn(Optional.empty());

        controller.submitFeedback(messageId, -1, "   ", new ConcurrentModel());

        verify(chatFeedbackService).submitRatingPreservingComment(messageId, -1);
        verify(chatFeedbackService, never()).submitFeedback(any(), anyInt(), any());
    }

    /**
     * Flipping the thumb after commenting must not wipe the comment. The buttons no longer send it
     * back (it would sit in the request URL), so the server is the only thing that can preserve it.
     */
    @Test
    void keepsTheStoredCommentWhenOnlyTheRatingChanges() {
        UUID messageId = UUID.randomUUID();
        Feedback flipped = new Feedback(UUID.randomUUID(), messageId, 1, "was wrong, now fixed",
            Instant.now(), Instant.now());
        when(chatFeedbackService.submitRatingPreservingComment(messageId, 1))
            .thenReturn(Optional.of(flipped));
        Model model = new ConcurrentModel();

        controller.submitFeedback(messageId, 1, null, model);

        assertThat(model.getAttribute("feedback")).isSameAs(flipped);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2, 5, -2, 42})
    void rejectsARatingThatIsNotAVote(int rating) {
        UUID messageId = UUID.randomUUID();

        assertThatThrownBy(
            () -> controller.submitFeedback(messageId, rating, "whatever", new ConcurrentModel()))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining(HttpStatus.BAD_REQUEST.toString());

        verifyNoInteractions(chatFeedbackService);
    }
}
