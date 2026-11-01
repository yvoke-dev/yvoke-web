package de.palsoftware.yvoke.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.area.TestAreas;
import de.palsoftware.yvoke.area.core.AreaService;
import de.palsoftware.yvoke.area.web.admin.AreaAdminController;
import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.service.CollectionService;
import de.palsoftware.yvoke.collection.web.admin.CollectionAdminController;
import de.palsoftware.yvoke.document.core.model.DocumentAdminViews.ChunkDetail;
import de.palsoftware.yvoke.document.core.model.DocumentAdminViews.ChunkDetailPage;
import de.palsoftware.yvoke.document.core.model.DocumentAdminViews.DocumentDetail;
import de.palsoftware.yvoke.document.core.model.DocumentAdminViews.DocumentDetailPage;
import de.palsoftware.yvoke.document.core.repository.DocumentRepository;
import de.palsoftware.yvoke.document.core.service.DocumentAdminViewService;
import de.palsoftware.yvoke.document.web.admin.DocumentAdminController;
import de.palsoftware.yvoke.jsonobject.core.model.JsonObject;
import de.palsoftware.yvoke.jsonobject.core.service.JsonObjectService;
import de.palsoftware.yvoke.jsonobject.web.admin.JsonObjectAdminController;
import de.palsoftware.yvoke.kg.core.repository.KgGraphReadRepository;
import de.palsoftware.yvoke.kg.core.repository.KgWriteRepository;
import de.palsoftware.yvoke.kg.core.service.KgAdminViewService;
import de.palsoftware.yvoke.kg.core.service.KgConsolidator;
import de.palsoftware.yvoke.kg.web.admin.KgAdminController;
import de.palsoftware.yvoke.shared.audit.repository.AuditLogRepository;
import de.palsoftware.yvoke.shared.jobengine.service.JobService;
import de.palsoftware.yvoke.shared.user.service.UserService;
import de.palsoftware.yvoke.tag.core.service.TagService;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.server.ResponseStatusException;

/**
 * Empirical Adversarial Verification Suite for Milestone 4 (Wave 4: Scoped Admin Console
 * Remediation). Authored by Challenger 1. Verifies all 5 mandatory adversarial challenges: 1.
 * Cross-area collection name hijack prevention. 2. Dual-area collection move checks (requires admin
 * on source AND target area). 3. Area Admin creation/renaming restrictions. 4. Document / chunk /
 * JSON object / KG cross-area refusals (404 opaque refusal). 5. Superadmin route guards (403 on
 * costs, pricing, audit, areas/delete, prompts/active).
 */
public class Milestone4Challenger1AdversarialTest {

    @AfterEach
    void tearDownSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // =========================================================================
    // Challenge 1: Cross-Area Collection Name Hijack Prevention
    // =========================================================================
    @Nested
    @DisplayName("Challenge 1: Cross-Area Collection Name Hijack Prevention")
    class Challenge1CrossAreaCollectionNameHijack {

        private CollectionService collectionService;
        private TagService tagService;
        private CollectionAdminController controller;

        @BeforeEach
        void setUp() {
            collectionService = mock(CollectionService.class);
            tagService = mock(TagService.class);
            controller = new CollectionAdminController(collectionService, tagService,
                TestAreas.withAreas("AreaA", "AreaB"));
        }

        @Test
        @DisplayName("Attempt to create collection in Area A with name existing in Area B is rejected and does not hijack existing collection")
        void createCollectionWithExistingNameInDifferentAreaIsRejected() {
            SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
                "areaAdminA", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_AREAA"));

            Collection existingInB = new Collection(UUID.randomUUID(), "SharedName", "desc",
                List.of(), OffsetDateTime.now(), "AreaB");
            when(collectionService.findByName("SharedName")).thenReturn(Optional.of(existingInB));

            MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

            assertThrows(Exception.class, () -> mvc.perform(
                post("/admin/collections").param("name", "SharedName").param("area", "AreaA")));

            verify(collectionService, never()).createCollection(eq("SharedName"), any(),
                eq("AreaA"));
        }

        @Test
        @DisplayName("Attempt to create collection in an area caller is not authorized to administer is forbidden")
        void createCollectionInUnauthorizedAreaIsForbidden() throws Exception {
            SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
                "areaAdminA", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_AREAA"));

            MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

            mvc.perform(post("/admin/collections").param("name", "NewColl").param("area", "AreaB"))
                .andExpect(status().isForbidden());

            verify(collectionService, never()).createCollection(any(), any(), any());
        }
    }

    // =========================================================================
    // Challenge 2: Dual-Area Collection Move Checks
    // =========================================================================
    @Nested
    @DisplayName("Challenge 2: Dual-Area Collection Move Checks")
    class Challenge2DualAreaCollectionMoveChecks {

