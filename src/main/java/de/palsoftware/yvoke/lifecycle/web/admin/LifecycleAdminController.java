package de.palsoftware.yvoke.lifecycle.web.admin;

import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.service.CollectionService;
import de.palsoftware.yvoke.document.core.model.DocumentRow;
import de.palsoftware.yvoke.document.core.repository.DocumentRepository;
import de.palsoftware.yvoke.lifecycle.core.service.LifecycleService;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin")
public class LifecycleAdminController {

    private static final Logger log = LoggerFactory.getLogger(LifecycleAdminController.class);

    private final LifecycleService lifecycleService;
    private final CollectionService collectionService;
    private final DocumentRepository documentRepository;

    public LifecycleAdminController(LifecycleService lifecycleService,
        CollectionService collectionService, DocumentRepository documentRepository) {
        this.lifecycleService = lifecycleService;
        this.collectionService = collectionService;
        this.documentRepository = documentRepository;
    }

    @PostMapping("/collections/delete")
    public String deleteCollectionCrud(@RequestParam String name,
        RedirectAttributes redirectAttributes) {
        log.info("LifecycleAdminController: Deleting collection '{}'", name);
        Collection col = collectionService.findByNameForAdmin(name)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Collection not found: " + name));
        lifecycleService.deleteCollection(col.name());
        redirectAttributes.addFlashAttribute("success",
            "Collection '" + col.name() + "' deleted successfully.");
        return "redirect:/admin/collections";
    }

    @PostMapping("/lifecycle/delete-collection")
    public String deleteCollection(@RequestParam String collection,
        RedirectAttributes redirectAttributes) {
        log.info("LifecycleAdminController: Deleting collection '{}'", collection);
        Collection col = collectionService.findByNameForAdmin(collection)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Collection not found: " + collection));
        lifecycleService.deleteCollection(col.name());
        redirectAttributes.addFlashAttribute("success",
            "Collection " + col.name() + " deleted successfully.");
        return "redirect:/admin/documents";
    }

    @PostMapping("/lifecycle/collections/{id}/delete")
    public String deleteCollectionById(@PathVariable UUID id,
        RedirectAttributes redirectAttributes) {
        log.info("LifecycleAdminController: Deleting collection by id '{}'", id);
        Collection col = collectionService.findByIdForAdmin(id).orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Collection not found: " + id));
        lifecycleService.deleteCollection(col.name());
        redirectAttributes.addFlashAttribute("success",
            "Collection " + col.name() + " deleted successfully.");
        return "redirect:/admin/collections";
    }

    @PostMapping("/lifecycle/delete-document/{id}")
    public String deleteDocument(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
        log.info("LifecycleAdminController: Deleting document '{}'", id);
        DocumentRow doc = documentRepository.findById(id).orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found: " + id));
        collectionService.findByIdForAdmin(doc.collectionId()).orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found: " + id));
        lifecycleService.deleteDocument(id);
        redirectAttributes.addFlashAttribute("success", "Document deleted successfully.");
        return "redirect:/admin/documents";
    }

    /**
     * Removes a tag from a collection. This is a destructive cross-domain operation (it may delete
     * content whose only tag was this one), so it lives in the lifecycle controller alongside the
     * other cascading deletes rather than in the collection admin controller (MNT-01).
     */
    @PostMapping("/collections/remove-tag")
    public String removeCollectionTag(@RequestParam UUID collectionId, @RequestParam String tag,
        RedirectAttributes redirectAttributes) {
        Collection col = collectionService.findByIdForAdmin(collectionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Collection not found: " + collectionId));
        lifecycleService.removeTagFromCollection(col.id(), tag);
        redirectAttributes.addFlashAttribute("success", "Tag removed successfully.");
        return "redirect:/admin/collections";
    }
}
