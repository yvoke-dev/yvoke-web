package de.palsoftware.yvoke.ingest.worker;

import org.springframework.stereotype.Component;
import de.palsoftware.yvoke.ingest.core.model.IngestJobKind;
import de.palsoftware.yvoke.ingest.core.service.CustomIngestService;
import de.palsoftware.yvoke.shared.jobengine.JobHandler;
import de.palsoftware.yvoke.shared.jobengine.model.JobContext;
import de.palsoftware.yvoke.shared.jobengine.model.JobCounts;

@Component
public class CustomIngestJobHandler implements JobHandler {

    private final CustomIngestService customIngestService;

    public CustomIngestJobHandler(CustomIngestService customIngestService) {
        this.customIngestService = customIngestService;
    }

    @Override
    public String kind() {
        return IngestJobKind.CUSTOM.getValue();
    }

    @Override
    public boolean expectsEntities() {
        return false;
    }

    @Override
    public JobCounts run(JobContext ctx) {
        return customIngestService.ingest(ctx.job(), ctx);
    }
}
