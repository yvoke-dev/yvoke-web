package de.palsoftware.yvoke.chat.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.palsoftware.yvoke.chat.core.model.Conversation;
import de.palsoftware.yvoke.chat.core.model.Message;
import de.palsoftware.yvoke.chat.core.service.DesktopSyncService.NewMessage;
import de.palsoftware.yvoke.shared.user.model.User;
import org.springframework.security.access.AccessDeniedException;
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
import org.springframework.http.HttpStatus;
import de.palsoftware.yvoke.chat.core.model.Feedback;

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

    /**
     * PATCH {@code /api/chat/v1/conversations/{id}} is partial in ONE direction and total in the
     * other, and both halves are silent data loss when they drift.
     *
     * <p>
     * A desktop client PATCHes whatever changed, so a settings-only edit carries a null title and a
     * title-only edit carries no settings. The guards ({@code title != null && !title.isBlank()},
     * {@code settings != null && !settings.isEmpty()}) are what stop those omissions from CLEARING
     * the other field. Drop them — the natural "simplify this condition" edit — and renaming a
     * conversation wipes its whole settings map, or a settings change blanks its title. Nothing
     * errors, nothing is logged, and the user notices days later that a thread lost its name or its
     * pinned chat-prompt.
     *
     * <p>
     * The opposite half is that a settings map which IS supplied replaces the stored jsonb wholesale
     * — {@code ConversationRepository.updateSettings} writes {@code SET settings = :settings::jsonb}
     * — because replacement is the only way a desktop client can REMOVE a setting (a stuck
     * chat-prompt override, say). {@code ChatConversationServiceTest#updateSettingsMergesIntoTheStoredMapInsteadOfReplacingIt}
     * pins merging in the WEB service, which sits on top of this same repository method, so if the
     * merge were pushed down into the repository that test would stay green while the desktop lost
     * the ability to delete a key forever.
     *
     * <p>
     * {@code updateConversationAppliesUpdatesAndMapsCamelCase} covers only the positive path with a
     * mocked repository, so neither the guards nor the stored jsonb is observed anywhere today.
     */
    @Test
    public void aPartialDesktopPatchNeitherClearsTheTitleNorMergesIntoTheSettingsMap() {
        userRepository.upsert(ENTRA_OID, "dss@local", "DSS User");
        User owner = userRepository.findByEntraOid(ENTRA_OID).orElseThrow();
        Conversation conv = service.createConversation(owner, "Release notes 10.0",
            Map.<String, Object>of("chat-prompt", "oim-expert", "thinking-level", "high"));
        conversationId = conv.id();

        // A PATCH that carries nothing, and one that carries blank/empty, are both no-ops.
        service.updateConversation(conversationId, owner, null, null);
        service.updateConversation(conversationId, owner, "   ", Map.of());

        assertThat(jdbcTemplate.queryForObject("SELECT title FROM conversations WHERE id = ?",
            String.class, conversationId)).as("a blank title must not clear the stored one")
            .isEqualTo("Release notes 10.0");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT settings ->> 'chat-prompt' FROM conversations WHERE id = ?", String.class,
            conversationId)).as("an empty settings map must not wipe the stored settings")
            .isEqualTo("oim-expert");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT settings ->> 'thinking-level' FROM conversations WHERE id = ?", String.class,
            conversationId)).isEqualTo("high");

        // A PATCH that DOES carry values applies them, and replaces the whole map.
        service.updateConversation(conversationId, owner, "  Renamed  ",
            Map.<String, Object>of("thinkingLevel", "low"));

        assertThat(jdbcTemplate.queryForObject("SELECT title FROM conversations WHERE id = ?",
            String.class, conversationId)).as("a real title is applied, trimmed")
            .isEqualTo("Renamed");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT settings ->> 'thinking-level' FROM conversations WHERE id = ?", String.class,
            conversationId)).as("camelCase is mapped onto the stored kebab-case key")
            .isEqualTo("low");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT settings::text FROM conversations WHERE id = ?", String.class, conversationId))
            .as("the map is REPLACED: a merge would make a setting impossible to remove")
            .doesNotContain("chat-prompt");
    }

    @AfterEach
    public void tearDown() {
        if (conversationId != null) {
            jdbcTemplate.update("DELETE FROM messages WHERE conversation_id = ?", conversationId);
            jdbcTemplate.update("DELETE FROM conversations WHERE id = ?", conversationId);
        }
        userRepository.findByEntraOid(ENTRA_OID)
            .ifPresent(u -> jdbcTemplate.update("DELETE FROM users WHERE id = ?", u.id()));
    }

    /**
     * The desktop API's ownership rule is deliberately STRICTER than the web chat's and the two
     * must not be "harmonised". {@code ChatConversationService} lets a `public`-tagged conversation
     * (and an admin) be READ by a non-owner — see spec § 2 — whereas
     * {@code DesktopSyncService.verifyOwnership} compares {@code conversation.userId()} to the
     * caller with no carve-out at all. Appearing in someone's list is not permission: adding a
     * `public` exemption here would expose another user's whole message history over
     * {@code /api/chat/v1}, and would make the read path writable too, since every mutating desktop
     * method funnels through this same check.
     */
    @Test
    public void anotherUsersPublicConversationIsNeitherReadableNorWritableOverTheDesktopApi() {
        userRepository.upsert(ENTRA_OID, "dss@local", "DSS User");
        User owner = userRepository.findByEntraOid(ENTRA_OID).orElseThrow();
        Conversation conv = service.createConversation(owner, "Owner's private thread", Map.of());
        conversationId = conv.id();
        // Even explicitly marked public, the desktop API must not open it up.
        jdbcTemplate.update("UPDATE conversations SET tags = ARRAY['public'] WHERE id = ?",
            conversationId);

        String otherOid = "dss-it-oid-other";
        userRepository.upsert(otherOid, "other@local", "Other User");
        User intruder = userRepository.findByEntraOid(otherOid).orElseThrow();
        try {
            assertThatThrownBy(() -> service.getMessages(conversationId, intruder, 100, 0))
                .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> service.appendMessages(conversationId, intruder,
                List.of(new NewMessage("user", "injected", null, null, null, null, null))))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> service.getFeedbackByMessageId(conversationId, intruder))
                .isInstanceOf(AccessDeniedException.class);

            // The listing must agree with the checks above. It used to disagree:
            // ConversationRepository.listByUserAndSource carried the web sidebar's
            // `OR 'public' = ANY(tags)` while this service has no such carve-out, so the desktop
            // API returned a conversation it then refused to open on every follow-up call — and
            // the row it returned carried a title auto-generated from the owner's FIRST MESSAGE
            // (autoTitle), i.e. their question text, disclosed to every other user.
            assertThat(service.listConversations(intruder, 100, 0))
                .as("another user's public-tagged conversation must not appear in the desktop list")
                .noneMatch(c -> c.id().equals(conversationId));

            // The complement, so the fix cannot be "return nothing": the owner still sees it.
            assertThat(service.listConversations(owner, 100, 0))
                .as("the owner must still see their own conversation")
                .anyMatch(c -> c.id().equals(conversationId));
        } finally {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", intruder.id());
        }
    }

    /**
     * The desktop DELETE is the user's ONLY way to erase a conversation, and the rows it must take
     * with it hold their verbatim questions. {@code deleteConversation} issues a single
     * {@code DELETE FROM conversations}; every message row disappears only because
     * {@code fk_messages_conversations} is {@code ON DELETE CASCADE}, and each rating only because
     * {@code fk_message_feedback_messages} cascades in turn. Nothing in Java expresses that — so if
     * the delete were ever softened to an UPDATE (a status flag, a {@code touch}) or a future
     * migration dropped a cascade, {@code DesktopChatController} would still answer 204, the client
     * would still drop the conversation from its list, and the text would live on in the database
     * forever with no error anywhere. That is why this counts the {@code messages} and
     * {@code message_feedback} rows and not just the {@code conversations} row: it makes the test
     * about data removal rather than about one statement having run.
     *
     * <p>
     * The second half pins the repeat delete, a real two-devices-one-account scenario: it must
     * surface as a clean 404 from {@code verifyOwnership} so the loser can tell "already gone" from
     * "deleted now". A silent success would teach the client the row still existed a moment ago,
     * and a 500 would read as an outage.
     */
    @Test
    public void deletingADesktopConversationTakesItsMessagesWithItAndASecondDeleteIsANotFound() {
        userRepository.upsert(ENTRA_OID, "dss@local", "DSS User");
        User owner = userRepository.findByEntraOid(ENTRA_OID).orElseThrow();
        Conversation conv = service.createConversation(owner, "Thread to erase", Map.of());
        conversationId = conv.id();

        List<NewMessage> turn = List.of(
            new NewMessage("user", "which table holds a Person?", null, null, null, null, null),
            new NewMessage("assistant", "the Person table", null, null, null, null, null));
        List<UUID> ids = service.appendMessages(conversationId, owner, turn);
        service.submitFeedback(ids.get(1), owner, -1, "wrong table");

        service.deleteConversation(conversationId, owner);

        Integer conversationRows = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM conversations WHERE id = ?", Integer.class, conversationId);
        assertThat(conversationRows).as("the conversation row must be gone, not merely flagged")
            .isZero();

        Integer messageRows = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM messages WHERE conversation_id = ?", Integer.class,
            conversationId);
        assertThat(messageRows).as("the user's verbatim questions must not survive the delete")
            .isZero();

        Integer feedbackRows = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM message_feedback WHERE message_id = ?", Integer.class,
            ids.get(1));
        assertThat(feedbackRows).as("ratings cascade away with their message").isZero();

        // Two devices syncing one account both issue the DELETE; the loser must see a clean 404.
        assertThatThrownBy(() -> service.deleteConversation(conversationId, owner))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
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

    /**
     * The map {@code getFeedbackByMessageId} returns is the sole source of thumbs-up/down state in
     * the desktop client, which looks each row up by the id of the MESSAGE it rendered. Keying it
     * by the feedback row's own id is a one-token slip that compiles — both are {@code UUID} —
     * and it fails completely silently: every message would come back with null feedback over a 200
     * response, so users would watch their past ratings vanish, re-rate answers they had already
     * rated, and fill the review queue with duplicates, with no exception and no log line.
     * {@code containsOnlyKeys} rather than {@code containsKey} is what makes the keying observable
     * at all.
     *
     * <p>
     * The second conversation is not decoration: on a single-row fixture the key assertion can pass
     * by accident, and it is also the only way to observe the scoping: {@code findByConversationId}
     * joins {@code messages} to reach {@code conversation_id}, so a join that lost its predicate
     * would hand one user's client another thread's ratings and comments.
     */
    @Test
    public void feedbackIsReturnedKeyedByMessageIdAndScopedToTheRequestedConversation() {
        userRepository.upsert(ENTRA_OID, "dss@local", "DSS User");
        User owner = userRepository.findByEntraOid(ENTRA_OID).orElseThrow();
        Conversation convA = service.createConversation(owner, "Thread A", Map.of());
        conversationId = convA.id();
        Conversation convB = service.createConversation(owner, "Thread B", Map.of());
        UUID otherConversationId = convB.id();

        try {
            UUID ratedInA = service.appendMessages(conversationId, owner,
                List.of(new NewMessage("assistant", "A's answer", null, null, null, null, null)))
                    .getFirst();
            UUID ratedInB = service.appendMessages(otherConversationId, owner,
                List.of(new NewMessage("assistant", "B's answer", null, null, null, null, null)))
                    .getFirst();
            service.submitFeedback(ratedInA, owner, 1, "spot on");
            service.submitFeedback(ratedInB, owner, -1, "B-only, must not leak into A");

            Map<UUID, Feedback> byMessageId = service.getFeedbackByMessageId(conversationId, owner);

            assertThat(byMessageId)
                .as("the key must be the MESSAGE id — the client has no other handle on a rating")
                .containsOnlyKeys(ratedInA);
            assertThat(byMessageId.get(ratedInA).rating()).isEqualTo(1);
            assertThat(byMessageId.get(ratedInA).comment()).isEqualTo("spot on");
            assertThat(byMessageId).doesNotContainKey(ratedInB);
            assertThat(byMessageId.values()).extracting(Feedback::comment)
                .as("another conversation's comment must never appear in this map")
                .doesNotContain("B-only, must not leak into A");
        } finally {
            jdbcTemplate.update("DELETE FROM messages WHERE conversation_id = ?",
                otherConversationId);
            jdbcTemplate.update("DELETE FROM conversations WHERE id = ?", otherConversationId);
        }
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
     * A desktop row is a FINISHED answer — the desktop app generated it, showed it to the user and
     * is posting the transcript afterwards — and its {@code status} is what says so. That column is
     * not decoration: {@code ChatMessageService.onApplicationReady} runs
     * {@code MessageRepository.resetGeneratingMessages()} on every
     * {@code ApplicationReadyEvent}, and that statement REPLACES the content of every
     * {@code status = 'generating'} row with "⚠️ *[Generation interrupted (system restart)]*". So if
     * desktop-appended rows ever landed as anything but {@code done}, the next restart would
     * silently overwrite real answers with an interruption notice — and this is the only
     * server-side copy of that text, the one {@code getMessages} rehydrates onto a reinstall or a
     * second device. The user's local copy and the server's would diverge with nothing failing
     * anywhere.
     *
     * <p>
     * The provenance of both values is a convenience constructor's defaults, not a route:
     * {@code appendMessages} builds the 12-arg {@code Message} overload, whose three nulls are
     * {@code retrievedChunkIds}/{@code citations}/{@code createdAt} and which delegates with
     * {@code status = "done"} and {@code model = null}. Nothing in the desktop path ever names
     * either, so a change to that overload — or a switch to the overload that takes an explicit
     * status — moves this contract without touching {@code DesktopSyncService} at all. The
     * {@code model} half is the complement and is asserted so the null is on record as intentional:
     * {@code MessageDto.model} is always null for desktop rows (only the web path sets it, via
     * {@code updateContentAndStatus}'s {@code COALESCE(:model, model)}), so any consumer that starts
     * treating it as populated is reading a field this route cannot fill.
     *
     * <p>
     * The five existing tests here cover ownership, atomicity, batch persistence and ordering; none
     * of them looks at either column.
     */
    @Test
    public void desktopAppendedMessagesLandWithStatusDoneAndNoModel() {
        userRepository.upsert(ENTRA_OID, "dss@local", "DSS User");
        User user = userRepository.findByEntraOid(ENTRA_OID).orElseThrow();
        Conversation conv = service.createConversation(user, "Title", Map.of());
        conversationId = conv.id();

        // Tokens are supplied so the assistant row is as fully populated as this route ever gets —
        // the point is that even then it carries no model name.
        service.appendMessages(conversationId, user,
            List.of(new NewMessage("user", "which table holds a Person?", null, null, null, null,
                null),
                new NewMessage("assistant", "the answer the desktop app already showed", 11, 22, 33,
                    null, null)));

        List<Message> stored = service.getMessages(conversationId, user, 100, 0);

        assertThat(stored).hasSize(2);
        assertThat(stored).extracting(Message::status)
            .as("'generating' would make the next restart overwrite these answers' text")
            .containsOnly("done");
        assertThat(stored).extracting(Message::model)
            .as("no desktop route records a model, so MessageDto.model is always null here")
            .containsOnlyNulls();

        // ...and those are the persisted columns, not defaults invented by the row mapper.
        Integer offSpec = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM messages WHERE conversation_id = ? "
                + "AND (status IS DISTINCT FROM 'done' OR model IS NOT NULL)",
            Integer.class, conversationId);
        assertThat(offSpec).as("every desktop row is written status='done', model NULL").isZero();
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
