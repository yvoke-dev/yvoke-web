package de.palsoftware.yvoke.shared.jobengine.model;

public enum JobStatus {
    QUEUED("queued"), RUNNING("running"), COMPLETED("completed"), FAILED("failed"), CANCELLED(
        "cancelled");

    private final String dbValue;

    JobStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    public static JobStatus fromDbValue(String value) {
        for (JobStatus status : values()) {
            if (status.dbValue.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown job status: " + value);
    }
}
