package de.palsoftware.yvoke.chat.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.chat.core.model.Conversation;
import de.palsoftware.yvoke.chat.core.service.ChatConversationService;
import de.palsoftware.yvoke.llm.core.model.LlmMessage;
import de.palsoftware.yvoke.llm.core.model.LlmRequest;
import de.palsoftware.yvoke.llm.core.model.LlmResponse;
import de.palsoftware.yvoke.llm.core.model.LlmUsage;
import de.palsoftware.yvoke.llm.core.service.LlmClient;
import de.palsoftware.yvoke.rag.prompt.Playbook;
import de.palsoftware.yvoke.rag.prompt.PlaybookService;
import de.palsoftware.yvoke.shared.web.GenerationConcurrencyLimiter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

class PlaybookValidationControllerTest {

    private ChatConversationService chatConversationService;
    private PlaybookService playbookService;
    private LlmClient llmClient;
    private ObjectMapper objectMapper;
    private PlaybookValidationController controller;

    private final UUID convId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        chatConversationService = mock(ChatConversationService.class);
        playbookService = mock(PlaybookService.class);
        llmClient = mock(LlmClient.class);
        objectMapper = new ObjectMapper();

        when(chatConversationService.isPlaybookValidationEnabled()).thenReturn(true);
        when(chatConversationService.verifyOwnership(convId, false))
            .thenReturn(new Conversation(convId, userId, "Test", Map.of(), null, null, List.of()));
        when(chatConversationService.effectiveModel(any())).thenReturn("test-model");

        controller = new PlaybookValidationController(chatConversationService, playbookService,
            llmClient, objectMapper);

        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken("user", "pass", "ROLE_USER", "AREA_USER_*"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void defaultPlaybookIsExemptFromValidationAndDoesNotCallLlm() {
        when(playbookService.listDefaultPlaybookNames()).thenReturn(Set.of("oim-full"));

        ValidationResponse response = controller.validatePlaybook(convId,
            "ADS sync marks all ADSAccount objects as outstanding — why?", " OIM-FULL ");

        assertThat(response.plausible()).isTrue();
        assertThat(response.reason()).isEmpty();
        assertThat(response.suggestedPlaybookName()).isNull();
        verify(llmClient, never()).generate(any(LlmRequest.class));
    }

    @Test
    void defaultPlaybookExemptionStillEnforcesConversationOwnership() {
        when(playbookService.listDefaultPlaybookNames()).thenReturn(Set.of("oim-full"));
        when(chatConversationService.verifyOwnership(convId, false))
            .thenThrow(new AccessDeniedException("Forbidden"));

        assertThatThrownBy(() -> controller.validatePlaybook(convId, "Question", "oim-full"))
            .isInstanceOf(AccessDeniedException.class);
        verify(llmClient, never()).generate(any(LlmRequest.class));
    }

    @Test
    void candidatePlaybooksExcludeDefaultPlaybooksFromPrompt() {
        when(playbookService.listDefaultPlaybookNames()).thenReturn(Set.of("oim-full"));

        Playbook full = new Playbook("oim-full", "OIM Full", "Everything", "tmpl", List.of(), false,
            "specialist", false, null, null, false);
        Playbook fieldQa = new Playbook("oim-field-qa", "Field QA", "Field experience", "tmpl",
            List.of(), false, "specialist", false, null, null, false);
        Playbook schema = new Playbook("oim-schema", "Schema", "Tables and columns", "tmpl",
            List.of(), false, "specialist", false, null, null, false);

        when(playbookService.listSpecializedPlaybooks()).thenReturn(List.of(full, fieldQa, schema));
        when(llmClient.generate(any(LlmRequest.class))).thenReturn(new LlmResponse(
            "{\"plausible\": true, \"reason\": \"\", \"suggestedPlaybookName\": null}",
            new LlmUsage(0, 0, 0, 0, 0)));

        ValidationResponse response =
            controller.validatePlaybook(convId, "How do I query Person?", "oim-field-qa");

        assertThat(response.plausible()).isTrue();

        ArgumentCaptor<LlmRequest> captor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmClient).generate(captor.capture());
        String systemPrompt = captor.getValue().messages().stream()
            .filter(m -> "system".equals(m.role())).map(LlmMessage::content).findFirst().orElse("");

        assertThat(systemPrompt).contains("oim-field-qa | Field QA | Field experience");
        assertThat(systemPrompt).contains("oim-schema | Schema | Tables and columns");
        assertThat(systemPrompt).doesNotContain("oim-full | OIM Full | Everything");
    }

