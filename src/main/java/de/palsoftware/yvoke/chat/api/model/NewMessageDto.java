package de.palsoftware.yvoke.chat.api.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * SEC-18: bounds on the free-text fields of a synced message so a single message cannot carry an
 * unbounded payload.
 */
public record NewMessageDto(@NotBlank @Size(max = 32) String role,
    @Size(max = 1_000_000) String content, Integer promptTokens, Integer completionTokens,
    Integer totalTokens, Integer cachedTokens, Integer thoughtTokens,
    @Size(max = 255) String playbook, Integer playbookVersion) {

    public NewMessageDto(String role, String content, Integer promptTokens,
        Integer completionTokens, Integer totalTokens, Integer cachedTokens,
        Integer thoughtTokens) {
        this(role, content, promptTokens, completionTokens, totalTokens, cachedTokens,
            thoughtTokens, null, null);
    }
}
