package de.palsoftware.yvoke.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.area.TestAreas;
import de.palsoftware.yvoke.area.core.AreaService;
import de.palsoftware.yvoke.chat.api.model.MessageTraceDto;
import de.palsoftware.yvoke.chat.core.model.Conversation;
import de.palsoftware.yvoke.chat.core.model.Message;
import de.palsoftware.yvoke.chat.core.model.MessageTrace;
import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository;
import de.palsoftware.yvoke.chat.core.service.ChatConversationService;
import de.palsoftware.yvoke.chat.core.service.ChatMessageService;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProfile;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProfileService;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProperties;
import de.palsoftware.yvoke.chat.web.ChatAsyncController;
import de.palsoftware.yvoke.chat.web.admin.AdminFeedbackController;
import de.palsoftware.yvoke.chat.web.admin.OrchestratorAdminController;
import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.service.CollectionService;
import de.palsoftware.yvoke.ingest.core.confluence.ConfluenceClientService;
import de.palsoftware.yvoke.ingest.core.confluence.ConfluenceInstance;
import de.palsoftware.yvoke.ingest.core.confluence.ConfluenceInstanceService;
import de.palsoftware.yvoke.ingest.core.confluence.ConfluenceInstanceView;
import de.palsoftware.yvoke.ingest.core.confluence.TokenHealth;
import de.palsoftware.yvoke.ingest.web.admin.ConfluenceConnectorController;
import de.palsoftware.yvoke.ingest.web.admin.ConfluenceInstanceForm;
import de.palsoftware.yvoke.rag.core.service.RagService;
import de.palsoftware.yvoke.rag.prompt.Playbook;
import de.palsoftware.yvoke.rag.prompt.PlaybookService;
import de.palsoftware.yvoke.rag.prompt.SystemPrompt;
import de.palsoftware.yvoke.rag.prompt.SystemPromptService;
import de.palsoftware.yvoke.rag.prompt.SystemPromptType;
import de.palsoftware.yvoke.rag.retrieval.HybridSearch;
import de.palsoftware.yvoke.rag.retrieval.RagAdminViewService;
import de.palsoftware.yvoke.rag.retrieval.RetrievalLogRepository;
import de.palsoftware.yvoke.rag.retrieval.RetrievalTelemetryService;
import de.palsoftware.yvoke.rag.web.admin.RagAdminController;
import de.palsoftware.yvoke.shared.audit.repository.AuditLogRepository;
import de.palsoftware.yvoke.shared.jobengine.model.EnqueueRequest;
import de.palsoftware.yvoke.shared.jobengine.model.EnqueueResult;
import de.palsoftware.yvoke.shared.jobengine.repository.JobRepository;
import de.palsoftware.yvoke.shared.jobengine.service.JobService;
import de.palsoftware.yvoke.shared.user.service.UserService;
import de.palsoftware.yvoke.shared.web.GenerationConcurrencyLimiter;
import de.palsoftware.yvoke.shared.web.admin.AdminController;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

/**
 * Empirical Adversarial Verification Suite for Milestone 4 (Wave 4: Scoped Admin Console
 * Remediation).
 * 
 * Actively stress-tests: 1. Bulk action scoping: sync-all and cancel-queued 2. Cross-area unique
 * key hijack prevention 3. Trace endpoint and feedback scoping by conversation area 4. RAG search
 * debug: area admin with empty collections cannot search or view OIM corpus data
 */
class Milestone4AdversarialChallengeTest {

    private RedirectAttributes redirect;
    private Model model;

    @BeforeEach
    void setUp() {
        redirect = new RedirectAttributesModelMap();
        model = new ConcurrentModel();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String... authorities) {
        SecurityContextHolder.getContext()
            .setAuthentication(new TestingAuthenticationToken("testUser", "testPass", authorities));
    }

    // =========================================================================
    // Challenge 1: Bulk Action Scoping
    // =========================================================================
    @Nested
    @DisplayName("Challenge 1: Bulk Action Scoping")
    class BulkActionScopingTests {

