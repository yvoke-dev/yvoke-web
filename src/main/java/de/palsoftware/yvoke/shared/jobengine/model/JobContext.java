package de.palsoftware.yvoke.shared.jobengine.model;

public interface JobContext {

    IngestionJob job();

    void report(JobStep step, int progress);

    default void report(JobStep step, int progress, String message) {
        report(step, progress);
    }
}
