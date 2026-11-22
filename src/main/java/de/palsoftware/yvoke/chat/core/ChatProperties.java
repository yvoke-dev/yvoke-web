package de.palsoftware.yvoke.chat.core;

import jakarta.validation.constraints.NotEmpty;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.chat")
public record ChatProperties(
        boolean enabled,
        @NotEmpty List<String> allowedModels,
        boolean playbookValidationEnabled,
        boolean traceToolCalls,
        String modelLabels,
        Duration staleGeneratingAfter,
        int maxMessageChars,
        int maxHistoryChars) {

    public ChatProperties {
        if (staleGeneratingAfter == null
                || staleGeneratingAfter.isZero()
                || staleGeneratingAfter.isNegative()) {
            throw new IllegalArgumentException(
                    "app.chat.stale-generating-after must be a positive duration");
        }
        if (maxMessageChars <= 0) {
            throw new IllegalArgumentException(
                    "app.chat.max-message-chars must be positive, got: " + maxMessageChars);
        }
        if (maxHistoryChars <= 0) {
            throw new IllegalArgumentException(
                    "app.chat.max-history-chars must be positive, got: " + maxHistoryChars);
        }
    }
}
