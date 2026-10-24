package de.palsoftware.yvoke.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.palsoftware.yvoke.shared.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

class SecurityConfigRouteGuardAdversarialTest {

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mockMvc;

    @RestController
    static class DummyTestController {
        @GetMapping("/admin/costs")
        public String costs() {
            return "costs";
        }

        @GetMapping("/admin/costs/{sub}")
        public String costsSub(@PathVariable String sub) {
            return "costs-" + sub;
        }

        @GetMapping("/admin/pricing")
        public String pricing() {
            return "pricing";
        }

        @GetMapping("/admin/pricing/{sub}")
        public String pricingSub(@PathVariable String sub) {
            return "pricing-" + sub;
        }

        @GetMapping("/admin/audit")
        public String audit() {
            return "audit";
        }

        @GetMapping("/admin/audit/{sub}")
        public String auditSub(@PathVariable String sub) {
            return "audit-" + sub;
        }

        @GetMapping("/admin/areas/delete")
        public String deleteArea() {
            return "delete-area";
        }

        @GetMapping("/admin/prompts/active")
        public String activePrompt() {
            return "active-prompt";
        }

        @GetMapping("/admin/collections")
        public String collections() {
            return "collections";
        }

        @GetMapping("/admin/documents")
        public String documents() {
            return "documents";
        }

        @GetMapping("/admin")
        public String adminRoot() {
            return "admin-root";
        }

        @GetMapping("/chat/{id}/messages/{messageId}/trace")
        public String trace(@PathVariable String id, @PathVariable String messageId) {
            return "trace-" + id + "-" + messageId;
        }

        @GetMapping("/chat/{id}")
        public String chat(@PathVariable String id) {
            return "chat-" + id;
        }

        @GetMapping("/chat")
        public String chatRoot() {
            return "chat-root";
        }

        @GetMapping("/api/ingest/v1/upload")
        public String ingestUpload() {
            return "ingest-upload";
        }

        @GetMapping("/api/document/v1")
        public String documentApi() {
            return "document-api";
        }

        @GetMapping("/api/jobs/v1/{id}")
        public String jobsApi(@PathVariable String id) {
            return "jobs-api-" + id;
        }
    }

    @Configuration
    @EnableWebSecurity
    @Import(SecurityConfig.class)
    static class TestSecurityWiring {
        @Bean
        DummyTestController dummyTestController() {
            return new DummyTestController();
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
        env.setProperty("app.security.area-api-keys",
            "{\"OIM\":\"oim-secret\",\"PingID\":\"pingid-secret\"}");
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
        SecurityContextHolder.clearContext();
        if (context != null) {
            context.close();
        }
    }

    // =========================================================================
    // 1. Superadmin-only paths: Require ROLE_ADMIN
    // /admin/costs, /admin/costs/**, /admin/pricing, /admin/pricing/**,
    // /admin/audit, /admin/audit/**, /admin/areas/delete, /admin/prompts/active
    // =========================================================================

