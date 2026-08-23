package de.palsoftware.yvoke.ingest.web.admin;

import de.palsoftware.yvoke.collection.core.service.CollectionService;
import de.palsoftware.yvoke.ingest.core.confluence.ConfluenceInstanceService;
import de.palsoftware.yvoke.rag.prompt.SystemPromptService;
import de.palsoftware.yvoke.rag.prompt.SystemPromptType;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/admin")
public class AdminConnectorController {

    private final CollectionService collectionService;
    private final ConfluenceInstanceService instanceService;
    private final SystemPromptService systemPromptService;

    public AdminConnectorController(CollectionService collectionService,
        ConfluenceInstanceService instanceService, SystemPromptService systemPromptService) {
        this.collectionService = collectionService;
        this.instanceService = instanceService;
        this.systemPromptService = systemPromptService;
    }

    /**
     * Renders the connected Confluence instances plus the create/edit form.
     *
     * <p>
     * Only {@link de.palsoftware.yvoke.ingest.core.confluence.ConfluenceInstanceView} reaches the
     * model: the domain record carries the token ciphertext and its key fingerprint, and a template
     * expression is all it takes to render either into the page.
     *
     * <p>
     * The tag dropdown starts EMPTY on purpose. The form opens in "create" mode with no collection
     * chosen, and the options are fetched by htmx from {@code /admin/collections/tag-options}
     * whenever the collection select changes — including when the Edit button selects one. Seeding
     * it server-side from an arbitrary collection is exactly how the saved tag used to end up
     * missing from its own dropdown.
     */
    @GetMapping("/connectors")
    public String getConnectors(Model model) {
        model.addAttribute("instances", instanceService.listInstances());
        model.addAttribute("collections", collectionService.listCollections());
        model.addAttribute("tags", List.of());
        model.addAttribute("versions", List.of());
        // A Confluence crawl has no per-run form, so the summarize prompt is per-instance config.
        // Offered as a list rather than free text: the enqueue validator rejects a name that does
        // not resolve, and typing one by hand is the only way to hit that.
        model.addAttribute("summarizePrompts",
            systemPromptService.listPromptsByType(SystemPromptType.SUMMARIZE));
        return "admin/connectors";
    }
}
