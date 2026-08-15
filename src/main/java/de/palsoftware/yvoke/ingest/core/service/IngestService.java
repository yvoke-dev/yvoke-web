package de.palsoftware.yvoke.ingest.core.service;

import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.service.CollectionService;
import de.palsoftware.yvoke.document.core.repository.DocumentRepository;
import de.palsoftware.yvoke.ingest.core.model.IngestJobKind;
import jakarta.annotation.Nullable;
import de.palsoftware.yvoke.shared.jobengine.model.EnqueueRequest;
import de.palsoftware.yvoke.shared.jobengine.model.EnqueueResult;
import de.palsoftware.yvoke.shared.jobengine.service.JobService;
import de.palsoftware.yvoke.tag.core.service.TagService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Orchestrates ingest job creation for the {@code /api/ingest/v1} endpoints (MNT-04): resolving
 * target documents/collections, staging uploaded files under a path-traversal-safe location, and
 * enqueuing the corresponding job. Extracted out of {@code IngestApiController} so the controller
 * is pure HTTP glue; each method returns the created job id and throws
 * {@link ResponseStatusException} with the same status codes and reason strings the API has always
 * returned.
 *
 * <p>
 * Collection creation and tag registration run before {@code jobService.enqueue}, because the
 * enqueue path validates that the collection and tag already exist; that ordering is load-bearing
 * and preserved. Each step keeps its own transaction — the orchestration is intentionally not
 * wrapped in a single transaction so partial-failure semantics are unchanged.
 */
@Service
public class IngestService {

    /** Job kinds {@code POST /api/ingest/v1/upload} serves: one uploaded file, one job. */
    private static final Set<String> UPLOAD_KINDS = Set.of(IngestJobKind.STANDARD.getValue(),
        IngestJobKind.HIERARCHICAL.getValue(), IngestJobKind.JSON_IMPORT.getValue());

    private final JobService jobService;
    private final CollectionService collectionService;
    private final TagService tagService;
    private final DocumentRepository documentRepository;
    private final String uploadDir;

    public IngestService(JobService jobService, CollectionService collectionService,
        TagService tagService, DocumentRepository documentRepository,
        @Value("${app.upload-dir}") String uploadDir) {
        this.jobService = jobService;
        this.collectionService = collectionService;
        this.tagService = tagService;
        this.documentRepository = documentRepository;
        this.uploadDir = uploadDir;
    }

    /**
     * Resolves the target document (by id, or by source file/collection/tag lookup), ensures the
     * target collection and tag exist, and enqueues a KG-extract job.
     *
     * <p>
     * Returns the {@link EnqueueResult}, not a bare id: a duplicate request ADOPTS the job already
     * in flight for the same document/target and that job runs with the settings it was enqueued
     * with — so the caller has to be able to tell "created" from "adopted" (the API reports the
     * latter as a 409, matching {@code POST /api/jobs/v1}) instead of being told 202 for work that
     * was never created.
     */
    public EnqueueResult processKg(UUID documentId, String sourceFile, String sourceCollection,
        String sourceTag, String targetCollectionName, String targetTag) {

        UUID targetDocId = documentId;
        if (targetDocId == null) {
            if (sourceFile == null || sourceCollection == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Either 'documentId' or both 'sourceFile' and 'sourceCollection' must be provided.");
            }
            String trimmedFile = sourceFile.trim();
            String trimmedCol = sourceCollection.trim();
            String trimmedSourceTag =
                (sourceTag != null && !sourceTag.isBlank()) ? sourceTag.trim() : targetTag.trim();

            Optional<UUID> foundId =
                documentRepository.findIdByFile(trimmedCol, trimmedSourceTag, trimmedFile);

            if (foundId.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    String.format("Document not found for file '%s' in collection '%s' tag '%s'",
                        trimmedFile, trimmedCol, trimmedSourceTag));
            }
            targetDocId = foundId.get();
        }

        // Validate target collection exists
        String trimmedTargetCol = targetCollectionName.trim();
        if (collectionService.getCollection(trimmedTargetCol).isEmpty()) {
            collectionService.createCollection(trimmedTargetCol, "Auto-created by KG process API");
        }

        // Ensure tag is added to the collection
        String trimmedTargetTag = targetTag.trim();
        Collection col = collectionService.getCollection(trimmedTargetCol).orElseThrow();
        tagService.addTagToCollection(col.id(), trimmedTargetTag);

