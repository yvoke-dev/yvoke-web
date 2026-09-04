package de.palsoftware.yvoke.chat.web;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.security.access.AccessDeniedException;

import de.palsoftware.yvoke.chat.core.model.Conversation;
import de.palsoftware.yvoke.chat.core.model.ConversationSidebar;
import de.palsoftware.yvoke.chat.core.model.Feedback;
import de.palsoftware.yvoke.chat.core.model.Message;
import de.palsoftware.yvoke.chat.core.service.ChatCancellationService;
import de.palsoftware.yvoke.chat.core.service.ChatConversationService;
import de.palsoftware.yvoke.chat.core.service.ChatFeedbackService;
import de.palsoftware.yvoke.chat.core.service.ChatMessageService;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProfile;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProfileOption;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProfileService;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProperties;
import de.palsoftware.yvoke.rag.prompt.PlaybookService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.web.server.ResponseStatusException;

class ChatControllerTest {

    private ChatConversationService conversationService;
    private ChatMessageService messageService;
    private ChatFeedbackService feedbackService;
    private ChatCancellationService cancellationService;
    private PlaybookService playbookService;
    private OrchestratorProperties orchestratorProperties;
    private OrchestratorProfileService orchestratorProfileService;
    private ChatController controller;

    @BeforeEach
    void setUp() {
        conversationService = mock(ChatConversationService.class);
        messageService = mock(ChatMessageService.class);
        feedbackService = mock(ChatFeedbackService.class);
        cancellationService = mock(ChatCancellationService.class);
        playbookService = mock(PlaybookService.class);
        orchestratorProperties = new OrchestratorProperties(3, 8, null,
            List.of(new OrchestratorProperties.Profile("yaml-profile", "orch", "rev", List.of(),
                null, null, null)));
        orchestratorProfileService = mock(OrchestratorProfileService.class);

        controller = new ChatController(conversationService, messageService, feedbackService,
            cancellationService, playbookService, orchestratorProperties,
            orchestratorProfileService);
    }

    @Test
    void updateOrchestratorProfile_validDbProfile_updatesSettingsSuccessfully() {
        UUID conversationId = UUID.randomUUID();
        Conversation conv = new Conversation(conversationId, UUID.randomUUID(), "T",
            Collections.emptyMap(), Instant.now(), Instant.now(), List.of());
        when(conversationService.getConversation(conversationId)).thenReturn(Optional.of(conv));

        OrchestratorProfile dbProfile = new OrchestratorProfile("OIM", 3, 8, "orch", "rev",
            List.of(), null, null, null, null, null, null, false, Instant.now(), Instant.now());
        when(orchestratorProfileService.getProfile("OIM")).thenReturn(Optional.of(dbProfile));

        assertDoesNotThrow(() -> controller.updateOrchestratorProfile(conversationId, "OIM"));

        verify(conversationService).updateSettings(eq(conversationId), any());
    }

    @Test
    void updateOrchestratorProfile_validPropertyProfile_updatesSettingsSuccessfully() {
        UUID conversationId = UUID.randomUUID();
        Conversation conv = new Conversation(conversationId, UUID.randomUUID(), "T",
            Collections.emptyMap(), Instant.now(), Instant.now(), List.of());
        when(conversationService.getConversation(conversationId)).thenReturn(Optional.of(conv));
        when(orchestratorProfileService.getProfile("yaml-profile")).thenReturn(Optional.empty());

        assertDoesNotThrow(
            () -> controller.updateOrchestratorProfile(conversationId, "yaml-profile"));

        verify(conversationService).updateSettings(eq(conversationId), any());
    }

    @Test
    void updateOrchestratorProfile_unknownProfile_throwsBadRequest() {
        UUID conversationId = UUID.randomUUID();
        when(orchestratorProfileService.getProfile("Unknown")).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.updateOrchestratorProfile(conversationId, "Unknown"));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void stopGeneration_verifiesOwnershipBeforeStopping() {
        UUID conversationId = UUID.randomUUID();

        controller.stopGeneration(conversationId);

        // Ownership must be verified BEFORE the generation is stopped (SEC-10).
        InOrder inOrder = Mockito.inOrder(conversationService, cancellationService);
        inOrder.verify(conversationService).verifyOwnership(conversationId, false);
        inOrder.verify(cancellationService).stop(conversationId);
    }

    @Test
    void stopGeneration_deniedForNonOwner_doesNotStop() {
        UUID conversationId = UUID.randomUUID();
        doThrow(new AccessDeniedException("not your conversation")).when(conversationService)
            .verifyOwnership(conversationId, false);

        assertThrows(AccessDeniedException.class, () -> controller.stopGeneration(conversationId));

        verify(cancellationService, never()).stop(any());
    }

