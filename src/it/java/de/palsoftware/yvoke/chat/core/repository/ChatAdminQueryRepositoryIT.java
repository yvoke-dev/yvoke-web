package de.palsoftware.yvoke.chat.core.repository;

import static org.assertj.core.api.Assertions.assertThat;

import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository.AdminConversation;
import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository.ConversationFilter;
import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository.ConversationOverviewStats;
import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository.ConversationUserOption;
import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository.FeedbackFilter;
import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository.TimeFilter;
import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository.UserConversationStats;
import de.palsoftware.yvoke.shared.user.repository.UserRepository;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "app.security.mock=true")
public class ChatAdminQueryRepositoryIT {

    @Autowired
    private ChatAdminQueryRepository chatAdminQueryRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM message_feedback WHERE comment LIKE 'CAQR-IT%'");
        jdbcTemplate.update("DELETE FROM messages WHERE content LIKE 'CAQR-IT%'");
        jdbcTemplate.update("DELETE FROM conversations WHERE title LIKE 'CAQR-IT%'");
        jdbcTemplate.update("DELETE FROM users WHERE email LIKE 'caqr-it%'");
    }

    private UUID createUser(String email, String displayName) {
        String entraOid = "oid-" + UUID.randomUUID();
        userRepository.upsert(entraOid, email, displayName);
        return userRepository.findByEntraOid(entraOid).orElseThrow().id();
    }

    private UUID createConversation(UUID userId, String title, OffsetDateTime createdAt) {
        UUID convId = UUID.randomUUID();
        conversationRepository.create(convId, userId, title, Map.of(), "web");
        if (createdAt != null) {
            jdbcTemplate.update(
                "UPDATE conversations SET created_at = ?, updated_at = ? WHERE id = ?",
                Timestamp.from(createdAt.toInstant()),
                Timestamp.from(createdAt.toInstant()),
                convId);
        }
        return convId;
    }

    private UUID createMessageWithFeedback(UUID convId, int rating) {
        UUID msgId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO messages (id, conversation_id, role, content, status, created_at, updated_at) VALUES (?, ?, 'assistant', 'CAQR-IT content', 'done', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
            msgId,
            convId);
        UUID fbId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO message_feedback (id, message_id, rating, comment, created_at, updated_at) VALUES (?, ?, ?, 'CAQR-IT feedback', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
            fbId,
            msgId,
            rating);
        return msgId;
    }

    private UUID createMessageWithoutFeedback(UUID convId) {
        UUID msgId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO messages (id, conversation_id, role, content, status, created_at, updated_at) VALUES (?, ?, 'user', 'CAQR-IT content', 'done', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
            msgId,
            convId);
        return msgId;
    }

    @Test
    void testFeedbackAggregation_countsThumbsUpAndThumbsDownAccurately() {
        UUID user = createUser("caqr-it-user1@example.com", "CAQR User 1");
        UUID conv = createConversation(user, "CAQR-IT Conv 1", OffsetDateTime.now(ZoneOffset.UTC));
        createMessageWithFeedback(conv, 1);
        createMessageWithFeedback(conv, 1);
        createMessageWithFeedback(conv, -1);
        createMessageWithoutFeedback(conv);

        List<AdminConversation> convs = chatAdminQueryRepository.listFilteredConversations(
            new ConversationFilter(Set.of(user), false, TimeFilter.of("all", (LocalDate) null, null), FeedbackFilter.ALL),
            10,
            0);

        assertThat(convs).hasSize(1);
        assertThat(convs.get(0).thumbsUpCount()).isEqualTo(2);
        assertThat(convs.get(0).thumbsDownCount()).isEqualTo(1);
    }

    @Test
    void testDistinctRowCountParity_countNeverInflatedByFeedbackOrMessages() {
        UUID user = createUser("caqr-it-parity@example.com", "CAQR Parity User");
        UUID conv1 = createConversation(user, "CAQR-IT Parity 1", OffsetDateTime.now(ZoneOffset.UTC));
        createMessageWithFeedback(conv1, 1);
        createMessageWithFeedback(conv1, 1);
        createMessageWithFeedback(conv1, -1);

        UUID conv2 = createConversation(user, "CAQR-IT Parity 2", OffsetDateTime.now(ZoneOffset.UTC));
        createMessageWithFeedback(conv2, 1);

        UUID conv3 = createConversation(user, "CAQR-IT Parity 3", OffsetDateTime.now(ZoneOffset.UTC));
        createMessageWithoutFeedback(conv3);

        ConversationFilter filter = new ConversationFilter(
            Set.of(user),
            false,
            TimeFilter.of("all", (LocalDate) null, null),
            FeedbackFilter.ALL);

        long count = chatAdminQueryRepository.countFilteredConversations(filter);
        List<AdminConversation> list = chatAdminQueryRepository.listFilteredConversations(filter, 100, 0);

        assertThat(count).isEqualTo(3);
        assertThat(list).hasSize(3);
        assertThat(count).isEqualTo(list.size());
    }

    @Test
    void testFeedbackFilterIsolation() {
        UUID user = createUser("caqr-it-isolation@example.com", "CAQR Isolation User");

        UUID convPos = createConversation(user, "CAQR-IT Positive", OffsetDateTime.now(ZoneOffset.UTC));
        createMessageWithFeedback(convPos, 1);

        UUID convNeg = createConversation(user, "CAQR-IT Negative", OffsetDateTime.now(ZoneOffset.UTC));
        createMessageWithFeedback(convNeg, -1);

        UUID convBoth = createConversation(user, "CAQR-IT Both", OffsetDateTime.now(ZoneOffset.UTC));
        createMessageWithFeedback(convBoth, 1);
        createMessageWithFeedback(convBoth, -1);

        UUID convNone = createConversation(user, "CAQR-IT None", OffsetDateTime.now(ZoneOffset.UTC));
        createMessageWithoutFeedback(convNone);

        // POSITIVE filter: convPos and convBoth
        ConversationFilter posFilter = new ConversationFilter(
            Set.of(user), false, TimeFilter.of("all", (LocalDate) null, null), FeedbackFilter.POSITIVE);
        List<AdminConversation> posList = chatAdminQueryRepository.listFilteredConversations(posFilter, 10, 0);
        assertThat(posList).extracting(AdminConversation::id).containsExactlyInAnyOrder(convPos, convBoth);
        assertThat(chatAdminQueryRepository.countFilteredConversations(posFilter)).isEqualTo(2);

        // NEGATIVE filter: convNeg and convBoth
        ConversationFilter negFilter = new ConversationFilter(
            Set.of(user), false, TimeFilter.of("all", (LocalDate) null, null), FeedbackFilter.NEGATIVE);
        List<AdminConversation> negList = chatAdminQueryRepository.listFilteredConversations(negFilter, 10, 0);
        assertThat(negList).extracting(AdminConversation::id).containsExactlyInAnyOrder(convNeg, convBoth);
        assertThat(chatAdminQueryRepository.countFilteredConversations(negFilter)).isEqualTo(2);

        // ANY filter: convPos, convNeg, convBoth
        ConversationFilter anyFilter = new ConversationFilter(
            Set.of(user), false, TimeFilter.of("all", (LocalDate) null, null), FeedbackFilter.ANY);
        List<AdminConversation> anyList = chatAdminQueryRepository.listFilteredConversations(anyFilter, 10, 0);
        assertThat(anyList).extracting(AdminConversation::id).containsExactlyInAnyOrder(convPos, convNeg, convBoth);
        assertThat(chatAdminQueryRepository.countFilteredConversations(anyFilter)).isEqualTo(3);

        // NONE filter: convNone
        ConversationFilter noneFilter = new ConversationFilter(
            Set.of(user), false, TimeFilter.of("all", (LocalDate) null, null), FeedbackFilter.NONE);
        List<AdminConversation> noneList = chatAdminQueryRepository.listFilteredConversations(noneFilter, 10, 0);
        assertThat(noneList).extracting(AdminConversation::id).containsExactlyInAnyOrder(convNone);
        assertThat(chatAdminQueryRepository.countFilteredConversations(noneFilter)).isEqualTo(1);
    }

    @Test
    void testEndOfDayBoundary_sameDateWindowRetrievesEveningConversation() {
        UUID user = createUser("caqr-it-eod@example.com", "CAQR EOD User");
        LocalDate day = LocalDate.of(2026, 7, 20);
        OffsetDateTime evening = OffsetDateTime.of(2026, 7, 20, 22, 30, 0, 0, ZoneOffset.UTC);
        UUID conv = createConversation(user, "CAQR-IT Evening", evening);

        ConversationFilter filter = ConversationFilter.of(List.of(user.toString()), "custom", day, day, "all");
        List<AdminConversation> results = chatAdminQueryRepository.listFilteredConversations(filter, 10, 0);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo(conv);
        assertThat(chatAdminQueryRepository.countFilteredConversations(filter)).isEqualTo(1);
    }

    @Test
    void testNonVacuousDateSwap_autoInvertsDates() {
        UUID user = createUser("caqr-it-swap@example.com", "CAQR Swap User");
        LocalDate day = LocalDate.of(2026, 8, 15);
        OffsetDateTime ts = OffsetDateTime.of(2026, 8, 15, 12, 0, 0, 0, ZoneOffset.UTC);
        UUID conv = createConversation(user, "CAQR-IT Midday", ts);

        LocalDate fromInverted = day.plusDays(5);
        LocalDate toInverted = day.minusDays(5);
        ConversationFilter filter = ConversationFilter.of(
            List.of(user.toString()), "custom", fromInverted, toInverted, "all");
        List<AdminConversation> results = chatAdminQueryRepository.listFilteredConversations(filter, 10, 0);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo(conv);
        assertThat(chatAdminQueryRepository.countFilteredConversations(filter)).isEqualTo(1);
    }

    @Test
    void testMultiCriteriaIntersection_andSemantics() {
        UUID userA = createUser("caqr-it-user-a@example.com", "CAQR User A");
        UUID userB = createUser("caqr-it-user-b@example.com", "CAQR User B");

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime twoMonthsAgo = now.minusDays(60);

        // Conv 1: User A, today, thumbs up (matches ALL criteria)
        UUID conv1 = createConversation(userA, "CAQR-IT Match All", now);
        createMessageWithFeedback(conv1, 1);

        // Conv 2: User A, today, thumbs down (matches user and time, NOT feedback)
        UUID conv2 = createConversation(userA, "CAQR-IT Wrong Feedback", now);
        createMessageWithFeedback(conv2, -1);

        // Conv 3: User A, 60 days ago, thumbs up (matches user and feedback, NOT time)
        UUID conv3 = createConversation(userA, "CAQR-IT Too Old", twoMonthsAgo);
        createMessageWithFeedback(conv3, 1);

        // Conv 4: User B, today, thumbs up (matches time and feedback, NOT user)
        UUID conv4 = createConversation(userB, "CAQR-IT Wrong User", now);
        createMessageWithFeedback(conv4, 1);

        ConversationFilter filter = new ConversationFilter(
            Set.of(userA),
            false,
            TimeFilter.of("month", (LocalDate) null, null),
            FeedbackFilter.POSITIVE);

        List<AdminConversation> results = chatAdminQueryRepository.listFilteredConversations(filter, 10, 0);
        assertThat(results).extracting(AdminConversation::id).containsExactly(conv1);
        assertThat(chatAdminQueryRepository.countFilteredConversations(filter)).isEqualTo(1);
    }

    @Test
    void testEmptyUserSet_appliesNoFilterAndEmitsNoInvalidInClause() {
        UUID user = createUser("caqr-it-empty-users@example.com", "CAQR Empty Users");
        UUID convUser = createConversation(user, "CAQR-IT With User", OffsetDateTime.now(ZoneOffset.UTC));
        UUID convAnon = createConversation(null, "CAQR-IT Anon", OffsetDateTime.now(ZoneOffset.UTC));

        ConversationFilter filter = new ConversationFilter(
            Set.of(),
            false,
            TimeFilter.of("all", (LocalDate) null, null),
            FeedbackFilter.ALL);

        List<AdminConversation> results = chatAdminQueryRepository.listFilteredConversations(filter, 10, 0);
        assertThat(results).extracting(AdminConversation::id).contains(convUser, convAnon);
        assertThat(chatAdminQueryRepository.countFilteredConversations(filter)).isGreaterThanOrEqualTo(2);
    }

    @Test
    void testAnonymousFiltering_strictlyIsolatesNullUserIds() {
        UUID user = createUser("caqr-it-anon-user@example.com", "CAQR Anon Test User");
        UUID convUser = createConversation(user, "CAQR-IT Known User", OffsetDateTime.now(ZoneOffset.UTC));
        UUID convAnon = createConversation(null, "CAQR-IT Anonymous Conv", OffsetDateTime.now(ZoneOffset.UTC));

        ConversationFilter filter = new ConversationFilter(
            Set.of(),
            true,
            TimeFilter.of("all", (LocalDate) null, null),
            FeedbackFilter.ALL);

        List<AdminConversation> results = chatAdminQueryRepository.listFilteredConversations(filter, 10, 0);
        assertThat(results).extracting(AdminConversation::id).contains(convAnon);
        assertThat(results).extracting(AdminConversation::id).doesNotContain(convUser);
    }

    @Test
    void testUserOptionCappingAndDynamicMerge() {
        List<UUID> userIds = new ArrayList<>();
        for (int i = 1; i <= 205; i++) {
            String padded = String.format("%03d", i);
            UUID uid = createUser("caqr-it-bulk-" + padded + "@example.com", "AAA-CAQR-User-" + padded);
            createConversation(uid, "CAQR-IT Bulk " + padded, OffsetDateTime.now(ZoneOffset.UTC));
            userIds.add(uid);
        }

        UUID user205Id = userIds.get(204); // 205th user

        List<ConversationUserOption> options = chatAdminQueryRepository.listConversationUserOptions(Set.of(user205Id));

        assertThat(options.size()).isGreaterThanOrEqualTo(200);
        assertThat(options).extracting(ConversationUserOption::id).contains(user205Id);
        assertThat(options).extracting(ConversationUserOption::displayName).contains("AAA-CAQR-User-205");
    }

    @Test
    void testGetConversationStats_aggregatesAccuratelyAndRespectsFilters() {
        LocalDate statsDay = LocalDate.of(2035, 6, 1);
        OffsetDateTime statsTime = OffsetDateTime.of(2035, 6, 1, 10, 0, 0, 0, ZoneOffset.UTC);
        TimeFilter scopedTime = TimeFilter.of("custom", statsDay, statsDay);

        // User 1 (Alice): 2 convs
        UUID alice = createUser("caqr-it-alice@example.com", "CAQR Alice");
        UUID aliceConv1 = createConversation(alice, "CAQR-IT Alice 1", statsTime);
        createMessageWithFeedback(aliceConv1, 1);
        createMessageWithFeedback(aliceConv1, 1);
        createMessageWithFeedback(aliceConv1, -1);
        createMessageWithoutFeedback(aliceConv1);

        UUID aliceConv2 = createConversation(alice, "CAQR-IT Alice 2", statsTime);
        createMessageWithFeedback(aliceConv2, 1);

        // User 2 (Bob): 2 convs
        UUID bob = createUser("caqr-it-bob@example.com", "CAQR Bob");
        UUID bobConv1 = createConversation(bob, "CAQR-IT Bob 1", statsTime);
        createMessageWithFeedback(bobConv1, -1);

        UUID bobConv2 = createConversation(bob, "CAQR-IT Bob 2", statsTime);
        createMessageWithoutFeedback(bobConv2);

        // Anonymous User (userId = null): 1 conv
        UUID anonConv = createConversation(null, "CAQR-IT Anon 1", statsTime);
        createMessageWithFeedback(anonConv, 1);

        // 1. Overall stats (scoped to test date window to prevent test-order coupling)
        ConversationFilter overallFilter =
            new ConversationFilter(Set.of(), false, scopedTime, FeedbackFilter.ALL);
        ConversationOverviewStats overall = chatAdminQueryRepository.getConversationStats(overallFilter);
        assertThat(overall).isNotNull();
        assertThat(overall.totalConversations()).isEqualTo(5);
        assertThat(overall.totalThumbsUp()).isEqualTo(4); // Alice: 3, Bob: 0, Anon: 1
        assertThat(overall.totalThumbsDown()).isEqualTo(2); // Alice: 1, Bob: 1, Anon: 0
        assertThat(overall.registeredUserCount()).isEqualTo(2); // Excludes anonymous bucket

        List<UserConversationStats> userStats = overall.userStats();
        assertThat(userStats).hasSize(3);

        UserConversationStats aliceStats = userStats.stream()
            .filter(u -> alice.equals(u.userId()))
            .findFirst()
            .orElseThrow();
        assertThat(aliceStats.userDisplayName()).isEqualTo("CAQR Alice");
        assertThat(aliceStats.userEmail()).isEqualTo("caqr-it-alice@example.com");
        assertThat(aliceStats.conversationCount()).isEqualTo(2);
        assertThat(aliceStats.thumbsUpCount()).isEqualTo(3);
        assertThat(aliceStats.thumbsDownCount()).isEqualTo(1);

        UserConversationStats bobStats = userStats.stream()
            .filter(u -> bob.equals(u.userId()))
            .findFirst()
            .orElseThrow();
        assertThat(bobStats.conversationCount()).isEqualTo(2);
        assertThat(bobStats.thumbsUpCount()).isEqualTo(0);
        assertThat(bobStats.thumbsDownCount()).isEqualTo(1);

        UserConversationStats anonStats = userStats.stream()
            .filter(u -> u.userId() == null)
            .findFirst()
            .orElseThrow();
        assertThat(anonStats.conversationCount()).isEqualTo(1);
        assertThat(anonStats.thumbsUpCount()).isEqualTo(1);
        assertThat(anonStats.thumbsDownCount()).isEqualTo(0);

        // 2. Filtered by User (Alice only)
        ConversationFilter aliceFilter = new ConversationFilter(
            Set.of(alice),
            false,
            scopedTime,
            FeedbackFilter.ALL);
        ConversationOverviewStats aliceOnly = chatAdminQueryRepository.getConversationStats(aliceFilter);
        assertThat(aliceOnly.totalConversations()).isEqualTo(2);
        assertThat(aliceOnly.totalThumbsUp()).isEqualTo(3);
        assertThat(aliceOnly.totalThumbsDown()).isEqualTo(1);
        assertThat(aliceOnly.userStats()).hasSize(1);
        assertThat(aliceOnly.userStats().get(0).userId()).isEqualTo(alice);
        assertThat(aliceOnly.registeredUserCount()).isEqualTo(1);

        // 3. Filtered by Feedback (Negative only)
        ConversationFilter negativeFilter = new ConversationFilter(
            Set.of(),
            false,
            scopedTime,
            FeedbackFilter.NEGATIVE);
        ConversationOverviewStats negativeOnly = chatAdminQueryRepository.getConversationStats(negativeFilter);
        // Only Alice Conv 1 and Bob Conv 1 contain negative feedback
        assertThat(negativeOnly.totalConversations()).isEqualTo(2);
        assertThat(negativeOnly.userStats()).extracting(UserConversationStats::userId).containsExactlyInAnyOrder(alice, bob);
        assertThat(negativeOnly.registeredUserCount()).isEqualTo(2);
    }
}