        // A duplicate request adopts the job already in flight for the same document/target, so
        // the caller gets that job's id instead of a 500 from the admission-control index.
        return jobService.enqueue(new EnqueueRequest(IngestJobKind.KG_EXTRACT.getValue(),
            targetDocId.toString(), trimmedTargetTag, trimmedTargetCol));
    }

    /**
     * Stages an uploaded custom-corpus zip in a fresh {@code custom_*} temp directory (confined
     * against path traversal) and enqueues a custom-ingest job. Returns the new job id.
     */
    public UUID enqueueCustom(MultipartFile file, String collection, String tag,
        String documentGlob, String entitiesFile, String relationshipsFile) {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File is empty");
        }

        try {
            // Stage inside app.upload-dir: the worker-side UploadPathGuard only accepts
            // sourceRefs under that root, so a system-temp staging dir can never be ingested.
            Path uploadRoot = Path.of(uploadDir).toAbsolutePath().normalize();
            Files.createDirectories(uploadRoot);
            Path tempDir = Files.createTempDirectory(uploadRoot, "custom_");
            // Never derive the staging path from the raw client filename: strip it to a bare
            // name component and confine it to tempDir, so a crafted filename (e.g. "../../x")
            // cannot escape the staging directory (CWE-22 arbitrary file write).
            Path zipPath = tempDir.resolve(safeUploadName(file.getOriginalFilename())).normalize();
            if (!zipPath.startsWith(tempDir)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid uploaded file name");
            }
            file.transferTo(zipPath.toFile());

            Map<String, Object> settings = new HashMap<>();
            settings.put("documentGlob", documentGlob);
            settings.put("entitiesFile", entitiesFile);
            settings.put("relationshipsFile", relationshipsFile);

            EnqueueRequest request = new EnqueueRequest(IngestJobKind.CUSTOM.getValue(),
                zipPath.toString(), tag, collection, settings);
            return jobService.enqueue(request).jobId();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Failed to save uploaded file", e);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * Ensures the collection/tag exist, stages the uploaded file under the configured upload
     * directory with a unique, traversal-safe name, and enqueues a job of the given kind. Returns
     * the new job id.
     *
     * <p>
     * {@code kind} is an untrusted request param, so it is checked against {@link #UPLOAD_KINDS} —
     * the single-file kinds this endpoint exists to serve. An allowlist rather than a denylist: the
     * connector kinds run a whole-space crawl on the stored admin token, and a future privileged
     * kind must be rejected by default instead of by remembering to name it.
     */
    public UUID uploadAndEnqueue(MultipartFile file, String collectionName, String tag, String kind,
        String jsonUniqueField, @Nullable Boolean buildSectionSummaries) {

        String normalizedKind = kind == null ? "" : kind.trim();
        if (!UPLOAD_KINDS.contains(normalizedKind)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Unsupported ingest kind. Supported kinds: " + String.join(", ", UPLOAD_KINDS));
        }

        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Uploaded file is empty.");
        }

        try {
            // 1. Ensure collection exists
            String trimmedCollection = collectionName.trim();
            if (collectionService.getCollection(trimmedCollection).isEmpty()) {
                collectionService.createCollection(trimmedCollection, "Auto-created by upload API");
            }

            // 2. Ensure tag is added to the collection as a tag
            if (tag != null && !tag.isBlank()) {
                Collection col = collectionService.getCollection(trimmedCollection).orElseThrow();
                tagService.addTagToCollection(col.id(), tag.trim());
            }

            // 3. Save uploaded file to unique target path
            Path uploadPath = Path.of(uploadDir);
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }

            // SEC-16: sanitize the client filename to a bare name before use — a UUID prefix alone
            // does NOT neutralize traversal (e.g. "../../x" survives as extra segments and can
            // climb
            // out of the upload dir). Confine the resolved target to the upload directory too.
            String uniqueFilename =
                UUID.randomUUID().toString() + "-" + safeUploadName(file.getOriginalFilename());
            Path uploadRoot = uploadPath.toAbsolutePath().normalize();
            Path targetFile = uploadRoot.resolve(uniqueFilename).normalize();
            if (!targetFile.startsWith(uploadRoot)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid uploaded file name");
            }
            Files.copy(file.getInputStream(), targetFile, StandardCopyOption.REPLACE_EXISTING);

            Map<String, Object> settings = new HashMap<>();
            if (jsonUniqueField != null && !jsonUniqueField.isBlank()) {
                settings.put("jsonUniqueField", jsonUniqueField.trim());
            }
            // Opt-in only; absent means off (DocumentIngestService treats a missing key as false).
            if (Boolean.TRUE.equals(buildSectionSummaries)) {
                settings.put(DocumentIngestService.SETTING_BUILD_SECTION_SUMMARIES, true);
            }

            // 4. Enqueue job
            return jobService.enqueue(new EnqueueRequest(normalizedKind,
                targetFile.toAbsolutePath().toString(),
                (tag != null && !tag.isBlank()) ? tag.trim() : null, trimmedCollection, settings))
                .jobId();

        } catch (IOException e) {
            // Generic client message; the cause is attached for server-side logging only.
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Failed to save uploaded file.", e);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * Reduces a client-supplied filename to a bare, separator-free name component so it cannot
     * carry path-traversal segments. Falls back to a fixed name when absent or unusable.
     */
    private static String safeUploadName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return "export.zip";
        }
        Path namePart = Path.of(rawName).getFileName();
        return namePart != null ? namePart.toString() : "export.zip";
    }
}
