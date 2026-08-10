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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;

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
import org.junit.jupiter.api.Assertions;

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

        Assertions.assertThrows(IllegalArgumentException.class, () -> {
            conversationRepository.create(id, userId, "New Title", settings, "invalid-source");
        });
    }

    /**
     * {@code conversations.source} is plain {@code TEXT NOT NULL DEFAULT 'web'} with no CHECK
     * constraint (V1__init_schema.sql:182), so {@code ConversationSource.fromValue} is the ONLY
     * thing standing between a caller's string and a permanent column value. It is used as a guard
     * — its return value is discarded at both call sites — which is exactly the shape a reader
     * deletes as dead code.
     *
     * <p>
     * The two sides fail differently and both are silent. On the write side, a bogus source is
     * persisted happily and the row is then invisible to every source-scoped read forever: the
     * desktop client's {@code listByUserAndSource(userId, "desktop", ...)} simply does not return
     * it, so a conversation exists, is owned, is billed for, and cannot be listed. On the read
     * side, a bogus source silently returns zero rows — a desktop client told "you have no
     * conversations" rather than "that is not a valid source", which is the single most misleading
     * answer a sync client can be given, because it looks exactly like a fresh install.
     *
     * <p>
     * Case is part of the contract and is easy to get wrong in the direction that looks friendlier:
     * {@code fromValue} compares with {@code equals}, and the column stores the lowercase form, so
     * "validate leniently, query strictly" here would mean accepting {@code "WEB"}, writing
     * {@code "WEB"}, and then never matching {@code source = 'web'} again — the same silent-empty
     * shape documented for the MCP collection/kind lookups.
     *
     * <p>
     * {@code testCreate_invalidSourceThrowsException} above covers ONE value on ONE of the two
     * entry points and asserts nothing about whether the repository reached SQL first;
     * {@code listByUserAndSource} — the read path, added later — has no coverage at all, and no
     * test anywhere in the codebase references {@code ConversationSource}.
     */
    @Test
    public void anUnknownConversationSourceIsRejectedBeforeItReachesSql() {
        UUID id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        // A client that is never stubbed: any interaction on it is a real one, so the
        // verifyNoInteractions below cannot be satisfied by a stubbing call from setUp().
        JdbcClient untouched = mock(JdbcClient.class);
        ConversationRepository guarded = new ConversationRepository(untouched, objectMapper);

        for (String rejected : new String[] {"mobile", "cli", "WEB", "Desktop", " web",
            "web'; DROP TABLE conversations; --"}) {
            assertThatThrownBy(() -> guarded.create(id, userId, "t", Map.of(), rejected))
                .as("create must refuse source '%s'", rejected)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid conversation source");
            assertThatThrownBy(() -> guarded.listByUserAndSource(userId, rejected, 10, 0))
                .as("listByUserAndSource must refuse source '%s'", rejected)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid conversation source");
        }

        assertThatThrownBy(() -> guarded.create(id, userId, "t", Map.of(), null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot be null");
        assertThatThrownBy(() -> guarded.listByUserAndSource(userId, null, 10, 0))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot be null");

        // Rejected BEFORE any SQL is built or bound — not by the database, which has no constraint
        // to reject it with.
        verifyNoInteractions(untouched);

        // The other half of the contract: exactly WEB and DESKTOP must still get through. Without
        // this the assertions above would also pass against a guard that refuses everything.
        conversationRepository.create(id, userId, "t", Map.of(), "web");
        conversationRepository.create(id, userId, "t", Map.of(), "desktop");
        conversationRepository.listByUserAndSource(userId, "desktop", 10, 0);
        verify(jdbcClient, times(2)).sql(contains("INSERT INTO conversations"));
        verify(jdbcClient).sql(contains("AND source = :source"));
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
