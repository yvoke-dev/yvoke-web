package de.palsoftware.yvoke.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.palsoftware.yvoke.area.core.Area;
import de.palsoftware.yvoke.area.core.AreaMembers;
import de.palsoftware.yvoke.area.core.AreaService;
import de.palsoftware.yvoke.area.core.AreaWithMembers;
import de.palsoftware.yvoke.chat.api.DesktopSyncController;
import de.palsoftware.yvoke.chat.api.model.AreaDto;
import de.palsoftware.yvoke.chat.api.model.AreaPackageDto;
import de.palsoftware.yvoke.chat.core.service.DesktopSyncService;
import de.palsoftware.yvoke.chat.orchestration.DesktopOrchestratorRunService;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProfileService;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProperties;
import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.service.CollectionService;
import de.palsoftware.yvoke.document.api.DocumentApiController;
import de.palsoftware.yvoke.document.api.model.DocumentDto;
import de.palsoftware.yvoke.document.core.model.DocumentDetails;
import de.palsoftware.yvoke.document.core.repository.DocumentRepository;
import de.palsoftware.yvoke.ingest.api.IngestApiController;
import de.palsoftware.yvoke.ingest.core.service.IngestService;
import de.palsoftware.yvoke.rag.prompt.PlaybookService;
import de.palsoftware.yvoke.rag.prompt.SystemPromptService;
import de.palsoftware.yvoke.shared.jobengine.api.JobApiController;
import de.palsoftware.yvoke.shared.jobengine.api.dto.JobDto;
import de.palsoftware.yvoke.shared.jobengine.model.EnqueueRequest;
import de.palsoftware.yvoke.shared.jobengine.model.EnqueueResult;
import de.palsoftware.yvoke.shared.jobengine.model.IngestionJob;
import de.palsoftware.yvoke.shared.jobengine.model.JobStatus;
import de.palsoftware.yvoke.shared.jobengine.repository.JobRepository;
import de.palsoftware.yvoke.shared.jobengine.service.JobProgressBroker;
import de.palsoftware.yvoke.shared.jobengine.service.JobService;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Empirical Adversarial Verification for Milestone 3 (Wave 3). Tests Desktop Sync API Gating and
 * Machine APIs (Ingest, Document, Job) Gating across single-area, multi-area union, global roles,
 * and per-area API keys.
 */
