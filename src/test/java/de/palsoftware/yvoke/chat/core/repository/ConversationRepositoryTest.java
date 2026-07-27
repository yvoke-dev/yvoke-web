package de.palsoftware.yvoke.chat.core.repository;

import de.palsoftware.yvoke.chat.core.model.Conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

public class ConversationRepositoryTest {

    private JdbcClient jdbcClient;
    private ObjectMapper objectMapper;
    private ConversationRepository conversationRepository;

    private JdbcClient.StatementSpec statementSpec;
    private JdbcClient.MappedQuerySpec<Conversation> querySpec;

    @SuppressWarnings("unchecked")
    @BeforeEach
    public void setUp() {
        jdbcClient = mock(JdbcClient.class);
        objectMapper = new ObjectMapper();
        conversationRepository = new ConversationRepository(jdbcClient, objectMapper);

        statementSpec = mock(JdbcClient.StatementSpec.class);
        querySpec =
            (JdbcClient.MappedQuerySpec<Conversation>) mock(JdbcClient.MappedQuerySpec.class);

        when(jdbcClient.sql(anyString())).thenReturn(statementSpec);
        when(statementSpec.param(anyString(), any())).thenReturn(statementSpec);
        when(statementSpec.query(any(RowMapper.class))).thenReturn(querySpec);
    }

    @Test
    public void testCreate() {
        UUID id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Map<String, Object> settings = Map.of("corpus", "OIM", "limit", 8);

        conversationRepository.create(id, userId, "New Title", settings);

        ArgumentCaptor<String> settingsCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcClient).sql(contains("INSERT INTO conversations"));
        verify(statementSpec).param("id", id);
        verify(statementSpec).param("userId", userId);
        verify(statementSpec).param("title", "New Title");
        verify(statementSpec).param(eq("settings"), settingsCaptor.capture());

        String capturedSettings = settingsCaptor.getValue();
        assertThat(capturedSettings).contains("\"corpus\":\"OIM\"");
        assertThat(capturedSettings).contains("\"limit\":8");

        verify(statementSpec).update();
    }

    @Test
    public void testCreate_invalidSourceThrowsException() {
        UUID id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Map<String, Object> settings = Map.of("corpus", "OIM", "limit", 8);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> {
            conversationRepository.create(id, userId, "New Title", settings, "invalid-source");
        });
    }

    @Test
    public void testFindById() {
        UUID id = UUID.randomUUID();
        Conversation mockConv = new Conversation(id, null, "Title", Collections.emptyMap(), null,
            null, Collections.emptyList());
        when(querySpec.optional()).thenReturn(Optional.of(mockConv));

        Optional<Conversation> result = conversationRepository.findById(id);

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(id);
        verify(jdbcClient).sql(contains("SELECT id, user_id, title"));
        verify(statementSpec).param("id", id);
    }
}
