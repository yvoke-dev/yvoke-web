package de.palsoftware.yvoke.chat.core.model;

public enum ConversationSource {
    WEB("web"), DESKTOP("desktop");

    private final String value;

    ConversationSource(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static ConversationSource fromValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Conversation source cannot be null");
        }
        for (ConversationSource src : ConversationSource.values()) {
            if (src.value.equals(value)) {
                return src;
            }
        }
        throw new IllegalArgumentException("Invalid conversation source: " + value);
    }
}
