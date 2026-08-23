package de.palsoftware.yvoke.ingest.api;

import de.palsoftware.yvoke.ingest.core.service.IngestService;
import de.palsoftware.yvoke.shared.jobengine.PrivilegedJobKindGuard;
import de.palsoftware.yvoke.shared.jobengine.model.EnqueueResult;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/ingest/v1")
public class IngestApiController {

    private final IngestService ingestService;

    public IngestApiController(IngestService ingestService) {
        this.ingestService = ingestService;
    }

    /**
     * Enqueues a KG-extract job, or reports the one already in flight for the same document and
     * target as a <strong>409 Conflict</strong> carrying its id — the same contract as
     * {@code POST /api/jobs/v1}. The adopted job runs with the settings it was enqueued with, so
     * answering 202 would tell the caller their request had been accepted when nothing was created.
     */
    @PostMapping(value = "/process-kg")
    public ResponseEntity<Map<String, UUID>> processDocumentKg(
        @RequestParam(value = "documentId", required = false) UUID documentId,
        @RequestParam(value = "sourceFile", required = false) String sourceFile,
        @RequestParam(value = "sourceCollection", required = false) String sourceCollection,
        @RequestParam(value = "sourceTag", required = false) String sourceTag,
        @RequestParam("collection") String targetCollectionName,
        @RequestParam("tag") String targetTag,
        @RequestParam(value = "kgPrompt", required = false) String kgPrompt) {

        EnqueueResult result = ingestService.processKg(documentId, sourceFile, sourceCollection,
            sourceTag, targetCollectionName, targetTag, kgPrompt);
        HttpStatus status = result.created() ? HttpStatus.ACCEPTED : HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(Map.of("id", result.jobId()));
    }

    @PostMapping(value = "/custom", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, UUID>> enqueueCustom(@RequestParam("file") MultipartFile file,
        @RequestParam("collection") String collection,
        @RequestParam(value = "tag", required = false) String tag,
        @RequestParam(value = "documentGlob", required = false,
            defaultValue = "**/*.md") String documentGlob,
        @RequestParam(value = "entitiesFile", required = false,
            defaultValue = "graph/entities.jsonl") String entitiesFile,
        @RequestParam(value = "relationshipsFile", required = false,
            defaultValue = "graph/relationships.jsonl") String relationshipsFile,
        @RequestParam(value = "summarizePrompt", required = false) String summarizePrompt) {

        UUID jobId = ingestService.enqueueCustom(file, collection, tag, documentGlob, entitiesFile,
            relationshipsFile, summarizePrompt);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("id", jobId));
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, UUID>> uploadAndEnqueue(
        @RequestParam("file") MultipartFile file, @RequestParam("collection") String collectionName,
        @RequestParam(value = "tag", required = false) String tag,
        @RequestParam("kind") String kind,
        @RequestParam(value = "jsonUniqueField", required = false) String jsonUniqueField,
        @RequestParam(value = "buildSectionSummaries",
            required = false) Boolean buildSectionSummaries,
        @RequestParam(value = "summarizePrompt", required = false) String summarizePrompt) {

        // Same gate as POST /api/jobs/v1: this endpoint names the job kind from a request param and
        // shares the ROLE_INGEST/USER/ADMIN chain, so without it a plain user could POST a 1-byte
        // file with kind=confluence-import and start a full space crawl on the stored admin token.
        // IngestService additionally allowlists the kinds this endpoint actually serves.
        PrivilegedJobKindGuard.requireAdminForPrivilegedKind(kind);
        UUID jobId = ingestService.uploadAndEnqueue(file, collectionName, tag, kind,
            jsonUniqueField, buildSectionSummaries, summarizePrompt);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("id", jobId));
    }
}
