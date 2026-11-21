package de.palsoftware.yvoke.chat.core.model;

import jakarta.annotation.Nullable;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Typed accessors and boundary validators for the untyped {@code conversations.settings} JSONB map.
 *
 * <p>
 * Eliminates raw {@code (String)} casts (which permanently 500 a conversation if a client ever
 * stored a non-string value) and ensures create/PATCH settings share the same key normalization and
 * type checking.
 */
public final class ConversationSettings {

    public static final int MAX_DESKTOP_VERSION_LENGTH = 256;
    public static final int MAX_STRING_SETTING_LENGTH = 256;

    private static final Set<String> DESKTOP_THINKING_LEVELS =
        Set.of("off", "low", "medium", "high");

    private static final Set<String> ALLOWED_KEYS =
        Set.of(ConversationSetting.MODEL.getValue(), ConversationSetting.THINKING_LEVEL.getValue(),
            ConversationSetting.STREAMING.getValue(), ConversationSetting.SHOW_THINKING.getValue(),
            ConversationSetting.SHOW_PROTOTYPES.getValue(),
            ConversationSetting.ORCHESTRATOR_PROFILE.getValue(),
            ConversationSetting.DESKTOP_VERSION.getValue(), "client");

    private ConversationSettings() {}

    @Nullable
    public static String getString(@Nullable Map<String, Object> settings,
        ConversationSetting key) {
        if (settings == null || key == null) {
            return null;
        }
        Object raw = settings.get(key.getValue());
        if (raw instanceof String s) {
            return s;
        }
        return null;
    }

    public static boolean getBoolean(@Nullable Map<String, Object> settings,
        ConversationSetting key, boolean defaultValue) {
        if (settings == null || key == null) {
            return defaultValue;
        }
        Object raw = settings.get(key.getValue());
        if (raw instanceof Boolean b) {
            return b;
        }
        if (raw instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        return defaultValue;
    }

    /**
     * Normalizes and validates a desktop sync settings map (create or PATCH):
     * <ul>
     * <li>Maps {@code thinkingLevel} to {@code thinking-level} consistently on both create and
     * update.</li>
     * <li>Validates every key against the allowed set and checks exact value types and length
     * caps.</li>
     * <li>Drops blank/null {@code desktopVersion}; rejects non-string or oversized versions.</li>
     * </ul>
     *
     * @throws IllegalArgumentException if any setting key is unknown or has an invalid type/value
     */
    public static Map<String, Object> normalizeAndValidateDesktop(
        @Nullable Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return new HashMap<>();
        }
        Map<String, Object> out = new HashMap<>();
        // Normalize camelCase thinkingLevel -> thinking-level first (canonical key wins if both are
        // supplied with the same value).
        if (raw.containsKey("thinkingLevel")
            && !raw.containsKey(ConversationSetting.THINKING_LEVEL.getValue())) {
            Object val = raw.get("thinkingLevel");
            validateThinkingLevel(val, out);
        }

        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String key = entry.getKey();
            Object val = entry.getValue();
            if ("thinkingLevel".equals(key)) {
                if (!out.containsKey(ConversationSetting.THINKING_LEVEL.getValue())) {
                    validateThinkingLevel(val, out);
                }
                continue;
            }
            if (!ALLOWED_KEYS.contains(key)) {
                throw new IllegalArgumentException("Unknown conversation setting key: " + key);
            }
            if (ConversationSetting.DESKTOP_VERSION.getValue().equals(key)) {
                validateDesktopVersion(val, out);
            } else if (ConversationSetting.THINKING_LEVEL.getValue().equals(key)) {
                validateThinkingLevel(val, out);
            } else if (ConversationSetting.STREAMING.getValue().equals(key)
                || ConversationSetting.SHOW_THINKING.getValue().equals(key)
                || ConversationSetting.SHOW_PROTOTYPES.getValue().equals(key)) {
                if (!(val instanceof Boolean)) {
                    throw new IllegalArgumentException("Invalid " + key + ": must be a boolean");
                }
                out.put(key, val);
            } else {
                // model, orchestrator-profile, client
                if (val == null) {
                    out.put(key, "");
                } else if (!(val instanceof String str)) {
                    throw new IllegalArgumentException("Invalid " + key + ": must be a string");
                } else {
                    String trimmed = str.trim();
                    if (trimmed.length() > MAX_STRING_SETTING_LENGTH) {
                        throw new IllegalArgumentException(
                            "Invalid " + key + ": exceeds maximum length");
                    }
                    out.put(key, trimmed);
                }
            }
        }
        return out;
    }

    private static void validateDesktopVersion(Object raw, Map<String, Object> out) {
        String key = ConversationSetting.DESKTOP_VERSION.getValue();
        if (raw == null) {
            return;
        }
        if (!(raw instanceof String str)) {
            throw new IllegalArgumentException("Invalid desktopVersion: must be a string");
        }
        String stripped = str.strip();
        if (stripped.isEmpty()) {
            return;
        }
        if (stripped.length() > MAX_DESKTOP_VERSION_LENGTH) {
            throw new IllegalArgumentException("Invalid desktopVersion: exceeds maximum length");
        }
        out.put(key, stripped);
    }

    private static void validateThinkingLevel(Object raw, Map<String, Object> out) {
        if (raw == null) {
            return;
        }
        if (!(raw instanceof String str)) {
            throw new IllegalArgumentException("Invalid thinking-level: must be a string");
        }
        String normalized = str.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return;
        }
        if (!DESKTOP_THINKING_LEVELS.contains(normalized)) {
            throw new IllegalArgumentException(
                "Invalid thinking-level '" + str + "'. Allowed values: off, low, medium, high.");
        }
        out.put(ConversationSetting.THINKING_LEVEL.getValue(), normalized);
    }
}