    @Test
    void updateOrchestratorProfile_emptyName_clearsProfile() {
        UUID conversationId = UUID.randomUUID();
        Conversation conv = new Conversation(conversationId, UUID.randomUUID(), "T",
            Map.of("orchestrator-profile", "OIM"), Instant.now(), Instant.now(), List.of());
        when(conversationService.getConversation(conversationId)).thenReturn(Optional.of(conv));

        assertDoesNotThrow(() -> controller.updateOrchestratorProfile(conversationId, "  "));

        verify(conversationService).updateSettings(eq(conversationId), any());
    }

    /**
     * The thread view used to resolve feedback one message at a time, and each of those calls cost
     * three statements (message lookup, ownership lookup, feedback select) — so a 20-message thread
     * issued ~60 queries just to render the thumbs widgets. It must use the conversation-scoped
     * batch query instead.
     */
    @Test
    void threadView_fetchesFeedbackInOneBatchNotPerMessage() {
        UUID conversationId = UUID.randomUUID();
        UUID firstAnswerId = UUID.randomUUID();
        UUID secondAnswerId = UUID.randomUUID();
        Conversation conv = new Conversation(conversationId, UUID.randomUUID(), "T", Map.of(),
            Instant.now(), Instant.now(), List.of());
        when(conversationService.getConversation(conversationId)).thenReturn(Optional.of(conv));
        when(conversationService.buildSidebar()).thenReturn(new ConversationSidebar(List.of(),
            Map.of(), List.of(), Map.of(), List.of(), 0, List.of(), UUID.randomUUID()));
        when(messageService.getMessages(conversationId))
            .thenReturn(List.of(assistantMessage(firstAnswerId, conversationId),
                userMessage(conversationId), assistantMessage(secondAnswerId, conversationId)));
        Feedback onFirstAnswer =
            new Feedback(UUID.randomUUID(), firstAnswerId, -1, "wrong", null, null);
        when(feedbackService.getFeedbackByConversation(conversationId))
            .thenReturn(Map.of(firstAnswerId, onFirstAnswer));

        Model model = new ConcurrentModel();
        String view = controller.threadView(conversationId, model);

        assertEquals("chat/thread", view);
        verify(feedbackService).getFeedbackByConversation(conversationId);
        verify(feedbackService, never()).getFeedback(any());
        assertEquals(Map.of(firstAnswerId, onFirstAnswer), model.getAttribute("feedbacks"));
    }

    /**
     * The two halves of the orchestrator-profile feature disagree by construction, and this pins
     * which side the dropdown is on. {@code threadView} populates it from
     * {@code orchestratorProfileService.listProfileNames()} — a plain {@code SELECT} over
     * {@code orchestrator_profiles} — while {@code updateOrchestratorProfile} accepts EITHER source
     * (DB row OR {@code OrchestratorProperties.profile(name)}), and {@code resolve} falls back to
     * the properties when no row exists. A yaml-defined profile is therefore fully runnable and
     * completely invisible: selectable only by hand-POSTing its name.
     *
     * <p>
     * That asymmetry is safe while no yaml profile ships, and stops being safe the moment one does.
     * The regression this guards is the reverse of the obvious bug report ("my configured profile
     * isn't in the list"): merging {@code orchestratorProperties.profileNames()} in here to "fix"
     * it would put a name in front of every user of every conversation, whether or not the
     * deployment intends it, and multi-agent runs are the expensive path — one orchestration is an
     * orchestrator turn plus N specialist runs plus review rounds. A profile becomes offerable
     * because someone edited a yaml default, with no admin action and nothing on screen to say
     * where it came from.
     *
     * <p>
     * {@code updateOrchestratorProfile_validPropertyProfile_updatesSettingsSuccessfully} pins the
     * POST accepting a properties-only profile, which is exactly why the dropdown's source needs
     * its own assertion: that test stays green whichever source the view uses.
     * {@code ChatThreadRenderingIT} renders the thread but never asserts on the profile list.
     */
    @Test
    void theThreadDropdownListsDatabaseProfilesOnlyEvenWhenPropertyProfilesExist() {
        UUID conversationId = UUID.randomUUID();
        Conversation conv = new Conversation(conversationId, UUID.randomUUID(), "T", Map.of(),
            Instant.now(), Instant.now(), List.of());
        when(conversationService.getConversation(conversationId)).thenReturn(Optional.of(conv));
        when(conversationService.buildSidebar()).thenReturn(new ConversationSidebar(List.of(),
            Map.of(), List.of(), Map.of(), List.of(), 0, List.of(), UUID.randomUUID()));
        when(messageService.getMessages(conversationId)).thenReturn(List.of());

        when(orchestratorProfileService.listProfileOptions())
            .thenReturn(List.of(new OrchestratorProfileOption("OIM", false)));

        Model model = new ConcurrentModel();
        assertEquals("chat/thread", controller.threadView(conversationId, model));

        assertEquals(List.of(new OrchestratorProfileOption("OIM", false)),
            model.getAttribute("orchestratorProfiles"));
    }

