package de.palsoftware.yvoke.kg.web.admin;

import de.palsoftware.yvoke.collection.core.service.CollectionService;
import de.palsoftware.yvoke.kg.core.model.KgAdminViews.KgEntityView;
import de.palsoftware.yvoke.kg.core.model.KgAdminViews.KgNeighborhoodView;
import de.palsoftware.yvoke.kg.core.service.KgAdminViewService;
import de.palsoftware.yvoke.kg.core.service.KgConsolidator;
import de.palsoftware.yvoke.kg.core.repository.KgGraphReadRepository;
import de.palsoftware.yvoke.kg.core.repository.KgWriteRepository;
import de.palsoftware.yvoke.shared.audit.repository.AuditLogRepository;
import de.palsoftware.yvoke.shared.user.model.User;
import de.palsoftware.yvoke.shared.user.service.UserService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;


@Controller
@RequestMapping("/admin")
public class KgAdminController {

    private static final Logger log = LoggerFactory.getLogger(KgAdminController.class);

    private final KgAdminViewService kgAdminViewService;
    private final KgGraphReadRepository kgReadRepository;
    private final KgWriteRepository kgWriteRepository;
    private final KgConsolidator kgConsolidator;
    private final AuditLogRepository auditLogRepository;
    private final UserService userService;
    private final CollectionService collectionService;

    public KgAdminController(KgAdminViewService kgAdminViewService,
        KgGraphReadRepository kgReadRepository, KgWriteRepository kgWriteRepository,
        KgConsolidator kgConsolidator, AuditLogRepository auditLogRepository,
        UserService userService, CollectionService collectionService) {
        this.kgAdminViewService = kgAdminViewService;
        this.kgReadRepository = kgReadRepository;
        this.kgWriteRepository = kgWriteRepository;
        this.kgConsolidator = kgConsolidator;
        this.auditLogRepository = auditLogRepository;
        this.userService = userService;
        this.collectionService = collectionService;
    }

    private String getCurrentAdminOid() {
        return userService.getCurrentUser().map(User::entraOid).orElse("anonymous_admin");
    }

    @GetMapping("/kg")
    public String kgOverview(Model model) {
        log.info("KgAdminController: Accessing KG Overview view");
        model.addAttribute("scopes", kgAdminViewService.listScopes());
        return "admin/kg";
    }

    @GetMapping("/kg/view")
    public String viewKg(@RequestParam String collection,
        @RequestParam(required = false) String tag, @RequestParam(required = false) String query,
        @RequestParam(required = false) String kind,
        @RequestParam(required = false) String selectedEntity,
        @RequestParam(defaultValue = "2") int hops, @RequestParam(defaultValue = "0") int page,
        Model model) {

        log.info("KgAdminController: Accessing KG Graph view for collection '{}'", collection);

        String queryParam = (query != null && !query.isBlank()) ? query : null;
        String tagParam = (tag != null && !tag.isBlank()) ? tag : null;
        String kindParam = (kind != null && !kind.isBlank()) ? kind : null;

        model.addAttribute("collection", collection);
        model.addAttribute("tag", tagParam);
        model.addAttribute("query", queryParam);
        model.addAttribute("kind", kindParam);
        model.addAttribute("hops", hops);
        model.addAttribute("kinds", kgAdminViewService.listKinds(collection, tagParam));

        if (queryParam != null) {
            List<KgEntityView> searchResults =
                kgAdminViewService.searchEntities(queryParam, 50, tagParam, collection, kindParam);
            model.addAttribute("searchResults", searchResults);
            model.addAttribute("browseMode", false);
            model.addAttribute("currentPage", 0);
            model.addAttribute("totalPages", 1);
            model.addAttribute("totalCount", searchResults.size());
        } else {
            // Default to browsing all entities in the scope (optionally filtered by kind).
            int size = 50;
            int safePage = Math.max(page, 0);
            List<KgEntityView> searchResults = kgAdminViewService.listEntities(collection, tagParam,
                kindParam, size, safePage * size);
            long totalCount = kgAdminViewService.countEntities(collection, tagParam, kindParam);
            int totalPages = (int) Math.ceil((double) totalCount / size);

            model.addAttribute("searchResults", searchResults);
            model.addAttribute("browseMode", true);
            model.addAttribute("currentPage", safePage);
            model.addAttribute("totalPages", totalPages);
            model.addAttribute("totalCount", totalCount);
        }

        if (selectedEntity != null && !selectedEntity.isBlank()) {
            // Generic semantic relationships incident to the entity (works for any collection,
            // including the manuals graph whose edges use free-form predicates).
            model.addAttribute("relationships",
                kgAdminViewService.entityRelationships(selectedEntity, tagParam, collection));

            Optional<KgNeighborhoodView> neighborhood =
                kgAdminViewService.neighborhood(selectedEntity, tagParam, collection);
            if (neighborhood.isPresent()) {
                KgNeighborhoodView nb = neighborhood.get();
                model.addAttribute("neighborhood", nb);
                model.addAttribute("activeEntity", nb.entity());
                model.addAttribute("selectedEntityName", selectedEntity);
                model.addAttribute("metadataJson",
                    nb.entity() != null ? nb.entity().metadataJson() : "{}");

                if ("OIM-DB".equals(collection)) {
                    model.addAttribute("fkWalk",
                        kgAdminViewService.fkWalk(selectedEntity, hops, tagParam, collection));
                    model.addAttribute("callers", kgAdminViewService.calls(selectedEntity,
                        "callers", 50, tagParam, collection));
                    model.addAttribute("callees", kgAdminViewService.calls(selectedEntity,
                        "callees", 50, tagParam, collection));
                }
            } else {
                // Fallback direct entity search if not a table
                Optional<KgEntityView> exact =
                    kgAdminViewService.firstFuzzyMatch(selectedEntity, tagParam, collection);
                if (exact.isPresent()) {
                    KgEntityView entity = exact.get();
                    model.addAttribute("activeEntity", entity);
                    model.addAttribute("selectedEntityName", selectedEntity);
                    model.addAttribute("metadataJson", entity.metadataJson());
                }
            }
        }

        return "admin/kg-view";
    }

