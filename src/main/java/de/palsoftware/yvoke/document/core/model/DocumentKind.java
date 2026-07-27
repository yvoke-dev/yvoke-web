package de.palsoftware.yvoke.document.core.model;

public enum DocumentKind {
    STANDARD("standard"), HIERARCHICAL("hierarchical"), CONFLUENCE("confluence");

    private final String value;

    DocumentKind(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
