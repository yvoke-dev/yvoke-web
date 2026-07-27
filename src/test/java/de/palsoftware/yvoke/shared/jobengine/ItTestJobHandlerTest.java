package de.palsoftware.yvoke.shared.jobengine;

import de.palsoftware.yvoke.shared.jobengine.model.*;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ItTestJobHandlerTest {

    @Test
    void walksFullStepTaxonomyAndReportsNonZeroCounts() {
        ItTestJobHandler handler = new ItTestJobHandler();
        List<JobStep> reportedSteps = new ArrayList<>();

        JobContext ctx = new JobContext() {
            @Override
            public IngestionJob job() {
                return null;
            }

            @Override
            public void report(JobStep step, int progress) {
                reportedSteps.add(step);
                assertThat(progress).isBetween(0, 100);
            }
        };

        JobCounts counts = handler.run(ctx);

        assertThat(reportedSteps).containsExactly(JobStep.CHUNK, JobStep.EMBED, JobStep.INSERT,
            JobStep.EXTRACT, JobStep.INJECT);
        assertThat(counts.entities()).isPositive();
        assertThat(counts.docs()).isPositive();
        assertThat(handler.kind()).isEqualTo("it_test");
        assertThat(handler.expectsEntities()).isTrue();
    }
}