        private CollectionService collectionService;
        private TagService tagService;
        private CollectionAdminController controller;

        @BeforeEach
        void setUp() {
            collectionService = mock(CollectionService.class);
            tagService = mock(TagService.class);
            controller = new CollectionAdminController(collectionService, tagService,
                TestAreas.withAreas("AreaA", "AreaB"));
        }

        @Test
        @DisplayName("Caller with only Area_A.Admin attempting to move collection from Area A to Area B is rejected with 403 Forbidden")
        void moveFromAreaAToAreaB_whenOnlyAdminOfSource_failsWithForbidden() throws Exception {
            SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
                "adminA", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_AREAA"));

            Collection colA = new Collection(UUID.randomUUID(), "CollA", "desc", List.of(),
                OffsetDateTime.now(), "AreaA");
            when(collectionService.findByName("CollA")).thenReturn(Optional.of(colA));

            MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

            mvc.perform(
                post("/admin/collections/area").param("name", "CollA").param("area", "AreaB"))
                .andExpect(status().isForbidden());

            verify(collectionService, never()).moveCollection(any(), any());
        }

        @Test
        @DisplayName("Caller with only Area_B.Admin attempting to move collection from Area A to Area B is rejected with 403 Forbidden")
        void moveFromAreaAToAreaB_whenOnlyAdminOfTarget_failsWithForbidden() throws Exception {
            SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
                "adminB", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_AREAB"));

            Collection colA = new Collection(UUID.randomUUID(), "CollA", "desc", List.of(),
                OffsetDateTime.now(), "AreaA");
            when(collectionService.findByName("CollA")).thenReturn(Optional.of(colA));

            MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

            mvc.perform(
                post("/admin/collections/area").param("name", "CollA").param("area", "AreaB"))
                .andExpect(status().isForbidden());

            verify(collectionService, never()).moveCollection(any(), any());
        }

        @Test
        @DisplayName("Caller with both Area_A.Admin and Area_B.Admin attempting to move collection from Area A to Area B is allowed")
        void moveFromAreaAToAreaB_whenAdminOfBothAreas_succeeds() throws Exception {
            SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
                "dualAdmin", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_AREAA", "AREA_ADMIN_AREAB"));

            Collection colA = new Collection(UUID.randomUUID(), "CollA", "desc", List.of(),
                OffsetDateTime.now(), "AreaA");
            when(collectionService.findByName("CollA")).thenReturn(Optional.of(colA));
            when(collectionService.moveCollection("CollA", "AreaB")).thenReturn("AreaB");

            MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

            mvc.perform(
                post("/admin/collections/area").param("name", "CollA").param("area", "AreaB"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/collections")).andExpect(
                    flash().attribute("success", "Collection 'CollA' moved to area 'AreaB'."));

            verify(collectionService).moveCollection("CollA", "AreaB");
        }
    }

    // =========================================================================
    // Challenge 3: Area Admin Creation/Renaming Restrictions
    // =========================================================================
    @Nested
    @DisplayName("Challenge 3: Area Admin Creation/Renaming Restrictions")
    class Challenge3AreaAdminCreationRenamingRestrictions {

        private AreaService areaService;
        private AreaAdminController controller;
        private MockMvc mvc;

        @BeforeEach
        void setUp() {
            areaService = mock(AreaService.class);
            controller = new AreaAdminController(areaService);
            mvc = MockMvcBuilders.standaloneSetup(controller).build();
        }

        @Test
        @DisplayName("Caller with Area_A.Admin attempting POST /admin/areas to create a new area is rejected with 403 Forbidden")
        void areaAdminCannotCreateArea() throws Exception {
            SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
                "adminA", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_AREAA"));

            mvc.perform(post("/admin/areas").param("name", "NewArea"))
                .andExpect(status().isForbidden());

            verify(areaService, never()).saveArea(any(), any(), any(), any(), any(Boolean.class),
                any(), any(), any());
        }

        @Test
        @DisplayName("Caller with Area_A.Admin attempting POST /admin/areas to rename Area A is rejected with 400 Bad Request")
        void areaAdminCannotRenameArea() throws Exception {
            SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
                "adminA", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_AREAA"));

            mvc.perform(
                post("/admin/areas").param("originalName", "AreaA").param("name", "RenamedAreaA"))
                .andExpect(status().isBadRequest());

            verify(areaService, never()).saveArea(any(), any(), any(), any(), any(Boolean.class),
                any(), any(), any());
        }

        @Test
        @DisplayName("Caller with Area_A.Admin attempting POST /admin/areas/delete is rejected with 403 Forbidden")
        void areaAdminCannotDeleteArea() throws Exception {
            SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
                "adminA", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_AREAA"));

            mvc.perform(post("/admin/areas/delete").param("name", "AreaA"))
                .andExpect(status().isForbidden());

            verify(areaService, never()).deleteArea(any());
        }

