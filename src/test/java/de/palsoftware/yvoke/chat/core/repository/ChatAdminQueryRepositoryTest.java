package de.palsoftware.yvoke.chat.core.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository.AdminConversation;
import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository.ConversationFilter;
import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository.ConversationOverviewStats;
import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository.FeedbackFilter;
import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository.TimeFilter;
import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository.UserConversationStats;
import java.sql.ResultSet;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

class ChatAdminQueryRepositoryTest {

    private JdbcClient jdbcClient;
    private ChatAdminQueryRepository repository;

    @BeforeEach
    void setUp() {
        jdbcClient = Mockito.mock(JdbcClient.class);
        repository = new ChatAdminQueryRepository(jdbcClient);
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
        TimeFilter custom = TimeFilter.custom(from, to);

        assertThat(custom.preset()).isEqualTo("custom");
        assertThat(custom.fromCutoff())
            .isEqualTo(OffsetDateTime.of(2026, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC));
        assertThat(custom.toCutoff())
            .isEqualTo(OffsetDateTime.of(2026, 6, 11, 0, 0, 0, 0, ZoneOffset.UTC));
        assertThat(custom.fromDate()).isEqualTo(from);
        assertThat(custom.toDate()).isEqualTo(to);
    }

    @Test
    void testTimeFilter_sameDateWindowInclusive() {
        LocalDate day = LocalDate.of(2026, 7, 15);
        TimeFilter custom = TimeFilter.custom(day, day);

        assertThat(custom.preset()).isEqualTo("custom");
        assertThat(custom.fromCutoff())
            .isEqualTo(OffsetDateTime.of(2026, 7, 15, 0, 0, 0, 0, ZoneOffset.UTC));
        assertThat(custom.toCutoff())
            .isEqualTo(OffsetDateTime.of(2026, 7, 16, 0, 0, 0, 0, ZoneOffset.UTC));
        assertThat(custom.fromDate()).isEqualTo(day);
        assertThat(custom.toDate()).isEqualTo(day);
    }

    @Test
    void testTimeFilter_invertsInvertedDates() {
        LocalDate from = LocalDate.of(2026, 8, 20);
        LocalDate to = LocalDate.of(2026, 8, 10);
        TimeFilter custom = TimeFilter.custom(from, to);

        assertThat(custom.preset()).isEqualTo("custom");
        assertThat(custom.fromCutoff())
            .isEqualTo(OffsetDateTime.of(2026, 8, 10, 0, 0, 0, 0, ZoneOffset.UTC));
        assertThat(custom.toCutoff())
            .isEqualTo(OffsetDateTime.of(2026, 8, 21, 0, 0, 0, 0, ZoneOffset.UTC));
        assertThat(custom.fromDate()).isEqualTo(to);
        assertThat(custom.toDate()).isEqualTo(from);
    }

    @Test
    void testConversationOverviewStats_recordProperties() {
        UserConversationStats regUser1 =
            new UserConversationStats(UUID.randomUUID(), "Alice", "alice@example.com", 5, 2, 0);
        UserConversationStats regUser2 =
            new UserConversationStats(UUID.randomUUID(), "Bob", "bob@example.com", 3, 1, 1);
        UserConversationStats anonUser =
            new UserConversationStats(null, "Anonymous / Deleted", null, 10, 3, 2);

        ConversationOverviewStats stats =
            new ConversationOverviewStats(18, 6, 3, 2, 3, List.of(regUser1, regUser2, anonUser));

        assertThat(stats.totalConversations()).isEqualTo(18);
        assertThat(stats.totalThumbsUp()).isEqualTo(6);
        assertThat(stats.totalThumbsDown()).isEqualTo(3);
        assertThat(stats.registeredUserCount()).isEqualTo(2);
        assertThat(stats.totalUserCount()).isEqualTo(3);
        assertThat(stats.userStats()).hasSize(3);
    }

    @Test
    void testAppendConversationFilters_emptyFilterGeneratesNoWhereClauses() {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM conversations c WHERE 1=1");
        Map<String, Object> params = new HashMap<>();

        ConversationFilter empty =
            new ConversationFilter(Set.of(), false, TimeFilter.all(), FeedbackFilter.ALL);
        repository.appendConversationFilters(sql, params, empty);

        assertThat(sql.toString()).isEqualTo("SELECT COUNT(*) FROM conversations c WHERE 1=1");
        assertThat(params).isEmpty();
    }

    @Test
    void testAppendConversationFilters_userIdsOnly() {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM conversations c WHERE 1=1");
        Map<String, Object> params = new HashMap<>();
        UUID uid = UUID.randomUUID();

        ConversationFilter filter =
            new ConversationFilter(Set.of(uid), false, TimeFilter.all(), FeedbackFilter.ALL);
        repository.appendConversationFilters(sql, params, filter);

        assertThat(sql.toString()).contains("AND c.user_id IN (:userIds)");
        assertThat(sql.toString()).doesNotContain("OR c.user_id IS NULL");
        assertThat(params).containsEntry("userIds", Set.of(uid));
    }

