package de.palsoftware.yvoke.collection.web.admin;

import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.service.CollectionService;
import de.palsoftware.yvoke.tag.core.service.TagService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;


@Controller
@RequestMapping("/admin")
public class CollectionAdminController {

    private static final Logger log = LoggerFactory.getLogger(CollectionAdminController.class);

    private final CollectionService collectionService;
    private final TagService tagService;

    public CollectionAdminController(CollectionService collectionService, TagService tagService) {
        this.collectionService = collectionService;
        this.tagService = tagService;
    }

    @GetMapping("/collections")
    public String viewCollections(Model model) {
        log.info("CollectionAdminController: Accessing Collections view");
        model.addAttribute("collections", collectionService.listCollections());
        model.addAttribute("allTags", collectionService.listAllTags());
        return "admin/collections";
    }

    @PostMapping("/collections")
    public String createCollection(@RequestParam String name,
        @RequestParam(required = false) String description, RedirectAttributes redirectAttributes) {
        collectionService.createCollection(name, description);
        redirectAttributes.addFlashAttribute("success",
            "Collection '" + name + "' created successfully.");
        return "redirect:/admin/collections";
    }

    /**
     * The tag {@code <select>} options for one collection, rendered as the calling page's
     * {@code tag-container} fragment.
     *
     * <p>
     * On a GET, htmx sends the TRIGGERING element's own name and value and nothing else, and the
     * three pages that use this endpoint cannot agree on one name: {@code /admin/ingest} and
     * {@code /admin/search} own a select named {@code collection}, while the connectors form must
     * name its select {@code targetCollection} so it binds to {@code ConfluenceInstanceForm}. Both
     * names are accepted rather than making the connectors page compute the parameter in JavaScript
     * — reading only {@code collection} left that page's tag container permanently empty, which
     * made every sync of a tagged collection fail on a blank tag and made editing an instance wipe
     * its stored tag.
     */
    @GetMapping({"/collections/{name}/version-options", "/collections/version-options",
        "/collections/{name}/tag-options", "/collections/tag-options"})
    public String getVersionOptions(@PathVariable(required = false) String name,
        @RequestParam(value = "collection", required = false) String collectionName,
        @RequestParam(value = "targetCollection", required = false) String targetCollectionName,
        @RequestHeader(value = "HX-Current-URL", required = false) String currentUrl, Model model) {
        String targetName =
            name != null ? name : (collectionName != null ? collectionName : targetCollectionName);
        Optional<Collection> colOpt =
            targetName != null ? collectionService.getCollection(targetName) : Optional.empty();
        List<String> versions = colOpt.map(Collection::tags).orElse(List.of());
        model.addAttribute("versions", versions);
        model.addAttribute("tags", versions);

        if (currentUrl != null && currentUrl.contains("/connectors")) {
            return "admin/connectors :: tag-container";
        }
        if (currentUrl != null && currentUrl.contains("/search")) {
            return "admin/search :: tag-container";
        }
        return "admin/ingest :: tag-container";
    }

    @PostMapping("/collections/add-tag")
    public String addCollectionTag(@RequestParam UUID collectionId, @RequestParam String tag,
        RedirectAttributes redirectAttributes) {
        tagService.addTagToCollection(collectionId, tag);
        redirectAttributes.addFlashAttribute("success", "Tag added successfully.");
        return "redirect:/admin/collections";
    }

}