        @Test
        @DisplayName("POST /admin/connectors/confluence/sync-all: Area Admin for AreaA enqueues sync ONLY for AreaA instances")
        void testSyncAll_scopesStrictlyToCallerAdministeredArea() {
            authenticateAs("ROLE_AREA_ADMIN", "AREA_ADMIN_AREAA");

            ConfluenceClientService confluenceClient = mock(ConfluenceClientService.class);
            ConfluenceInstanceService instanceService = mock(ConfluenceInstanceService.class);
            CollectionService collectionService = mock(CollectionService.class);
            AuditLogRepository auditRepo = mock(AuditLogRepository.class);
            JobService jobService = mock(JobService.class);
            UserService userService = mock(UserService.class);

            ConfluenceConnectorController controller =
                new ConfluenceConnectorController(confluenceClient, instanceService,
                    collectionService, auditRepo, jobService, userService);

            UUID instAId = UUID.randomUUID();
            UUID instBId = UUID.randomUUID();
            UUID instADisabledId = UUID.randomUUID();

            ConfluenceInstanceView instA = new ConfluenceInstanceView(instAId, "Wiki A", "wiki-a",
                "https://wiki.area-a.local", "a@test.com", "SPACEA", "1", "public", null, "Col-A",
                "1.0", false, true, TokenHealth.OK);
            ConfluenceInstanceView instB = new ConfluenceInstanceView(instBId, "Wiki B", "wiki-b",
                "https://wiki.area-b.local", "b@test.com", "SPACEB", "2", "public", null, "Col-B",
                "1.0", false, true, TokenHealth.OK);
            ConfluenceInstanceView instADisabled = new ConfluenceInstanceView(instADisabledId,
                "Wiki A Disabled", "wiki-a-dis", "https://wiki.area-a.local", "a@test.com",
                "SPACEA", "3", "public", null, "Col-A", "1.0", false, false, TokenHealth.OK);

            when(instanceService.listInstances()).thenReturn(List.of(instA, instB, instADisabled));

            Collection colA = new Collection(UUID.randomUUID(), "Col-A", "Desc A", List.of("1.0"),
                OffsetDateTime.now(), "AreaA");
            Collection colB = new Collection(UUID.randomUUID(), "Col-B", "Desc B", List.of("1.0"),
                OffsetDateTime.now(), "AreaB");

            when(collectionService.getCollection("Col-A")).thenReturn(Optional.of(colA));
            when(collectionService.getCollection("Col-B")).thenReturn(Optional.of(colB));

            when(jobService.enqueue(any(EnqueueRequest.class)))
                .thenReturn(EnqueueResult.created(UUID.randomUUID()));

            String view = controller.syncAllInstances(redirect);

            assertThat(view).isEqualTo("redirect:/admin/connectors");
            ArgumentCaptor<EnqueueRequest> captor = ArgumentCaptor.forClass(EnqueueRequest.class);
            verify(jobService).enqueue(captor.capture());

            // Assert: Only instA in AreaA was enqueued! instB in AreaB and disabled instA were
            // never touched
            assertThat(captor.getValue().collection()).isEqualTo("Col-A");
            assertThat(redirect.getFlashAttributes().get("success").toString())
                .contains("Sync enqueued for 1 instance(s)");
        }

        @Test
        @DisplayName("POST /admin/jobs/cancel-queued: Area Admin scopes cancelQueued to administered areas")
        void testCancelQueued_areaAdmin_scopedToAdministeredArea() {
            authenticateAs("ROLE_AREA_ADMIN", "AREA_ADMIN_AREAA");

            JobRepository jobRepo = mock(JobRepository.class);
            JobService jobService = mock(JobService.class);
            AuditLogRepository auditRepo = mock(AuditLogRepository.class);
            UserService userService = mock(UserService.class);

            AdminController controller =
                new AdminController(jobRepo, jobService, auditRepo, userService);

            UUID cancelledId = UUID.randomUUID();
            when(jobRepo.cancelQueued(eq("confluence-import"), eq(Set.of("areaa"))))
                .thenReturn(List.of(cancelledId));

            String view = controller.cancelQueuedJobs("confluence-import", redirect);

            assertThat(view).isEqualTo("redirect:/admin/jobs");
            verify(jobRepo).cancelQueued("confluence-import", Set.of("areaa"));
            verify(jobService).publishSnapshot(cancelledId);
        }

