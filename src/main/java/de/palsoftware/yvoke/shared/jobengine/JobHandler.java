package de.palsoftware.yvoke.shared.jobengine;

import de.palsoftware.yvoke.shared.jobengine.model.*;

import java.util.List;

public interface JobHandler {

    String kind();

    default List<JobStep> steps() {
        return List.of(JobStep.CHUNK, JobStep.EMBED, JobStep.INSERT, JobStep.INJECT);
    }

    default boolean expectsEntities(IngestionJob job) {
        return expectsEntities();
    }

    default boolean expectsEntities() {
        return false;
    }

    JobCounts run(JobContext ctx);
}
