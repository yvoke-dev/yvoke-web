package de.palsoftware.yvoke.ingest.worker;

import de.palsoftware.yvoke.ingest.core.service.DocumentIngestService;
import de.palsoftware.yvoke.ingest.core.model.IngestJobKind;
import de.palsoftware.yvoke.shared.jobengine.JobHandler;
import de.palsoftware.yvoke.shared.jobengine.model.JobContext;
import de.palsoftware.yvoke.shared.jobengine.model.JobCounts;
import de.palsoftware.yvoke.shared.jobengine.model.JobStep;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class KgDocumentExtractJobHandler implements JobHandler {

    private final DocumentIngestService documentIngestService;

    public KgDocumentExtractJobHandler(DocumentIngestService documentIngestService) {
        this.documentIngestService = documentIngestService;
    }

    @Override
    public String kind() {
        return IngestJobKind.KG_EXTRACT.getValue();
    }

    @Override
    public List<JobStep> steps() {
        return List.of(JobStep.CHUNK, JobStep.EXTRACT, JobStep.INJECT);
    }

    @Override
    public boolean expectsEntities() {
        return true;
    }

    @Override
    public JobCounts run(JobContext ctx) {
        return documentIngestService.processDocumentKg(ctx.job(), ctx);
    }
}
