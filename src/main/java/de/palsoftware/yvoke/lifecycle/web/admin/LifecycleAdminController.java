package de.palsoftware.yvoke.lifecycle.web.admin;


import de.palsoftware.yvoke.lifecycle.core.service.LifecycleService;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;


@Controller
@RequestMapping("/admin")
public class LifecycleAdminController {

    private static final Logger log = LoggerFactory.getLogger(LifecycleAdminController.class);

    private final LifecycleService lifecycleService;

    public LifecycleAdminController(LifecycleService lifecycleService) {
        this.lifecycleService = lifecycleService;
    }

    @PostMapping("/collections/delete")
    public String deleteCollectionCrud(@RequestParam String name,
        RedirectAttributes redirectAttributes) {
        log.info("LifecycleAdminController: Deleting collection '{}'", name);
        lifecycleService.deleteCollection(name);
        redirectAttributes.addFlashAttribute("success",
            "Collection '" + name + "' deleted successfully.");
        return "redirect:/admin/collections";
    }

    @PostMapping("/lifecycle/delete-collection")
    public String deleteCollection(@RequestParam String collection,
        RedirectAttributes redirectAttributes) {
        log.info("LifecycleAdminController: Deleting collection '{}'", collection);
        lifecycleService.deleteCollection(collection);
        redirectAttributes.addFlashAttribute("success",
            "Collection " + collection + " deleted successfully.");
        return "redirect:/admin/documents";
    }

    @PostMapping("/lifecycle/delete-document/{id}")
    public String deleteDocument(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
        log.info("LifecycleAdminController: Deleting document '{}'", id);
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
        lifecycleService.removeTagFromCollection(collectionId, tag);
        redirectAttributes.addFlashAttribute("success", "Tag removed successfully.");
        return "redirect:/admin/collections";
    }
}
