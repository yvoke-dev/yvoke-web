package de.palsoftware.yvoke.shared.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = "app.security.mock=false")
public class SecurityGatingIT {

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

        // Permit static files without auth
        mockMvc.perform(get("/css/index.css"))
                .andExpect(status().isOk());
    }

    @Test
    public void testProtectedEndpointsRedirectOr401Unauthenticated() throws Exception {
        mockMvc.perform(get("/chat"))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(get("/admin/documents"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    public void testUserAccessGating() throws Exception {
        mockMvc.perform(get("/chat")
                        .with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/admin/documents")
                        .with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    public void testAdminAccessGating() throws Exception {
        mockMvc.perform(get("/admin/documents")
                        .with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/chat")
                        .with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isOk());
    }
}
