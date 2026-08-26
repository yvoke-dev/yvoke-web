package de.palsoftware.yvoke.chat.web;

import de.palsoftware.yvoke.chat.core.service.ChatConversationService;
import de.palsoftware.yvoke.chat.core.service.ChatMessageService;
import de.palsoftware.yvoke.chat.core.service.ChatFeedbackService;
import de.palsoftware.yvoke.chat.core.service.ChatCancellationService;
import de.palsoftware.yvoke.chat.core.model.Conversation;
import de.palsoftware.yvoke.chat.core.model.ConversationSetting;
import de.palsoftware.yvoke.chat.core.model.ConversationSidebar;
import de.palsoftware.yvoke.chat.core.model.Feedback;
import de.palsoftware.yvoke.chat.core.model.Message;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProperties;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProfileService;
import de.palsoftware.yvoke.rag.prompt.PlaybookService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;

@Controller
@RequestMapping("/chat")
public class ChatController {
    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatConversationService chatConversationService;
    private final ChatMessageService chatMessageService;
    private final ChatFeedbackService chatFeedbackService;
    private final ChatCancellationService chatCancellationService;
    private final PlaybookService playbookService;
    private final OrchestratorProperties orchestratorProperties;
    private final OrchestratorProfileService orchestratorProfileService;

    public ChatController(ChatConversationService chatConversationService,
        ChatMessageService chatMessageService, ChatFeedbackService chatFeedbackService,
        ChatCancellationService chatCancellationService, PlaybookService playbookService,
        OrchestratorProperties orchestratorProperties,
        OrchestratorProfileService orchestratorProfileService) {
        this.chatConversationService = chatConversationService;
        this.chatMessageService = chatMessageService;
        this.chatFeedbackService = chatFeedbackService;
        this.chatCancellationService = chatCancellationService;
        this.playbookService = playbookService;
        this.orchestratorProperties = orchestratorProperties;
        this.orchestratorProfileService = orchestratorProfileService;
    }

    /**
     * Spreads the sidebar view model onto the model under the attribute names the templates read.
     */
    private static void addSidebar(Model model, ConversationSidebar sidebar) {
        model.addAttribute("conversations", sidebar.conversations());
        model.addAttribute("folders", sidebar.folders());
        model.addAttribute("untagged", sidebar.untagged());
        model.addAttribute("publicFolders", sidebar.publicFolders());
        model.addAttribute("publicUntagged", sidebar.publicUntagged());
        model.addAttribute("publicCount", sidebar.publicCount());
        model.addAttribute("allTags", sidebar.allTags());
        model.addAttribute("currentUserId", sidebar.currentUserId());
    }

    @GetMapping
    public String index(Model model) {
        log.info("Accessing chat list view");
        addSidebar(model, chatConversationService.buildSidebar());
        return "chat/index";
    }

    @PostMapping("/new")
    public String createConversation() {
        log.info("Creating new conversation");
        Conversation conversation = chatConversationService.createConversation();
        return "redirect:/chat/" + conversation.id();
    }

