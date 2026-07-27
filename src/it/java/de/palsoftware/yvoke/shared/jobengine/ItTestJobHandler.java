package de.palsoftware.yvoke.shared.jobengine;

import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import de.palsoftware.yvoke.shared.jobengine.model.JobContext;
import de.palsoftware.yvoke.shared.jobengine.model.JobCounts;
import de.palsoftware.yvoke.shared.jobengine.model.JobStep;

@Component
@Profile("!prod")
public class ItTestJobHandler implements JobHandler {

    public static final String KIND = "it_test";

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public List<JobStep> steps() {
        return List.of(JobStep.CHUNK, JobStep.EMBED, JobStep.INSERT, JobStep.EXTRACT,
            JobStep.INJECT);
    }

    @Override
    public boolean expectsEntities() {
        return true;
    }

    @Override
    public JobCounts run(JobContext ctx) {
        ctx.report(JobStep.CHUNK, 20);
        ctx.report(JobStep.EMBED, 40);
        ctx.report(JobStep.INSERT, 60);
        ctx.report(JobStep.EXTRACT, 80);
        ctx.report(JobStep.INJECT, 95);
        return new JobCounts(1, 8, 5, 7, 0);
    }
}