    @Test
    void suggestedPlaybookMatchingSelectedPlaybookPassesValidation() {
        when(playbookService.listDefaultPlaybookNames()).thenReturn(Set.of("oim-full"));

        Playbook fieldQa = new Playbook("oim-field-qa", "Field QA", "Field experience", "tmpl",
            List.of(), false, "specialist", false, null, null, false);
        when(playbookService.listSpecializedPlaybooks()).thenReturn(List.of(fieldQa));

        // Model returned plausible: false, but suggested the exact same playbook as selected
        when(llmClient.generate(any(LlmRequest.class))).thenReturn(new LlmResponse(
            "{\"plausible\": false, \"reason\": \"ADS sync issues are covered by field Q&A.\", \"suggestedPlaybookName\": \"oim-field-qa\"}",
            new LlmUsage(0, 0, 0, 0, 0)));

        ValidationResponse response = controller.validatePlaybook(convId,
            "ADS sync marks all ADSAccount objects as outstanding — why?", "oim-field-qa");

        assertThat(response.plausible()).isTrue();
        assertThat(response.reason()).isEmpty();
        assertThat(response.suggestedPlaybookName()).isNull();
    }

    @Test
    void suggestedPlaybookDifferentFromSelectedReturnsObjection() {
        when(playbookService.listDefaultPlaybookNames()).thenReturn(Set.of("oim-full"));

        Playbook wrong = new Playbook("wrong-playbook", "Wrong", "Wrong desc", "tmpl", List.of(),
            false, "specialist", false, null, null, false);
        Playbook suggested = new Playbook("suggested-playbook", "Suggested", "Suggested desc",
            "tmpl", List.of(), false, "specialist", false, null, null, false);
        when(playbookService.listSpecializedPlaybooks()).thenReturn(List.of(wrong, suggested));

        when(llmClient.generate(any(LlmRequest.class))).thenReturn(new LlmResponse(
            "{\"plausible\": false, \"reason\": \"Question is about user setup.\", \"suggestedPlaybookName\": \"suggested-playbook\"}",
            new LlmUsage(0, 0, 0, 0, 0)));

        ValidationResponse response =
            controller.validatePlaybook(convId, "Setup a new user", "wrong-playbook");

        assertThat(response.plausible()).isFalse();
        assertThat(response.reason()).isEqualTo("Question is about user setup.");
        assertThat(response.suggestedPlaybookName()).isEqualTo("suggested-playbook");
    }

    @Test
    void suggestedPlaybookNonExistentDropsSuggestion() {
        when(playbookService.listDefaultPlaybookNames()).thenReturn(Set.of("oim-full"));

        Playbook wrong = new Playbook("wrong-playbook", "Wrong", "Wrong desc", "tmpl", List.of(),
            false, "specialist", false, null, null, false);
        when(playbookService.listSpecializedPlaybooks()).thenReturn(List.of(wrong));

        when(llmClient.generate(any(LlmRequest.class))).thenReturn(new LlmResponse(
            "{\"plausible\": false, \"reason\": \"Bad selection.\", \"suggestedPlaybookName\": \"non-existent\"}",
            new LlmUsage(0, 0, 0, 0, 0)));

        ValidationResponse response =
            controller.validatePlaybook(convId, "Hello", "wrong-playbook");

        assertThat(response.plausible()).isFalse();
        assertThat(response.reason()).isEqualTo("Bad selection.");
        assertThat(response.suggestedPlaybookName()).isNull();
    }

