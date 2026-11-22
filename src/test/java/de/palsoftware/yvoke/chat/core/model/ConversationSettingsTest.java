package de.palsoftware.yvoke.chat.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConversationSettingsTest {

    @Test
    void typedAccessorsIgnoreCorruptNonStringOrNonBooleanValuesInsteadOfThrowingClassCast() {
        Map<String, Object> corrupt =
                Map.of(
                        "model",
                        12345,
                        "thinking-level",
                        List.of("high"),
                        "orchestrator-profile",
                        Map.of("nested", true),
                        "streaming",
                        List.of(true));

        assertThat(ConversationSettings.getString(corrupt, ConversationSetting.MODEL)).isNull();
        assertThat(ConversationSettings.getString(corrupt, ConversationSetting.THINKING_LEVEL))
                .isNull();
        assertThat(
                        ConversationSettings.getString(
                                corrupt, ConversationSetting.ORCHESTRATOR_PROFILE))
                .isNull();
        assertThat(ConversationSettings.getBoolean(corrupt, ConversationSetting.STREAMING, false))
                .isFalse();
    }

    @Test
    void normalizeDesktopMapsThinkingLevelAndAcceptsValidKeysOnCreateAndPatch() {
        Map<String, Object> input =
                Map.of(
                        "thinkingLevel",
                        "HIGH",
                        "model",
                        " claude-sonnet-4-6 ",
                        "desktopVersion",
                        " 1.4.0 ",
                        "client",
                        "desktop",
                        "streaming",
                        true);

        Map<String, Object> normalized = ConversationSettings.normalizeAndValidateDesktop(input);

        assertThat(normalized)
                .containsEntry("thinking-level", "high")
                .containsEntry("model", "claude-sonnet-4-6")
                .containsEntry("desktopVersion", "1.4.0")
                .containsEntry("client", "desktop")
                .containsEntry("streaming", true)
                .doesNotContainKey("thinkingLevel");
    }

    @Test
    void normalizeDesktopAcceptsOffThinkingLevelUsedByDesktopApp() {
        Map<String, Object> normalized =
                ConversationSettings.normalizeAndValidateDesktop(Map.of("thinkingLevel", "off"));
        assertThat(normalized).containsEntry("thinking-level", "off");
    }

    @Test
    void normalizeDesktopRejectsUnknownKeysAndInvalidTypes() {
        assertThatThrownBy(() ->
                        ConversationSettings.normalizeAndValidateDesktop(
                                Map.of("injected", "boom")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown conversation setting key");

        assertThatThrownBy(
                        () -> ConversationSettings.normalizeAndValidateDesktop(Map.of("model", 42)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a string");

        assertThatThrownBy(() ->
                        ConversationSettings.normalizeAndValidateDesktop(
                                Map.of("streaming", "yes")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a boolean");

        assertThatThrownBy(() ->
                        ConversationSettings.normalizeAndValidateDesktop(
                                Map.of("thinkingLevel", "max")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("thinking-level");

        Map<String, Object> nullMap = new HashMap<>();
        nullMap.put("desktopVersion", null);
        assertThat(ConversationSettings.normalizeAndValidateDesktop(nullMap)).isEmpty();
    }
}
