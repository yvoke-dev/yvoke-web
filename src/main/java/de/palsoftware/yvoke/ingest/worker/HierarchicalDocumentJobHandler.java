package de.palsoftware.yvoke.ingest.worker;

import java.util.List;
import org.springframework.stereotype.Component;
import de.palsoftware.yvoke.ingest.core.model.IngestJobKind;
import de.palsoftware.yvoke.ingest.core.service.DocumentIngestService;
import de.palsoftware.yvoke.shared.jobengine.JobHandler;
import de.palsoftware.yvoke.shared.jobengine.model.IngestionJob;
import de.palsoftware.yvoke.shared.jobengine.model.JobContext;
import de.palsoftware.yvoke.shared.jobengine.model.JobCounts;
import de.palsoftware.yvoke.shared.jobengine.model.JobStep;

@Component
public class HierarchicalDocumentJobHandler implements JobHandler {

    private final DocumentIngestService documentIngestService;

    public HierarchicalDocumentJobHandler(DocumentIngestService documentIngestService) {
        this.documentIngestService = documentIngestService;
    }

    @Override
    public String kind() {
        return IngestJobKind.HIERARCHICAL.getValue();
    }

    @Override
    public List<JobStep> steps() {
        return List.of(JobStep.CHUNK, JobStep.EMBED, JobStep.INSERT, JobStep.EXTRACT);
    }

    @Override
    public boolean expectsEntities(IngestionJob job) {
        return false;
    }

    @Override
    public boolean expectsEntities() {
        return false;
    }

    @Override
    public JobCounts run(JobContext ctx) {
        return documentIngestService.ingestHierarchical(ctx.job(), ctx);
    }
}
