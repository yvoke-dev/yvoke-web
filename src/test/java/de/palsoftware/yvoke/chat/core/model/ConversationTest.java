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
    void desktopConversationWithDesktopVersionFormatsBadge() {
        Conversation conv = conversationWith("desktop",
            Map.of(ConversationSetting.DESKTOP_VERSION.getValue(), "1.3.1"));

        assertThat(conv.clientVersion()).isEqualTo("1.3.1");
        assertThat(conv.sourceBadge()).isEqualTo("desktop v1.3.1");
    }

    @Test
    void desktopConversationStripsLeadingVAndWhitespaceWithoutDoublePrefix() {
        for (String raw : List.of("v1.3.1", "V1.3.1", "vv1.3.1", " 1.3.1 ", "  v1.3.1  ",
            "v 1.3.1")) {
            Conversation conv = conversationWith("desktop",
                Map.of(ConversationSetting.DESKTOP_VERSION.getValue(), raw));

            assertThat(conv.clientVersion()).as("raw=%s", raw).isEqualTo("1.3.1");
            assertThat(conv.sourceBadge()).as("raw=%s", raw).isEqualTo("desktop v1.3.1");
        }
    }

    @Test
    void claudePluginConversationWithPluginVersionFormatsBadge() {
        Conversation conv = conversationWith("claude-plugin",
            Map.of(ConversationSetting.PLUGIN_VERSION.getValue(), "0.4.2"));

        assertThat(conv.clientVersion()).isEqualTo("0.4.2");
        assertThat(conv.sourceBadge()).isEqualTo("claude-plugin v0.4.2");

        Conversation prefixed = conversationWith("claude-plugin",
            Map.of(ConversationSetting.PLUGIN_VERSION.getValue(), "v0.4.2"));
        assertThat(prefixed.clientVersion()).isEqualTo("0.4.2");
        assertThat(prefixed.sourceBadge()).isEqualTo("claude-plugin v0.4.2");
    }

    @Test
    void desktopConversationWithMissingBlankBareVOrNonStringVersionFallsBackToBareSource() {
        assertThat(conversationWith("desktop", Map.of()).clientVersion()).isNull();
        assertThat(conversationWith("desktop", Map.of()).sourceBadge()).isEqualTo("desktop");

        assertThat(conversationWith("desktop", null).clientVersion()).isNull();
        assertThat(conversationWith("desktop", null).sourceBadge()).isEqualTo("desktop");

        Map<String, Object> withNull = new HashMap<>();
        withNull.put("desktopVersion", null);
        assertThat(conversationWith("desktop", withNull).clientVersion()).isNull();
        assertThat(conversationWith("desktop", withNull).sourceBadge()).isEqualTo("desktop");

        for (Object invalid : List.of("", "   ", "v", "V", "vv", "v   ", "  V  ",
            Integer.valueOf(123), List.of("1.3.1"), Map.of("v", "1.3.1"))) {
            Conversation conv = conversationWith("desktop", Map.of("desktopVersion", invalid));
            assertThat(conv.clientVersion()).as("invalid=%s", invalid).isNull();
            assertThat(conv.sourceBadge()).as("invalid=%s", invalid).isEqualTo("desktop");
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

        assertThat(Conversation.formatSourceBadge(null, "1.3.1")).isEqualTo("web v1.3.1");
        assertThat(Conversation.formatSourceBadge("  ", null)).isEqualTo("web");
    }
}
