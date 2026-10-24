package de.palsoftware.yvoke.rag.web.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.area.core.AreaService;
import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.service.CollectionService;
import de.palsoftware.yvoke.rag.prompt.Playbook;
import de.palsoftware.yvoke.rag.prompt.PlaybookService;
import de.palsoftware.yvoke.rag.prompt.SystemPrompt;
import de.palsoftware.yvoke.rag.prompt.SystemPromptService;
import de.palsoftware.yvoke.rag.prompt.SystemPromptType;
import de.palsoftware.yvoke.rag.retrieval.HybridSearch;
import de.palsoftware.yvoke.rag.retrieval.RagAdminViewService;
import de.palsoftware.yvoke.rag.retrieval.RagAdminViews.LaneTrace;
import de.palsoftware.yvoke.rag.retrieval.RagAdminViews.RetrievalLogView;
import de.palsoftware.yvoke.rag.retrieval.RetrievalLogRepository;
import de.palsoftware.yvoke.rag.retrieval.RetrievalTelemetryRow;
import de.palsoftware.yvoke.rag.retrieval.RetrievalTelemetryService;
import de.palsoftware.yvoke.rag.retrieval.SearchOptions;
import de.palsoftware.yvoke.rag.retrieval.SearchWithId;
import jakarta.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import de.palsoftware.yvoke.rag.core.service.RagService;
import de.palsoftware.yvoke.rag.prompt.PlaybookMarkdownParser;
import de.palsoftware.yvoke.shared.security.AreaAccessContext;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.yaml.snakeyaml.Yaml;


@Controller
@RequestMapping("/admin")
public class RagAdminController {

    private static final Logger log = LoggerFactory.getLogger(RagAdminController.class);

    /**
     * How many rows of the fused ordering the console lists. The fused set runs to
     * {@code semanticLimit + fullTextLimit} candidates, far more than fits the panel; the template
     * states the total alongside, so a truncated list never reads as the whole ordering.
     */
    private static final int FUSION_TRACE_ROWS = 15;

    private final HybridSearch hybridSearch;
    private final RetrievalTelemetryService telemetryService;
    private final RetrievalLogRepository retrievalLogRepository;
    private final RagAdminViewService ragAdminViewService;
    private final CollectionService collectionService;
    private final PlaybookService playbookService;
    private final SystemPromptService systemPromptService;
    private final RagService ragService;
    private final ObjectMapper objectMapper;
    private final int logQueryMaxChars;

    /**
     * Bound into the search form's {@code max} so the console cannot offer a Top-K that
     * {@link HybridSearch} will silently clamp — a form accepting 50 against a ceiling of 20
     * returns 20 rows with nothing on screen saying why.
     */
    private final int maxLimit;

    private final AreaService areaService;