        @Test
        @DisplayName("POST /admin/jobs/cancel-queued: Superadmin scopes cancelQueued globally (null area set)")
        void testCancelQueued_superAdmin_scopedGlobally() {
            authenticateAs("ROLE_ADMIN");

            JobRepository jobRepo = mock(JobRepository.class);
            JobService jobService = mock(JobService.class);
            AuditLogRepository auditRepo = mock(AuditLogRepository.class);
            UserService userService = mock(UserService.class);

            AdminController controller =
                new AdminController(jobRepo, jobService, auditRepo, userService);

            UUID cancelledId = UUID.randomUUID();
            when(jobRepo.cancelQueued(eq("confluence-import"), isNull()))
                .thenReturn(List.of(cancelledId));

            String view = controller.cancelQueuedJobs("confluence-import", redirect);

            assertThat(view).isEqualTo("redirect:/admin/jobs");
            verify(jobRepo).cancelQueued("confluence-import", null);
            verify(jobService).publishSnapshot(cancelledId);
        }
    }

    // =========================================================================
    // Challenge 2: Cross-Area Unique Key Hijack Prevention
    // =========================================================================
    @Nested
    @DisplayName("Challenge 2: Cross-Area Unique Key Hijack Prevention")
    class CrossAreaHijackPreventionTests {

        private ConfluenceConnectorController confluenceController;
        private ConfluenceInstanceService confluenceInstanceService;
        private CollectionService collectionService;

        @BeforeEach
        void initConfluence() {
            ConfluenceClientService confluenceClient = mock(ConfluenceClientService.class);
            confluenceInstanceService = mock(ConfluenceInstanceService.class);
            collectionService = mock(CollectionService.class);
            AuditLogRepository auditRepo = mock(AuditLogRepository.class);
            JobService jobService = mock(JobService.class);
            UserService userService = mock(UserService.class);

            confluenceController = new ConfluenceConnectorController(confluenceClient,
                confluenceInstanceService, collectionService, auditRepo, jobService, userService);
        }

        @Test
        @DisplayName("Confluence: Save with ID matching an instance in another area -> 404 NOT FOUND")
        void testConfluence_saveWithCrossAreaId_throws404() {
            authenticateAs("ROLE_AREA_ADMIN", "AREA_ADMIN_AREAA");

            UUID crossAreaId = UUID.randomUUID();
            ConfluenceInstance existing = new ConfluenceInstance(crossAreaId, "Wiki B", "wiki-b",
                "https://wiki.area-b.local", "b@test.com", null, null, "SPACEB", "1", null, null,
                "Col-B", "1.0", false, true, null, null);
            when(confluenceInstanceService.findInstance(crossAreaId))
                .thenReturn(Optional.of(existing));

            Collection colA = new Collection(UUID.randomUUID(), "Col-A", "", List.of("1.0"),
                OffsetDateTime.now(), "AreaA");
            Collection colB = new Collection(UUID.randomUUID(), "Col-B", "", List.of("1.0"),
                OffsetDateTime.now(), "AreaB");
            when(collectionService.getCollection("Col-A")).thenReturn(Optional.of(colA));
            when(collectionService.getCollection("Col-B")).thenReturn(Optional.of(colB));

            ConfluenceInstanceForm form = new ConfluenceInstanceForm(crossAreaId, "Attacker Wiki",
                "attacker-wiki", "https://wiki.area-a.local", "a@test.com", "SPACEA", "1", "public",
                "draft", "Col-A", "1.0", false, true);

            BindingResult br = new BeanPropertyBindingResult(form, "instanceForm");

            assertThatThrownBy(() -> confluenceController.saveInstance(form, br, "tok", redirect))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
        }

        @Test
        @DisplayName("Confluence: Save with name matching an instance in another area -> IllegalArgumentException")
        void testConfluence_saveWithCrossAreaName_throwsIllegalArgumentException() {
            authenticateAs("ROLE_AREA_ADMIN", "AREA_ADMIN_AREAA");

            ConfluenceInstance existing = new ConfluenceInstance(UUID.randomUUID(),
                "Existing Name B", "wiki-b", "https://wiki.area-b.local", "b@test.com", null, null,
                "SPACEB", "1", null, null, "Col-B", "1.0", false, true, null, null);
            when(confluenceInstanceService.findByName("Existing Name B"))
                .thenReturn(Optional.of(existing));

            Collection colA = new Collection(UUID.randomUUID(), "Col-A", "", List.of("1.0"),
                OffsetDateTime.now(), "AreaA");
            Collection colB = new Collection(UUID.randomUUID(), "Col-B", "", List.of("1.0"),
                OffsetDateTime.now(), "AreaB");
            when(collectionService.getCollection("Col-A")).thenReturn(Optional.of(colA));
            when(collectionService.getCollection("Col-B")).thenReturn(Optional.of(colB));

            ConfluenceInstanceForm form = new ConfluenceInstanceForm(null, "Existing Name B",
                "new-slug-a", "https://wiki.area-a.local", "a@test.com", "SPACEA", "1", "public",
                "draft", "Col-A", "1.0", false, true);

            BindingResult br = new BeanPropertyBindingResult(form, "instanceForm");

            assertThatThrownBy(() -> confluenceController.saveInstance(form, br, "tok", redirect))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(
                    "Another Confluence instance in another area already uses the name");
        }

