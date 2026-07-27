package de.palsoftware.yvoke.shared.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = "app.security.mock=true")
public class McpSecurityGatingIT {

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
    public void testProtectedResourceMetadataEndpointIsPublic() throws Exception {
        mockMvc.perform(get("/.well-known/oauth-protected-resource"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.resource").value("http://localhost:8080/mcp"))
                .andExpect(jsonPath("$.authorization_servers[0]").value("https://login.microsoftonline.com/common/v2.0"))
                .andExpect(jsonPath("$.scopes_supported[0]").value("api://oim-kb/mcp.read"))
                .andExpect(jsonPath("$.bearer_methods_supported[0]").value("header"));
    }

    @Test
    public void testMcpEndpointRejectsUnauthenticated() throws Exception {
        mockMvc.perform(get("/mcp"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", containsString("Bearer realm=\"mcp\"")))
                .andExpect(header().string("WWW-Authenticate", containsString("resource_metadata=\"http://localhost:8080/.well-known/oauth-protected-resource\"")));

        mockMvc.perform(post("/mcp"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", containsString("Bearer realm=\"mcp\"")))
                .andExpect(header().string("WWW-Authenticate", containsString("resource_metadata=\"http://localhost:8080/.well-known/oauth-protected-resource\"")));
    }

    @Test
    public void testMcpChainIgnoresAmbientBrowserSession() throws Exception {
        // Log in through the stateful cookie chain to obtain a browser session carrying ROLE_USER.
        org.springframework.mock.web.MockHttpSession session =
            (org.springframework.mock.web.MockHttpSession) mockMvc.perform(post("/login").with(csrf())
                    .param("username", "user").param("password", "password"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getRequest().getSession();

        // That session must NOT authenticate against the bearer-only MCP chain: it is STATELESS, so
        // the ambient cookie authority never leaks into /mcp (SEC-13). Expect 401, not a 400/200.
        mockMvc.perform(get("/mcp").session(session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testMcpEndpointAcceptsMockBearerToken() throws Exception {
        // With mock=true, the JwtDecoder accepts any bearer token and populates valid scope claims.
        // We verify that it does not return 401 Unauthorized or 403 Forbidden.
        // A GET /mcp request will result in a 400 Bad Request because the Mcp-Session-Id header is missing,
        // but it will successfully bypass the security filter without 401 or 403.
        mockMvc.perform(get("/mcp")
                        .header("Authorization", "Bearer mock-jwt-token"))
                .andExpect(status().isBadRequest());
    }
}
