package de.palsoftware.yvoke.chat.core.repository;

import de.palsoftware.yvoke.chat.core.model.Message;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@SpringBootTest(
    properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=filesystem:docker/db/migration"
    })
public class MessageRepositoryIT {

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private UUID userId;
    private UUID conversationId;

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
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM messages WHERE conversation_id = ?", conversationId);
        jdbcTemplate.update("DELETE FROM conversations WHERE id = ?", conversationId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    void findInitialPlaybook_emptyConversation_returnsEmpty() {
        Optional<String> initial = messageRepository.findInitialPlaybook(conversationId);
        assertThat(initial).isEmpty();
    }

    @Test
    void findInitialPlaybook_singleUserMessageWithPlaybook_returnsTrimmedPlaybook() {
        UUID msgId = UUID.randomUUID();
        Message userMsg = new Message(msgId, conversationId, "user", "What is an account?",
            "  onboarding-playbook  ", Collections.emptyList(), Collections.emptyList(),
            Instant.now());
        messageRepository.save(userMsg);

        Optional<String> initial = messageRepository.findInitialPlaybook(conversationId);
        assertThat(initial).contains("onboarding-playbook");
    }

    @Test
    void findInitialPlaybook_assistantMessageWithPlaybook_isIgnored() {
        UUID asstId = UUID.randomUUID();
        Message asstMsg = new Message(asstId, conversationId, "assistant", "Answer",
            "asst-playbook", Collections.emptyList(), Collections.emptyList(), Instant.now());
        messageRepository.save(asstMsg);

        Optional<String> initial = messageRepository.findInitialPlaybook(conversationId);
        assertThat(initial).isEmpty();
    }

    @Test
    void findInitialPlaybook_userMessageWithBlankPlaybook_isIgnored() {
        UUID msgId = UUID.randomUUID();
        Message blankMsg = new Message(msgId, conversationId, "user", "Question",
            "   ", Collections.emptyList(), Collections.emptyList(), Instant.now());
        messageRepository.save(blankMsg);

        Optional<String> initial = messageRepository.findInitialPlaybook(conversationId);
        assertThat(initial).isEmpty();
    }

    @Test
    void findInitialPlaybook_desktopMessageWithoutPlaybookThenPlaybookMessage_returnsInitialPlaybook() {
        UUID msg1Id = UUID.randomUUID();
        Instant t1 = Instant.now().minusSeconds(30);
        Message desktopMsg = new Message(msg1Id, conversationId, "user", "Desktop question",
            null, Collections.emptyList(), Collections.emptyList(), t1);
        messageRepository.save(desktopMsg);

        UUID msg2Id = UUID.randomUUID();
        Instant t2 = Instant.now().minusSeconds(10);
        Message webMsg = new Message(msg2Id, conversationId, "user", "Web question",
            "pb-first", Collections.emptyList(), Collections.emptyList(), t2);
        messageRepository.save(webMsg);

        UUID msg3Id = UUID.randomUUID();
        Instant t3 = Instant.now();
        Message laterMsg = new Message(msg3Id, conversationId, "user", "Later question",
            "pb-second", Collections.emptyList(), Collections.emptyList(), t3);
        messageRepository.save(laterMsg);

        Optional<String> initial = messageRepository.findInitialPlaybook(conversationId);
        assertThat(initial).contains("pb-first");
    }

    @Test
    void lockForUpdate_executesSuccessfullyInsideTransaction() {
        assertThatCode(() -> {
            transactionTemplate.executeWithoutResult(status -> {
                conversationRepository.lockForUpdate(conversationId);
            });
        }).doesNotThrowAnyException();
    }

    @Test
    void hasInDepthTurns_onlyPlaybookMessages_returnsFalse() {
        UUID msgId = UUID.randomUUID();
        Message userMsg = new Message(msgId, conversationId, "user", "Question",
            "pb-standard", Collections.emptyList(), Collections.emptyList(), Instant.now());
        messageRepository.save(userMsg);

        assertThat(messageRepository.hasInDepthTurns(conversationId)).isFalse();
    }

    @Test
    void hasInDepthTurns_messageWithNullOrBlankPlaybook_returnsTrue() {
        UUID msgId = UUID.randomUUID();
        Message inDepthMsg = new Message(msgId, conversationId, "user", "Orchestrated Question",
            null, Collections.emptyList(), Collections.emptyList(), Instant.now());
        messageRepository.save(inDepthMsg);

        assertThat(messageRepository.hasInDepthTurns(conversationId)).isTrue();
    }
}
