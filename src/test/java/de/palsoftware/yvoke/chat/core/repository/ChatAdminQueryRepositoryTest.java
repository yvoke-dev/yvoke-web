package de.palsoftware.yvoke.chat.core.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository.ConversationFilter;
import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository.ConversationOverviewStats;
import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository.FeedbackFilter;
import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository.TimeFilter;
import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository.UserConversationStats;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.simple.JdbcClient;

class ChatAdminQueryRepositoryTest {

    private ChatAdminQueryRepository repository;

    @BeforeEach
    void setUp() {
        JdbcClient jdbcClient = Mockito.mock(JdbcClient.class);
        repository = new ChatAdminQueryRepository(jdbcClient);
    }

    @Test
    void testFeedbackFilter_parsingAndFallbacks() {
        assertThat(FeedbackFilter.fromString("positive")).isEqualTo(FeedbackFilter.POSITIVE);
        assertThat(FeedbackFilter.fromString("POSITIVE")).isEqualTo(FeedbackFilter.POSITIVE);
        assertThat(FeedbackFilter.fromString("negative")).isEqualTo(FeedbackFilter.NEGATIVE);
        assertThat(FeedbackFilter.fromString("NEGATIVE")).isEqualTo(FeedbackFilter.NEGATIVE);
        assertThat(FeedbackFilter.fromString("any")).isEqualTo(FeedbackFilter.ANY);
        assertThat(FeedbackFilter.fromString("ANY")).isEqualTo(FeedbackFilter.ANY);
        assertThat(FeedbackFilter.fromString("none")).isEqualTo(FeedbackFilter.NONE);
        assertThat(FeedbackFilter.fromString("NONE")).isEqualTo(FeedbackFilter.NONE);
        assertThat(FeedbackFilter.fromString("all")).isEqualTo(FeedbackFilter.ALL);
        assertThat(FeedbackFilter.fromString("ALL")).isEqualTo(FeedbackFilter.ALL);

        // Fallbacks
        assertThat(FeedbackFilter.fromString(null)).isEqualTo(FeedbackFilter.ALL);
        assertThat(FeedbackFilter.fromString("")).isEqualTo(FeedbackFilter.ALL);
        assertThat(FeedbackFilter.fromString("   ")).isEqualTo(FeedbackFilter.ALL);

        // Rejection of unknown values
        assertThatThrownBy(() -> FeedbackFilter.fromString("garbage-rating"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testTimeFilter_presetsWithFixedClock() {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC);

        TimeFilter all = TimeFilter.preset("all", fixedClock);
        assertThat(all.preset()).isEqualTo("all");
        assertThat(all.fromCutoff()).isNull();
        assertThat(all.toCutoff()).isNull();

        TimeFilter day = TimeFilter.preset("day", fixedClock);
        assertThat(day.preset()).isEqualTo("day");
        assertThat(day.fromCutoff()).isEqualTo(OffsetDateTime.parse("2026-06-14T12:00:00Z"));
        assertThat(day.toCutoff()).isNull();

        TimeFilter week = TimeFilter.preset("week", fixedClock);
        assertThat(week.preset()).isEqualTo("week");
        assertThat(week.fromCutoff()).isEqualTo(OffsetDateTime.parse("2026-06-08T12:00:00Z"));
        assertThat(week.toCutoff()).isNull();

        TimeFilter month = TimeFilter.preset("month", fixedClock);
        assertThat(month.preset()).isEqualTo("month");
        assertThat(month.fromCutoff()).isEqualTo(OffsetDateTime.parse("2026-05-16T12:00:00Z"));
        assertThat(month.toCutoff()).isNull();
    }

    @Test
    void testTimeFilter_customDateHalfOpenUtcWindow() {
        LocalDate from = LocalDate.of(2026, 6, 1);
        LocalDate to = LocalDate.of(2026, 6, 10);
        TimeFilter custom = TimeFilter.of("custom", from, to);

        assertThat(custom.preset()).isEqualTo("custom");
        assertThat(custom.fromCutoff())
            .isEqualTo(OffsetDateTime.of(2026, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC));
        assertThat(custom.toCutoff())
            .isEqualTo(OffsetDateTime.of(2026, 6, 11, 0, 0, 0, 0, ZoneOffset.UTC));
    }

    @Test
    void testTimeFilter_sameDateWindowInclusive() {
        LocalDate day = LocalDate.of(2026, 7, 15);
        TimeFilter custom = TimeFilter.of("custom", day, day);

        assertThat(custom.preset()).isEqualTo("custom");
        assertThat(custom.fromCutoff())
            .isEqualTo(OffsetDateTime.of(2026, 7, 15, 0, 0, 0, 0, ZoneOffset.UTC));
        assertThat(custom.toCutoff())
            .isEqualTo(OffsetDateTime.of(2026, 7, 16, 0, 0, 0, 0, ZoneOffset.UTC));
    }

    @Test
    void testTimeFilter_invertsInvertedDates() {
        LocalDate from = LocalDate.of(2026, 8, 20);
        LocalDate to = LocalDate.of(2026, 8, 10);
        TimeFilter custom = TimeFilter.of("custom", from, to);

        assertThat(custom.preset()).isEqualTo("custom");
        assertThat(custom.fromCutoff())
            .isEqualTo(OffsetDateTime.of(2026, 8, 10, 0, 0, 0, 0, ZoneOffset.UTC));
        assertThat(custom.toCutoff())
            .isEqualTo(OffsetDateTime.of(2026, 8, 21, 0, 0, 0, 0, ZoneOffset.UTC));
    }

    @Test
    void testConversationOverviewStats_registeredUserCount() {
        UserConversationStats regUser1 =
            new UserConversationStats(UUID.randomUUID(), "Alice", "alice@example.com", 5, 2, 0);
        UserConversationStats regUser2 =
            new UserConversationStats(UUID.randomUUID(), "Bob", "bob@example.com", 3, 1, 1);
        UserConversationStats anonUser =
            new UserConversationStats(null, "Anonymous / Deleted", null, 10, 3, 2);

        ConversationOverviewStats stats =
            new ConversationOverviewStats(18, 6, 3, List.of(regUser1, regUser2, anonUser));

        // registeredUserCount must exclude the anonymous row (userId == null)
        assertThat(stats.registeredUserCount()).isEqualTo(2);

        ConversationOverviewStats nullStats = new ConversationOverviewStats(0, 0, 0, null);
        assertThat(nullStats.registeredUserCount()).isEqualTo(0);
    }

    @Test
    void testAppendConversationFilters_emptyFilterGeneratesNoWhereClauses() {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM conversations c WHERE 1=1");
        Map<String, Object> params = new HashMap<>();

        ConversationFilter empty = new ConversationFilter(Set.of(), false,
            TimeFilter.of("all", (LocalDate) null, null), FeedbackFilter.ALL);
        repository.appendConversationFilters(sql, params, empty);

        assertThat(sql.toString()).isEqualTo("SELECT COUNT(*) FROM conversations c WHERE 1=1");
        assertThat(params).isEmpty();
    }

    @Test
    void testAppendConversationFilters_userIdsOnly() {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM conversations c WHERE 1=1");
        Map<String, Object> params = new HashMap<>();
        UUID uid = UUID.randomUUID();

        ConversationFilter filter = new ConversationFilter(Set.of(uid), false,
            TimeFilter.of("all", (LocalDate) null, null), FeedbackFilter.ALL);
        repository.appendConversationFilters(sql, params, filter);

        assertThat(sql.toString()).contains("AND c.user_id IN (:userIds)");
        assertThat(sql.toString()).doesNotContain("OR c.user_id IS NULL");
        assertThat(params).containsEntry("userIds", Set.of(uid));
    }

    @Test
    void testAppendConversationFilters_anonymousOnly() {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM conversations c WHERE 1=1");
        Map<String, Object> params = new HashMap<>();

        ConversationFilter filter = new ConversationFilter(Set.of(), true,
            TimeFilter.of("all", (LocalDate) null, null), FeedbackFilter.ALL);
        repository.appendConversationFilters(sql, params, filter);

        assertThat(sql.toString()).contains("AND c.user_id IS NULL");
        assertThat(sql.toString()).doesNotContain("IN (:userIds)");
        assertThat(params).isEmpty();
    }

    @Test
    void testAppendConversationFilters_userIdsAndAnonymousCombined() {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM conversations c WHERE 1=1");
        Map<String, Object> params = new HashMap<>();
        UUID uid = UUID.randomUUID();

        ConversationFilter filter = new ConversationFilter(Set.of(uid), true,
            TimeFilter.of("all", (LocalDate) null, null), FeedbackFilter.ALL);
        repository.appendConversationFilters(sql, params, filter);

        assertThat(sql.toString()).contains("AND (c.user_id IN (:userIds) OR c.user_id IS NULL)");
        assertThat(params).containsEntry("userIds", Set.of(uid));
    }

    @Test
    void testAppendConversationFilters_timeBoundaries() {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM conversations c WHERE 1=1");
        Map<String, Object> params = new HashMap<>();

        OffsetDateTime from = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime to = OffsetDateTime.of(2026, 1, 2, 0, 0, 0, 0, ZoneOffset.UTC);
        TimeFilter timeFilter = new TimeFilter("custom", from, to, null, null);
        ConversationFilter filter =
            new ConversationFilter(Set.of(), false, timeFilter, FeedbackFilter.ALL);

        repository.appendConversationFilters(sql, params, filter);

        assertThat(sql.toString()).contains("AND c.created_at >= :fromCutoff");
        assertThat(sql.toString()).contains("AND c.created_at < :toCutoff");
        assertThat(params).containsEntry("fromCutoff", from);
        assertThat(params).containsEntry("toCutoff", to);
    }

    @Test
    void testAppendConversationFilters_feedbackFilters() {
        for (FeedbackFilter fb : List.of(FeedbackFilter.POSITIVE, FeedbackFilter.NEGATIVE, FeedbackFilter.ANY, FeedbackFilter.NONE)) {
            StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM conversations c WHERE 1=1");
            Map<String, Object> params = new HashMap<>();
            ConversationFilter filter = new ConversationFilter(Set.of(), false, TimeFilter.of("all", (LocalDate) null, null), fb);
            repository.appendConversationFilters(sql, params, filter);

            switch (fb) {
                case POSITIVE -> {
                    assertThat(sql.toString()).contains("EXISTS");
                    assertThat(sql.toString()).contains("mf.rating = 1");
                }
                case NEGATIVE -> {
                    assertThat(sql.toString()).contains("EXISTS");
                    assertThat(sql.toString()).contains("mf.rating = -1");
                }
                case ANY -> {
                    assertThat(sql.toString()).contains("EXISTS");
                    assertThat(sql.toString()).contains("mf.rating = 1 OR mf.rating = -1");
                }
                case NONE -> {
                    assertThat(sql.toString()).contains("NOT EXISTS");
                    assertThat(sql.toString()).contains("mf.rating = 1 OR mf.rating = -1");
                }
                default -> {}
            }
        }
    }
}