    public RagAdminController(HybridSearch hybridSearch,
        RetrievalLogRepository retrievalLogRepository, RagAdminViewService ragAdminViewService,
        CollectionService collectionService, PlaybookService playbookService,
        SystemPromptService systemPromptService, RagService ragService, ObjectMapper objectMapper,
        RetrievalTelemetryService telemetryService,
        @Value("${app.retrieval.log-query-max-chars}") int logQueryMaxChars,
        @Value("${app.retrieval.max-limit}") int maxLimit, AreaService areaService) {
        this.areaService = areaService;
        this.hybridSearch = hybridSearch;
        this.telemetryService = telemetryService;
        this.retrievalLogRepository = retrievalLogRepository;
        this.ragAdminViewService = ragAdminViewService;
        this.collectionService = collectionService;
        this.playbookService = playbookService;
        this.systemPromptService = systemPromptService;
        this.ragService = ragService;
        this.objectMapper = objectMapper;
        this.logQueryMaxChars = logQueryMaxChars;
        this.maxLimit = maxLimit;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonMap(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    @GetMapping("/search")
    public String testSearch(@RequestParam(required = false) String query,
        @RequestParam(required = false) String collection,
        @RequestParam(required = false) String tag, @RequestParam(defaultValue = "8") Integer limit,
        @RequestParam(required = false) Boolean semantic,
        @RequestParam(required = false) Boolean fulltext, Model model) {

        log.info("RagAdminController: Accessing Search view");
        List<Collection> collections =
            AreaAccessContext.isSuperAdmin() ? collectionService.listCollections()
                : collectionService.listForAdminUser();
        model.addAttribute("collections", collections);

        String activeCollection = collection;
        if (activeCollection != null && !activeCollection.isBlank()) {
            Optional<Collection> reqCol = collectionService.getCollection(activeCollection);
            if (reqCol.isPresent() && !AreaAccessContext.canAdministerArea(reqCol.get().area())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Collection not found: " + activeCollection);
            }
            String colName = activeCollection;
            if (reqCol.isEmpty() && collections.stream().noneMatch(c -> c.name().equals(colName))) {
                activeCollection = !collections.isEmpty() ? collections.get(0).name() : null;
            }
        } else if (!collections.isEmpty()) {
            activeCollection = collections.get(0).name();
        } else {
            activeCollection = null;
        }

        List<String> tags = activeCollection != null ? collectionService
            .getCollection(activeCollection).map(Collection::tags).orElse(List.of()) : List.of();

        String defaultTag = tags.contains("9.3") ? "9.3"
            : (tags.contains("9.3.1") ? "9.3.1" : (tags.isEmpty() ? null : tags.get(0)));
        String tagParam;
        if (tag == null) {
            tagParam = defaultTag;
        } else if (tag.isBlank() || "all".equalsIgnoreCase(tag)) {
            tagParam = null;
        } else {
            tagParam = tag;
        }

        Boolean activeSemantic = semantic;
        Boolean activeFulltext = fulltext;
        if (query == null) {
            if (activeSemantic == null)
                activeSemantic = true;
            if (activeFulltext == null)
                activeFulltext = true;
        } else {
            if (activeSemantic == null)
                activeSemantic = false;
            if (activeFulltext == null)
                activeFulltext = false;
        }

        model.addAttribute("tags", tags);
        model.addAttribute("query", query);
        model.addAttribute("collection", activeCollection);
        model.addAttribute("tag", tagParam);
        model.addAttribute("limit", limit);
        model.addAttribute("maxLimit", maxLimit);
        model.addAttribute("semantic", activeSemantic);
        model.addAttribute("fulltext", activeFulltext);

        if (query != null && !query.isBlank() && activeCollection != null) {
            SearchOptions opts = new SearchOptions(activeCollection, limit, activeSemantic,
                activeFulltext, tagParam, 0);
            SearchWithId search = hybridSearch.searchWithId(query, opts);
            model.addAttribute("results", ragAdminViewService.toSearchResults(search.results()));

            // Telemetry is written off the search hot path, so read it back for THIS searchId
            // rather than "the newest row for this collection" — the latter renders the previous
            // search's numbers beside the current results whenever the write has not landed yet,
            // and every field still looks plausible when it does.
            telemetryService.flush();
            Optional<RetrievalTelemetryRow> telemetry =
                retrievalLogRepository.findTelemetryById(search.searchId());

            model.addAttribute("telemetryPool",
                parseJsonMap(telemetry.map(RetrievalTelemetryRow::poolsJson).orElse(null)));
            model.addAttribute("telemetryFinal",
                parseJsonMap(telemetry.map(RetrievalTelemetryRow::finalJson).orElse(null)));
            model.addAttribute("telemetryRerank",
                parseJsonMap(telemetry.map(RetrievalTelemetryRow::rerankJson).orElse(null)));
            model.addAttribute("laneTrace",
                telemetry.map(t -> RagAdminViewService.toLaneTrace(t, FUSION_TRACE_ROWS))
                    .orElseGet(LaneTrace::empty));
        }

        return "admin/search";
    }

    @GetMapping("/logs")
    public String retrievalLogs(@RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size, Model model) {

        log.info("RagAdminController: Accessing Logs view");
        Set<String> allowedAreasLower = AreaAccessContext.allowedAdminAreasLower().orElse(null);
        List<RetrievalLogView> logs =
            ragAdminViewService.listLogs(size, page * size, allowedAreasLower);
        long totalCount = ragAdminViewService.countLogs(allowedAreasLower);
        int totalPages = (int) Math.ceil((double) totalCount / size);

        model.addAttribute("logs", logs);
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("totalCount", totalCount);
        model.addAttribute("logQueryMaxChars", logQueryMaxChars);

        return "admin/logs";
    }

    @GetMapping("/playbooks")
    public String viewPlaybooks(Model model) {
        log.info("RagAdminController: Accessing Playbooks view");
        model.addAttribute("playbooks", AreaAccessContext
            .filterByAdminArea(playbookService.listAllPlaybooks(), Playbook::area));
        model.addAttribute("areas",
            AreaAccessContext.filterByAdminArea(areaService.listAreaNames(), a -> a));
        model.addAttribute("systemPrompts", AreaAccessContext.filterByAdminArea(
            systemPromptService.listPromptsByType(SystemPromptType.CHAT), SystemPrompt::area));
        model.addAttribute("availableTools",
            ragService.getToolRegistry().keySet().stream().sorted().collect(Collectors.toList()));
        return "admin/playbooks";
    }

    @PostMapping("/playbooks")
    public String createOrUpdatePlaybook(@RequestParam String name, @RequestParam String title,
        @RequestParam(required = false) String description, @RequestParam String templateText,
        @RequestParam(required = false) List<String> tools,
        @RequestParam(required = false, defaultValue = "false") boolean codeExecution,
        @RequestParam(required = false, defaultValue = "specialist") String targetAgent,
        @RequestParam(required = false, defaultValue = "false") boolean prototype,
        @RequestParam String area, @RequestParam(required = false) String systemPrompt,
        @RequestParam(required = false, defaultValue = "1") int version,
        RedirectAttributes redirectAttributes) {

        if (!AreaAccessContext.canAdministerArea(area)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Not authorized to manage playbooks in area: " + area);
        }

        Optional<Playbook> existing = playbookService.getPlaybook(name);
        if (existing.isPresent()) {
            if (!AreaAccessContext.canAdministerArea(existing.get().area())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Existing playbook belongs to another area: " + name);
            }
            if (!existing.get().area().equalsIgnoreCase(area)) {
                redirectAttributes.addFlashAttribute("error", "Cannot change playbook area.");
                return "redirect:/admin/playbooks";
            }
        }

        String sysPrompt =
            (systemPrompt != null && !systemPrompt.isBlank()) ? systemPrompt.trim() : null;
        if (sysPrompt != null) {
            Optional<SystemPrompt> spOpt = systemPromptService.getPrompt(sysPrompt);
            if (spOpt.isEmpty() || !AreaAccessContext.canAdministerArea(spOpt.get().area())) {
                redirectAttributes.addFlashAttribute("error",
                    "Referenced system prompt not found in area: " + sysPrompt);
                return "redirect:/admin/playbooks";
            }
        }

        playbookService.savePlaybook(name, title, description, templateText, tools, codeExecution,
            targetAgent, prototype, area, sysPrompt, version);
        redirectAttributes.addFlashAttribute("success",
            "Playbook '" + title + "' saved successfully.");
        return "redirect:/admin/playbooks";
    }

    @GetMapping("/playbooks/export")
    public ResponseEntity<Resource> exportPlaybook(@RequestParam String name) {
        Optional<Playbook> pb = playbookService.getPlaybook(name);
        if (pb.isEmpty() || !AreaAccessContext.canAdministerArea(pb.get().area())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Playbook not found: " + name);
        }
        String md = playbookService.exportPlaybookToMarkdown(name);
        byte[] bytes = md.getBytes(StandardCharsets.UTF_8);
        ByteArrayResource resource = new ByteArrayResource(bytes);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(name + ".md", StandardCharsets.UTF_8)
                    .build().toString())
            .contentType(MediaType.parseMediaType("text/markdown")).contentLength(bytes.length)
            .body(resource);
    }

    @PostMapping("/playbooks/import")
    public String importPlaybook(@RequestParam("file") MultipartFile file,
        @RequestParam(required = false) String area, RedirectAttributes redirectAttributes)
        throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty.");
        }
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        String originalFilename = file.getOriginalFilename();
        String fallbackName = originalFilename != null && originalFilename.contains(".")
            ? originalFilename.substring(0, originalFilename.lastIndexOf('.'))
            : originalFilename;

