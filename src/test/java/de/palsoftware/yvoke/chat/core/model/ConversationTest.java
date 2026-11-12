package de.palsoftware.yvoke.chat.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConversationTest {

    private static Conversation conversationWith(String source, Map<String, Object> settings) {
        return new Conversation(UUID.randomUUID(), UUID.randomUUID(), "Title", settings,
            Instant.now(), Instant.now(), List.of(), source, null);
    }

    @Test
    void desktopConversationPreservesVersionVerbatimAndFormatsBadgeWithSpaceOnly() {
        Map<String, String> cases =
            Map.of("1.3.1", "1.3.1", "v1.3.1", "v1.3.1", "V1.3.1", "V1.3.1", "vv1.3.1", "vv1.3.1",
                "Version 1.3.1", "Version 1.3.1", "vNext", "vNext", "v", "v", "V", "V");

        for (Map.Entry<String, String> entry : cases.entrySet()) {
            String raw = entry.getKey();
            String expected = entry.getValue();
            Conversation conv = conversationWith("desktop",
                Map.of(ConversationSetting.DESKTOP_VERSION.getValue(), raw));

            assertThat(conv.clientVersion()).as("raw=%s", raw).isEqualTo(expected);
            assertThat(conv.sourceBadge()).as("raw=%s", raw).isEqualTo("desktop " + expected);
        }

        for (String asIs : List.of(" 1.3.1 ", "1".repeat(257))) {
            Conversation conv = conversationWith("desktop",
                Map.of(ConversationSetting.DESKTOP_VERSION.getValue(), asIs));
            assertThat(conv.clientVersion()).isEqualTo(asIs);
            assertThat(conv.sourceBadge()).isEqualTo("desktop " + asIs);
        }
    }

    @Test
    void claudePluginConversationPreservesVersionVerbatimAndFormatsBadgeWithSpaceOnly() {
        Conversation conv = conversationWith("claude-plugin",
            Map.of(ConversationSetting.PLUGIN_VERSION.getValue(), "0.4.2"));
        assertThat(conv.clientVersion()).isEqualTo("0.4.2");
        assertThat(conv.sourceBadge()).isEqualTo("claude-plugin 0.4.2");

        Conversation prefixed = conversationWith("claude-plugin",
            Map.of(ConversationSetting.PLUGIN_VERSION.getValue(), "v0.4.2"));
        assertThat(prefixed.clientVersion()).isEqualTo("v0.4.2");
        assertThat(prefixed.sourceBadge()).isEqualTo("claude-plugin v0.4.2");
    }

    @Test
    void nonStringNullOrUnicodeBlankVersionFallsBackToBareSourceAcrossAllSources() {
        for (String source : List.of("desktop", "claude-plugin", "web")) {
            String key =
                "claude-plugin".equals(source) ? ConversationSetting.PLUGIN_VERSION.getValue()
                    : ConversationSetting.DESKTOP_VERSION.getValue();

            assertThat(conversationWith(source, Map.of()).clientVersion()).isNull();
            assertThat(conversationWith(source, Map.of()).sourceBadge()).isEqualTo(source);

            assertThat(conversationWith(source, null).clientVersion()).isNull();
            assertThat(conversationWith(source, null).sourceBadge()).isEqualTo(source);

            Map<String, Object> withNull = new HashMap<>();
            withNull.put(key, null);
            assertThat(conversationWith(source, withNull).clientVersion()).isNull();
            assertThat(conversationWith(source, withNull).sourceBadge()).isEqualTo(source);

            for (Object invalid : List.of("", " \t\n\u2003 ", Integer.valueOf(123), Boolean.TRUE,
                List.of(), Map.of())) {
                Conversation conv = conversationWith(source, Map.of(key, invalid));
                assertThat(conv.clientVersion()).as("source=%s invalid=%s", source, invalid)
                    .isNull();
                assertThat(conv.sourceBadge()).as("source=%s invalid=%s", source, invalid)
                    .isEqualTo(source);
            }
        }
    }

    @Test
    void webAndCrossSourceConversationsNeverExposeClientVersion() {
        Conversation webWithDesktopVer =
            conversationWith("web", Map.of("desktopVersion", "1.3.1", "pluginVersion", "0.4.2"));
        assertThat(webWithDesktopVer.clientVersion()).isNull();
        assertThat(webWithDesktopVer.sourceBadge()).isEqualTo("web");

        Conversation desktopWithPluginVer =
            conversationWith("desktop", Map.of("pluginVersion", "0.4.2"));
        assertThat(desktopWithPluginVer.clientVersion()).isNull();
        assertThat(desktopWithPluginVer.sourceBadge()).isEqualTo("desktop");

        Conversation pluginWithDesktopVer =
            conversationWith("claude-plugin", Map.of("desktopVersion", "1.3.1"));
        assertThat(pluginWithDesktopVer.clientVersion()).isNull();
        assertThat(pluginWithDesktopVer.sourceBadge()).isEqualTo("claude-plugin");
    }

    @Test
    void nullOrBlankSourceDefaultsToWebBadge() {
        Conversation nullSource = conversationWith(null, Map.of("desktopVersion", "1.3.1"));
        assertThat(nullSource.clientVersion()).isNull();
        assertThat(nullSource.sourceBadge()).isEqualTo("web");

        Conversation blankSource = conversationWith("   ", Map.of("desktopVersion", "1.3.1"));
        assertThat(blankSource.clientVersion()).isNull();
        assertThat(blankSource.sourceBadge()).isEqualTo("web");

        assertThat(Conversation.formatSourceBadge(null, "1.3.1")).isEqualTo("web 1.3.1");
        assertThat(Conversation.formatSourceBadge("  ", null)).isEqualTo("web");
        assertThat(Conversation.formatSourceBadge("desktop", "   ")).isEqualTo("desktop");
    }
}
