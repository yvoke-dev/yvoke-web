package de.palsoftware.yvoke.ingest.web.admin;

import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.service.CollectionService;
import de.palsoftware.yvoke.ingest.core.service.DocumentIngestService;
import de.palsoftware.yvoke.rag.prompt.SystemPromptService;
import de.palsoftware.yvoke.rag.prompt.SystemPromptType;
import de.palsoftware.yvoke.shared.audit.repository.AuditLogRepository;
import de.palsoftware.yvoke.shared.jobengine.model.EnqueueRequest;
import de.palsoftware.yvoke.shared.jobengine.model.EnqueueResult;
import de.palsoftware.yvoke.shared.jobengine.service.JobService;
import de.palsoftware.yvoke.shared.user.model.User;
import de.palsoftware.yvoke.shared.user.service.UserService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;


@Controller
@RequestMapping("/admin")
public class IngestAdminController {

    private static final Logger log = LoggerFactory.getLogger(IngestAdminController.class);

    private final CollectionService collectionService;
    private final SystemPromptService systemPromptService;
    private final JobService jobService;
    private final AuditLogRepository auditLogRepository;
    private final UserService userService;
    private final String uploadDir;

    public IngestAdminController(CollectionService collectionService,
        SystemPromptService systemPromptService, JobService jobService,
        AuditLogRepository auditLogRepository, UserService userService,
        @Value("${app.upload-dir}") String uploadDir) {
        this.collectionService = collectionService;
        this.systemPromptService = systemPromptService;
        this.jobService = jobService;
        this.auditLogRepository = auditLogRepository;
        this.userService = userService;
        this.uploadDir = uploadDir;
    }

    private String getCurrentAdminOid() {
        return userService.getCurrentUser().map(User::entraOid).orElse("anonymous_admin");
    }

    @GetMapping("/ingest")
    public String ingestView(Model model, @RequestParam(required = false) String collection) {
        log.info("IngestAdminController: Accessing Ingest view");
        List<Collection> collections = collectionService.listCollections();
        model.addAttribute("collections", collections);

        String targetCollection = collection;
        if (targetCollection == null && !collections.isEmpty()) {
            targetCollection = collections.get(0).name();
        }

        List<String> tags = (targetCollection != null) ? collectionService
            .getCollection(targetCollection).map(Collection::tags).orElse(List.of()) : List.of();
        model.addAttribute("tags", tags);
        model.addAttribute("versions", tags);

        model.addAttribute("kgPrompts", systemPromptService.listPromptsByType(SystemPromptType.KG));
        model.addAttribute("summarizePrompts",
            systemPromptService.listPromptsByType(SystemPromptType.SUMMARIZE));
        return "admin/ingest";
    }

    @PostMapping("/ingest/upload")
    public String uploadIngest(@RequestParam("file") MultipartFile file,
        @RequestParam("collection") String collection,
        @RequestParam(value = "tag", required = false) String tag,
        @RequestParam("kind") String kind,
        @RequestParam(value = "kgPromptName", required = false) String kgPromptName,
        @RequestParam(value = "summarizePromptName", required = false) String summarizePromptName,
        @RequestParam(value = "documentGlob", required = false) String documentGlob,
        @RequestParam(value = "entitiesFile", required = false) String entitiesFile,
        @RequestParam(value = "relationshipsFile", required = false) String relationshipsFile,
        @RequestParam(value = "enableGraph", required = false) Boolean enableGraph,
        @RequestParam(value = "jsonUniqueField", required = false) String jsonUniqueField,
        @RequestParam(value = "buildSectionSummaries",
            required = false) Boolean buildSectionSummaries,
        RedirectAttributes redirectAttributes) throws IOException {

        if (file.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Please select a file to upload.");
            return "redirect:/admin/ingest";
        }

        Path uploadPath = Path.of(uploadDir);
        if (!Files.exists(uploadPath)) {
            Files.createDirectories(uploadPath);
        }

        String originalFilename = file.getOriginalFilename();
        String uniqueFilename = UUID.randomUUID().toString() + "-" + originalFilename;
        Path targetFile = uploadPath.resolve(uniqueFilename);
        Files.copy(file.getInputStream(), targetFile, StandardCopyOption.REPLACE_EXISTING);

        String jobKind = kind;
        String finalTag = (tag != null && !tag.isBlank()) ? tag.trim() : null;

        Map<String, Object> settings = new HashMap<>();
        if (kgPromptName != null && !kgPromptName.isBlank()) {
            settings.put("kgPrompt", kgPromptName.trim());
        }
        if (summarizePromptName != null && !summarizePromptName.isBlank()) {
            settings.put("summarizePrompt", summarizePromptName.trim());
        }
        if (documentGlob != null && !documentGlob.isBlank()) {
            settings.put("documentGlob", documentGlob.trim());
        }
        if (entitiesFile != null && !entitiesFile.isBlank()) {
            settings.put("entitiesFile", entitiesFile.trim());
        }
        if (relationshipsFile != null && !relationshipsFile.isBlank()) {
            settings.put("relationshipsFile", relationshipsFile.trim());
        }
        // The form has always posted this (ingest.html) and JsonImportJobHandler has always read
        // it, but the controller never bound it — so an admin-initiated json-import silently
        // ignored the operator's unique field and appended duplicates instead of upserting.
        if (jsonUniqueField != null && !jsonUniqueField.isBlank()) {
            settings.put("jsonUniqueField", jsonUniqueField.trim());
        }
        if (enableGraph != null) {
            settings.put("enableGraph", enableGraph);
        } else {
            settings.put("enableGraph", false);
        }
        // Opt-in only: summarising costs an LLM call per uncached section, so an unchecked box
        // (which posts nothing at all) must leave the key absent rather than write an explicit
        // false — DocumentIngestService treats absent as off.
        if (Boolean.TRUE.equals(buildSectionSummaries)) {
            settings.put(DocumentIngestService.SETTING_BUILD_SECTION_SUMMARIES, true);
        }

        EnqueueResult result = jobService.enqueue(new EnqueueRequest(jobKind,
            targetFile.toAbsolutePath().toString(), finalTag, collection, settings));
        UUID jobId = result.jobId();

        auditLogRepository.log(getCurrentAdminOid(), "INGEST_DATA", jobId.toString(),
            Map.of("kind", jobKind, "collection", collection, "tag",
                finalTag != null ? finalTag : "", "filename",
                originalFilename != null ? originalFilename : ""));

        if (result.created()) {
            redirectAttributes.addFlashAttribute("success",
                "Job enqueued successfully! Job ID: " + jobId);
        } else {
            // Same warning channel as the KG path: an adopted job keeps the settings (prompts,
            // graph flag) it was enqueued with, so the ones chosen on this form were not applied.
            // Practically unreachable here — the staged file name carries a fresh UUID, so this
            // upload's source_ref is unique — but the message must not claim success if it happens.
            redirectAttributes.addFlashAttribute("warning", "An ingest of this file into '"
                + collection + "' is already queued or running — showing that job. It runs with the"
                + " settings it was enqueued with, so the options selected here were not applied;"
                + " cancel that job and submit again to change them.");
        }
        return "redirect:/admin/jobs/" + jobId;
    }
}