        @Test
        @DisplayName("Caller with Area_A.Admin updating settings for Area A without renaming succeeds")
        void areaAdminUpdatingSettingsSucceeds() throws Exception {
            SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
                "adminA", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_AREAA"));

            mvc.perform(post("/admin/areas").param("originalName", "AreaA").param("name", "AreaA")
                .param("title", "New Title").param("description", "Updated desc"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/areas"))
                .andExpect(flash().attribute("success", "Area 'AreaA' saved."));

            verify(areaService).saveArea("AreaA", "AreaA", "New Title", "Updated desc", false, null,
                null, null);
        }

        @Test
        @DisplayName("Superadmin can create and rename areas successfully")
        void superAdminCanCreateAndRenameArea() throws Exception {
            SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("superAdmin", "pass", "ROLE_ADMIN"));

            mvc.perform(post("/admin/areas").param("name", "BrandNewArea"))
                .andExpect(status().is3xxRedirection());
            verify(areaService).saveArea(null, "BrandNewArea", null, null, false, null, null, null);

            mvc.perform(
                post("/admin/areas").param("originalName", "OldName").param("name", "NewName"))
                .andExpect(status().is3xxRedirection());
            verify(areaService).saveArea("OldName", "NewName", null, null, false, null, null, null);
        }
    }

    // =========================================================================
    // Challenge 4: Document / Chunk / JSON Object / KG Cross-Area Refusals (404)
    // =========================================================================
    @Nested
    @DisplayName("Challenge 4: Document / Chunk / JSON Object / KG Cross-Area Refusals (404)")
    class Challenge4CrossAreaRefusals {

        @Test
        @DisplayName("Area Admin for Area A attempting GET /admin/documents/{id} for document in Area B receives 404 NOT FOUND")
        void getDocumentDetails_crossArea_throws404() {
            SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
                "adminA", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_AREAA"));

            DocumentAdminViewService docViewService = mock(DocumentAdminViewService.class);
            CollectionService colService = mock(CollectionService.class);
            DocumentAdminController controller = new DocumentAdminController(
                mock(DocumentRepository.class), docViewService, colService, mock(TagService.class),
                mock(JobService.class), mock(AuditLogRepository.class), mock(UserService.class));

            UUID docId = UUID.randomUUID();
            DocumentDetail detail = new DocumentDetail(docId, "Secret Doc", "CollB", "standard",
                List.of(), "completed", Instant.now(), "{}");
            DocumentDetailPage page = new DocumentDetailPage(detail, 1, List.of(), List.of());
            when(docViewService.documentDetailPage(docId)).thenReturn(Optional.of(page));

            Collection colB = new Collection(UUID.randomUUID(), "CollB", "desc", List.of(),
                OffsetDateTime.now(), "AreaB");
            when(colService.getCollection("CollB")).thenReturn(Optional.of(colB));

            Model model = new ConcurrentModel();
            assertThrows(ResponseStatusException.class,
                () -> controller.getDocumentDetails(docId, model));
        }

        @Test
        @DisplayName("Area Admin for Area A attempting GET /admin/chunks/{id} for chunk in Area B receives 404 NOT FOUND")
        void getChunkDetails_crossArea_throws404() {
            SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
                "adminA", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_AREAA"));

            DocumentAdminViewService docViewService = mock(DocumentAdminViewService.class);
            CollectionService colService = mock(CollectionService.class);
            DocumentAdminController controller = new DocumentAdminController(
                mock(DocumentRepository.class), docViewService, colService, mock(TagService.class),
                mock(JobService.class), mock(AuditLogRepository.class), mock(UserService.class));

            UUID chunkId = UUID.randomUUID();
            ChunkDetail detail = new ChunkDetail(chunkId, UUID.randomUUID(), "CollB", "v1",
                "standard", "h1", 0, 1, List.of(), "secret chunk text", "{}");
            ChunkDetailPage page = new ChunkDetailPage(detail, true, List.of());
            when(docViewService.chunkDetailPage(chunkId.toString())).thenReturn(Optional.of(page));

            Collection colB = new Collection(UUID.randomUUID(), "CollB", "desc", List.of(),
                OffsetDateTime.now(), "AreaB");
            when(colService.getCollection("CollB")).thenReturn(Optional.of(colB));

            Model model = new ConcurrentModel();
            assertThrows(ResponseStatusException.class,
                () -> controller.getChunkDetails(chunkId, model));
        }