        Playbook parsed = PlaybookMarkdownParser.parseMarkdown(content, fallbackName, r -> 1);
        String targetArea =
            (parsed.area() != null && !parsed.area().isBlank()) ? parsed.area() : area;
        if (!AreaAccessContext.canAdministerArea(targetArea)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Not authorized for area: " + targetArea);
        }

        Optional<Playbook> existing = playbookService.getPlaybook(parsed.name());
        if (existing.isPresent()) {
            if (!AreaAccessContext.canAdministerArea(existing.get().area())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Existing playbook belongs to another area: " + parsed.name());
            }
            if (!existing.get().area().equalsIgnoreCase(targetArea)) {
                redirectAttributes.addFlashAttribute("error", "Cannot change playbook area.");
                return "redirect:/admin/playbooks";
            }
        }

        Playbook imported = playbookService.importPlaybookFromMarkdown(content, fallbackName, area);
        redirectAttributes.addFlashAttribute("success",
            "Playbook '" + imported.title() + "' imported successfully.");
        return "redirect:/admin/playbooks";
    }

    @PostMapping("/playbooks/delete")
    public String deletePlaybook(@RequestParam String name, RedirectAttributes redirectAttributes) {
        Optional<Playbook> pb = playbookService.getPlaybook(name);
        if (pb.isEmpty() || !AreaAccessContext.canAdministerArea(pb.get().area())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Playbook not found: " + name);
        }
        playbookService.deletePlaybook(name);
        redirectAttributes.addFlashAttribute("success", "Playbook deleted successfully.");
        return "redirect:/admin/playbooks";
    }

    @GetMapping("/prompts")
    public String viewPrompts(Model model) {
        model.addAttribute("prompts", AreaAccessContext
            .filterByAdminArea(systemPromptService.listAllPrompts(), SystemPrompt::area));
        model.addAttribute("areas",
            AreaAccessContext.filterByAdminArea(areaService.listAreaNames(), a -> a));
        model.addAttribute("chatPrompts", AreaAccessContext.filterByAdminArea(
            systemPromptService.listPromptsByType(SystemPromptType.CHAT), SystemPrompt::area));
        model.addAttribute("activeChatPrompt", systemPromptService.getDefaultChatPromptName());
        return "admin/prompts";
    }

    @PostMapping("/prompts/active")
    public String updateActiveChatPrompt(@RequestParam String activeChatPrompt,
        RedirectAttributes redirectAttributes) {
        if (!AreaAccessContext.isSuperAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Only superadmin can change active chat prompt");
        }
        systemPromptService.setDefaultChatPromptName(activeChatPrompt);
        redirectAttributes.addFlashAttribute("success",
            "Active default chat system prompt updated to '" + activeChatPrompt
                + "' successfully.");
        return "redirect:/admin/prompts";
    }

    @PostMapping("/prompts")
    public String createOrUpdatePrompt(@RequestParam String name,
        @RequestParam SystemPromptType type, @RequestParam(required = false) String description,
        @RequestParam String systemPrompt, @RequestParam String area,
        RedirectAttributes redirectAttributes) {

        if (!AreaAccessContext.canAdministerArea(area)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Not authorized for area: " + area);
        }

        Optional<SystemPrompt> existing = systemPromptService.getPrompt(name);
        if (existing.isPresent()) {
            if (!AreaAccessContext.canAdministerArea(existing.get().area())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Existing prompt belongs to another area: " + name);
            }
            if (!existing.get().area().equalsIgnoreCase(area)) {
                redirectAttributes.addFlashAttribute("error", "Cannot change prompt area.");
                return "redirect:/admin/prompts";
            }
        }

        systemPromptService.savePrompt(name, type, systemPrompt, description, area);
        redirectAttributes.addFlashAttribute("success",
            "System prompt '" + name + "' saved successfully.");
        return "redirect:/admin/prompts";
    }

    @PostMapping("/prompts/delete")
    public String deletePrompt(@RequestParam String name, RedirectAttributes redirectAttributes) {
        Optional<SystemPrompt> existing = systemPromptService.getPrompt(name);
        if (existing.isEmpty() || !AreaAccessContext.canAdministerArea(existing.get().area())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "System prompt not found: " + name);
        }
        systemPromptService.deletePrompt(name);
        redirectAttributes.addFlashAttribute("success", "System prompt deleted successfully.");
        return "redirect:/admin/prompts";
    }

    @GetMapping("/prompts/export")
    public ResponseEntity<Resource> exportPrompt(@RequestParam String name) {
        Optional<SystemPrompt> existing = systemPromptService.getPrompt(name);
        if (existing.isEmpty() || !AreaAccessContext.canAdministerArea(existing.get().area())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "System prompt not found: " + name);
        }
        String md = systemPromptService.exportPromptToMarkdown(name);
        byte[] bytes = md.getBytes(StandardCharsets.UTF_8);
        ByteArrayResource resource = new ByteArrayResource(bytes);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(name + ".md", StandardCharsets.UTF_8)
                    .build().toString())
            .contentType(MediaType.parseMediaType("text/markdown")).contentLength(bytes.length)
            .body(resource);
    }

    @PostMapping("/prompts/import")
    public String importPrompt(@RequestParam("file") MultipartFile file,
        @RequestParam(required = false) String area, RedirectAttributes redirectAttributes)
        throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty.");
        }
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        String originalFilename = file.getOriginalFilename();
        String fallbackName = originalFilename != null && originalFilename.contains(".")
            ? originalFilename.substring(0, originalFilename.lastIndexOf('.'))
            : originalFilename;

        String frontmatterArea = null;
        String promptName = fallbackName;
        if (content.startsWith("---")) {
            int endIdx = content.indexOf("---", 3);
            if (endIdx > 0) {
                String yaml = content.substring(3, endIdx).trim();
                try {
                    Object loaded = new Yaml().load(yaml);
                    if (loaded instanceof Map<?, ?> map) {
                        if (map.get("name") != null) {
                            promptName = String.valueOf(map.get("name")).trim();
                        }
                        if (map.get("area") != null) {
                            frontmatterArea = String.valueOf(map.get("area")).trim();
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
        String targetArea =
            (frontmatterArea != null && !frontmatterArea.isBlank()) ? frontmatterArea : area;
        if (!AreaAccessContext.canAdministerArea(targetArea)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Not authorized for area: " + targetArea);
        }

        Optional<SystemPrompt> existing = systemPromptService.getPrompt(promptName);
        if (existing.isPresent()) {
            if (!AreaAccessContext.canAdministerArea(existing.get().area())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Existing prompt belongs to another area: " + promptName);
            }
            if (!existing.get().area().equalsIgnoreCase(targetArea)) {
                redirectAttributes.addFlashAttribute("error", "Cannot change prompt area.");
                return "redirect:/admin/prompts";
            }
        }

        SystemPrompt imported =
            systemPromptService.importPromptFromMarkdown(content, fallbackName, area);
        redirectAttributes.addFlashAttribute("success",
            "System prompt '" + imported.name() + "' imported successfully.");
        return "redirect:/admin/prompts";
    }

    @GetMapping("/prompts/kg-options")
    @ResponseBody
    public String getKgPromptOptions() {
        List<SystemPrompt> prompts = AreaAccessContext.filterByAdminArea(
            systemPromptService.listPromptsByType(SystemPromptType.KG), SystemPrompt::area);
        StringBuilder sb = new StringBuilder();
        for (SystemPrompt p : prompts) {
            sb.append("<option value=\"").append(p.name()).append("\" title=\"")
                .append(p.description() != null ? p.description().replace("\"", "&quot;") : "")
                .append("\">").append(p.name()).append("</option>");
        }
        return sb.toString();
    }
}
