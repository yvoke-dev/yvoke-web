package de.palsoftware.yvoke.shared.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = "app.security.mock=true")
public class SecurityMockGatingIT {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    public void setup() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    public void testPublicEndpointsAreAccessibleWithoutAuth() throws Exception {
        // Actuator runs on the dedicated management port (9090), so it is not mapped on the main
        // servlet context under test. A 404 (rather than 401/403/redirect-to-login) confirms the
        // security chain permits /actuator/** without authentication.
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/css/index.css"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/login"))
                .andExpect(status().isOk());
    }

    @Test
    public void testProtectedEndpointsRedirectToLoginUnauthenticated() throws Exception {
        mockMvc.perform(get("/chat"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));

        mockMvc.perform(get("/admin/documents"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    public void testFormLoginSuccessForUser() throws Exception {
        mockMvc.perform(post("/login").with(csrf())
                        .param("username", "user")
                        .param("password", "password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/chat"));
    }

    @Test
    public void testFormLoginSuccessForAdmin() throws Exception {
        mockMvc.perform(post("/login").with(csrf())
                        .param("username", "admin")
                        .param("password", "password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin"));
    }

    @Test
    public void testFormLoginRedirectsToSavedRequest() throws Exception {
        org.springframework.mock.web.MockHttpSession session = (org.springframework.mock.web.MockHttpSession) mockMvc.perform(get("/chat").header("Accept", "text/html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"))
                .andReturn().getRequest().getSession();

        mockMvc.perform(post("/login").with(csrf())
                        .session(session)
                        .param("username", "admin")
                        .param("password", "password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/chat?continue"));
    }

    @Test
    public void testLogoutRedirectsToLoggedOutPage() throws Exception {
        mockMvc.perform(post("/logout").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/logged-out"));
    }

    @Test
    public void testLoggedOutPageIsPublic() throws Exception {
        mockMvc.perform(get("/logged-out"))
                .andExpect(status().isOk());
    }

    @Test
    public void testContentSecurityPolicyHeaderIsPresentOnSessionChain() throws Exception {
        // SEC-12: the interactive (cookie/session) chain must ship a CSP. We assert the high-value,
        // zero-breakage directives are present.
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")))
                .andExpect(header().string("Content-Security-Policy", containsString("object-src 'none'")))
                .andExpect(header().string("Content-Security-Policy", containsString("base-uri 'self'")));
    }

    @Test
    public void testFormLoginFailure() throws Exception {
        mockMvc.perform(post("/login").with(csrf())
                        .param("username", "unknown")
                        .param("password", "password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    public void testUserAccessGating() throws Exception {
        mockMvc.perform(get("/chat")
                        .with(user("user").roles("USER")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/admin/documents")
                        .with(user("user").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    public void testAdminAccessGating() throws Exception {
        mockMvc.perform(get("/admin/documents")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());

        // Admin has ROLE_USER usually, but we test if the route requires USER
        mockMvc.perform(get("/chat")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/chat")
                        .with(user("admin").roles("ADMIN", "USER")))
                .andExpect(status().isOk());
    }

    @Test
    public void testProcessDocumentKgRequiresCsrfAndAdmin() throws Exception {
        UUID docId = UUID.randomUUID();

        // 1. Unauthenticated but with CSRF -> 302 redirect to /login
        mockMvc.perform(post("/admin/documents/" + docId + "/process-kg").with(csrf()))
                .andExpect(status().is3xxRedirection());

        // 2. Authenticated but no CSRF -> 403 Forbidden
        mockMvc.perform(post("/admin/documents/" + docId + "/process-kg")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());

        // 3. Authenticated as User with CSRF -> 403 Forbidden
        mockMvc.perform(post("/admin/documents/" + docId + "/process-kg").with(csrf())
                        .with(user("user").roles("USER")))
                .andExpect(status().isForbidden());

        // 4. Authenticated as Admin with CSRF -> since document doesn't exist, expects 404 (Not Found) rather than 403 (Forbidden)
        mockMvc.perform(post("/admin/documents/" + docId + "/process-kg").with(csrf())
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound());
    }
}