        @Test
        @DisplayName("Area Admin for Area A attempting GET /admin/json-objects specifying collection in Area B receives 404 NOT FOUND")
        void getJsonObjects_crossArea_throws404() {
            SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
                "adminA", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_AREAA"));

            CollectionService colService = mock(CollectionService.class);
            JsonObjectService jsonObjectService = mock(JsonObjectService.class);
            JsonObjectAdminController controller =
                new JsonObjectAdminController(jsonObjectService, colService, new ObjectMapper());

            Collection colB = new Collection(UUID.randomUUID(), "CollB", "desc", List.of(),
                OffsetDateTime.now(), "AreaB");
            when(colService.getCollection("CollB")).thenReturn(Optional.of(colB));

            Model model = new ConcurrentModel();
            assertThrows(ResponseStatusException.class,
                () -> controller.listObjects("CollB", null, null, null, null, 0, 20, model));
        }

        @Test
        @DisplayName("Area Admin for Area A attempting GET /admin/kg/view specifying collection in Area B receives 404 NOT FOUND")
        void getKgView_crossArea_throws404() {
            SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
                "adminA", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_AREAA"));

            CollectionService colService = mock(CollectionService.class);
            KgAdminViewService kgAdminViewService = mock(KgAdminViewService.class);
            KgAdminController controller =
                new KgAdminController(kgAdminViewService, mock(KgGraphReadRepository.class),
                    mock(KgWriteRepository.class), mock(KgConsolidator.class),
                    mock(AuditLogRepository.class), mock(UserService.class), colService);

            Collection colB = new Collection(UUID.randomUUID(), "CollB", "desc", List.of(),
                OffsetDateTime.now(), "AreaB");
            when(colService.getCollection("CollB")).thenReturn(Optional.of(colB));

            Model model = new ConcurrentModel();
            assertThrows(ResponseStatusException.class,
                () -> controller.viewKg("CollB", null, null, null, null, 2, 0, model));
        }
    }

    // =========================================================================
    // Challenge 5: Superadmin Route Guards (403 Forbidden)
    // =========================================================================
    @Nested
    @DisplayName("Challenge 5: Superadmin Route Guards")
    class Challenge5SuperadminRouteGuards {

        private AnnotationConfigWebApplicationContext context;
        private MockMvc mockMvc;

        @RestController
        static class TestAdminEndpoints {
            @GetMapping("/admin/costs")
            public String costs() {
                return "costs";
            }

            @GetMapping("/admin/pricing")
            public String pricing() {
                return "pricing";
            }

            @GetMapping("/admin/audit")
            public String audit() {
                return "audit";
            }

            @GetMapping("/admin/areas/delete")
            public String deleteArea() {
                return "delete";
            }

            @GetMapping("/admin/prompts/active")
            public String activePrompt() {
                return "prompt";
            }
        }

        @Configuration
        @EnableWebSecurity
        @Import(SecurityConfig.class)
        static class TestSecurityWiring {
            @Bean
            TestAdminEndpoints testAdminEndpoints() {
                return new TestAdminEndpoints();
            }

            @Bean
            UserService userService() {
                return mock(UserService.class);
            }
        }

        @BeforeEach
        void setUp() {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles("test");
            env.setProperty("app.security.mock", "true");
            env.setProperty("app.security.api-key", "test-global-key");
            env.setProperty("app.security.area-api-keys", "{\"AreaA\":\"secretA\"}");
            env.setProperty("app.security.roles.admin-claim", "Admin");
            env.setProperty("app.security.roles.user-claim", "User");
            env.setProperty("app.security.mcp.allowed-audience", "api://test-aud");
            env.setProperty("app.security.mcp.required-scope", "api://test-aud/scope");
            env.setProperty("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                "https://example/keys");
            env.setProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                "https://example/issuer");
            env.setProperty("app.security.mcp.resource-url", "http://localhost:8080/mcp");

            MockServletContext servletContext = new MockServletContext();
            context = new AnnotationConfigWebApplicationContext();
            context.setEnvironment(env);
            context.setServletContext(servletContext);
            context.register(TestSecurityWiring.class);
            context.refresh();

            mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        }

        @AfterEach
        void tearDown() {
            if (context != null) {
                context.close();
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {"/admin/costs", "/admin/pricing", "/admin/audit",
            "/admin/areas/delete", "/admin/prompts/active"})
        @DisplayName("Area Admin is rejected with 403 Forbidden from superadmin-only endpoints")
        void superadminEndpoints_forbidAreaAdmin(String path) throws Exception {
            mockMvc.perform(get(path).with(user("areaAdminA").roles("AREA_ADMIN", "USER")))
                .andExpect(status().isForbidden());
        }

        @ParameterizedTest
        @ValueSource(strings = {"/admin/costs", "/admin/pricing", "/admin/audit",
            "/admin/areas/delete", "/admin/prompts/active"})
        @DisplayName("Superadmin with ROLE_ADMIN is allowed on superadmin endpoints")
        void superadminEndpoints_permitSuperAdmin(String path) throws Exception {
            mockMvc.perform(get(path).with(user("superAdmin").roles("ADMIN", "USER")))
                .andExpect(status().isOk());
        }
    }
}
