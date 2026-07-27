package de.palsoftware.yvoke.ingest.worker;

import java.util.List;
import org.springframework.stereotype.Component;
import de.palsoftware.yvoke.ingest.core.confluence.ConfluenceIngestService;
import de.palsoftware.yvoke.ingest.core.model.IngestJobKind;
import de.palsoftware.yvoke.shared.jobengine.JobHandler;
import de.palsoftware.yvoke.shared.jobengine.model.IngestionJob;
import de.palsoftware.yvoke.shared.jobengine.model.JobContext;
import de.palsoftware.yvoke.shared.jobengine.model.JobCounts;
import de.palsoftware.yvoke.shared.jobengine.model.JobStep;

@Component
public class ConfluenceImportJobHandler implements JobHandler {

    private final ConfluenceIngestService confluenceIngestService;

    public ConfluenceImportJobHandler(ConfluenceIngestService confluenceIngestService) {
        this.confluenceIngestService = confluenceIngestService;
    }

    @Override
    public String kind() {
        return IngestJobKind.CONFLUENCE_IMPORT.getValue();
    }

    @Override
    public List<JobStep> steps() {
        return List.of(JobStep.CRAWL, JobStep.DISPATCH);
    }

    @Override
    public boolean expectsEntities(IngestionJob job) {
        return false; // Skip Knowledge Graph generation
    }

    @Override
    public boolean expectsEntities() {
        return false;
    }

    @Override
    public JobCounts run(JobContext ctx) {
        return confluenceIngestService.ingest(ctx);
    }
}
