package de.palsoftware.yvoke.chat.core.model;

import java.util.Locale;

/** Allowed reasoning/thinking effort levels for chat and orchestrator profiles. */
public enum ThinkingLevel {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high");

    private final String value;

    ThinkingLevel(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static ThinkingLevel parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Thinking level must be one of: low, medium, high.");
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (ThinkingLevel level : values()) {
            if (level.value.equals(normalized)) {
                return level;
            }
        }
        throw new IllegalArgumentException(
                "Invalid thinking level '" + raw + "'. Allowed values: low, medium, high.");
    }

    /** Validates and normalizes an optional thinking level (null/blank -> null). */
    public static String normalizeOptional(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return parse(raw).value();
    }
}
