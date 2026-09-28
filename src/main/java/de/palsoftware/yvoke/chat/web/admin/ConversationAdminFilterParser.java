package de.palsoftware.yvoke.chat.web.admin;

import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository.ConversationFilter;
import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository.FeedbackFilter;
import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository.TimeFilter;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Web-layer parser for conversation administration filter parameters. Validates inputs fail-closed,
 * normalizes inverted custom dates, and constructs typed domain records.
 */
public final class ConversationAdminFilterParser {

    private static final Set<String> ALLOWED_PRESETS =
        Set.of("all", "day", "week", "month", "custom");

    private ConversationAdminFilterParser() {}

    public static ConversationFilter parse(List<String> rawUserIds, String rawTimeRange,
        String rawFromDate, String rawToDate, String rawFeedback, Clock clock) {

        Set<UUID> userIds = new LinkedHashSet<>();
        boolean includeAnonymous = false;
        if (rawUserIds != null) {
            for (String raw : rawUserIds) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                String trimmed = raw.trim();
                if ("anonymous".equalsIgnoreCase(trimmed)) {
                    includeAnonymous = true;
                } else {
                    try {
                        userIds.add(UUID.fromString(trimmed));
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("Invalid user ID: " + trimmed);
                    }
                }
            }
        }

        LocalDate fromDate = parseDate(rawFromDate);
        LocalDate toDate = parseDate(rawToDate);

        String normalizedPreset =
            rawTimeRange != null ? rawTimeRange.trim().toLowerCase(Locale.ROOT) : "all";
        if (!normalizedPreset.isBlank() && !ALLOWED_PRESETS.contains(normalizedPreset)) {
            throw new IllegalArgumentException("Unknown time range preset: " + rawTimeRange);
        }

        Clock c = clock != null ? clock : Clock.systemUTC();
        TimeFilter timeFilter;
        if ("custom".equals(normalizedPreset)
            || (rawTimeRange == null && (fromDate != null || toDate != null))) {
            if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
                LocalDate temp = fromDate;
                fromDate = toDate;
                toDate = temp;
            }
            OffsetDateTime fromCutoff =
                fromDate != null ? fromDate.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime() : null;
            OffsetDateTime toCutoff =
                toDate != null ? toDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime()
                    : null;
            timeFilter = new TimeFilter("custom", fromCutoff, toCutoff, fromDate, toDate);
        } else if ("day".equals(normalizedPreset)) {
            timeFilter =
                new TimeFilter("day", OffsetDateTime.now(c).minusDays(1), null, null, null);
        } else if ("week".equals(normalizedPreset)) {
            timeFilter =
                new TimeFilter("week", OffsetDateTime.now(c).minusWeeks(1), null, null, null);
        } else if ("month".equals(normalizedPreset)) {
            timeFilter =
                new TimeFilter("month", OffsetDateTime.now(c).minusDays(30), null, null, null);
        } else {
            timeFilter = new TimeFilter("all", null, null, null, null);
        }

        FeedbackFilter feedbackFilter = parseFeedback(rawFeedback);

        return new ConversationFilter(Collections.unmodifiableSet(userIds), includeAnonymous,
            timeFilter, feedbackFilter);
    }

    public static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim(), DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                "Invalid date format: " + raw + ". Expected yyyy-MM-dd");
        }
    }

    public static FeedbackFilter parseFeedback(String raw) {
        if (raw == null || raw.isBlank()) {
            return FeedbackFilter.ALL;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        try {
            return FeedbackFilter.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown feedback filter: " + raw);
        }
    }
}
