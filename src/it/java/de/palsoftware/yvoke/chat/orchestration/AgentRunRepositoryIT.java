package de.palsoftware.yvoke.chat.orchestration;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=filesystem:docker/db/migration"
})
public class AgentRunRepositoryIT {

    @Autowired
    private AgentRunRepository agentRunRepository;

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
            userId, "oid-" + userId, "run-" + userId + "@example.com", "Run User");

        conversationId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO conversations (id, user_id, title) VALUES (?, ?, ?)",
            conversationId, userId, "Run Conversation");

        messageId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO messages (id, conversation_id, role, content) VALUES (?, ?, ?, ?)",
            messageId, conversationId, "assistant", "Agent response");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM agent_runs WHERE conversation_id = ?", conversationId);
        jdbcTemplate.update("DELETE FROM messages WHERE id = ?", messageId);
        jdbcTemplate.update("DELETE FROM conversations WHERE id = ?", conversationId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    void testFindByMessageIdReturnsLatestRunWhenPresent() {
        UUID olderRunId = UUID.randomUUID();
        UUID newerRunId = UUID.randomUUID();

        // Create older run
        agentRunRepository.create(olderRunId, conversationId, "default", null);
        agentRunRepository.finish(olderRunId, messageId, "completed", 1, null, 10, 20, 30, 0, 0, null);

        // Adjust started_at of older run to the past
        jdbcTemplate.update("UPDATE agent_runs SET started_at = started_at - INTERVAL '10 seconds' WHERE id = ?", olderRunId);

        // Create newer run for same message
        agentRunRepository.create(newerRunId, conversationId, "default", null);
        agentRunRepository.finish(newerRunId, messageId, "completed", 2, null, 40, 50, 90, 5, 0, null);

        Optional<AgentRun> found = agentRunRepository.findByMessageId(messageId);
        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(newerRunId);
        assertThat(found.get().reviewRounds()).isEqualTo(2);
        assertThat(found.get().totalTokens()).isEqualTo(90);
    }

    @Test
    void testFindByMessageIdReturnsEmptyWhenNoMatch() {
        UUID nonExistentMessageId = UUID.randomUUID();
        Optional<AgentRun> found = agentRunRepository.findByMessageId(nonExistentMessageId);
        assertThat(found).isEmpty();
    }
}