    @Test
    void testAppendConversationFilters_anonymousOnly() {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM conversations c WHERE 1=1");
        Map<String, Object> params = new HashMap<>();

        ConversationFilter filter =
            new ConversationFilter(Set.of(), true, TimeFilter.all(), FeedbackFilter.ALL);
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

        ConversationFilter filter =
            new ConversationFilter(Set.of(uid), true, TimeFilter.all(), FeedbackFilter.ALL);
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
            ConversationFilter filter = new ConversationFilter(Set.of(), false, TimeFilter.all(), fb);
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

    @Test
    void testAdminConversation_normalizesSourceAndClientVersionVerbatimAndFormatsSourceBadge() {
        UUID id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        AdminConversation desktopPrefixed = new AdminConversation(id, userId, "Alice",
            "alice@example.com", "Title", "desktop", now, now, 1, 0, "v1.3.1");
        assertThat(desktopPrefixed.source()).isEqualTo("desktop");
        assertThat(desktopPrefixed.clientVersion()).isEqualTo("v1.3.1");
        assertThat(desktopPrefixed.sourceBadge()).isEqualTo("desktop v1.3.1");

        AdminConversation desktopBareSemver = new AdminConversation(id, userId, "Alice",
            "alice@example.com", "Title", "desktop", now, now, 1, 0, "1.3.1");
        assertThat(desktopBareSemver.clientVersion()).isEqualTo("1.3.1");
        assertThat(desktopBareSemver.sourceBadge()).isEqualTo("desktop 1.3.1");

        AdminConversation pluginVersioned = new AdminConversation(id, userId, "Alice",
            "alice@example.com", "Title", "claude-plugin", now, now, 0, 0, "0.4.2");
        assertThat(pluginVersioned.clientVersion()).isEqualTo("0.4.2");
        assertThat(pluginVersioned.sourceBadge()).isEqualTo("claude-plugin 0.4.2");

        for (String blank : List.of("", "   ", "\t\u2003")) {
            AdminConversation unversioned = new AdminConversation(id, userId, "Alice",
                "alice@example.com", "Title", "desktop", now, now, 0, 0, blank);
            assertThat(unversioned.clientVersion()).isNull();
            assertThat(unversioned.sourceBadge()).isEqualTo("desktop");
        }

        AdminConversation tenArg = new AdminConversation(id, userId, "Alice", "alice@example.com",
            "Title", "desktop", now, now, 0, 0);
        assertThat(tenArg.clientVersion()).isNull();
        assertThat(tenArg.sourceBadge()).isEqualTo("desktop");

        AdminConversation nullSource = new AdminConversation(id, userId, "Alice",
            "alice@example.com", "Title", null, now, now, 0, 0);
        assertThat(nullSource.source()).isEqualTo("web");
        assertThat(nullSource.sourceBadge()).isEqualTo("web");

        AdminConversation blankSource = new AdminConversation(id, userId, "Alice",
            "alice@example.com", "Title", "   ", now, now, 0, 0);
        assertThat(blankSource.source()).isEqualTo("web");
        assertThat(blankSource.sourceBadge()).isEqualTo("web");
    }

    @Test
    @SuppressWarnings("unchecked")
    void testListFilteredConversations_projectsAndMapsClientVersion() throws Exception {
        JdbcClient.StatementSpec statementSpec = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<AdminConversation> querySpec =
            (JdbcClient.MappedQuerySpec<AdminConversation>) mock(JdbcClient.MappedQuerySpec.class);
        when(jdbcClient.sql(anyString())).thenReturn(statementSpec);
        when(statementSpec.params(anyMap())).thenReturn(statementSpec);
        when(statementSpec.query(any(RowMapper.class))).thenReturn(querySpec);
        when(querySpec.list()).thenReturn(List.of());

        repository.listFilteredConversations(ConversationFilter.empty(), 20, 0L);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcClient).sql(sqlCaptor.capture());
        assertThat(sqlCaptor.getValue()).contains("COALESCE(c.source, 'web') AS source",
            "WHEN c.source = 'desktop' THEN c.settings->>'desktopVersion'",
            "WHEN c.source = 'claude-plugin' THEN c.settings->>'pluginVersion'",
            "END AS client_version").doesNotContain("BTRIM", "jsonb_typeof", ":ws");

        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(statementSpec).params(paramsCaptor.capture());
        assertThat(paramsCaptor.getValue()).doesNotContainKey("ws");

        ArgumentCaptor<RowMapper<AdminConversation>> mapperCaptor =
            ArgumentCaptor.forClass(RowMapper.class);
        verify(statementSpec).query(mapperCaptor.capture());

        UUID convId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("id", UUID.class)).thenReturn(convId);
        when(rs.getObject("user_id", UUID.class)).thenReturn(userId);
        when(rs.getString("display_name")).thenReturn("Alice");
        when(rs.getString("email")).thenReturn("alice@example.com");
        when(rs.getString("title")).thenReturn("Desktop Chat");
        when(rs.getString("source")).thenReturn("desktop");
        when(rs.getObject("created_at", OffsetDateTime.class)).thenReturn(now);
        when(rs.getObject("updated_at", OffsetDateTime.class)).thenReturn(now);
        when(rs.getInt("thumbs_up_count")).thenReturn(2);
        when(rs.getInt("thumbs_down_count")).thenReturn(1);
        when(rs.getString("client_version")).thenReturn("v1.3.1");

        AdminConversation mapped = mapperCaptor.getValue().mapRow(rs, 0);
        assertThat(mapped).isNotNull();
        assertThat(mapped.clientVersion()).isEqualTo("v1.3.1");
        assertThat(mapped.sourceBadge()).isEqualTo("desktop v1.3.1");
    }
}
