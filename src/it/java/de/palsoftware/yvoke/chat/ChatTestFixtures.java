package de.palsoftware.yvoke.chat;

import de.palsoftware.yvoke.rag.prompt.SystemPromptService;
import de.palsoftware.yvoke.rag.prompt.SystemPromptType;

/**
 * Shared test fixtures for chat integration and E2E tests.
 */
public final class ChatTestFixtures {

    private ChatTestFixtures() {}

    /**
     * Ensures that a default CHAT base system prompt is seeded in the database.
     * Required for tests that send chat questions without explicit playbook/system prompt configuration.
     */
    public static void ensureDefaultChatPrompt(SystemPromptService systemPromptService) {
        if (systemPromptService != null && systemPromptService.findChatPrompt(null).isEmpty()) {
            systemPromptService.savePrompt("default-chat", SystemPromptType.CHAT,
                "Base system prompt for IT", "Default Chat IT", "OIM");
            systemPromptService.setDefaultChatPromptName("default-chat");
        }
    }
}
