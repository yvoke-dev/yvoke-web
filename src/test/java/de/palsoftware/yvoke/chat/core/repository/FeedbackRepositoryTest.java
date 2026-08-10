package de.palsoftware.yvoke.chat.core.repository;

import de.palsoftware.yvoke.chat.core.model.Feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.junit.jupiter.api.Assertions;

public class FeedbackRepositoryTest {

    private JdbcClient jdbcClient;
    private FeedbackRepository feedbackRepository;

    private JdbcClient.StatementSpec statementSpec;
    private JdbcClient.MappedQuerySpec<Feedback> querySpec;

    @SuppressWarnings("unchecked")
    @BeforeEach
    public void setUp() {
        jdbcClient = mock(JdbcClient.class);
        feedbackRepository = new FeedbackRepository(jdbcClient);

        statementSpec = mock(JdbcClient.StatementSpec.class);
        querySpec = (JdbcClient.MappedQuerySpec<Feedback>) mock(JdbcClient.MappedQuerySpec.class);

        when(jdbcClient.sql(anyString())).thenReturn(statementSpec);
        when(statementSpec.param(anyString(), any())).thenReturn(statementSpec);
        when(statementSpec.query(any(RowMapper.class))).thenReturn(querySpec);
    }

    @Test
    public void testUpsert() {
        UUID messageId = UUID.randomUUID();

        feedbackRepository.upsert(messageId, 1, "Good answer");

        verify(jdbcClient).sql(contains("INSERT INTO message_feedback"));
        verify(statementSpec).param("messageId", messageId);
        verify(statementSpec).param("rating", 1);
        verify(statementSpec).param("comment", "Good answer");
        verify(statementSpec).update();
    }

    @Test
    public void testUpsert_invalidRatingThrowsException() {
        UUID messageId = UUID.randomUUID();

        Assertions.assertThrows(IllegalArgumentException.class, () -> {
            feedbackRepository.upsert(messageId, -2, "Bad rating");
        });

        Assertions.assertThrows(IllegalArgumentException.class, () -> {
            feedbackRepository.upsert(messageId, 6, "Bad rating");
        });
    }

    @Test
    public void testFindByMessageId() {
        UUID messageId = UUID.randomUUID();
        Feedback mockFeedback = new Feedback(UUID.randomUUID(), messageId, -1, "Bad", null, null);
        when(querySpec.optional()).thenReturn(Optional.of(mockFeedback));

        Optional<Feedback> result = feedbackRepository.findByMessageId(messageId);

        assertThat(result).isPresent();
        assertThat(result.get().messageId()).isEqualTo(messageId);
        assertThat(result.get().rating()).isEqualTo(-1);
        verify(jdbcClient).sql(contains("SELECT id, message_id"));
        verify(statementSpec).param("messageId", messageId);
    }
}
