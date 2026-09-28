package de.palsoftware.yvoke.chat.core.repository;

import de.palsoftware.yvoke.document.core.repository.ChunkSurfacingMessageLookup;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Admin-facing read/update queries over chat-domain tables (conversations, messages,
 * message_feedback). Extracted from the former shared.web.admin.AdminQueryRepository.
 */
@Repository
public class ChatAdminQueryRepository implements ChunkSurfacingMessageLookup {

    private final JdbcClient jdbcClient;

    public ChatAdminQueryRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public enum FeedbackFilter {
        ALL, ANY, POSITIVE, NEGATIVE, NONE;
    }

    public record TimeFilter(
        String preset,
        OffsetDateTime fromCutoff,
        OffsetDateTime toCutoff,
        LocalDate fromDate,
        LocalDate toDate) {

        public static TimeFilter preset(String preset, Clock clock) {
            String normalized = preset != null ? preset.trim().toLowerCase(Locale.ROOT) : "all";
            Clock c = clock != null ? clock : Clock.systemUTC();
            if ("day".equals(normalized)) {
                return new TimeFilter("day", OffsetDateTime.now(c).minusDays(1), null, null, null);
            } else if ("week".equals(normalized)) {
                return new TimeFilter("week", OffsetDateTime.now(c).minusWeeks(1), null, null, null);
            } else if ("month".equals(normalized)) {
                return new TimeFilter("month", OffsetDateTime.now(c).minusDays(30), null, null, null);
            } else {
                return new TimeFilter("all", null, null, null, null);
            }
        }

        public static TimeFilter custom(LocalDate fromDate, LocalDate toDate) {
            if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
                LocalDate temp = fromDate;
                fromDate = toDate;
                toDate = temp;
            }
            OffsetDateTime fromCutoff =
                fromDate != null ? fromDate.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime() : null;
            OffsetDateTime toCutoff =
                toDate != null ? toDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime() : null;
            return new TimeFilter("custom", fromCutoff, toCutoff, fromDate, toDate);
        }

