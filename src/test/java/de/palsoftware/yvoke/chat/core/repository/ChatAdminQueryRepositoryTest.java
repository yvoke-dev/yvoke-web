package de.palsoftware.yvoke.chat.core.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository.ConversationFilter;
import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository.FeedbackFilter;
import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository.TimeFilter;
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
        assertThat(FeedbackFilter.fromString("garbage-rating")).isEqualTo(FeedbackFilter.ALL);

        // fromParam alias
        assertThat(FeedbackFilter.fromParam("positive")).isEqualTo(FeedbackFilter.POSITIVE);
        assertThat(FeedbackFilter.fromParam("invalid")).isEqualTo(FeedbackFilter.ALL);
    }

    @Test
    void testTimeFilter_presets() {
        TimeFilter all = TimeFilter.of("all", (LocalDate) null, null);
        assertThat(all.preset()).isEqualTo("all");
        assertThat(all.fromCutoff()).isNull();
        assertThat(all.toCutoff()).isNull();

        TimeFilter day = TimeFilter.of("day", (LocalDate) null, null);
        assertThat(day.preset()).isEqualTo("day");
        assertThat(day.fromCutoff()).isNotNull();
        assertThat(day.fromCutoff()).isBefore(OffsetDateTime.now(ZoneOffset.UTC));
        assertThat(day.toCutoff()).isNull();

        TimeFilter week = TimeFilter.of("week", (LocalDate) null, null);
        assertThat(week.preset()).isEqualTo("week");
        assertThat(week.fromCutoff()).isNotNull();
        assertThat(week.toCutoff()).isNull();

        TimeFilter month = TimeFilter.of("month", (LocalDate) null, null);
        assertThat(month.preset()).isEqualTo("month");
        assertThat(month.fromCutoff()).isNotNull();
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
    void testTimeFilter_stringParsingAndSafeFallbacks() {
        TimeFilter custom = TimeFilter.of("custom", "2026-05-01", "2026-05-05");
        assertThat(custom.fromCutoff())
            .isEqualTo(OffsetDateTime.of(2026, 5, 1, 0, 0, 0, 0, ZoneOffset.UTC));
        assertThat(custom.toCutoff())
            .isEqualTo(OffsetDateTime.of(2026, 5, 6, 0, 0, 0, 0, ZoneOffset.UTC));

        // Malformed dates do not throw
        assertThatCode(() -> {
            TimeFilter fallback = TimeFilter.of("custom", "not-a-date", "garbage");
            assertThat(fallback.fromCutoff()).isNull();
            assertThat(fallback.toCutoff()).isNull();
        }).doesNotThrowAnyException();
    }

    @Test
    void testConversationFilter_extractsUserIdsAndAnonymousSafely() {
        UUID uid1 = UUID.randomUUID();
        UUID uid2 = UUID.randomUUID();
        List<String> rawUsers = List.of(uid1.toString(), "anonymous", "ANONYMOUS", uid2.toString(),
            "not-a-uuid", "", "   ");

        ConversationFilter filter =
            ConversationFilter.of(rawUsers, "day", (LocalDate) null, (LocalDate) null, "positive");

        assertThat(filter.userIds()).containsExactlyInAnyOrder(uid1, uid2);
        assertThat(filter.includeAnonymous()).isTrue();
        assertThat(filter.timeFilter().preset()).isEqualTo("day");
        assertThat(filter.feedbackFilter()).isEqualTo(FeedbackFilter.POSITIVE);
    }

    @Test
    void testConversationFilter_stringDateOverload() {
        ConversationFilter filter = ConversationFilter.of(List.of("anonymous"), "custom",
            "2026-09-01", "2026-09-10", "negative");

        assertThat(filter.userIds()).isEmpty();
        assertThat(filter.includeAnonymous()).isTrue();
        assertThat(filter.timeFilter().fromCutoff())
            .isEqualTo(OffsetDateTime.of(2026, 9, 1, 0, 0, 0, 0, ZoneOffset.UTC));
        assertThat(filter.timeFilter().toCutoff())
            .isEqualTo(OffsetDateTime.of(2026, 9, 11, 0, 0, 0, 0, ZoneOffset.UTC));
        assertThat(filter.feedbackFilter()).isEqualTo(FeedbackFilter.NEGATIVE);
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
        TimeFilter timeFilter = new TimeFilter("custom", from, to);
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
