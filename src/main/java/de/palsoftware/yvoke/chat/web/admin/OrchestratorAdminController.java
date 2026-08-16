package de.palsoftware.yvoke.chat.web.admin;

import de.palsoftware.yvoke.chat.core.service.ChatConversationService;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProfile;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProfileService;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProperties;
import de.palsoftware.yvoke.rag.prompt.Playbook;
import de.palsoftware.yvoke.rag.prompt.PlaybookService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/orchestrators")
public class OrchestratorAdminController {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorAdminController.class);

    private final OrchestratorProfileService profileService;
    private final PlaybookService playbookService;
    private final ChatConversationService chatConversationService;
    private final OrchestratorProperties properties;

    public OrchestratorAdminController(OrchestratorProfileService profileService,
        PlaybookService playbookService, ChatConversationService chatConversationService,
        OrchestratorProperties properties) {
        this.profileService = profileService;
        this.playbookService = playbookService;
        this.chatConversationService = chatConversationService;
        this.properties = properties;
    }

    @GetMapping
    public String viewOrchestrators(Model model) {
        log.info("Accessing Orchestrators Admin view");
        List<Playbook> allPlaybooks = playbookService.listAllPlaybooks();

        List<Playbook> orchestratorPlaybooks = allPlaybooks.stream()
            .filter(p -> "orchestrator".equalsIgnoreCase(p.targetAgent())).toList();

        List<Playbook> reviewerPlaybooks = allPlaybooks.stream()
            .filter(p -> "reviewer".equalsIgnoreCase(p.targetAgent())).toList();

        List<Playbook> specialistPlaybooks = playbookService.listSpecializedPlaybooks();

        model.addAttribute("profiles", profileService.listAllProfiles());
        model.addAttribute("playbooks", allPlaybooks);
        model.addAttribute("orchestratorPlaybooks",
            orchestratorPlaybooks.isEmpty() ? allPlaybooks : orchestratorPlaybooks);
        model.addAttribute("reviewerPlaybooks",
            reviewerPlaybooks.isEmpty() ? allPlaybooks : reviewerPlaybooks);
        model.addAttribute("specialistPlaybooks", specialistPlaybooks);
        model.addAttribute("allowedModels", chatConversationService.getAllowedModels());
        // The form and both of its JS prefill paths used to carry these numbers as literals, which
        // is how three of them stayed at 2 after the limit was raised to 3. They render from here
        // now, so the value exists once — in application.yml.
        model.addAttribute("defaultMaxReviewRounds", properties.resolvedMaxReviewRounds());
        model.addAttribute("defaultMaxSpecialistCalls", properties.resolvedMaxSpecialistCalls());
        model.addAttribute("activeTab", "orchestrators");
        return "admin/orchestrators";
    }

    /**
     * {@code maxReviewRounds} and {@code maxSpecialistCalls} are deliberately boxed and
     * {@code required = false} rather than carrying a {@code defaultValue}: a literal here is a
     * second definition of a limit that already has one, and that is exactly how this endpoint kept
     * writing 2 after the limit became 3. An absent parameter resolves through
     * {@code OrchestratorProperties}, which reads {@code application.yml}.
     */
    @PostMapping
    public String createOrUpdateProfile(@RequestParam String name,
        @RequestParam(required = false) Integer maxReviewRounds,
        @RequestParam(required = false) Integer maxSpecialistCalls,
        @RequestParam String orchestratorPlaybook, @RequestParam String reviewerPlaybook,
        @RequestParam(required = false) List<String> specialistPlaybooks,
        @RequestParam(required = false) String orchestratorModel,
        @RequestParam(required = false) String orchestratorThinkingLevel,
        @RequestParam(required = false) String reviewerModel,
        @RequestParam(required = false) String reviewerThinkingLevel,
        @RequestParam(required = false) String specialistModel,
        @RequestParam(required = false) String specialistThinkingLevel,
        RedirectAttributes redirectAttributes) {

        int rounds =
            maxReviewRounds != null ? maxReviewRounds : properties.resolvedMaxReviewRounds();
        int calls = maxSpecialistCalls != null ? maxSpecialistCalls
            : properties.resolvedMaxSpecialistCalls();

        OrchestratorProfile profile =
            new OrchestratorProfile(name, rounds, calls, orchestratorPlaybook, reviewerPlaybook,
                specialistPlaybooks != null ? specialistPlaybooks : List.of(), orchestratorModel,
                orchestratorThinkingLevel, reviewerModel, reviewerThinkingLevel, specialistModel,
                specialistThinkingLevel, null, null);
        profileService.saveProfile(profile);
        redirectAttributes.addFlashAttribute("success",
            "Orchestrator profile '" + name + "' saved successfully.");
        return "redirect:/admin/orchestrators";
    }

    @GetMapping("/export")
    public ResponseEntity<Resource> exportProfile(@RequestParam String name) {
        String json = profileService.exportProfileToJson(name);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ByteArrayResource resource = new ByteArrayResource(bytes);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + ".json\"")
            .contentType(MediaType.APPLICATION_JSON).contentLength(bytes.length).body(resource);
    }

    @PostMapping("/import")
    public String importProfile(@RequestParam("file") MultipartFile file,
        RedirectAttributes redirectAttributes) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty.");
        }
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        OrchestratorProfile imported = profileService.importProfileFromJson(content);
        redirectAttributes.addFlashAttribute("success",
            "Orchestrator profile '" + imported.name() + "' imported successfully.");
        return "redirect:/admin/orchestrators";
    }

    @PostMapping("/delete")
    public String deleteProfile(@RequestParam String name, RedirectAttributes redirectAttributes) {
        profileService.deleteProfile(name);
        redirectAttributes.addFlashAttribute("success",
            "Orchestrator profile '" + name + "' deleted successfully.");
        return "redirect:/admin/orchestrators";
    }
}
