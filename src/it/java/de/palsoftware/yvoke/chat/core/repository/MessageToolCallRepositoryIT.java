package de.palsoftware.yvoke.chat.core.repository;

import de.palsoftware.yvoke.chat.core.model.ToolCallRecord;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=filesystem:docker/db/migration"
})
public class MessageToolCallRepositoryIT {

    @Autowired
    private MessageToolCallRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID userId;
    private UUID conversationId;
    private UUID messageId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO users (id, entra_oid, email, display_name) VALUES (?, ?, ?, ?)",
            userId, "oid-" + userId, "user-" + userId + "@example.com", "Test User");

        conversationId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO conversations (id, user_id, title) VALUES (?, ?, ?)",
            conversationId, userId, "Test Conversation");

        messageId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO messages (id, conversation_id, role, content) VALUES (?, ?, ?, ?)",
            messageId, conversationId, "assistant", "Assistant response");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM message_tool_calls WHERE message_id = ?", messageId);
        jdbcTemplate.update("DELETE FROM messages WHERE id = ?", messageId);
        jdbcTemplate.update("DELETE FROM conversations WHERE id = ?", conversationId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    void testBatchInsertAndFindByMessageIdOrderedBySeq() {
        ToolCallRecord call2 = new ToolCallRecord(
            UUID.randomUUID(), messageId, 2, "call_2", "calculate", "{\"expr\":\"1+1\"}", "2", false, Instant.now());
        ToolCallRecord call0 = new ToolCallRecord(
            UUID.randomUUID(), messageId, 0, "call_0", "search_corpus", "{\"q\":\"search\"}", "results", false, Instant.now());
        ToolCallRecord call1 = new ToolCallRecord(
            UUID.randomUUID(), messageId, 1, "call_1", "get_section", "{\"sec\":1}", "content", true, Instant.now());

        // Insert in arbitrary order: 2, 0, 1
        repository.insertAll(messageId, List.of(call2, call0, call1));

        List<ToolCallRecord> retrieved = repository.findByMessageId(messageId);
        assertThat(retrieved).hasSize(3);

        // Verify ordered by seq ASC: 0, 1, 2
        assertThat(retrieved.get(0).seq()).isEqualTo(0);
        assertThat(retrieved.get(0).toolCallId()).isEqualTo("call_0");
        assertThat(retrieved.get(0).toolName()).isEqualTo("search_corpus");
        assertThat(retrieved.get(0).arguments()).isEqualTo("{\"q\":\"search\"}");
        assertThat(retrieved.get(0).result()).isEqualTo("results");
        assertThat(retrieved.get(0).isError()).isFalse();

        assertThat(retrieved.get(1).seq()).isEqualTo(1);
        assertThat(retrieved.get(1).toolCallId()).isEqualTo("call_1");
        assertThat(retrieved.get(1).toolName()).isEqualTo("get_section");
        assertThat(retrieved.get(1).arguments()).isEqualTo("{\"sec\":1}");
        assertThat(retrieved.get(1).result()).isEqualTo("content");
        assertThat(retrieved.get(1).isError()).isTrue();

        assertThat(retrieved.get(2).seq()).isEqualTo(2);
        assertThat(retrieved.get(2).toolCallId()).isEqualTo("call_2");
        assertThat(retrieved.get(2).toolName()).isEqualTo("calculate");
        assertThat(retrieved.get(2).arguments()).isEqualTo("{\"expr\":\"1+1\"}");
        assertThat(retrieved.get(2).result()).isEqualTo("2");
        assertThat(retrieved.get(2).isError()).isFalse();
    }

    @Test
    void testInsertAllWithNullOrEmptyIsNoOp() {
        repository.insertAll(messageId, null);
        repository.insertAll(messageId, List.of());

        List<ToolCallRecord> retrieved = repository.findByMessageId(messageId);
        assertThat(retrieved).isEmpty();
    }

    @Test
    void testDuplicateKeyException() {
        ToolCallRecord callA = new ToolCallRecord(
            UUID.randomUUID(), messageId, 0, "call_a", "search_corpus", "{}", "res_a", false, Instant.now());
        ToolCallRecord callB = new ToolCallRecord(
            UUID.randomUUID(), messageId, 0, "call_b", "search_corpus", "{}", "res_b", false, Instant.now());

        // Same message and same seq: must throw DataIntegrityViolationException
        assertThatThrownBy(() -> repository.insertAll(messageId, List.of(callA, callB)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void testNullByteSanitization() {
        // Postgres text columns reject null byte \u0000. Sanitization must remove it cleanly.
        ToolCallRecord callWithNullBytes = new ToolCallRecord(
            UUID.randomUUID(),
            messageId,
            0,
            "call\u0000_id",
            "tool\u0000_name",
            "{\"arg\": \"val\u0000ue\"}",
            "res\u0000ult",
            false,
            Instant.now()
        );

        repository.insertAll(messageId, List.of(callWithNullBytes));

        List<ToolCallRecord> retrieved = repository.findByMessageId(messageId);
        assertThat(retrieved).hasSize(1);
        ToolCallRecord actual = retrieved.get(0);
        assertThat(actual.toolCallId()).isEqualTo("call_id");
        assertThat(actual.toolName()).isEqualTo("tool_name");
        assertThat(actual.arguments()).isEqualTo("{\"arg\": \"value\"}");
        assertThat(actual.result()).isEqualTo("result");
    }

    @Test
    void testExtremePayloads() {
        // Build 100KB arguments and 200KB result strings
        String largeArguments = "{\"data\":\"" + "a".repeat(100_000) + "\"}";
        String largeResult = "result-payload-" + "x".repeat(200_000);

        ToolCallRecord largeRecord = new ToolCallRecord(
            UUID.randomUUID(),
            messageId,
            0,
            "call_large",
            "search_large",
            largeArguments,
            largeResult,
            false,
            Instant.now()
        );

        repository.insertAll(messageId, List.of(largeRecord));

        List<ToolCallRecord> retrieved = repository.findByMessageId(messageId);
        assertThat(retrieved).hasSize(1);
        ToolCallRecord actual = retrieved.get(0);
        assertThat(actual.arguments()).isEqualTo(largeArguments);
        assertThat(actual.result()).isEqualTo(largeResult);
    }
}