        @Test
        @DisplayName("Confluence: Save with slug matching an instance in another area -> IllegalArgumentException")
        void testConfluence_saveWithCrossAreaSlug_throwsIllegalArgumentException() {
            authenticateAs("ROLE_AREA_ADMIN", "AREA_ADMIN_AREAA");

            ConfluenceInstance existing = new ConfluenceInstance(UUID.randomUUID(), "Wiki B",
                "existing-slug-b", "https://wiki.area-b.local", "b@test.com", null, null, "SPACEB",
                "1", null, null, "Col-B", "1.0", false, true, null, null);
            when(confluenceInstanceService.findBySlug("existing-slug-b"))
                .thenReturn(Optional.of(existing));

            Collection colA = new Collection(UUID.randomUUID(), "Col-A", "", List.of("1.0"),
                OffsetDateTime.now(), "AreaA");
            Collection colB = new Collection(UUID.randomUUID(), "Col-B", "", List.of("1.0"),
                OffsetDateTime.now(), "AreaB");
            when(collectionService.getCollection("Col-A")).thenReturn(Optional.of(colA));
            when(collectionService.getCollection("Col-B")).thenReturn(Optional.of(colB));

            ConfluenceInstanceForm form = new ConfluenceInstanceForm(null, "New Wiki A",
                "existing-slug-b", "https://wiki.area-a.local", "a@test.com", "SPACEA", "1",
                "public", "draft", "Col-A", "1.0", false, true);

            BindingResult br = new BeanPropertyBindingResult(form, "instanceForm");

            assertThatThrownBy(() -> confluenceController.saveInstance(form, br, "tok", redirect))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(
                    "Another Confluence instance in another area already uses the slug");
        }

