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

    @Test
    void testFindByMessageIdMatchesAssistantMessageId() {
        UUID userMsgId = UUID.randomUUID();
        UUID assistantMsgId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO messages (id, conversation_id, role, content) VALUES (?, ?, 'user', 'Question')",
            userMsgId, conversationId);
        jdbcTemplate.update(
            "INSERT INTO messages (id, conversation_id, role, content) VALUES (?, ?, 'assistant', 'Answer')",
            assistantMsgId, conversationId);

        UUID runId = UUID.randomUUID();
        agentRunRepository.create(runId, conversationId, "mas-profile", null);
        // Filed under user message id (as web orchestrated flow does)
        agentRunRepository.finish(runId, userMsgId, "done", 1, null, 10, 20, 30, 0, 0, null);
        // Link delivered assistant message id
        agentRunRepository.updateAssistantMessageId(runId, assistantMsgId);

        // Found by assistant message id
        Optional<AgentRun> byAssistant = agentRunRepository.findByMessageId(assistantMsgId);
        assertThat(byAssistant).isPresent();
        assertThat(byAssistant.get().id()).isEqualTo(runId);
        assertThat(byAssistant.get().assistantMessageId()).isEqualTo(assistantMsgId);
        assertThat(byAssistant.get().messageId()).isEqualTo(userMsgId);

        // Also still found by user message id
        Optional<AgentRun> byUser = agentRunRepository.findByMessageId(userMsgId);
        assertThat(byUser).isPresent();
        assertThat(byUser.get().id()).isEqualTo(runId);
    }

    @Test
    void testFindByMessageIdTieBreakerOnIdDescWhenStartedAtMatches() {
        UUID smallerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID largerId = UUID.fromString("00000000-0000-0000-0000-000000000002");

        agentRunRepository.create(smallerId, conversationId, "profile1", null);
        agentRunRepository.finish(smallerId, messageId, "done", 1, null, 10, 20, 30, 0, 0, null);

        agentRunRepository.create(largerId, conversationId, "profile2", null);
        agentRunRepository.finish(largerId, messageId, "done", 2, null, 10, 20, 30, 0, 0, null);

        // Set identical started_at timestamp
        jdbcTemplate.update("UPDATE agent_runs SET started_at = '2026-01-01 12:00:00+00' WHERE conversation_id = ?", conversationId);

        Optional<AgentRun> found = agentRunRepository.findByMessageId(messageId);
        assertThat(found).isPresent();
        // largerId should win via id DESC tie-breaker
        assertThat(found.get().id()).isEqualTo(largerId);
    }
}