    @GetMapping("/{id}")
    public String threadView(@PathVariable UUID id, Model model) {
        log.info("Accessing conversation thread view for id: {}", id);
        Conversation conversation = chatConversationService.getConversation(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Conversation not found: " + id));

        ConversationSidebar sidebar = chatConversationService.buildSidebar();
        addSidebar(model, sidebar);

        List<Message> messages = chatMessageService.getMessages(id);
        List<String> allowedModels = chatConversationService.getAllowedModels();

        // One query for the whole thread. Resolving per message re-checked ownership and re-read
        // the
        // message row for every widget, so this scaled with thread length.
        Map<UUID, Feedback> feedbacks = chatFeedbackService.getFeedbackByConversation(id);

        boolean isReadOnly = !Objects.equals(conversation.userId(), sidebar.currentUserId());

        model.addAttribute("conversation", conversation);
        model.addAttribute("messages", messages);
        model.addAttribute("allowedModels", allowedModels);
        model.addAttribute("settings", conversation.settings());
        model.addAttribute("feedbacks", feedbacks);
        model.addAttribute("prompts", playbookService.listSpecializedPlaybooks());
        model.addAttribute("isReadOnly", isReadOnly);
        model.addAttribute("playbookValidationEnabled",
            chatConversationService.isPlaybookValidationEnabled());
        model.addAttribute("orchestratorProfiles", orchestratorProfileService.listProfileNames());

        return "chat/thread";
    }


    @PostMapping("/{id}/model")
    @ResponseBody
    public void updateModel(@PathVariable UUID id,
        @RequestParam(name = "model") String modelParam) {
        log.info("Updating model for conversation id: {} to {}", id, modelParam);
        // Whitelist validation lives in the service so every caller is bound by the same rule
        // (SEC-04).
        chatConversationService.updateModel(id, modelParam);
    }

    @PostMapping("/{id}/streaming")
    @ResponseBody
    public void updateStreaming(@PathVariable UUID id, @RequestParam("enabled") boolean enabled) {
        log.info("Updating streaming setting for conversation id: {} to {}", id, enabled);
        Map<String, Object> settings = chatConversationService.getConversation(id)
            .map(Conversation::settings).map(HashMap::new).orElseGet(HashMap::new);
        settings.put(ConversationSetting.STREAMING.getValue(), enabled);
        chatConversationService.updateSettings(id, settings);
    }

    @PostMapping("/{id}/show-thinking")
    @ResponseBody
    public void updateShowThinking(@PathVariable UUID id,
        @RequestParam("enabled") boolean enabled) {
        log.info("Updating show-thinking setting for conversation id: {} to {}", id, enabled);
        Map<String, Object> settings = chatConversationService.getConversation(id)
            .map(Conversation::settings).map(HashMap::new).orElseGet(HashMap::new);
        settings.put(ConversationSetting.SHOW_THINKING.getValue(), enabled);
        chatConversationService.updateSettings(id, settings);
    }

    @PostMapping("/{id}/show-prototypes")
    @ResponseBody
    public void updateShowPrototypes(@PathVariable UUID id,
        @RequestParam("enabled") boolean enabled) {
        log.info("Updating show-prototypes setting for conversation id: {} to {}", id, enabled);
        Map<String, Object> settings = chatConversationService.getConversation(id)
            .map(Conversation::settings).map(HashMap::new).orElseGet(HashMap::new);
        settings.put(ConversationSetting.SHOW_PROTOTYPES.getValue(), enabled);
        chatConversationService.updateSettings(id, settings);
    }

    @PostMapping("/{id}/thinking-level")
    @ResponseBody
    public void updateThinkingLevel(@PathVariable UUID id, @RequestParam("level") String level) {
        log.info("Updating thinking-level setting for conversation id: {} to {}", id, level);
        Map<String, Object> settings = chatConversationService.getConversation(id)
            .map(Conversation::settings).map(HashMap::new).orElseGet(HashMap::new);
        settings.put(ConversationSetting.THINKING_LEVEL.getValue(), level);
        chatConversationService.updateSettings(id, settings);
    }

    @PostMapping("/{id}/orchestrator-profile")
    @ResponseBody
    public void updateOrchestratorProfile(@PathVariable UUID id,
        @RequestParam(name = "name", required = false) String name) {
        String selected = (name == null) ? "" : name.trim();
        if (!selected.isEmpty()) {
            boolean exists = (orchestratorProfileService != null
                && orchestratorProfileService.getProfile(selected).isPresent())
                || orchestratorProperties.profile(selected).isPresent();
            if (!exists) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown orchestrator profile: " + selected);
            }
        }
        log.info("Updating orchestrator-profile for conversation id: {} to '{}'", id, selected);
        Map<String, Object> settings = chatConversationService.getConversation(id)
            .map(Conversation::settings).map(HashMap::new).orElseGet(HashMap::new);
        settings.put(ConversationSetting.ORCHESTRATOR_PROFILE.getValue(), selected);
        chatConversationService.updateSettings(id, settings);
    }


    @PostMapping("/{id}/tags/add")
    public String addTag(@PathVariable UUID id, @RequestParam("tag") String tag) {
        log.info("Adding tag {} to conversation id: {}", tag, id);
        chatConversationService.addTag(id, tag);
        return "redirect:/chat/" + id;
    }

    @PostMapping("/{id}/tags/remove")
    public String removeTag(@PathVariable UUID id, @RequestParam("tag") String tag) {
        log.info("Removing tag {} from conversation id: {}", tag, id);
        chatConversationService.removeTag(id, tag);
        return "redirect:/chat/" + id;
    }

    @PostMapping("/{id}/delete")
    public String deleteConversation(@PathVariable UUID id) {
        log.info("Deleting conversation id: {}", id);
        chatConversationService.deleteConversation(id);
        return "redirect:/chat";
    }

    @PostMapping("/{id}/stop")
    @ResponseBody
    public void stopGeneration(@PathVariable UUID id) {
        log.info("Stopping generation for conversation id: {}", id);
        // Only the conversation's owner may cancel its in-flight generation (SEC-10). Without this,
        // any authenticated user could stop another user's stream by guessing the conversation id.
        chatConversationService.verifyOwnership(id, false);
        chatCancellationService.stop(id);
    }
}