class Wave3AdversarialSecurityTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static void setAuthentication(String... authorities) {
        List<SimpleGrantedAuthority> auths =
            List.of(authorities).stream().map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("test-client", "n/a", auths));
    }

    private static void setRoleClaims(List<String> claims) {
        Authentication auth = new UsernamePasswordAuthenticationToken("test-client", "n/a",
            AreaAccessContext.mapRoleClaimsToAuthorities(claims, "Admin", "User"));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // =========================================================================
    // 1. Desktop Sync API Gating
    // =========================================================================
    @Nested
    @DisplayName("Desktop Sync API Gating")
    class DesktopSyncApiGatingTests {

        private DesktopSyncService syncService;
        private SystemPromptService systemPromptService;
        private PlaybookService playbookService;
        private OrchestratorProperties orchestratorProperties;
        private DesktopOrchestratorRunService orchestratorRunService;
        private AreaService areaService;
        private DesktopSyncController controller;

        private final Area areaOim =
            new Area("OIM", "OIM KB", "desc", false, "p1", "pb1", "prof1", null, null);
        private final Area areaPing =
            new Area("PingID", "PingID KB", "desc", false, "p2", "pb2", "prof2", null, null);
        private final Area areaFin =
            new Area("Finance", "Finance KB", "desc", false, "p3", "pb3", "prof3", null, null);

        @BeforeEach
        void setUp() {
            syncService = mock(DesktopSyncService.class);
            systemPromptService = mock(SystemPromptService.class);
            playbookService = mock(PlaybookService.class);
            orchestratorProperties = new OrchestratorProperties(2, 8, null, List.of());
            orchestratorRunService = mock(DesktopOrchestratorRunService.class);
            areaService = mock(AreaService.class);

            controller = new DesktopSyncController(syncService, systemPromptService,
                playbookService, orchestratorProperties, null, orchestratorRunService, areaService);
        }

        @Test
        @DisplayName("GET /api/chat/v1/areas: single-area user sees only authorized area")
        void listAreasSingleAreaUser() {
            setRoleClaims(List.of("OIM.User"));
            when(areaService.listAccessibleForCurrentUser()).thenAnswer(inv -> AreaAccessContext
                .filterByUserArea(List.of(areaOim, areaPing, areaFin), Area::name));

            List<AreaDto> result = controller.listAreas();
            assertThat(result).extracting(AreaDto::name).containsExactly("OIM");
        }

        @Test
        @DisplayName("GET /api/chat/v1/areas: multi-role union sees all authorized areas")
        void listAreasMultiRoleUnion() {
            setRoleClaims(List.of("OIM.User", "Finance.User"));
            when(areaService.listAccessibleForCurrentUser()).thenAnswer(inv -> AreaAccessContext
                .filterByUserArea(List.of(areaOim, areaPing, areaFin), Area::name));

            List<AreaDto> result = controller.listAreas();
            assertThat(result).extracting(AreaDto::name).containsExactlyInAnyOrder("OIM",
                "Finance");
        }

        @Test
        @DisplayName("GET /api/chat/v1/areas: global User sees all areas")
        void listAreasGlobalUser() {
            setRoleClaims(List.of("User"));
            when(areaService.listAccessibleForCurrentUser()).thenAnswer(inv -> AreaAccessContext
                .filterByUserArea(List.of(areaOim, areaPing, areaFin), Area::name));

            List<AreaDto> result = controller.listAreas();
            assertThat(result).extracting(AreaDto::name).containsExactlyInAnyOrder("OIM", "PingID",
                "Finance");
        }

        @Test
        @DisplayName("GET /api/chat/v1/areas: global Admin sees all areas")
        void listAreasGlobalAdmin() {
            setRoleClaims(List.of("Admin"));
            when(areaService.listAccessibleForCurrentUser()).thenAnswer(inv -> AreaAccessContext
                .filterByUserArea(List.of(areaOim, areaPing, areaFin), Area::name));

            List<AreaDto> result = controller.listAreas();
            assertThat(result).extracting(AreaDto::name).containsExactlyInAnyOrder("OIM", "PingID",
                "Finance");
        }

        @Test
        @DisplayName("GET /api/chat/v1/areas: user without area claims sees empty list")
        void listAreasNoAreaClaims() {
            setAuthentication("ROLE_USER");
            when(areaService.listAccessibleForCurrentUser()).thenAnswer(inv -> AreaAccessContext
                .filterByUserArea(List.of(areaOim, areaPing, areaFin), Area::name));

            List<AreaDto> result = controller.listAreas();
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("GET /api/chat/v1/areas: null areaService returns empty list gracefully")
        void listAreasNullAreaService() {
            DesktopSyncController noAreaController =
                new DesktopSyncController(syncService, systemPromptService, playbookService,
                    orchestratorProperties, null, orchestratorRunService, null);
            List<AreaDto> result = noAreaController.listAreas();
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("GET /api/chat/v1/areas/{area}/package: authorized area user receives package")
        void getAreaPackageAuthorized() {
            setRoleClaims(List.of("OIM.User"));
            AreaMembers members = new AreaMembers(List.of("p1"), List.of("col-oim"), List.of("pb1"),
                List.of("prof1"));
            when(areaService.findByNameForUser("OIM")).thenReturn(Optional.of(areaOim));
            when(areaService.findAreaWithMembers("OIM"))
                .thenReturn(Optional.of(new AreaWithMembers(areaOim, members)));

            AreaPackageDto pkg = controller.getAreaPackage("OIM");
            assertThat(pkg.name()).isEqualTo("OIM");
            assertThat(pkg.playbooks()).containsExactly("pb1");
            assertThat(pkg.collections()).containsExactly("col-oim");
        }

        @Test
        @DisplayName("GET /api/chat/v1/areas/{area}/package: unauthorized area yields 404")
        void getAreaPackageUnauthorizedYields404() {
            setRoleClaims(List.of("OIM.User"));
            when(areaService.findByNameForUser("PingID")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> controller.getAreaPackage("PingID"))
                .isInstanceOf(ResponseStatusException.class).satisfies(e -> {
                    ResponseStatusException rse = (ResponseStatusException) e;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(rse.getReason()).contains("Area not found: PingID");
                });
        }

        @Test
        @DisplayName("GET /api/chat/v1/areas/{area}/package: non-existent area yields 404")
        void getAreaPackageNonExistentYields404() {
            setRoleClaims(List.of("Admin"));
            when(areaService.findByNameForUser("NoSuchArea")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> controller.getAreaPackage("NoSuchArea"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
        }

        @Test
        @DisplayName("GET /api/chat/v1/areas/{area}/package: null areaService throws 404")
        void getAreaPackageNullAreaServiceThrows404() {
            DesktopSyncController noAreaController =
                new DesktopSyncController(syncService, systemPromptService, playbookService,
                    orchestratorProperties, null, orchestratorRunService, null);
            assertThatThrownBy(() -> noAreaController.getAreaPackage("OIM"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
        }
    }

    // =========================================================================
    // 2. Machine APIs Gating: DocumentApiController
    // =========================================================================
    @Nested
    @DisplayName("Machine APIs: DocumentApiController Gating")
    class DocumentApiControllerGatingTests {

        private DocumentRepository documentRepository;
        private CollectionService collectionService;
        private DocumentApiController controller;

        private final Collection colOim = new Collection(UUID.randomUUID(), "oim-col", "desc",
            List.of("1.0"), OffsetDateTime.now(), "OIM");
        private final Collection colPing = new Collection(UUID.randomUUID(), "ping-col", "desc",
            List.of("1.0"), OffsetDateTime.now(), "PingID");
        private final Collection colUnscoped = new Collection(UUID.randomUUID(), "unscoped-col",
            "desc", List.of("1.0"), OffsetDateTime.now(), null);

        @BeforeEach
        void setUp() {
            documentRepository = mock(DocumentRepository.class);
            collectionService = mock(CollectionService.class);
            controller = new DocumentApiController(documentRepository, collectionService);
            when(collectionService.listCollections())
                .thenReturn(List.of(colOim, colPing, colUnscoped));
        }

        @Test
        @DisplayName("DocumentApi: per-area API key for OIM can list OIM documents")
        void oimApiKeyCanAccessOimCollection() {
            setAuthentication("ROLE_INGEST", "AREA_INGEST_OIM");
            UUID docId = UUID.randomUUID();
            when(documentRepository.listByCollectionAndTag("oim-col", "1.0")).thenReturn(
                List.of(new DocumentDetails(docId, colOim.id(), "OIM", "manual", "Doc 1",
                    Map.of("source_file", "f.md"), "completed", 1L, false, Instant.now())));

            List<DocumentDto> docs = controller.listDocuments("oim-col", "1.0");
            assertThat(docs).hasSize(1);
            assertThat(docs.get(0).title()).isEqualTo("Doc 1");
        }

        @Test
        @DisplayName("DocumentApi: per-area API key for OIM gets 404 on PingID collection")
        void oimApiKeyGets404OnPingCollection() {
            setAuthentication("ROLE_INGEST", "AREA_INGEST_OIM");

            assertThatThrownBy(() -> controller.listDocuments("ping-col", "1.0"))
                .isInstanceOf(ResponseStatusException.class).satisfies(e -> {
                    ResponseStatusException rse = (ResponseStatusException) e;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(rse.getReason()).isEqualTo("Collection 'ping-col' does not exist.");
                });
            verify(documentRepository, never()).listByCollectionAndTag(any(), any());
        }

        @Test
        @DisplayName("DocumentApi: global API key (AREA_INGEST_*) can list any collection")
        void globalApiKeyCanAccessAnyCollection() {
            setAuthentication("ROLE_INGEST", "AREA_INGEST_*");
            when(documentRepository.listByCollectionAndTag("ping-col", "1.0"))
                .thenReturn(List.of());

            List<DocumentDto> docs = controller.listDocuments("ping-col", "1.0");
            assertThat(docs).isEmpty();
            verify(documentRepository).listByCollectionAndTag("ping-col", "1.0");
        }

        @Test
        @DisplayName("DocumentApi: Area Admin authority can list documents in administered area")
        void areaAdminCanAccessCollection() {
            setAuthentication("ROLE_AREA_ADMIN", "AREA_ADMIN_OIM");
            when(documentRepository.listByCollectionAndTag("oim-col", "1.0")).thenReturn(List.of());

            List<DocumentDto> docs = controller.listDocuments("oim-col", "1.0");
            assertThat(docs).isEmpty();
        }

        @Test
        @DisplayName("DocumentApi: unscoped (null area) collection is accessible to ingest role")
        void unscopedCollectionIsAccessible() {
            setAuthentication("ROLE_INGEST", "AREA_INGEST_OIM");
            when(documentRepository.listByCollectionAndTag("unscoped-col", "1.0"))
                .thenReturn(List.of());

            List<DocumentDto> docs = controller.listDocuments("unscoped-col", "1.0");
            assertThat(docs).isEmpty();
        }

        @Test
        @DisplayName("DocumentApi: non-existent collection returns 404")
        void nonExistentCollectionReturns404() {
            setAuthentication("ROLE_INGEST", "AREA_INGEST_*");

            assertThatThrownBy(() -> controller.listDocuments("non-existent", "1.0"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
        }
    }

    // =========================================================================
    // 3. Machine APIs Gating: JobApiController
    // =========================================================================
    @Nested
    @DisplayName("Machine APIs: JobApiController Gating")
    class JobApiControllerGatingTests {

        private JobService jobService;
        private JobRepository jobRepository;
        private JobProgressBroker progressBroker;
        private JobApiController controller;

        private final UUID jobIdOim = UUID.randomUUID();
        private final UUID jobIdPing = UUID.randomUUID();

        @BeforeEach
        void setUp() {
            jobService = mock(JobService.class);
            jobRepository = mock(JobRepository.class);
            progressBroker = mock(JobProgressBroker.class);
            controller = new JobApiController(jobService, jobRepository, progressBroker);

            when(jobRepository.findCollectionArea("oim-col")).thenReturn(Optional.of("OIM"));
            when(jobRepository.findCollectionArea("ping-col")).thenReturn(Optional.of("PingID"));

            when(jobRepository.findJobArea(jobIdOim)).thenReturn(Optional.of("OIM"));
            when(jobRepository.findJobArea(jobIdPing)).thenReturn(Optional.of("PingID"));

            IngestionJob jobOim = new IngestionJob(jobIdOim, "standard", "ref1", "1.0", "oim-col",
                JobStatus.RUNNING, null, 50, 1, null, null, OffsetDateTime.now(), null, null);
            IngestionJob jobPing =
                new IngestionJob(jobIdPing, "standard", "ref2", "1.0", "ping-col",
                    JobStatus.RUNNING, null, 50, 1, null, null, OffsetDateTime.now(), null, null);

            when(jobRepository.findById(jobIdOim)).thenReturn(Optional.of(jobOim));
            when(jobRepository.findById(jobIdPing)).thenReturn(Optional.of(jobPing));
        }

        @Test
        @DisplayName("JobApi: enqueue with OIM key succeeds on OIM collection")
        void enqueueOimKeyOnOimCollection() {
            setAuthentication("ROLE_INGEST", "AREA_INGEST_OIM");
            UUID newJobId = UUID.randomUUID();
            EnqueueRequest req = new EnqueueRequest("standard", "ref", "1.0", "oim-col");
            when(jobService.enqueue(req)).thenReturn(EnqueueResult.created(newJobId));

            ResponseEntity<Map<String, UUID>> response = controller.enqueue(req);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(response.getBody()).containsEntry("id", newJobId);
        }

        @Test
        @DisplayName("JobApi: enqueue with OIM key yields 404 on PingID collection")
        void enqueueOimKeyOnPingCollectionYields404() {
            setAuthentication("ROLE_INGEST", "AREA_INGEST_OIM");
            EnqueueRequest req = new EnqueueRequest("standard", "ref", "1.0", "ping-col");

            assertThatThrownBy(() -> controller.enqueue(req))
                .isInstanceOf(ResponseStatusException.class).satisfies(e -> {
                    ResponseStatusException rse = (ResponseStatusException) e;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(rse.getReason()).isEqualTo("Collection not found: ping-col");
                });
            verify(jobService, never()).enqueue(any());
        }

        @Test
        @DisplayName("JobApi: enqueue with global key succeeds on PingID collection")
        void enqueueGlobalKeyOnPingCollection() {
            setAuthentication("ROLE_INGEST", "AREA_INGEST_*");
            UUID newJobId = UUID.randomUUID();
            EnqueueRequest req = new EnqueueRequest("standard", "ref", "1.0", "ping-col");
            when(jobService.enqueue(req)).thenReturn(EnqueueResult.created(newJobId));

            ResponseEntity<Map<String, UUID>> response = controller.enqueue(req);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        }

        @Test
        @DisplayName("JobApi: GET /{id} with OIM key succeeds on OIM job")
        void getJobOimKeyOnOimJob() {
            setAuthentication("ROLE_INGEST", "AREA_INGEST_OIM");
            JobDto dto = controller.get(jobIdOim);
            assertThat(dto.id()).isEqualTo(jobIdOim);
            assertThat(dto.collectionName()).isEqualTo("oim-col");
        }

        @Test
        @DisplayName("JobApi: GET /{id} with OIM key yields 404 on PingID job")
        void getJobOimKeyOnPingJobYields404() {
            setAuthentication("ROLE_INGEST", "AREA_INGEST_OIM");

            assertThatThrownBy(() -> controller.get(jobIdPing))
                .isInstanceOf(ResponseStatusException.class).satisfies(e -> {
                    ResponseStatusException rse = (ResponseStatusException) e;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(rse.getReason()).isEqualTo("Unknown job id: " + jobIdPing);
                });
        }

        @Test
        @DisplayName("JobApi: GET /{id} with global key succeeds on PingID job")
        void getJobGlobalKeyOnPingJob() {
            setAuthentication("ROLE_INGEST", "AREA_INGEST_*");
            JobDto dto = controller.get(jobIdPing);
            assertThat(dto.id()).isEqualTo(jobIdPing);
        }

        @Test
        @DisplayName("JobApi: GET /{id}/progress with OIM key yields 404 on PingID job")
        void progressOimKeyOnPingJobYields404() {
            setAuthentication("ROLE_INGEST", "AREA_INGEST_OIM");

            assertThatThrownBy(() -> controller.progress(jobIdPing))
                .isInstanceOf(ResponseStatusException.class).satisfies(e -> {
                    ResponseStatusException rse = (ResponseStatusException) e;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(rse.getReason()).isEqualTo("Unknown job id: " + jobIdPing);
                });
            verify(progressBroker, never()).subscribe(any());
        }

        @Test
        @DisplayName("JobApi: GET /{id}/progress with OIM key succeeds on OIM job")
        void progressOimKeyOnOimJob() {
            setAuthentication("ROLE_INGEST", "AREA_INGEST_OIM");
            SseEmitter emitter = new SseEmitter();
            when(progressBroker.subscribe(jobIdOim)).thenReturn(emitter);

            SseEmitter res = controller.progress(jobIdOim);
            assertThat(res).isNotNull();
            verify(progressBroker).subscribe(jobIdOim);
        }
    }

    // =========================================================================
    // 4. Machine APIs Gating: IngestApiController & IngestService
    // =========================================================================
    @Nested
    @DisplayName("Machine APIs: IngestApiController & IngestService Gating")
    class IngestApiGatingTests {

        private IngestService ingestService;
        private IngestApiController controller;

        @BeforeEach
        void setUp() {
            ingestService = mock(IngestService.class);
            controller = new IngestApiController(ingestService);
        }

        @Test
        @DisplayName("IngestApi: processDocumentKg forwards parameters and wraps 202")
        void processDocumentKgDelegates() {
            setAuthentication("ROLE_INGEST", "AREA_INGEST_OIM");
            UUID docId = UUID.randomUUID();
            UUID jobId = UUID.randomUUID();
            when(ingestService.processKg(docId, null, null, null, "oim-col", "1.0", null, "OIM"))
                .thenReturn(EnqueueResult.created(jobId));

            ResponseEntity<Map<String, UUID>> response = controller.processDocumentKg(docId, null,
                null, null, "oim-col", "1.0", null, "OIM");
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(response.getBody()).containsEntry("id", jobId);
        }

        @Test
        @DisplayName("IngestApi: uploadAndEnqueue throws 404 when IngestService rejects unauthorized collection")
        void uploadUnauthorizedAreaCollectionThrows404() {
            setAuthentication("ROLE_INGEST", "AREA_INGEST_OIM");
            MockMultipartFile file =
                new MockMultipartFile("file", "test.md", "text/markdown", "content".getBytes());
            when(ingestService.uploadAndEnqueue(file, "ping-col", null, "standard", null, null,
                null, null))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Collection 'ping-col' does not exist."));

            assertThatThrownBy(() -> controller.uploadAndEnqueue(file, "ping-col", null, "standard",
                null, null, null, null)).isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    ResponseStatusException rse = (ResponseStatusException) e;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(rse.getReason()).isEqualTo("Collection 'ping-col' does not exist.");
                });
        }

        @Test
        @DisplayName("IngestApi: uploadAndEnqueue throws 403 when creating collection in unauthorized area")
        void uploadCreateUnauthorizedAreaThrows403() {
            setAuthentication("ROLE_INGEST", "AREA_INGEST_OIM");
            MockMultipartFile file =
                new MockMultipartFile("file", "test.md", "text/markdown", "content".getBytes());
            when(ingestService.uploadAndEnqueue(file, "new-col", null, "standard", null, null, null,
                "PingID"))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Forbidden: cannot ingest into area 'PingID'"));

            assertThatThrownBy(() -> controller.uploadAndEnqueue(file, "new-col", null, "standard",
                null, null, null, "PingID")).isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    ResponseStatusException rse = (ResponseStatusException) e;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(rse.getReason())
                        .isEqualTo("Forbidden: cannot ingest into area 'PingID'");
                });
        }
    }
}
