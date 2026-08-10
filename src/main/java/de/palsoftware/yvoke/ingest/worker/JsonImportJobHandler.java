package de.palsoftware.yvoke.ingest.worker;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.ingest.core.UploadPathGuard;
import de.palsoftware.yvoke.ingest.core.model.IngestJobKind;
import de.palsoftware.yvoke.jsonobject.core.service.JsonObjectService;
import de.palsoftware.yvoke.shared.jobengine.JobHandler;
import de.palsoftware.yvoke.shared.jobengine.model.IngestionJob;
import de.palsoftware.yvoke.shared.jobengine.model.JobContext;
import de.palsoftware.yvoke.shared.jobengine.model.JobCounts;
import de.palsoftware.yvoke.shared.jobengine.model.JobStatus;
import de.palsoftware.yvoke.shared.jobengine.model.JobStep;
import de.palsoftware.yvoke.shared.jobengine.repository.JobRepository;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class JsonImportJobHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(JsonImportJobHandler.class);

    private final JsonObjectService jsonObjectService;
    private final ObjectMapper objectMapper;
    private final JobRepository jobRepository;
    private final UploadPathGuard uploadPathGuard;

    public JsonImportJobHandler(JsonObjectService jsonObjectService, ObjectMapper objectMapper,
        JobRepository jobRepository, UploadPathGuard uploadPathGuard) {
        this.jsonObjectService = jsonObjectService;
        this.objectMapper = objectMapper;
        this.jobRepository = jobRepository;
        this.uploadPathGuard = uploadPathGuard;
    }

    @Override
    public String kind() {
        return IngestJobKind.JSON_IMPORT.getValue();
    }

    @Override
    public List<JobStep> steps() {
        return List.of(JobStep.CHUNK, JobStep.INSERT);
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
        String sourceRef = ctx.job().sourceRef();
        if (sourceRef == null || sourceRef.isBlank()) {
            throw new IllegalArgumentException("Source reference (file path) is required");
        }

        // A sourceRef is attacker-influenced (POST /api/jobs/v1 lets any ROLE_INGEST caller name a
        // path), so it MUST be confined to app.upload-dir like every other file-backed handler —
        // reading it directly was an arbitrary-file-read into a searchable collection.
        File file = uploadPathGuard.resolve(sourceRef).toFile();
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("File not found: " + sourceRef);
        }

        ctx.report(JobStep.CHUNK, 0);

        List<Map<String, Object>> objects = new ArrayList<>();
        String filename = file.getName();

        if (filename.toLowerCase().endsWith(".jsonl")) {
            // parse jsonl
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                int lineCount = 0;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().isEmpty())
                        continue;
                    Map<String, Object> obj =
                        objectMapper.readValue(line, new TypeReference<Map<String, Object>>() {});
                    objects.add(obj);
                    lineCount++;
                    if (lineCount % 1000 == 0) {
                        log.debug("Parsed {} lines from {}", lineCount, filename);
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to parse JSONL file: " + e.getMessage(), e);
            }
        } else {
            // parse normal json, expecting an array
            // or single object? Let's parse as List<Map> or Map and wrap in List
            try {
                objects =
                    objectMapper.readValue(file, new TypeReference<List<Map<String, Object>>>() {});
            } catch (Exception e) {
                // maybe it's a single object
                try {
                    Map<String, Object> singleObj =
                        objectMapper.readValue(file, new TypeReference<Map<String, Object>>() {});
                    objects.add(singleObj);
                } catch (Exception e2) {
                    throw new IllegalArgumentException(
                        "File must contain a JSON array of objects or a single JSON object. Error: "
                            + e.getMessage());
                }
            }
        }

        // Cooperative cancellation, mirroring ConfluenceIngestService. This handler has no batches
        // to check between — the import is ONE @Transactional bulk write — so the checkpoint is
        // placed where it can still prevent something: after the parse (minutes, for a large
        // .jsonl) and before the write. A stop issued during the parse used to be ignored and the
        // objects were written anyway.
        checkCancellation(ctx.job().id());

        ctx.report(JobStep.INSERT, 50);
        log.info("Importing {} json objects into collection '{}'", objects.size(),
            ctx.job().collectionName());

        String uniqueFieldPath = null;
        if (ctx.job().settings() != null && ctx.job().settings().containsKey("jsonUniqueField")) {
            uniqueFieldPath = (String) ctx.job().settings().get("jsonUniqueField");
        }

        jsonObjectService.importObjects(ctx.job().collectionId(), ctx.job().collectionName(),
            objects, filename, ctx.job().tags(), uniqueFieldPath);

        ctx.report(JobStep.INSERT, 100);
        return new JobCounts(0, 0, 0, 0, objects.size());
    }

    /**
     * A job that is no longer RUNNING (an admin stopped it) aborts here. A missing row is treated
     * as cancelled — there is nothing left to write to; a null id only happens outside the worker.
     */
    private void checkCancellation(UUID jobId) {
        if (jobId == null) {
            return;
        }
        JobStatus status = jobRepository.findStatusById(jobId).orElse(JobStatus.CANCELLED);
        if (status != JobStatus.RUNNING) {
            throw new IllegalStateException("Job was cancelled by administrator");
        }
    }
}
