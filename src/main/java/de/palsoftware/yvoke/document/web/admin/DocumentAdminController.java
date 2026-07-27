package de.palsoftware.yvoke.document.web.admin;

import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.service.CollectionService;
import de.palsoftware.yvoke.document.core.model.DocumentAdminViews.ChunkDetailPage;
import de.palsoftware.yvoke.document.core.model.DocumentAdminViews.DocumentDetailPage;
import de.palsoftware.yvoke.document.core.model.DocumentRow;
import de.palsoftware.yvoke.document.core.repository.DocumentRepository;
import de.palsoftware.yvoke.document.core.service.DocumentAdminViewService;
import de.palsoftware.yvoke.shared.audit.repository.AuditLogRepository;
import de.palsoftware.yvoke.shared.jobengine.model.EnqueueRequest;
import de.palsoftware.yvoke.shared.jobengine.model.EnqueueResult;
import de.palsoftware.yvoke.shared.jobengine.service.JobService;
import de.palsoftware.yvoke.shared.user.model.User;
import de.palsoftware.yvoke.shared.user.service.UserService;
import de.palsoftware.yvoke.tag.core.service.TagService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;


@Controller
@RequestMapping("/admin")
public class DocumentAdminController {

    private static final Logger log = LoggerFactory.getLogger(DocumentAdminController.class);

    private final DocumentRepository documentRepository;
    private final DocumentAdminViewService documentAdminViewService;
    private final CollectionService collectionService;
    private final TagService tagService;
    private final JobService jobService;
    private final AuditLogRepository auditLogRepository;
    private final UserService userService;

    public DocumentAdminController(DocumentRepository documentRepository,
        DocumentAdminViewService documentAdminViewService, CollectionService collectionService,
        TagService tagService, JobService jobService, AuditLogRepository auditLogRepository,
        UserService userService) {
        this.documentRepository = documentRepository;
        this.documentAdminViewService = documentAdminViewService;
        this.collectionService = collectionService;
        this.tagService = tagService;
        this.jobService = jobService;
        this.auditLogRepository = auditLogRepository;
        this.userService = userService;
    }

    private String getCurrentAdminOid() {
        return userService.getCurrentUser().map(User::entraOid).orElse("anonymous_admin");
    }

    @GetMapping("/documents")
    public String listDocuments(@RequestParam(required = false) String collection,
        @RequestParam(required = false) String kind, @RequestParam(required = false) String tag,
        @RequestParam(required = false) String searchId,
        @RequestParam(required = false) String searchTitle,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
        Model model) {
        log.info("DocumentAdminController: Accessing Documents view");

        String collFilter = (collection != null && !collection.isBlank()) ? collection : null;
        String kindFilter = (kind != null && !kind.isBlank()) ? kind : null;
        String tagFilter = (tag != null && !tag.isBlank()) ? tag : null;
        String searchIdFilter = (searchId != null && !searchId.isBlank()) ? searchId.trim() : null;
        String searchTitleFilter =
            (searchTitle != null && !searchTitle.isBlank()) ? searchTitle.trim() : null;

        long totalCount = documentAdminViewService.countDocuments(collFilter, kindFilter, tagFilter,
            searchIdFilter, searchTitleFilter);
        int totalPages = (int) Math.ceil((double) totalCount / size);

        List<Collection> allCols = collectionService.listCollections();

        model.addAttribute("documents", documentAdminViewService.listDocuments(collFilter, size,
            page * size, kindFilter, tagFilter, searchIdFilter, searchTitleFilter));
        model.addAttribute("collections", allCols.stream().map(Collection::name).toList());
        model.addAttribute("allCollections", allCols);
        model.addAttribute("kinds", documentAdminViewService.distinctKinds());
        model.addAttribute("allTags", tagOptions(collFilter, tagFilter));

        model.addAttribute("selectedCollection", collFilter);
        model.addAttribute("selectedKind", kindFilter);
        model.addAttribute("selectedTag", tagFilter);
        model.addAttribute("selectedSearchId", searchIdFilter);
        model.addAttribute("selectedSearchTitle", searchTitleFilter);
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("totalCount", totalCount);

        return "admin/documents";
    }

