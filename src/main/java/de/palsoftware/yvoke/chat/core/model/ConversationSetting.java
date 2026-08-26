package de.palsoftware.yvoke.chat.core.model;

public enum ConversationSetting {
    MODEL("model"), CHAT_PROMPT("chat-prompt"), STREAMING("streaming"), SHOW_THINKING(
        "show-thinking"), THINKING_LEVEL("thinking-level"), ORCHESTRATOR_PROFILE(
            "orchestrator-profile"), SHOW_PROTOTYPES("show-prototypes");

    private final String value;

    ConversationSetting(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
