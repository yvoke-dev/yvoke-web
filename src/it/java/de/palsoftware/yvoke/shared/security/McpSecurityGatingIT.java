package de.palsoftware.yvoke.shared.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;

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

    /**
     * Two fields in the discovery document are ones a client cannot recover from on its own, and
     * neither is pinned by the sibling test above.
     *
     * <p>
     * {@code jwks_uri} is deliberately hard-coded to the {@code common} tenant while every other
     * endpoint in the same map is derived from the configured tenant base. That asymmetry looks
     * like an oversight and invites "tidying" it to {@code tenantBase + "/discovery/v2.0/keys"} —
     * but signing-key discovery for a multi-tenant app must go to {@code common}, so the tidy-up
     * leaves every guest and cross-tenant token unverifiable, failing at signature validation with
     * nothing pointing back at this document.
     *
     * <p>
     * {@code code_challenge_methods_supported} advertising exactly {@code S256} is what makes the
     * authorization-code flow legal for a PUBLIC client, which every MCP client is — there is no
     * client secret to hold. Drop it and conformant libraries refuse to start the flow at all;
     * widen it to include {@code plain} and a conformant library may pick the downgrade, which is
     * why the list is asserted as an exact singleton rather than with {@code contains}.
     *
     * <p>
     * Both paths are checked because {@code getOpenIdConfiguration()} delegates to
     * {@code getAuthorizationServerMetadata()} today, and a future "specialisation" of one of them
     * would hand a client that probed the other a different key set or a weaker PKCE contract.
     */
    @Test
    public void theDiscoveryDocumentPinsTheCommonTenantJwksUriAndS256Pkce() throws Exception {
        String commonJwks = "https://login.microsoftonline.com/common/discovery/v2.0/keys";

        mockMvc.perform(get("/.well-known/oauth-authorization-server")).andExpect(status().isOk())
            .andExpect(jsonPath("$.jwks_uri").value(commonJwks))
            .andExpect(jsonPath("$.code_challenge_methods_supported.length()").value(1))
            .andExpect(jsonPath("$.code_challenge_methods_supported[0]").value("S256"));

        mockMvc.perform(get("/.well-known/openid-configuration")).andExpect(status().isOk())
            .andExpect(jsonPath("$.jwks_uri").value(commonJwks))
            .andExpect(jsonPath("$.code_challenge_methods_supported.length()").value(1))
            .andExpect(jsonPath("$.code_challenge_methods_supported[0]").value("S256"));
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
        MockHttpSession session =
            (MockHttpSession) mockMvc.perform(post("/login").with(csrf())
                    .param("username", "user").param("password", "password"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getRequest().getSession();

        // That session must NOT authenticate against the bearer-only MCP chain: it is STATELESS, so
        // the ambient cookie authority never leaks into /mcp (SEC-13). Expect 401, not a 400/200.
        mockMvc.perform(get("/mcp").session(session))
                .andExpect(status().isUnauthorized());
    }

    /**
     * The same STATELESS guarantee for the OTHER bearer-only chain, and the higher-blast-radius one.
     * {@code /api/chat/**} is the desktop sync API: it exposes POST /conversations, PATCH and
     * DELETE /conversations/{id} and POST /conversations/{id}/messages, and it is the one chain that
     * is BOTH {@code csrf().disable()}d and bearer-only. If its
     * {@code SessionCreationPolicy.STATELESS} line were dropped — a plausible "let the web UI reuse
     * this API" change — an ambient browser cookie would authenticate it with no CSRF token
     * demanded and no second gate, since the chain's authority check ({@code ROLE_USER}) is
     * satisfied by the session itself. Any third-party page could then drive those endpoints as the
     * victim.
     *
     * <p>
     * This MUST use a real session cookie rather than the {@code oidcLogin()} request
     * post-processor: that seeds {@code TestSecurityContextHolder} directly and would pass even if
     * the chain became stateful, proving nothing.
     */
    @Test
    public void theDesktopChatApiChainAlsoIgnoresAmbientBrowserSession() throws Exception {
        MockHttpSession session =
            (MockHttpSession) mockMvc
                .perform(post("/login").with(csrf()).param("username", "user").param("password",
                    "password"))
                .andExpect(status().is3xxRedirection()).andReturn().getRequest().getSession();

        // A read the session would happily serve on the cookie chain.
        mockMvc.perform(get("/api/chat/v1/conversations").session(session))
            .andExpect(status().isUnauthorized());

        // And a state-changing call with NO CSRF token — the combination that would be exploitable.
        mockMvc.perform(post("/api/chat/v1/conversations").session(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"injected\"}")).andExpect(status().isUnauthorized());
    }

    /**
     * An MCP client that has never seen this server bootstraps entirely from unauthenticated
     * discovery: it reads {@code /.well-known/oauth-protected-resource} (already pinned above),
     * follows the {@code authorization_servers} link, and then fetches the authorization-server
     * metadata to learn where to send the user and which scope to request. Both documents must
     * therefore be reachable with no credentials — they are served by {@code @RestController}
     * methods, so the only thing keeping them public is the {@code /.well-known/**} permitAll entry
     * in {@code SecurityConfig}'s browser chain, one line that a future "lock down everything by
     * default" edit removes without any test noticing. A gated discovery document does not produce a
     * useful error: the client is told to authenticate before it has been told how, so onboarding
     * simply dead-ends.
     *
     * <p>
     * Two paths are served because clients are split on which one to probe — MCP's own spec points
     * at {@code /.well-known/oauth-authorization-server} (RFC 8414) while many OAuth libraries only
     * know {@code /.well-known/openid-configuration}. {@code getOpenIdConfiguration()} delegates to
     * {@code getAuthorizationServerMetadata()} for exactly that reason, and the bodies are compared
     * as parsed JSON rather than as strings so the test states the real requirement (same content)
     * rather than an accident of serialization order: if someone ever "specialises" one of the two,
     * a client that probed the other gets a different issuer or a different scope and fails at token
     * validation, far from here.
     *
     * <p>
     * The issuer must end in {@code /v2.0}. Entra signs tokens with
     * {@code https://login.microsoftonline.com/{tenant}/v2.0} as the {@code iss} claim, and the
     * resource server validates {@code iss} against the issuer advertised here — a tenant URL
     * configured without the suffix (the natural way to write it, and what an operator copying the
     * tenant id produces) would advertise a v1-shaped issuer while the tokens carry the v2 one, so
     * every bearer request 401s with nothing in the logs pointing at this document.
     *
     * <p>
     * The last stanza builds the controller directly, and that is not redundant: the configured
     * {@code app.security.mcp.authorization-server-url} default ALREADY ends in {@code /v2.0}, so
     * the HTTP round trip above cannot observe the normalisation at all — deleting it would leave
     * every assertion above green. Only a value that needs normalising can prove it still happens.
     */
    @Test
    public void theAuthorizationServerMetadataIsPublicAndOpenidConfigurationIsIdentical()
        throws Exception {
        String authorizationServerBody =
            mockMvc.perform(get("/.well-known/oauth-authorization-server"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.issuer").exists()).andReturn().getResponse()
                .getContentAsString();

        String openIdBody = mockMvc.perform(get("/.well-known/openid-configuration"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("application/json")).andReturn()
            .getResponse().getContentAsString();

        ObjectMapper mapper = new ObjectMapper();
        Map<?, ?> authorizationServerMetadata = mapper.readValue(authorizationServerBody, Map.class);
        Map<?, ?> openIdMetadata = mapper.readValue(openIdBody, Map.class);

        assertThat(openIdMetadata)
            .as("the two discovery paths must describe the same authorization server")
            .isEqualTo(authorizationServerMetadata);
        assertThat((String) authorizationServerMetadata.get("issuer"))
            .as("Entra signs tokens with a /v2.0 issuer; the advertised one must match")
            .endsWith("/v2.0");

        @SuppressWarnings("unchecked")
        List<Object> scopes = (List<Object>) authorizationServerMetadata.get("scopes_supported");
        assertThat(scopes).as("a client cannot request a scope the metadata never advertises")
            .contains("api://oim-kb/mcp.read");

        // The configured value already ends in /v2.0, so only a non-normalised tenant URL can show
        // that the normalisation is still there.
        Map<String, Object> normalised = new ProtectedResourceMetadataController(
            "http://localhost:8080/mcp", "https://login.microsoftonline.com/tenant-id/",
            "api://oim-kb/mcp.read").getAuthorizationServerMetadata();
        assertThat(normalised.get("issuer"))
            .as("a tenant URL without the suffix must be normalised, not advertised as-is")
            .isEqualTo("https://login.microsoftonline.com/tenant-id/v2.0");
        assertThat(normalised.get("authorization_endpoint"))
            .isEqualTo("https://login.microsoftonline.com/tenant-id/oauth2/v2.0/authorize");
    }

    /**
     * Entra puts the RELATIVE scope name in the {@code scp} claim — {@code mcp.read} — not the full
     * {@code api://oim-kb/mcp.read} identifier URI, which travels in {@code aud} instead. A real MCP
     * client's access token therefore converts to exactly one scope authority,
     * {@code SCOPE_mcp.read}, and the {@code "SCOPE_" + relativeScope} entry in this chain's
     * {@code hasAnyAuthority} is the only thing that admits it. Delete that entry — an obvious "we
     * already list the configured scope, this one is redundant" cleanup, and it LOOKS redundant
     * because the two strings are derived from the same property — and every genuine bearer request
     * authenticates successfully and then 403s at authorization. The client is told it lacks access
     * to a resource whose own discovery document just instructed it to request that exact scope, so
     * the operator hunts for a missing app-role assignment in Entra that was never missing.
     *
     * <p>
     * Nothing existing covers it. {@code testMcpEndpointAcceptsMockBearerToken} goes through the
     * mock {@code JwtDecoder}, which synthesises {@code scp = [api://oim-kb/mcp.read]} — the FULL
     * scope — so it exercises only the other branch and stays green after the deletion. This is also
     * the only assertion that would notice a change to how {@code relativeScope} is derived in the
     * {@code SecurityConfig} constructor (e.g. taking the segment before the last {@code /} rather
     * than after it).
     */
    @Test
    public void aBearerCarryingOnlyTheRelativeScopeStillReachesMcp() throws Exception {
        int status = mockMvc
            .perform(
                get("/mcp").with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_mcp.read"))))
            .andReturn().getResponse().getStatus();

        // 400 is the MCP servlet answering a GET that carries no Mcp-Session-Id — i.e. the request
        // got past security. 401/403 would mean the relative scope was never admitted.
        assertThat(status)
            .as("a token carrying only the relative scope must clear the MCP chain, got " + status)
            .isNotEqualTo(401).isNotEqualTo(403);
    }

    /**
     * The MCP chain installs its OWN {@code authenticationEntryPoint}, and that single lambda is
     * what every rejection on {@code /mcp} goes through — not just the anonymous case that
     * {@link #testMcpEndpointRejectsUnauthenticated()} covers.
     * {@code BearerTokenAuthenticationFilter.doFilterInternal} wraps the token RESOLUTION in a try
     * that calls {@code authenticationEntryPoint.commence(...)} on failure, and a decode/validation
     * failure reaches the same entry point through the default failure handler; the configurer wires
     * our lambda into both. So a header that Spring cannot even parse as a bearer token must still
     * come back as {@code 401} carrying the {@code resource_metadata} pointer, because that pointer
     * is the ONLY thing that tells a fresh MCP client where to discover the authorization server. A
     * {@code 400} instead is a dead end: a conformant client treats it as "my request was
     * malformed", not "authenticate and retry", so a client whose token expired or got mangled in
     * transit stops re-authenticating and simply fails.
     *
     * <p>
     * The second assertion kills a diagnostic that is written down in this repo and does not work.
     * {@code McpServerEndpointsIT} tells the next person that "a WWW-Authenticate: Bearer error=…
     * means Spring Security rejected the token (its bearer entry point answers invalid_request with
     * 400, not 401)". Both halves are wrong for {@code /mcp}: the {@code 400 invalid_request}
     * mapping belongs to the DEFAULT {@code BearerTokenAuthenticationEntryPoint}, which this chain
     * replaces, and our lambda writes no {@code error=} parameter at all — so the header is present
     * and identical on every rejection and can never discriminate anything. Pinning its exact shape
     * here is what stops that folklore being re-derived.
     */
    @Test
    public void aMalformedBearerHeaderStillGetsThe401ChallengeRatherThanA400() throws Exception {
        // Spaces are not in DefaultBearerTokenResolver's token pattern: this throws
        // invalid_token("Bearer token is malformed") before any JwtDecoder is consulted, so the
        // mock decoder (which accepts every token) cannot mask the branch.
        String challenge = mockMvc.perform(get("/mcp").header("Authorization", "Bearer not a token"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("WWW-Authenticate", containsString("Bearer realm=\"mcp\"")))
            .andExpect(header().string("WWW-Authenticate", containsString(
                "resource_metadata=\"http://localhost:8080/.well-known/oauth-protected-resource\"")))
            .andReturn().getResponse().getHeader("WWW-Authenticate");

        assertThat(challenge)
            .as("the /mcp entry point emits no error= parameter, so 'error=…' identifies nothing")
            .doesNotContain("error=");

        // The scheme keyword with no credential at all: same resolver failure, same answer.
        mockMvc.perform(get("/mcp").header("Authorization", "Bearer"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("WWW-Authenticate", containsString("Bearer realm=\"mcp\"")));

        // A different scheme is not a bearer token: the resolver returns null and the request falls
        // through to the authorization filter, which must land on the SAME 401 challenge.
        mockMvc.perform(get("/mcp").header("Authorization", "Basic YWRtaW46YWRtaW4="))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("WWW-Authenticate", containsString("Bearer realm=\"mcp\"")));

        // And a POST — the method a real MCP client uses for the initialize handshake.
        mockMvc.perform(post("/mcp").header("Authorization", "Bearer not a token"))
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