    /**
     * Options for the corpus browser's tag filter: the tags the browsed collection declares, or the
     * whole declared vocabulary when browsing all collections.
     *
     * <p>
     * Derived from {@code collections.tags}. Until V6 this list came from a {@code tags} registry
     * table written only by {@code TagRepository.getOrCreateTag}, i.e. only for tags typed into an
     * admin form or passed to the ingest enqueue — so tags that arrived with an imported collection
     * (the import writes {@code collections.tags} directly) were absent and their documents could
     * not be filtered at all.
     *
     * <p>
     * {@code selectedTag} is appended when the scope does not already contain it, because the
     * template marks the active option with {@code th:selected="${t == selectedTag}"}: without it,
     * switching collection while a tag filter is applied would render "All Tags" over a query that
     * is still filtered — rows vanish with no visible cause.
     */
    private List<String> tagOptions(String collFilter, String selectedTag) {
        List<String> declared = collFilter == null ? collectionService.listAllTags()
            : collectionService.listTagsOf(collFilter);
        if (selectedTag == null || declared.contains(selectedTag)) {
            return declared;
        }
        List<String> withSelected = new ArrayList<>(declared);
        withSelected.add(selectedTag);
        return withSelected;
    }

    @GetMapping("/documents/{id}")
    public String getDocumentDetails(@PathVariable UUID id, Model model) {
        DocumentDetailPage page = documentAdminViewService.documentDetailPage(id).orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found: " + id));

        model.addAttribute("document", page.document());
        model.addAttribute("chunks", page.chunks());
        model.addAttribute("chunkCount", page.chunkCount());
        model.addAttribute("sectionSummaries", page.sectionSummaries());
        model.addAttribute("metadataJson", page.document().metadataJson());
        // The add-tag datalist offers what this document's own collection declares — the same set
        // CollectionTagEnqueueValidator admits an ingest against.
        model.addAttribute("allTags", collectionService.listTagsOf(page.document().collection()));

        return "admin/document-detail";
    }

    @PostMapping("/documents/{id}/process-kg")
    public String processDocumentKg(@PathVariable UUID id,
        @RequestParam(value = "collection", required = false) String collection,
        @RequestParam(value = "tag", required = false) String tag,
        @RequestParam(value = "kgPrompt", required = false) String kgPrompt,
        RedirectAttributes redirectAttributes) {

        DocumentRow document = documentRepository.findById(id).orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found: " + id));

        String targetCollection = (collection != null && !collection.isBlank()) ? collection.trim()
            : document.collection();
        String targetTag = (tag != null && !tag.isBlank()) ? tag.trim() : null;

        Map<String, Object> settings = new HashMap<>();
        if (kgPrompt != null && !kgPrompt.isBlank()) {
            settings.put("kgPrompt", kgPrompt.trim());
        }

        EnqueueResult result = jobService.enqueue(new EnqueueRequest("kg-extract",
            document.id().toString(), targetTag, targetCollection, settings));
        UUID jobId = result.jobId();

        auditLogRepository.log(getCurrentAdminOid(), "PROCESS_KG", document.id().toString(),
            Map.of("collection", document.collection(), "tag", targetTag != null ? targetTag : "",
                "jobId", jobId.toString()));

        if (result.created()) {
            redirectAttributes.addFlashAttribute("success",
                "Knowledge Graph processing job enqueued! Job ID: " + jobId);
        } else {
            // A WARNING, not a success: the admission key is (kind, source_ref, collection, tags)
            // and excludes settings, so this submission adopted the job already in flight and the
            // prompt just chosen was NOT applied — the adopted job may even have been enqueued via
            // the REST API with no settings at all.
            redirectAttributes.addFlashAttribute("warning",
                "Knowledge Graph processing for this document is already queued or running — "
                    + "showing that job. It runs with the settings it was enqueued with, so the "
                    + "prompt selected here was not applied; cancel that job and submit again to "
                    + "change them.");
        }
        return "redirect:/admin/jobs/" + jobId;
    }

    @GetMapping("/chunks/{id}")
    public String getChunkDetails(@PathVariable UUID id, Model model) {
        ChunkDetailPage page = documentAdminViewService.chunkDetailPage(id.toString()).orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chunk not found: " + id));

        model.addAttribute("chunk", page.chunk());
        model.addAttribute("hasEmbedding", page.hasEmbedding());
        model.addAttribute("metadataJson", page.chunk().metadataJson());
        model.addAttribute("surfacedMessages", page.surfacedMessages());

        return "admin/chunk-detail";
    }

    @PostMapping("/documents/{id}/add-tag")
    public String addDocumentTag(@PathVariable UUID id, @RequestParam String tag,
        RedirectAttributes redirectAttributes) {
        tagService.addTagToDocument(id, tag);
        redirectAttributes.addFlashAttribute("success", "Tag added successfully.");
        return "redirect:/admin/documents/" + id;
    }

    @PostMapping("/documents/{id}/remove-tag")
    public String removeDocumentTag(@PathVariable UUID id, @RequestParam String tag,
        RedirectAttributes redirectAttributes) {
        tagService.removeTagFromDocument(id, tag);
        redirectAttributes.addFlashAttribute("success", "Tag removed successfully.");
        return "redirect:/admin/documents/" + id;
    }
}