        @Test
        @DisplayName("Playbook: Save with name matching playbook in another area -> 403 FORBIDDEN")
        void testPlaybook_saveWithCrossAreaName_throws403() {
            authenticateAs("ROLE_AREA_ADMIN", "AREA_ADMIN_AREAA");

            PlaybookService playbookService = mock(PlaybookService.class);
            SystemPromptService promptService = mock(SystemPromptService.class);
            AreaService areaService = TestAreas.withAreas("AreaA", "AreaB");

            RagAdminController ragController =
                new RagAdminController(mock(HybridSearch.class), mock(RetrievalLogRepository.class),
                    mock(RagAdminViewService.class), mock(CollectionService.class), playbookService,
                    promptService, mock(RagService.class), new ObjectMapper(),
                    mock(RetrievalTelemetryService.class), 200, 20, areaService);

            Playbook existingB = new Playbook("cross-pb", "Cross PB", "desc", "tmpl", List.of(),
                false, "specialist", false, Instant.now(), Instant.now(), false, "AreaB");
            when(playbookService.getPlaybook("cross-pb")).thenReturn(Optional.of(existingB));

            assertThatThrownBy(() -> ragController.createOrUpdatePlaybook("cross-pb", "New Title",
                "desc", "tmpl", null, false, "specialist", false, "AreaA", null, 1, redirect))
                .isInstanceOf(ResponseStatusException.class).satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(rse.getReason())
                        .contains("Existing playbook belongs to another area");
                });
        }

        @Test
        @DisplayName("Playbook: Import with name matching playbook in another area -> 403 FORBIDDEN")
        void testPlaybook_importWithCrossAreaName_throws403() throws IOException {
            authenticateAs("ROLE_AREA_ADMIN", "AREA_ADMIN_AREAA");

            PlaybookService playbookService = mock(PlaybookService.class);
            SystemPromptService promptService = mock(SystemPromptService.class);
            AreaService areaService = TestAreas.withAreas("AreaA", "AreaB");

            RagAdminController ragController =
                new RagAdminController(mock(HybridSearch.class), mock(RetrievalLogRepository.class),
                    mock(RagAdminViewService.class), mock(CollectionService.class), playbookService,
                    promptService, mock(RagService.class), new ObjectMapper(),
                    mock(RetrievalTelemetryService.class), 200, 20, areaService);

            Playbook existingB = new Playbook("cross-pb", "Cross PB", "desc", "tmpl", List.of(),
                false, "specialist", false, Instant.now(), Instant.now(), false, "AreaB");
            when(playbookService.getPlaybook("cross-pb")).thenReturn(Optional.of(existingB));

            String markdown =
                "---\nname: cross-pb\ntitle: Cross PB\narea: AreaA\n---\nPlaybook prompt";
            MockMultipartFile file = new MockMultipartFile("file", "cross-pb.md", "text/markdown",
                markdown.getBytes(StandardCharsets.UTF_8));

            assertThatThrownBy(() -> ragController.importPlaybook(file, "AreaA", redirect))
                .isInstanceOf(ResponseStatusException.class).satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(rse.getReason())
                        .contains("Existing playbook belongs to another area");
                });
        }

        @Test
        @DisplayName("System Prompt: Save with name matching prompt in another area -> 403 FORBIDDEN")
        void testSystemPrompt_saveWithCrossAreaKey_throws403() {
            authenticateAs("ROLE_AREA_ADMIN", "AREA_ADMIN_AREAA");

            PlaybookService playbookService = mock(PlaybookService.class);
            SystemPromptService promptService = mock(SystemPromptService.class);
            AreaService areaService = TestAreas.withAreas("AreaA", "AreaB");

            RagAdminController ragController =
                new RagAdminController(mock(HybridSearch.class), mock(RetrievalLogRepository.class),
                    mock(RagAdminViewService.class), mock(CollectionService.class), playbookService,
                    promptService, mock(RagService.class), new ObjectMapper(),
                    mock(RetrievalTelemetryService.class), 200, 20, areaService);

            SystemPrompt existingB = new SystemPrompt("cross-prompt", SystemPromptType.CHAT,
                "prompt", "desc", Instant.now(), Instant.now(), false, "AreaB");
            when(promptService.getPrompt("cross-prompt")).thenReturn(Optional.of(existingB));

            assertThatThrownBy(() -> ragController.createOrUpdatePrompt("cross-prompt",
                SystemPromptType.CHAT, "desc", "new text", "AreaA", redirect))
                .isInstanceOf(ResponseStatusException.class).satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(rse.getReason()).contains("Existing prompt belongs to another area");
                });
        }

        @Test
        @DisplayName("System Prompt: Import with name matching prompt in another area -> 403 FORBIDDEN")
        void testSystemPrompt_importWithCrossAreaKey_throws403() throws IOException {
            authenticateAs("ROLE_AREA_ADMIN", "AREA_ADMIN_AREAA");

            PlaybookService playbookService = mock(PlaybookService.class);
            SystemPromptService promptService = mock(SystemPromptService.class);
            AreaService areaService = TestAreas.withAreas("AreaA", "AreaB");

            RagAdminController ragController =
                new RagAdminController(mock(HybridSearch.class), mock(RetrievalLogRepository.class),
                    mock(RagAdminViewService.class), mock(CollectionService.class), playbookService,
                    promptService, mock(RagService.class), new ObjectMapper(),
                    mock(RetrievalTelemetryService.class), 200, 20, areaService);

            SystemPrompt existingB = new SystemPrompt("cross-prompt", SystemPromptType.CHAT,
                "prompt", "desc", Instant.now(), Instant.now(), false, "AreaB");
            when(promptService.getPrompt("cross-prompt")).thenReturn(Optional.of(existingB));

            String markdown = "---\nname: cross-prompt\ntype: CHAT\narea: AreaA\n---\nPrompt body";
            MockMultipartFile file = new MockMultipartFile("file", "cross-prompt.md",
                "text/markdown", markdown.getBytes(StandardCharsets.UTF_8));

            assertThatThrownBy(() -> ragController.importPrompt(file, "AreaA", redirect))
                .isInstanceOf(ResponseStatusException.class).satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(rse.getReason()).contains("Existing prompt belongs to another area");
                });
        }

        @Test
        @DisplayName("Orchestrator Profile: Save with name matching profile in another area -> 403 FORBIDDEN")
        void testOrchestratorProfile_saveWithCrossAreaName_throws403() {
            authenticateAs("ROLE_AREA_ADMIN", "AREA_ADMIN_AREAA");

            OrchestratorProfileService profileService = mock(OrchestratorProfileService.class);
            PlaybookService playbookService = mock(PlaybookService.class);
            ChatConversationService convService = mock(ChatConversationService.class);
            OrchestratorProperties props = new OrchestratorProperties(3, 8, null, List.of());
            AreaService areaService = TestAreas.withAreas("AreaA", "AreaB");

            OrchestratorAdminController controller = new OrchestratorAdminController(profileService,
                playbookService, convService, props, areaService);

            OrchestratorProfile existingB = new OrchestratorProfile("profile-b", 2, 8, "orch",
                "rev", List.of(), null, null, null, null, null, null, false, null, null, "AreaB");
            when(profileService.getProfile("profile-b")).thenReturn(Optional.of(existingB));

            assertThatThrownBy(() -> controller.createOrUpdateProfile("profile-b", 2, 8, "orch",
                "rev", List.of(), null, null, null, null, null, null, false, "AreaA", redirect))
                .isInstanceOf(ResponseStatusException.class).satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(rse.getReason()).contains("Cannot overwrite profile in area: AreaB");
                });
        }
    }

    // =========================================================================
    // Challenge 3: Trace Endpoint & Feedback Area Scoping
    // =========================================================================
    @Nested
    @DisplayName("Challenge 3: Trace Endpoint & Feedback Area Scoping")
    class TraceAndFeedbackScopingTests {

        @Test
        @DisplayName("Trace: Area Admin for AreaA accessing AreaA conversation trace -> 200 OK")
        void testTrace_areaAdminAccessingOwnAreaConversation_allowed() {
            authenticateAs("ROLE_AREA_ADMIN", "AREA_ADMIN_AREAA");

            ChatMessageService chatMessageService = mock(ChatMessageService.class);
            ChatConversationService chatConvService = mock(ChatConversationService.class);
            GenerationConcurrencyLimiter limiter = mock(GenerationConcurrencyLimiter.class);

            ChatAsyncController controller =
                new ChatAsyncController(chatMessageService, chatConvService, limiter);

            UUID convId = UUID.randomUUID();
            UUID msgId = UUID.randomUUID();
            Conversation conv = new Conversation(convId, null, "Title", Map.of(), null, null,
                List.of(), "web", "AreaA");
            when(chatConvService.verifyOwnership(convId, true)).thenReturn(conv);

            Message msg = new Message(msgId, convId, "assistant", "Answer", List.of(), List.of(),
                Instant.now());
            MessageTrace trace = MessageTrace.forSingle(msg, List.of());
            when(chatMessageService.getMessageTrace(convId, msgId)).thenReturn(Optional.of(trace));

            ResponseEntity<MessageTraceDto> response = controller.getMessageTrace(convId, msgId);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().messageId()).isEqualTo(msgId);
        }

        @Test
        @DisplayName("Trace: Area Admin for AreaA accessing AreaB conversation trace -> 404 NOT FOUND")
        void testTrace_areaAdminAccessingOtherAreaConversation_throws404() {
            authenticateAs("ROLE_AREA_ADMIN", "AREA_ADMIN_AREAA");

            ChatMessageService chatMessageService = mock(ChatMessageService.class);
            ChatConversationService chatConvService = mock(ChatConversationService.class);
            GenerationConcurrencyLimiter limiter = mock(GenerationConcurrencyLimiter.class);

            ChatAsyncController controller =
                new ChatAsyncController(chatMessageService, chatConvService, limiter);

            UUID convId = UUID.randomUUID();
            UUID msgId = UUID.randomUUID();
            Conversation conv = new Conversation(convId, null, "Title", Map.of(), null, null,
                List.of(), "web", "AreaB");
            when(chatConvService.verifyOwnership(convId, true)).thenReturn(conv);

            assertThatThrownBy(() -> controller.getMessageTrace(convId, msgId))
                .isInstanceOf(ResponseStatusException.class).satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(rse.getReason()).contains("Conversation not found");
                });

            verify(chatMessageService, never()).getMessageTrace(any(), any());
        }

        @Test
        @DisplayName("Trace: Regular user without admin roles accessing trace -> 403 AccessDeniedException")
        void testTrace_regularUser_throwsAccessDeniedException() {
            authenticateAs("ROLE_USER", "AREA_USER_AREAA");

            ChatMessageService chatMessageService = mock(ChatMessageService.class);
            ChatConversationService chatConvService = mock(ChatConversationService.class);
            GenerationConcurrencyLimiter limiter = mock(GenerationConcurrencyLimiter.class);

            ChatAsyncController controller =
                new ChatAsyncController(chatMessageService, chatConvService, limiter);

            UUID convId = UUID.randomUUID();
            UUID msgId = UUID.randomUUID();

            assertThatThrownBy(() -> controller.getMessageTrace(convId, msgId))
                .isInstanceOf(AccessDeniedException.class).hasMessageContaining(
                    "Access denied: trace endpoint requires ROLE_ADMIN or ROLE_AREA_ADMIN");
        }

        @Test
        @DisplayName("Feedback toggle-reviewed: Area Admin mutating feedback in AreaA -> allowed")
        void testFeedbackToggleReviewed_sameArea_allowed() {
            authenticateAs("ROLE_AREA_ADMIN", "AREA_ADMIN_AREAA");

            ChatAdminQueryRepository queryRepo = mock(ChatAdminQueryRepository.class);
            AdminFeedbackController controller = new AdminFeedbackController(queryRepo);

            UUID feedbackId = UUID.randomUUID();
            when(queryRepo.findFeedbackArea(feedbackId)).thenReturn(Optional.of("AreaA"));

            String view = controller.toggleReviewed(feedbackId, "true", model);

            assertThat(view).isEqualTo("admin/feedback :: reviewed-cell");
            verify(queryRepo).setFeedbackReviewed(feedbackId, true);
        }

        @Test
        @DisplayName("Feedback toggle-reviewed: Area Admin mutating feedback in AreaB -> 404 NOT FOUND")
        void testFeedbackToggleReviewed_otherArea_throws404() {
            authenticateAs("ROLE_AREA_ADMIN", "AREA_ADMIN_AREAA");

            ChatAdminQueryRepository queryRepo = mock(ChatAdminQueryRepository.class);
            AdminFeedbackController controller = new AdminFeedbackController(queryRepo);

            UUID feedbackId = UUID.randomUUID();
            when(queryRepo.findFeedbackArea(feedbackId)).thenReturn(Optional.of("AreaB"));

            assertThatThrownBy(() -> controller.toggleReviewed(feedbackId, "true", model))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));

            verify(queryRepo, never()).setFeedbackReviewed(any(), anyBoolean());
        }

        @Test
        @DisplayName("Feedback update notes: Area Admin mutating notes in AreaA -> allowed")
        void testFeedbackUpdateNotes_sameArea_allowed() {
            authenticateAs("ROLE_AREA_ADMIN", "AREA_ADMIN_AREAA");

            ChatAdminQueryRepository queryRepo = mock(ChatAdminQueryRepository.class);
            AdminFeedbackController controller = new AdminFeedbackController(queryRepo);

            UUID feedbackId = UUID.randomUUID();
            when(queryRepo.findFeedbackArea(feedbackId)).thenReturn(Optional.of("AreaA"));

            String view = controller.updateNotes(feedbackId, "Review completed", model);

            assertThat(view).isEqualTo("admin/feedback :: notes-cell");
            verify(queryRepo).setFeedbackNotes(feedbackId, "Review completed");
        }

        @Test
        @DisplayName("Feedback update notes: Area Admin mutating notes in AreaB -> 404 NOT FOUND")
        void testFeedbackUpdateNotes_otherArea_throws404() {
            authenticateAs("ROLE_AREA_ADMIN", "AREA_ADMIN_AREAA");

            ChatAdminQueryRepository queryRepo = mock(ChatAdminQueryRepository.class);
            AdminFeedbackController controller = new AdminFeedbackController(queryRepo);

            UUID feedbackId = UUID.randomUUID();
            when(queryRepo.findFeedbackArea(feedbackId)).thenReturn(Optional.of("AreaB"));

            assertThatThrownBy(() -> controller.updateNotes(feedbackId, "Malicious note", model))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));

            verify(queryRepo, never()).setFeedbackNotes(any(), anyString());
        }
    }

    // =========================================================================
    // Challenge 4: RAG Search & Prompt Scoping
    // =========================================================================
    @Nested
    @DisplayName("Challenge 4: RAG Search & Prompt Scoping")
    class RagSearchAndPromptScopingTests {

        @Test
        @DisplayName("Search: Area Admin with empty collections cannot search or view OIM corpus data")
        void testSearch_areaAdminWithEmptyCollections_activeCollectionIsNullAndSearchNeverExecuted() {
            authenticateAs("ROLE_AREA_ADMIN", "AREA_ADMIN_EMPTYAREA");

            HybridSearch hybridSearch = mock(HybridSearch.class);
            CollectionService collectionService = mock(CollectionService.class);
            AreaService areaService = TestAreas.withAreas("EmptyArea", "OIM");

            // Empty collections for this admin
            when(collectionService.listForAdminUser()).thenReturn(Collections.emptyList());

            RagAdminController controller =
                new RagAdminController(hybridSearch, mock(RetrievalLogRepository.class),
                    mock(RagAdminViewService.class), collectionService, mock(PlaybookService.class),
                    mock(SystemPromptService.class), mock(RagService.class), new ObjectMapper(),
                    mock(RetrievalTelemetryService.class), 200, 20, areaService);

            String view =
                controller.testSearch("sensitive query", null, null, 8, true, true, model);

            assertThat(view).isEqualTo("admin/search");
            // Model must have activeCollection == null
            assertThat(model.getAttribute("collection")).isNull();
            // hybridSearch must NEVER be invoked!
            verify(hybridSearch, never()).searchWithId(any(), any());
            // Results attribute must NOT be populated with any corpus data
            assertThat(model.getAttribute("results")).isNull();
        }

        @Test
        @DisplayName("Search: Area Admin requesting cross-area collection (e.g. OIM) directly -> 404 NOT FOUND")
        void testSearch_areaAdminAccessingCrossAreaCollection_throws404() {
            authenticateAs("ROLE_AREA_ADMIN", "AREA_ADMIN_EMPTYAREA");

            HybridSearch hybridSearch = mock(HybridSearch.class);
            CollectionService collectionService = mock(CollectionService.class);
            AreaService areaService = TestAreas.withAreas("EmptyArea", "OIM");

            Collection oimCol = new Collection(UUID.randomUUID(), "OIM", "OIM collection",
                List.of(), OffsetDateTime.now(), "OIM");
            when(collectionService.getCollection("OIM")).thenReturn(Optional.of(oimCol));

            RagAdminController controller =
                new RagAdminController(hybridSearch, mock(RetrievalLogRepository.class),
                    mock(RagAdminViewService.class), collectionService, mock(PlaybookService.class),
                    mock(SystemPromptService.class), mock(RagService.class), new ObjectMapper(),
                    mock(RetrievalTelemetryService.class), 200, 20, areaService);

            assertThatThrownBy(
                () -> controller.testSearch("query", "OIM", null, 8, true, true, model))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));

            verify(hybridSearch, never()).searchWithId(any(), any());
        }

        @Test
        @DisplayName("Prompt: Area Admin attempting to set active default chat prompt -> 403 FORBIDDEN")
        void testUpdateActiveDefaultPrompt_areaAdmin_forbidden() {
            authenticateAs("ROLE_AREA_ADMIN", "AREA_ADMIN_AREAA");

            SystemPromptService systemPromptService = mock(SystemPromptService.class);
            AreaService areaService = TestAreas.withAreas("AreaA");

            RagAdminController controller = new RagAdminController(mock(HybridSearch.class),
                mock(RetrievalLogRepository.class), mock(RagAdminViewService.class),
                mock(CollectionService.class), mock(PlaybookService.class), systemPromptService,
                mock(RagService.class), new ObjectMapper(), mock(RetrievalTelemetryService.class),
                200, 20, areaService);

            assertThatThrownBy(
                () -> controller.updateActiveChatPrompt("oim-access-governance", redirect))
                .isInstanceOf(ResponseStatusException.class).satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(rse.getReason())
                        .contains("Only superadmin can change active chat prompt");
                });

            verify(systemPromptService, never()).setDefaultChatPromptName(any());
        }

        @Test
        @DisplayName("Prompt: Superadmin setting active default chat prompt -> allowed")
        void testUpdateActiveDefaultPrompt_superAdmin_allowed() {
            authenticateAs("ROLE_ADMIN");

            SystemPromptService systemPromptService = mock(SystemPromptService.class);
            AreaService areaService = TestAreas.withAreas("AreaA");

            RagAdminController controller = new RagAdminController(mock(HybridSearch.class),
                mock(RetrievalLogRepository.class), mock(RagAdminViewService.class),
                mock(CollectionService.class), mock(PlaybookService.class), systemPromptService,
                mock(RagService.class), new ObjectMapper(), mock(RetrievalTelemetryService.class),
                200, 20, areaService);

            String view = controller.updateActiveChatPrompt("oim-access-governance", redirect);

            assertThat(view).isEqualTo("redirect:/admin/prompts");
            verify(systemPromptService).setDefaultChatPromptName("oim-access-governance");
        }
    }
}