    @Test
    void candidatePlaybooksExcludePlaybooksFromUnauthorizedAreas() {
        when(playbookService.listDefaultPlaybookNames()).thenReturn(Set.of("default-pb"));

        Playbook oimPlaybook = new Playbook("oim-pb", "OIM Playbook", "desc", "tmpl", List.of(),
            false, "specialist", false, Instant.now(), Instant.now(), false, "OIM");
        Playbook finPlaybook = new Playbook("fin-pb", "Finance Playbook", "desc", "tmpl", List.of(),
            false, "specialist", false, Instant.now(), Instant.now(), false, "Finance");
        when(playbookService.listSpecializedPlaybooks())
            .thenReturn(List.of(oimPlaybook, finPlaybook));

        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken("user", "pass", "ROLE_USER", "AREA_USER_OIM"));

        when(llmClient.generate(any(LlmRequest.class))).thenReturn(new LlmResponse(
            "{\"plausible\": true, \"reason\": \"\", \"suggestedPlaybookName\": null}",
            new LlmUsage(0, 0, 0, 0, 0)));

        controller.validatePlaybook(convId, "How to sync?", "oim-pb");

        ArgumentCaptor<LlmRequest> captor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmClient).generate(captor.capture());

        String prompt = captor.getValue().messages().get(0).content();
        assertThat(prompt).contains("oim-pb");
        assertThat(prompt).doesNotContain("fin-pb");
    }

    @Test
    void validatePlaybook_withUnauthorizedPromptName_rejectsWithoutDelegatingToLlm() {
        Playbook finPlaybook = new Playbook("fin-pb", "Finance Playbook", "desc", "tmpl", List.of(),
            false, "specialist", false, Instant.now(), Instant.now(), false, "Finance");
        when(playbookService.getPlaybook("fin-pb")).thenReturn(Optional.of(finPlaybook));

        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken("user", "pass", "ROLE_USER", "AREA_USER_OIM"));

        ValidationResponse response = controller.validatePlaybook(convId, "Question", "fin-pb");

        assertThat(response.plausible()).isFalse();
        verify(llmClient, never()).generate(any());
    }

    @Test
    void validatePlaybook_enforcesConcurrencyLimiterAndReleasesPermitOnSuccessAndError() {
        GenerationConcurrencyLimiter limiter = new GenerationConcurrencyLimiter(1);
        PlaybookValidationController limitedController = new PlaybookValidationController(
            chatConversationService, playbookService, llmClient, objectMapper, limiter);
        Playbook pb = new Playbook("pb-1", "PB 1", "desc", "tmpl", List.of(), false, "specialist",
            false, Instant.now(), Instant.now(), false, "OIM");
        when(playbookService.listDefaultPlaybookNames()).thenReturn(Set.of());
        when(playbookService.listSpecializedPlaybooks()).thenReturn(List.of(pb));

        // 1. At capacity -> HTTP 429 (must NOT be swallowed into fail-open 200).
        assertThat(limiter.tryAcquire()).isTrue();
        assertThatThrownBy(() -> limitedController.validatePlaybook(convId, "Question", "pb-1"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        verify(llmClient, never()).generate(any());
        limiter.release();

        // 2. Success releases the permit back to 1.
        when(llmClient.generate(any(LlmRequest.class))).thenReturn(new LlmResponse(
            "{\"plausible\": true, \"reason\": \"\", \"suggestedPlaybookName\": null}",
            new LlmUsage(0, 0, 0, 0, 0)));
        assertThat(limitedController.validatePlaybook(convId, "Question", "pb-1").plausible())
            .isTrue();
        assertThat(limiter.availablePermits()).isEqualTo(1);

        // 3. LLM exception fails open AND still releases the permit back to 1.
        when(llmClient.generate(any(LlmRequest.class)))
            .thenThrow(new RuntimeException("provider timeout"));
        assertThat(limitedController.validatePlaybook(convId, "Question", "pb-1").plausible())
            .isTrue();
        assertThat(limiter.availablePermits()).isEqualTo(1);
    }
}