    /**
     * A prototype profile reaches the page FLAGGED, not filtered out.
     *
     * <p>
     * The obvious implementation — drop prototype rows in the controller when the conversation's
     * {@code show-prototypes} setting is off — is wrong in both directions, and silently. It would
     * make the toggle a page reload rather than a live control, since {@code thread.js} flips the
     * chips and the dropdown in the browser with no round trip; and it would drop the
     * conversation's OWN selected profile out of the dropdown, which then renders "Single playbook"
     * over a conversation that is still in multi-agent mode. The visibility decision belongs to
     * {@code profileOptionVisibility} in the client, and it can only make it if the server sends
     * every profile with its flag.
     */
    @Test
    void aPrototypeProfileIsSentToThePageFlaggedRatherThanWithheld() {
        UUID conversationId = UUID.randomUUID();
        Conversation conv = new Conversation(conversationId, UUID.randomUUID(), "T",
            Map.of("show-prototypes", false), Instant.now(), Instant.now(), List.of());
        when(conversationService.getConversation(conversationId)).thenReturn(Optional.of(conv));
        when(conversationService.buildSidebar()).thenReturn(new ConversationSidebar(List.of(),
            Map.of(), List.of(), Map.of(), List.of(), 0, List.of(), UUID.randomUUID()));
        when(messageService.getMessages(conversationId)).thenReturn(List.of());

        when(orchestratorProfileService.listProfileOptions())
            .thenReturn(List.of(new OrchestratorProfileOption("OIM", false),
                new OrchestratorProfileOption("OIM - Browsing", true)));

        Model model = new ConcurrentModel();
        assertEquals("chat/thread", controller.threadView(conversationId, model));

        assertEquals(
            List.of(new OrchestratorProfileOption("OIM", false),
                new OrchestratorProfileOption("OIM - Browsing", true)),
            model.getAttribute("orchestratorProfiles"));
    }

    private static Message assistantMessage(UUID id, UUID conversationId) {
        return new Message(id, conversationId, "assistant", "An answer.", List.of(), List.of(),
            Instant.now());
    }

    private static Message userMessage(UUID conversationId) {
        return new Message(UUID.randomUUID(), conversationId, "user", "A question.", List.of(),
            List.of(), Instant.now());
    }

    /**
     * Both halves of one contract, because either alone passes while the bug is live.
     *
     * <p>
     * A conversation pinned to a model that has since left {@code app.chat.allowed-models} has no
     * matching {@code <option>}, so the browser selects the FIRST — the picker then displays a
     * model the conversation is not on, and choosing that same model fires no change event, so the
     * conversation cannot even be moved off the retired one. The send path resolves the same way
     * now ({@code ChatMessageService#resolveModelToUse}), so the displayed model IS the one that
     * will answer.
     *
     * <p>
     * The template half is asserted on the source: {@code th:selected} must read
     * {@code effectiveModel}, never {@code settings['model']}. A controller test alone cannot see a
     * template that ignores the attribute, and Thymeleaf reports nothing when it does — every
     * option simply renders unselected, which is the same visible bug.
     */
    @Test
    void theModelPickerShowsTheModelThatWillActuallyAnswerNotTheRetiredStoredOne()
        throws Exception {
        UUID conversationId = UUID.randomUUID();
        Conversation conv = new Conversation(conversationId, UUID.randomUUID(), "T",
            Map.of("model", "gemini-3.6-flash"), Instant.now(), Instant.now(), List.of());
        when(conversationService.getConversation(conversationId)).thenReturn(Optional.of(conv));
        when(conversationService.buildSidebar()).thenReturn(new ConversationSidebar(List.of(),
            Map.of(), List.of(), Map.of(), List.of(), 0, List.of(), UUID.randomUUID()));
        when(messageService.getMessages(conversationId)).thenReturn(List.of());
        when(conversationService.effectiveModel("gemini-3.6-flash")).thenReturn("gemini-3.8-flash");

        Model model = new ConcurrentModel();
        assertEquals("chat/thread", controller.threadView(conversationId, model));

        assertEquals("gemini-3.8-flash", model.getAttribute("effectiveModel"),
            "the picker must be driven by the resolved model, not the raw setting");

        String template = Files.readString(
            Paths.get("src/main/resources/templates/chat/thread.html"), StandardCharsets.UTF_8);
        int selector = template.indexOf("id=\"model-selector\"");
        assertTrue(selector > 0, "model-selector not found in chat/thread.html");
        String block = template.substring(selector, template.indexOf("</select>", selector));
        assertTrue(block.contains("${effectiveModel == modelOpt}"),
            "the model picker must select on effectiveModel: " + block);
        assertFalse(block.contains("settings['model']"),
            "reading the raw setting reintroduces the retired-model divergence: " + block);
    }
}
