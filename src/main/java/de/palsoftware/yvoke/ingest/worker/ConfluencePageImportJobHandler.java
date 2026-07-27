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
public class ConfluencePageImportJobHandler implements JobHandler {

    private final ConfluenceIngestService confluenceIngestService;

    public ConfluencePageImportJobHandler(ConfluenceIngestService confluenceIngestService) {
        this.confluenceIngestService = confluenceIngestService;
    }

    @Override
    public String kind() {
        return IngestJobKind.CONFLUENCE_PAGE_IMPORT.getValue();
    }

    @Override
    public List<JobStep> steps() {
        return List.of(JobStep.CHUNK, JobStep.EMBED, JobStep.INJECT);
    }

    @Override
    public boolean expectsEntities(IngestionJob job) {
        return false; // Skip Knowledge Graph generation for now
    }

    @Override
    public boolean expectsEntities() {
        return false;
    }

    @Override
    public JobCounts run(JobContext ctx) {
        IngestionJob job = ctx.job();
        String pageId = (String) job.settings().get("pageId");
        String title = (String) job.settings().get("title");

        if (pageId == null || pageId.isBlank()) {
            throw new IllegalArgumentException("Missing pageId in job settings");
        }

        return confluenceIngestService.ingestPage(ctx, pageId,
            title != null ? title : "Unknown Page");
    }
}