    @PostMapping("/kg/clear")
    public String clearGraph(@RequestParam String collection,
        @RequestParam(required = false) String tag, RedirectAttributes redirectAttributes) {

        String tagParam = (tag != null && !tag.isBlank()) ? tag : null;
        if (tagParam == null) {
            redirectAttributes.addFlashAttribute("error",
                "A specific tag is required to clear the knowledge graph.");
            return "redirect:/admin/kg";
        }

        int entityCount = kgReadRepository.getEntityCount(collection, tagParam);
        int relationshipCount = kgReadRepository.getRelationshipCount(collection, tagParam);
        kgWriteRepository.deleteTagGraph(collection, tagParam);

        redirectAttributes.addFlashAttribute("success",
            "Cleared knowledge graph for " + collection + " / " + tagParam + " — deleted "
                + entityCount + " entities and " + relationshipCount + " relationships.");
        return "redirect:/admin/kg";
    }

    /**
     * The collection is resolved to its STORED spelling before consolidating.
     * {@code KgConsolidator} matches {@code WHERE c.name = :collection} — case-sensitive, untrimmed
     * — at four SQL sites, so forwarding the raw request parameter matched zero rows and still
     * flashed "Consolidation done … Groups: 0", a success message for work that never ran (the
     * other caller, {@code DocumentIngestService}, is safe because the enqueue validator has
     * already canonicalised the name). An unknown collection is now an error rather than a silent
     * no-op: "Groups: 0" is a legitimate result for a graph with nothing to merge, so the operator
     * could not otherwise tell the two apart.
     */
    @PostMapping("/kg/consolidate")
    public String consolidateKg(@RequestParam String collection, @RequestParam String tag,
        RedirectAttributes redirectAttributes) {

        String tagParam = (tag != null && !tag.isBlank()) ? tag.trim() : null;
        if (tagParam == null) {
            redirectAttributes.addFlashAttribute("error",
                "A specific tag is required to consolidate the knowledge graph.");
            return "redirect:/admin/kg";
        }

        Optional<String> resolved = collectionService.getCollection(collection).map(c -> c.name());
        if (resolved.isEmpty()) {
            redirectAttributes.addFlashAttribute("error",
                "Unknown collection: '" + collection + "'. Nothing was consolidated.");
            return "redirect:/admin/kg";
        }
        String collectionName = resolved.get();

        auditLogRepository.log(getCurrentAdminOid(), "CONSOLIDATE_KG",
            collectionName + " " + tagParam, Map.of("collection", collectionName, "tag", tagParam));

        var stats = kgConsolidator.consolidate(collectionName, tagParam);

        redirectAttributes.addFlashAttribute("success", String.format(
            "Consolidation done for %s / %s! Groups: %d, Entities Deleted: %d, Relationships Repointed: %d, Collapsed: %d",
            collectionName, tagParam, stats.groupsProcessed(), stats.rowsDeleted(),
            stats.relationshipsRepointCount(), stats.relationshipsCollapsedCount()));
        return "redirect:/admin/kg";
    }
}
