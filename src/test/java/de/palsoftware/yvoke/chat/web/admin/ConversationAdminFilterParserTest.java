package de.palsoftware.yvoke.chat.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository.ConversationFilter;
import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository.FeedbackFilter;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConversationAdminFilterParserTest {

    private final Clock fixedClock =
        Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void testParse_defaultAndNullParameters() {
        ConversationFilter filter =
            ConversationAdminFilterParser.parse(null, null, null, null, null, fixedClock);

        assertThat(filter.userIds()).isEmpty();
        assertThat(filter.includeAnonymous()).isFalse();
        assertThat(filter.timeFilter().preset()).isEqualTo("all");
        assertThat(filter.timeFilter().fromCutoff()).isNull();
        assertThat(filter.timeFilter().toCutoff()).isNull();
        assertThat(filter.timeFilter().fromDate()).isNull();
        assertThat(filter.timeFilter().toDate()).isNull();
        assertThat(filter.feedbackFilter()).isEqualTo(FeedbackFilter.ALL);
    }

    @Test
    void testParse_validUsersAndAnonymousSentinel() {
        UUID uid1 = UUID.randomUUID();
        UUID uid2 = UUID.randomUUID();
        List<String> rawUsers =
            List.of(uid1.toString(), "anonymous", "ANONYMOUS", uid2.toString(), "", "   ");

        ConversationFilter filter = ConversationAdminFilterParser.parse(rawUsers, "day", null, null,
            "positive", fixedClock);

        assertThat(filter.userIds()).containsExactlyInAnyOrder(uid1, uid2);
        assertThat(filter.includeAnonymous()).isTrue();
        assertThat(filter.timeFilter().preset()).isEqualTo("day");
        assertThat(filter.timeFilter().fromCutoff())
            .isEqualTo(OffsetDateTime.parse("2026-06-14T12:00:00Z"));
        assertThat(filter.feedbackFilter()).isEqualTo(FeedbackFilter.POSITIVE);
    }

    @Test
    void testParse_malformedUuidThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> ConversationAdminFilterParser.parse(List.of("not-a-uuid"), "all",
            null, null, "all", fixedClock)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid user ID");
    }

    @Test
    void testParse_customDateWindowAndDateSwap() {
        ConversationFilter filter = ConversationAdminFilterParser.parse(null, "custom",
            "2026-06-10", "2026-06-01", "all", fixedClock);

        assertThat(filter.timeFilter().preset()).isEqualTo("custom");
        assertThat(filter.timeFilter().fromDate()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(filter.timeFilter().toDate()).isEqualTo(LocalDate.of(2026, 6, 10));
        assertThat(filter.timeFilter().fromCutoff())
            .isEqualTo(OffsetDateTime.of(2026, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC));
        assertThat(filter.timeFilter().toCutoff())
            .isEqualTo(OffsetDateTime.of(2026, 6, 11, 0, 0, 0, 0, ZoneOffset.UTC));
    }

    @Test
    void testParse_malformedDateThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> ConversationAdminFilterParser.parse(null, "custom", "invalid-date",
            "2026-06-01", "all", fixedClock)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid date format");

        assertThatThrownBy(() -> ConversationAdminFilterParser.parse(null, "custom", "2026-06-01",
            "garbage", "all", fixedClock)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid date format");
    }

    @Test
    void testParse_presetsWithFixedClock() {
        ConversationFilter day =
            ConversationAdminFilterParser.parse(null, "day", null, null, "all", fixedClock);
        assertThat(day.timeFilter().preset()).isEqualTo("day");
        assertThat(day.timeFilter().fromCutoff())
            .isEqualTo(OffsetDateTime.parse("2026-06-14T12:00:00Z"));

        ConversationFilter week =
            ConversationAdminFilterParser.parse(null, "week", null, null, "all", fixedClock);
        assertThat(week.timeFilter().preset()).isEqualTo("week");
        assertThat(week.timeFilter().fromCutoff())
            .isEqualTo(OffsetDateTime.parse("2026-06-08T12:00:00Z"));

        ConversationFilter month =
            ConversationAdminFilterParser.parse(null, "month", null, null, "all", fixedClock);
        assertThat(month.timeFilter().preset()).isEqualTo("month");
        assertThat(month.timeFilter().fromCutoff())
            .isEqualTo(OffsetDateTime.parse("2026-05-16T12:00:00Z"));
    }

    @Test
    void testParse_unknownTimePresetThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> ConversationAdminFilterParser.parse(null, "unknown-preset", null,
            null, "all", fixedClock)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown time range preset");
    }

    @Test
    void testParse_feedbackFiltersAndUnknownValue() {
        assertThat(ConversationAdminFilterParser
            .parse(null, "all", null, null, "positive", fixedClock).feedbackFilter())
            .isEqualTo(FeedbackFilter.POSITIVE);
        assertThat(ConversationAdminFilterParser
            .parse(null, "all", null, null, "negative", fixedClock).feedbackFilter())
            .isEqualTo(FeedbackFilter.NEGATIVE);
        assertThat(ConversationAdminFilterParser.parse(null, "all", null, null, "any", fixedClock)
            .feedbackFilter()).isEqualTo(FeedbackFilter.ANY);
        assertThat(ConversationAdminFilterParser.parse(null, "all", null, null, "none", fixedClock)
            .feedbackFilter()).isEqualTo(FeedbackFilter.NONE);

        assertThatThrownBy(() -> ConversationAdminFilterParser.parse(null, "all", null, null,
            "unknown-fb", fixedClock)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown feedback filter");
    }
}
