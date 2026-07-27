package de.palsoftware.yvoke.document.core.model;

public enum DocumentIngestionStatus {
    PENDING("pending"), PROCESSING("processing"), COMPLETED("completed"), FAILED("failed");

    private final String value;

    DocumentIngestionStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static DocumentIngestionStatus fromValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Document ingestion status cannot be null");
        }
        for (DocumentIngestionStatus status : DocumentIngestionStatus.values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Invalid document ingestion status: " + value);
    }
}
