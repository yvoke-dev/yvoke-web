package de.palsoftware.yvoke.chat.core.model;

public enum MessageRole {
    USER("user"), ASSISTANT("assistant"), SYSTEM("system");

    private final String value;

    MessageRole(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static MessageRole fromValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Message role cannot be null");
        }
        for (MessageRole role : MessageRole.values()) {
            if (role.value.equals(value)) {
                return role;
            }
        }
        throw new IllegalArgumentException("Invalid message role: " + value);
    }
}
