package de.palsoftware.yvoke.chat.core.repository;

import de.palsoftware.yvoke.chat.core.model.Message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.rag.core.service.CitationVerifier.CitationCheckResult;
import de.palsoftware.yvoke.rag.core.service.CitationVerifier.CitationStatus;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

public class MessageRepositoryTest {

    private JdbcClient jdbcClient;
    private ObjectMapper objectMapper;
    private MessageRepository messageRepository;

    private JdbcClient.StatementSpec statementSpec;
    private JdbcClient.MappedQuerySpec<Message> querySpec;

    @SuppressWarnings("unchecked")
    @BeforeEach
    public void setUp() {
        jdbcClient = mock(JdbcClient.class);
        objectMapper = new ObjectMapper();
        messageRepository = new MessageRepository(jdbcClient, objectMapper);

        statementSpec = mock(JdbcClient.StatementSpec.class);
        querySpec = (JdbcClient.MappedQuerySpec<Message>) mock(JdbcClient.MappedQuerySpec.class);

        when(jdbcClient.sql(anyString())).thenReturn(statementSpec);
        when(statementSpec.param(anyString(), any())).thenReturn(statementSpec);
        when(statementSpec.query(any(RowMapper.class))).thenReturn(querySpec);
    }

    @Test
    public void testSave() {
        UUID id = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        CitationCheckResult citation =
            new CitationCheckResult("[chunk_id=abc]", "chunk", CitationStatus.REAL, "1 chunk(s)");

        Message message = new Message(id, conversationId, "assistant", "Hello world",
            List.of(chunkId), List.of(citation), null);

        messageRepository.save(message);

        verify(jdbcClient).sql(contains("INSERT INTO messages"));
        verify(statementSpec).param("id", id);
        verify(statementSpec).param("conversationId", conversationId);
        verify(statementSpec).param("role", "assistant");
        verify(statementSpec).param("content", "Hello world");
        verify(statementSpec).param(eq("retrievedChunkIds"), any(UUID[].class));
        verify(statementSpec).param(eq("citations"), anyString());
        verify(statementSpec).update();
    }

    @Test
    public void testSave_invalidRoleThrowsException() {
        UUID id = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        Message message = new Message(id, conversationId, "invalid-role", "Hello world",
            Collections.emptyList(), Collections.emptyList(), null);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> {
            messageRepository.save(message);
        });
    }

    @Test
    public void testFindById() {
        UUID id = UUID.randomUUID();
        Message mockMsg = new Message(id, UUID.randomUUID(), "user", "hi", Collections.emptyList(),
            Collections.emptyList(), null);
        when(querySpec.optional()).thenReturn(Optional.of(mockMsg));

        Optional<Message> result = messageRepository.findById(id);

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(id);
        verify(jdbcClient).sql(contains("SELECT id, conversation_id"));
        verify(statementSpec).param("id", id);
    }
}