    @ParameterizedTest
    @ValueSource(
        strings = {"/admin/costs", "/admin/costs/explorer", "/admin/pricing", "/admin/pricing/edit",
            "/admin/audit", "/admin/audit/logs", "/admin/areas/delete", "/admin/prompts/active"})
    @DisplayName("Superadmin-only routes forbid plain ROLE_USER")
    void superadminRoutesForbidPlainUser(String path) throws Exception {
        mockMvc.perform(get(path).with(user("regularUser").roles("USER")))
            .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(
        strings = {"/admin/costs", "/admin/costs/explorer", "/admin/pricing", "/admin/pricing/edit",
            "/admin/audit", "/admin/audit/logs", "/admin/areas/delete", "/admin/prompts/active"})
    @DisplayName("Superadmin-only routes strictly forbid ROLE_AREA_ADMIN without ROLE_ADMIN")
    void superadminRoutesForbidAreaAdmin(String path) throws Exception {
        mockMvc.perform(get(path).with(user("areaAdmin").roles("AREA_ADMIN", "USER")))
            .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(
        strings = {"/admin/costs", "/admin/costs/explorer", "/admin/pricing", "/admin/pricing/edit",
            "/admin/audit", "/admin/audit/logs", "/admin/areas/delete", "/admin/prompts/active"})
    @DisplayName("Superadmin-only routes permit ROLE_ADMIN")
    void superadminRoutesPermitAdmin(String path) throws Exception {
        mockMvc.perform(get(path).with(user("superAdmin").roles("ADMIN", "USER")))
            .andExpect(status().isOk());
    }

    // =========================================================================
    // 2. Area Admin routes: Permit both ROLE_ADMIN and ROLE_AREA_ADMIN
    // /admin/**, /admin, /admin/collections, /admin/documents
    // =========================================================================

    @ParameterizedTest
    @ValueSource(strings = {"/admin", "/admin/collections", "/admin/documents"})
    @DisplayName("General admin routes permit ROLE_AREA_ADMIN")
    void generalAdminRoutesPermitAreaAdmin(String path) throws Exception {
        mockMvc.perform(get(path).with(user("areaAdmin").roles("AREA_ADMIN", "USER")))
            .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/admin", "/admin/collections", "/admin/documents"})
    @DisplayName("General admin routes permit ROLE_ADMIN")
    void generalAdminRoutesPermitAdmin(String path) throws Exception {
        mockMvc.perform(get(path).with(user("superAdmin").roles("ADMIN", "USER")))
            .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/admin", "/admin/collections", "/admin/documents"})
    @DisplayName("General admin routes forbid plain ROLE_USER")
    void generalAdminRoutesForbidUser(String path) throws Exception {
        mockMvc.perform(get(path).with(user("regularUser").roles("USER")))
            .andExpect(status().isForbidden());
    }

    // =========================================================================
    // 3. Trace route: /chat/*/messages/*/trace
    // Must precede /chat/** and require ADMIN or AREA_ADMIN (USER forbidden)
    // =========================================================================

    @Test
    @DisplayName("Trace endpoint strictly forbids plain ROLE_USER even though it starts with /chat/")
    void traceEndpointForbidsPlainUser() throws Exception {
        mockMvc.perform(
            get("/chat/conv-123/messages/msg-456/trace").with(user("regularUser").roles("USER")))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Trace endpoint permits ROLE_AREA_ADMIN through route guard")
    void traceEndpointPermitsAreaAdmin() throws Exception {
        mockMvc.perform(get("/chat/conv-123/messages/msg-456/trace")
            .with(user("areaAdmin").roles("AREA_ADMIN", "USER"))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Trace endpoint permits ROLE_ADMIN through route guard")
    void traceEndpointPermitsAdmin() throws Exception {
        mockMvc.perform(get("/chat/conv-123/messages/msg-456/trace")
            .with(user("superAdmin").roles("ADMIN", "USER"))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Regular /chat/{id} endpoint permits plain ROLE_USER")
    void regularChatEndpointPermitsPlainUser() throws Exception {
        mockMvc.perform(get("/chat/conv-123").with(user("regularUser").roles("USER")))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Unauthenticated requests to protected endpoints redirect to login")
    void unauthenticatedRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/admin/costs")).andExpect(status().is3xxRedirection());

        mockMvc.perform(get("/chat/conv-123/messages/msg-456/trace"))
            .andExpect(status().is3xxRedirection());

        mockMvc.perform(get("/chat/conv-123")).andExpect(status().is3xxRedirection());
    }

    // =========================================================================
    // 4. API Key Chain: /api/ingest/**, /api/document/**, /api/jobs/**
    // Valid global key -> ROLE_INGEST + AREA_INGEST_*
    // Valid area key -> ROLE_INGEST + AREA_INGEST_<AREA>
    // Invalid key -> 401 "Invalid API Key"
    // Plain USER session -> 403 Forbidden
    // =========================================================================

    @ParameterizedTest
    @ValueSource(strings = {"/api/ingest/v1/upload", "/api/document/v1", "/api/jobs/v1/123"})
    @DisplayName("API Key routes authenticate valid global key as api-key-client with ROLE_INGEST and AREA_INGEST_*")
    void apiKeyGlobalKeyAuthenticates(String path) throws Exception {
        mockMvc.perform(get(path).header("X-API-Key", "test-global-key"))
            .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/ingest/v1/upload", "/api/document/v1", "/api/jobs/v1/123"})
    @DisplayName("API Key routes authenticate valid area key as api-key-client:OIM with ROLE_INGEST and AREA_INGEST_OIM")
    void apiKeyAreaKeyAuthenticates(String path) throws Exception {
        mockMvc.perform(get(path).header("X-API-Key", "oim-secret")).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/ingest/v1/upload", "/api/document/v1", "/api/jobs/v1/123"})
    @DisplayName("API Key routes reject invalid key with 401 and verbatim 'Invalid API Key'")
    void apiKeyInvalidKeyRejectedWith401(String path) throws Exception {
        mockMvc.perform(get(path).header("X-API-Key", "wrong-secret"))
            .andExpect(status().isUnauthorized())
            .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                .isEqualTo("Invalid API Key"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/ingest/v1/upload", "/api/document/v1", "/api/jobs/v1/123"})
    @DisplayName("API Key routes forbid plain ROLE_USER session")
    void apiKeyRoutesForbidPlainUserSession(String path) throws Exception {
        mockMvc.perform(get(path).with(user("regularUser").roles("USER")))
            .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/ingest/v1/upload", "/api/document/v1", "/api/jobs/v1/123"})
    @DisplayName("API Key routes permit browser admin session (ROLE_ADMIN)")
    void apiKeyRoutesPermitAdminSession(String path) throws Exception {
        mockMvc.perform(get(path).with(user("superAdmin").roles("ADMIN")))
            .andExpect(status().isOk());
    }
}
