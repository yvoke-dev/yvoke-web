package de.palsoftware.yvoke.chat.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.palsoftware.yvoke.chat.core.model.Conversation;
import de.palsoftware.yvoke.chat.core.model.Message;
import de.palsoftware.yvoke.chat.core.service.DesktopSyncService.NewMessage;
import de.palsoftware.yvoke.shared.user.model.User;
import de.palsoftware.yvoke.shared.user.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * PRF-11 / ARC-02: {@link DesktopSyncService#appendMessages} performs several writes (per-message
 * inserts + title/touch). A failure part-way through the batch must roll the whole thing back rather
 * than leaving orphaned messages.
 */
@SpringBootTest(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=filesystem:docker/db/migration"
})
public class DesktopSyncServiceIT {

    private static final String ENTRA_OID = "dss-it-oid";

    @Autowired
    private DesktopSyncService service;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID conversationId;

    @AfterEach
    public void tearDown() {
        if (conversationId != null) {
            jdbcTemplate.update("DELETE FROM messages WHERE conversation_id = ?", conversationId);
            jdbcTemplate.update("DELETE FROM conversations WHERE id = ?", conversationId);
        }
        userRepository.findByEntraOid(ENTRA_OID)
            .ifPresent(u -> jdbcTemplate.update("DELETE FROM users WHERE id = ?", u.id()));
    }

    @Test
    public void appendMessagesRollsBackEntireBatchOnMidBatchFailure() {
        userRepository.upsert(ENTRA_OID, "dss@local", "DSS User");
        User user = userRepository.findByEntraOid(ENTRA_OID).orElseThrow();
        Conversation conv = service.createConversation(user, "Title", Map.of());
        conversationId = conv.id();

        // First message is valid (gets saved), the second has blank content and fails mid-batch.
        List<NewMessage> batch = List.of(
            new NewMessage("user", "valid first message", null, null, null, null, null),
            new NewMessage("assistant", "   ", null, null, null, null, null));

        assertThatThrownBy(() -> service.appendMessages(conversationId, user, batch))
            .isInstanceOf(ResponseStatusException.class);

        // Atomic: the already-saved first message must have been rolled back too.
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM messages WHERE conversation_id = ?", Integer.class, conversationId);
        assertThat(count).isZero();
    }

    @Test
    public void appendMessagesPersistsWholeValidBatch() {
        userRepository.upsert(ENTRA_OID, "dss@local", "DSS User");
        User user = userRepository.findByEntraOid(ENTRA_OID).orElseThrow();
        Conversation conv = service.createConversation(user, "Title", Map.of());
        conversationId = conv.id();

        List<NewMessage> batch = List.of(
            new NewMessage("user", "first", null, null, null, null, null),
            new NewMessage("assistant", "second", null, null, null, null, null));

        List<UUID> ids = service.appendMessages(conversationId, user, batch);

        assertThat(ids).hasSize(2);
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM messages WHERE conversation_id = ?", Integer.class, conversationId);
        assertThat(count).isEqualTo(2);
    }

    /**
     * Regression: the desktop client posts a whole turn as one batch, and {@code appendMessages} is
     * {@code @Transactional} — so every row was stamped with the same {@code CURRENT_TIMESTAMP} (the
     * <em>transaction</em> start time in Postgres, not the statement time). The read path's
     * {@code ORDER BY created_at ASC, id ASC} then fell through to its tie-break on a random v4
     * {@code UUID}, so each turn rendered user-first or assistant-first by coin flip.
     *
     * <p>The batch is deliberately eight messages rather than two: the order assertion alone would
     * pass ~50% of the time on a two-row turn, but only 1-in-8! of the time here. The strictly
     * increasing {@code createdAt} assertion below is the deterministic anchor — it fails on every
     * run against {@code CURRENT_TIMESTAMP}.
     */
    @Test
    public void appendMessagesPreservesBatchOrderWithDistinctTimestamps() {
        userRepository.upsert(ENTRA_OID, "dss@local", "DSS User");
        User user = userRepository.findByEntraOid(ENTRA_OID).orElseThrow();
        Conversation conv = service.createConversation(user, "Title", Map.of());
        conversationId = conv.id();

        List<NewMessage> batch = List.of(
            new NewMessage("user", "q1", null, null, null, null, null),
            new NewMessage("assistant", "a1", null, null, null, null, null),
            new NewMessage("user", "q2", null, null, null, null, null),
            new NewMessage("assistant", "a2", null, null, null, null, null),
            new NewMessage("user", "q3", null, null, null, null, null),
            new NewMessage("assistant", "a3", null, null, null, null, null),
            new NewMessage("user", "q4", null, null, null, null, null),
            new NewMessage("assistant", "a4", null, null, null, null, null));

        service.appendMessages(conversationId, user, batch);

        List<Message> stored = service.getMessages(conversationId, user, 100, 0);

        assertThat(stored).extracting(Message::content).containsExactly("q1", "a1", "q2", "a2", "q3",
            "a3", "q4", "a4");
        assertThat(stored).extracting(Message::role).containsExactly("user", "assistant", "user",
            "assistant", "user", "assistant", "user", "assistant");

        List<Instant> timestamps = stored.stream().map(Message::createdAt).toList();
        assertThat(timestamps).doesNotContainNull().doesNotHaveDuplicates().isSorted();
    }
}