        public static TimeFilter all() {
            return preset("all", Clock.systemUTC());
        }
    }

    public record ConversationFilter(
        Set<UUID> userIds,
        boolean includeAnonymous,
        TimeFilter timeFilter,
        FeedbackFilter feedbackFilter) {

        public static ConversationFilter empty() {
            return new ConversationFilter(Set.of(), false, TimeFilter.all(), FeedbackFilter.ALL);
        }
    }

    public record ConversationUserOption(UUID id, String displayName, String email) {}

    public record UserConversationStats(UUID userId, String userDisplayName, String userEmail,
        long conversationCount, long thumbsUpCount, long thumbsDownCount) {}

    public record ConversationOverviewStats(long totalConversations, long totalThumbsUp,
        long totalThumbsDown, long registeredUserCount, List<UserConversationStats> userStats) {}

    public record AdminConversation(UUID id, UUID userId, String userDisplayName, String userEmail,
        String title, String source, OffsetDateTime createdAt, OffsetDateTime updatedAt,
        int thumbsUpCount, int thumbsDownCount) {}

    public record FeedbackComment(String feedbackId, String messageId, int rating, String comment,
        OffsetDateTime createdAt, String queryText, String conversationId, boolean reviewed,
        String notes) {}

    public List<AdminConversation> listFilteredConversations(ConversationFilter filter, int limit,
        long offset) {
        StringBuilder sql = new StringBuilder(
            """
                SELECT c.id, c.user_id, u.display_name, u.email, c.title, c.source, c.created_at, c.updated_at,
                       COALESCE(fb.thumbs_up_count, 0) AS thumbs_up_count,
                       COALESCE(fb.thumbs_down_count, 0) AS thumbs_down_count
                FROM conversations c
                LEFT JOIN users u ON c.user_id = u.id
                LEFT JOIN LATERAL (
                    SELECT COUNT(*) FILTER (WHERE mf.rating = 1) AS thumbs_up_count,
                           COUNT(*) FILTER (WHERE mf.rating = -1) AS thumbs_down_count
                    FROM messages m
                    JOIN message_feedback mf ON mf.message_id = m.id
                    WHERE m.conversation_id = c.id
                ) fb ON true
                WHERE 1=1
                """);
        Map<String, Object> params = new HashMap<>();
        params.put("limit", limit);
        params.put("offset", offset);
        appendConversationFilters(sql, params, filter);

        sql.append(" ORDER BY c.created_at DESC, c.id DESC LIMIT :limit OFFSET :offset");

        return jdbcClient.sql(sql.toString()).params(params)
            .query((rs, rowNum) -> new AdminConversation(rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class), rs.getString("display_name"),
                rs.getString("email"), rs.getString("title"), rs.getString("source"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class), rs.getInt("thumbs_up_count"),
                rs.getInt("thumbs_down_count")))
            .list();
    }

    public ConversationOverviewStats getConversationStats(ConversationFilter filter) {
        StringBuilder sql = new StringBuilder(
            """
                SELECT c.user_id, u.display_name, u.email,
                       COUNT(DISTINCT c.id) AS conversation_count,
                       COALESCE(SUM(CASE WHEN fbk.rating = 1 THEN 1 ELSE 0 END), 0) AS thumbs_up_count,
                       COALESCE(SUM(CASE WHEN fbk.rating = -1 THEN 1 ELSE 0 END), 0) AS thumbs_down_count,
                       SUM(COUNT(DISTINCT c.id)) OVER () AS total_conversations,
                       SUM(COALESCE(SUM(CASE WHEN fbk.rating = 1 THEN 1 ELSE 0 END), 0)) OVER () AS total_thumbs_up,
                       SUM(COALESCE(SUM(CASE WHEN fbk.rating = -1 THEN 1 ELSE 0 END), 0)) OVER () AS total_thumbs_down,
                       COUNT(*) FILTER (WHERE c.user_id IS NOT NULL) OVER () AS registered_user_count
                FROM conversations c
                LEFT JOIN users u ON c.user_id = u.id
                LEFT JOIN (
                    SELECT m.conversation_id, mf.rating
                    FROM messages m
                    JOIN message_feedback mf ON mf.message_id = m.id
                ) fbk ON fbk.conversation_id = c.id
                WHERE 1=1
                """);
        Map<String, Object> params = new HashMap<>();
        appendConversationFilters(sql, params, filter);
        sql.append(
            """
                 GROUP BY c.user_id, u.display_name, u.email
                 ORDER BY conversation_count DESC, thumbs_up_count DESC, u.display_name ASC NULLS LAST, c.user_id ASC NULLS LAST
                 LIMIT 50
                """);

        record StatRow(
            UserConversationStats user,
            long totalConversations,
            long totalThumbsUp,
            long totalThumbsDown,
            long registeredUserCount) {}

        List<StatRow> rows = jdbcClient.sql(sql.toString()).params(params)
            .query((rs, rowNum) -> new StatRow(
                new UserConversationStats(
                    rs.getObject("user_id", UUID.class),
                    rs.getString("display_name"),
                    rs.getString("email"),
                    rs.getLong("conversation_count"),
                    rs.getLong("thumbs_up_count"),
                    rs.getLong("thumbs_down_count")),
                rs.getLong("total_conversations"),
                rs.getLong("total_thumbs_up"),
                rs.getLong("total_thumbs_down"),
                rs.getLong("registered_user_count")))
            .list();

        if (rows.isEmpty()) {
            return new ConversationOverviewStats(0L, 0L, 0L, 0L, List.of());
        }

        StatRow first = rows.get(0);
        List<UserConversationStats> userStats = rows.stream().map(StatRow::user).toList();
        return new ConversationOverviewStats(
            first.totalConversations(),
            first.totalThumbsUp(),
            first.totalThumbsDown(),
            first.registeredUserCount(),
            userStats);
    }

    public List<ConversationUserOption> listConversationUserOptions(Set<UUID> additionalUserIds) {
        StringBuilder sql = new StringBuilder(
            """
                SELECT opt.id, opt.display_name, opt.email FROM (
                    (
                        SELECT u.id, u.display_name, u.email
                        FROM users u
                        WHERE EXISTS (SELECT 1 FROM conversations c WHERE c.user_id = u.id)
                        ORDER BY COALESCE(NULLIF(TRIM(u.display_name), ''), u.email) ASC NULLS LAST, u.email ASC, u.id ASC
                        LIMIT 200
                    )
                """);
        Map<String, Object> params = new HashMap<>();
        if (additionalUserIds != null && !additionalUserIds.isEmpty()) {
            sql.append("""
                UNION
                (
                    SELECT u.id, u.display_name, u.email
                    FROM users u
                    WHERE u.id IN (:additionalUserIds)
                )
                """);
            params.put("additionalUserIds", additionalUserIds);
        }
        sql.append(
            """
                ) opt
                ORDER BY COALESCE(NULLIF(TRIM(opt.display_name), ''), opt.email) ASC NULLS LAST, opt.email ASC, opt.id ASC
                """);

        List<ConversationUserOption> options =
            new ArrayList<>(jdbcClient.sql(sql.toString()).params(params)
                .query((rs, rowNum) -> new ConversationUserOption(rs.getObject("id", UUID.class),
                    rs.getString("display_name"), rs.getString("email")))
                .list());

        if (additionalUserIds != null && !additionalUserIds.isEmpty()) {
            Set<UUID> returnedIds = new HashSet<>();
            for (ConversationUserOption opt : options) {
                returnedIds.add(opt.id());
            }
            for (UUID uid : additionalUserIds) {
                if (uid != null && !returnedIds.contains(uid)) {
                    options.add(new ConversationUserOption(uid, "Unknown (" + uid + ")", ""));
                }
            }
        }

        return Collections.unmodifiableList(options);
    }

    void appendConversationFilters(StringBuilder sql, Map<String, Object> params, ConversationFilter filter) {
        if (filter == null) {
            return;
        }

        // 1. User filter
        Set<UUID> userIds = filter.userIds();
        boolean includeAnonymous = filter.includeAnonymous();
        if (userIds != null && !userIds.isEmpty() && includeAnonymous) {
            sql.append(" AND (c.user_id IN (:userIds) OR c.user_id IS NULL)");
            params.put("userIds", userIds);
        } else if (userIds != null && !userIds.isEmpty()) {
            sql.append(" AND c.user_id IN (:userIds)");
            params.put("userIds", userIds);
        } else if (includeAnonymous) {
            sql.append(" AND c.user_id IS NULL");
        }

        // 2. Time filter
        TimeFilter timeFilter = filter.timeFilter();
        if (timeFilter != null) {
            if (timeFilter.fromCutoff() != null) {
                sql.append(" AND c.created_at >= :fromCutoff");
                params.put("fromCutoff", timeFilter.fromCutoff());
            }
            if (timeFilter.toCutoff() != null) {
                sql.append(" AND c.created_at < :toCutoff");
                params.put("toCutoff", timeFilter.toCutoff());
            }
        }

        // 3. Feedback filter
        FeedbackFilter feedbackFilter = filter.feedbackFilter();
        if (feedbackFilter != null) {
            switch (feedbackFilter) {
                case POSITIVE -> sql.append("""
                     AND EXISTS (
                        SELECT 1 FROM messages m
                        JOIN message_feedback mf ON mf.message_id = m.id
                        WHERE m.conversation_id = c.id AND mf.rating = 1
                    )""");
                case NEGATIVE -> sql.append("""
                     AND EXISTS (
                        SELECT 1 FROM messages m
                        JOIN message_feedback mf ON mf.message_id = m.id
                        WHERE m.conversation_id = c.id AND mf.rating = -1
                    )""");
                case ANY -> sql.append("""
                     AND EXISTS (
                        SELECT 1 FROM messages m
                        JOIN message_feedback mf ON mf.message_id = m.id
                        WHERE m.conversation_id = c.id AND (mf.rating = 1 OR mf.rating = -1)
                    )""");
                case NONE -> sql.append("""
                     AND NOT EXISTS (
                        SELECT 1 FROM messages m
                        JOIN message_feedback mf ON mf.message_id = m.id
                        WHERE m.conversation_id = c.id AND (mf.rating = 1 OR mf.rating = -1)
                    )""");
                case ALL -> {
                    // No feedback filter
                }
            }
        }
    }

    @Override
    public List<Map<String, Object>> findMessagesSurfacingChunk(UUID chunkId) {
        String sql =
            """
                SELECT m.id::text AS message_id, m.conversation_id::text AS conversation_id, m.role, m.content, m.created_at,
                       c.title AS conversation_title
                FROM messages m
                JOIN conversations c ON m.conversation_id = c.id
                WHERE :chunkId = ANY(m.retrieved_chunk_ids)
                ORDER BY m.created_at DESC
                LIMIT 10
                """;
        return jdbcClient.sql(sql).param("chunkId", chunkId).query((rs, rowNum) -> {
            Map<String, Object> map = new HashMap<>();
            map.put("message_id", rs.getString("message_id"));
            map.put("conversation_id", rs.getString("conversation_id"));
            map.put("role", rs.getString("role"));
            map.put("content", rs.getString("content"));
            map.put("created_at", rs.getObject("created_at", OffsetDateTime.class));
            map.put("conversation_title", rs.getString("conversation_title"));
            return map;
        }).list();
    }

    public long countFeedbackByRating(int rating) {
        return jdbcClient.sql("SELECT COUNT(*) FROM message_feedback WHERE rating = :rating")
            .param("rating", rating).query(Long.class).single();
    }

    public long countFilteredFeedback(String rating, Boolean reviewed, String timeRange) {
        StringBuilder sql = new StringBuilder("""
            SELECT COUNT(*)
            FROM message_feedback f
            JOIN messages m ON f.message_id = m.id
            WHERE 1=1
            """);
        Map<String, Object> params = new HashMap<>();
        appendFilters(sql, params, rating, reviewed, timeRange);
        return jdbcClient.sql(sql.toString()).params(params).query(Long.class).single();
    }

    public List<FeedbackComment> listFeedback(String rating, Boolean reviewed, String timeRange,
        String sort, int size, int offset) {
        StringBuilder sql = new StringBuilder(
            """
                SELECT f.id::text AS feedback_id, f.message_id::text AS message_id, f.rating, f.comment, f.created_at,
                       f.reviewed, f.notes, m.content AS query_text, m.conversation_id::text AS conversation_id
                FROM message_feedback f
                JOIN messages m ON f.message_id = m.id
                WHERE 1=1
                """);
        Map<String, Object> params = new HashMap<>();
        params.put("limit", size);
        params.put("offset", offset);
        appendFilters(sql, params, rating, reviewed, timeRange);

        if ("oldest".equalsIgnoreCase(sort)) {
            sql.append(" ORDER BY f.created_at ASC, f.id ASC");
        } else {
            sql.append(" ORDER BY f.created_at DESC, f.id DESC");
        }
        sql.append(" LIMIT :limit OFFSET :offset");

        return jdbcClient.sql(sql.toString()).params(params)
            .query((rs, rowNum) -> new FeedbackComment(rs.getString("feedback_id"),
                rs.getString("message_id"), rs.getInt("rating"), rs.getString("comment"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getString("query_text"),
                rs.getString("conversation_id"), rs.getBoolean("reviewed"), rs.getString("notes")))
            .list();
    }

    public void setFeedbackReviewed(UUID id, boolean reviewed) {
        jdbcClient.sql(
            "UPDATE message_feedback SET reviewed = :reviewed, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
            .param("reviewed", reviewed).param("id", id).update();
    }

    public void setFeedbackNotes(UUID id, String notes) {
        jdbcClient.sql(
            "UPDATE message_feedback SET notes = :notes, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
            .param("notes", notes != null && !notes.isBlank() ? notes.trim() : null).param("id", id)
            .update();
    }

    private void appendFilters(StringBuilder sql, Map<String, Object> params, String rating,
        Boolean reviewed, String timeRange) {
        if ("good".equalsIgnoreCase(rating)) {
            sql.append(" AND f.rating = 1");
        } else if ("bad".equalsIgnoreCase(rating)) {
            sql.append(" AND f.rating = -1");
        }
        if (reviewed != null) {
            sql.append(" AND f.reviewed = :reviewed");
            params.put("reviewed", reviewed);
        }
        if (timeRange != null && !timeRange.isBlank() && !"all".equalsIgnoreCase(timeRange)) {
            TimeFilter tf = TimeFilter.preset(timeRange, Clock.systemUTC());
            if (tf.fromCutoff() != null) {
                sql.append(" AND f.created_at >= :timeCutoff");
                params.put("timeCutoff", tf.fromCutoff());
            }
        }
    }
}
