package de.palsoftware.yvoke.chat.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.chat.core.model.Conversation;
import de.palsoftware.yvoke.chat.core.model.ConversationSetting;
import de.palsoftware.yvoke.chat.core.service.ChatConversationService;
import de.palsoftware.yvoke.llm.core.model.LlmMessage;
import de.palsoftware.yvoke.llm.core.model.LlmRequest;
import de.palsoftware.yvoke.llm.core.model.LlmResponse;
import de.palsoftware.yvoke.llm.core.service.LlmClient;
import de.palsoftware.yvoke.rag.prompt.Playbook;
import de.palsoftware.yvoke.rag.prompt.PlaybookService;
import de.palsoftware.yvoke.llm.core.context.LlmCallContextHolder;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/chat")
public class PlaybookValidationController {

    private static final Logger log = LoggerFactory.getLogger(PlaybookValidationController.class);

    private final ChatConversationService chatConversationService;
    private final PlaybookService playbookService;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public PlaybookValidationController(ChatConversationService chatConversationService,
        PlaybookService playbookService, LlmClient llmClient, ObjectMapper objectMapper) {
        this.chatConversationService = chatConversationService;
        this.playbookService = playbookService;
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/{id}/validate-playbook")
    public ValidationResponse validatePlaybook(@PathVariable UUID id,
        @RequestParam("content") String content,
        @RequestParam(value = "promptName", required = false) String promptName) {

        if (!chatConversationService.isPlaybookValidationEnabled()) {
            return new ValidationResponse(true, "", null);
        }

        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Content cannot be blank");
        }

        Conversation conversation = chatConversationService.verifyOwnership(id, false);

        try {
            LlmCallContextHolder.set(conversation.id(), null, null, conversation.userId(),
                "playbook_validator", "validator");
            List<Playbook> playbooks = playbookService.listSpecializedPlaybooks();

            String model = null;
            if (conversation.settings() != null) {
                Object val = conversation.settings().get(ConversationSetting.MODEL.getValue());
                if (val instanceof String s && !s.isBlank()) {
                    model = s;
                }
            }
            if (model == null) {
                List<String> allowedModels = chatConversationService.getAllowedModels();
                if (allowedModels == null || allowedModels.isEmpty()) {
                    throw new IllegalArgumentException("No allowed models configured");
                }
                model = allowedModels.get(0);
            }

            StringBuilder playbooksBuilder = new StringBuilder();
            for (Playbook playbook : playbooks) {
                playbooksBuilder.append(playbook.name()).append(" | ").append(playbook.title())
                    .append(" | ").append(playbook.description()).append("\n");
            }
            String playbooksList = playbooksBuilder.toString().trim();

            String systemPrompt =
                """
                    You are a playbook validator for a system managing database and product knowledge of One Identity Manager.
                    Your task is to analyze the user's question, evaluate if their selected playbook is the most appropriate one, and if not, suggest the single best playbook from the list of available playbooks.

                    Available Playbooks (name | title | description):
                    %s

                    User Question:
                    %s

                    Selected Playbook Name:
                    %s

                    Decide:
                    1. Is the selected playbook appropriate and correct for this user question?
                    2. If not, which playbook name from the list of available playbooks is the best match? (Return null if the selected one is appropriate).
                    3. Provide a concise explanation (1-2 sentences) of why the selected playbook is wrong/why the recommended one is better.

                    You MUST respond ONLY with a raw JSON object matching the schema below. Do not wrap the JSON in markdown blocks (e.g. ```json).
                    JSON Schema:
                    {
                      "plausible": boolean (true if selected playbook is correct, false if incorrect),
                      "reason": string (explanation if incorrect, otherwise empty string or null),
                      "suggestedPlaybookName": string (the name of the suggested playbook from the available list if incorrect, otherwise null)
                    }
                    """
                    .formatted(playbooksList, content, promptName != null ? promptName : "");

            List<LlmMessage> messages =
                List.of(new LlmMessage("system", systemPrompt), new LlmMessage("user", content));

            LlmRequest request = new LlmRequest(model, messages, 0.0, 500, Collections.emptyList(),
                "low", "application/json", null, null, false);
            LlmResponse response = llmClient.generate(request);


            String cleaned = cleanMarkdown(response.content());
            ValidationResponse validationResponse =
                objectMapper.readValue(cleaned, ValidationResponse.class);

            String suggested = validationResponse.suggestedPlaybookName();
            if (suggested != null && !suggested.isBlank()) {
                boolean exists = playbooks.stream().anyMatch(p -> p.name().equals(suggested));
                if (!exists) {
                    validationResponse = new ValidationResponse(validationResponse.plausible(),
                        validationResponse.reason(), null);
                }
            }
            return validationResponse;
        } catch (Exception e) {
            log.error("Failed to validate playbook selection, returning fallback", e);
            return new ValidationResponse(true, "", null);
        } finally {
            LlmCallContextHolder.clear();
        }
    }

    private String cleanMarkdown(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("`")) {
            trimmed = trimmed.replaceFirst("^`+[a-zA-Z]*\\s*", "");
            trimmed = trimmed.replaceFirst("`+$", "");
            trimmed = trimmed.trim();
        }
        return trimmed;
    }
}
