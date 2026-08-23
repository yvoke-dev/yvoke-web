package de.palsoftware.yvoke.shared.security;

import org.junit.jupiter.api.BeforeEach;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {"app.security.mock=false", "app.security.api-key=it-security-gating-api-key"})
public class SecurityGatingIT {

    /**
     * Must match the {@code app.security.api-key} property above. Any value other than the shipped
     * placeholder {@code dev-key-12345} makes {@code SecurityConfig} install
     * {@code ApiKeyAuthenticationFilter} on the X-API-Key chain (see
     * {@code SecurityConfig.apiKeyConfigured()}); with the placeholder — which is what this context
     * inherited before — the filter is not in the chain at all and no IT exercises it.
     */
    private static final String API_KEY = "it-security-gating-api-key";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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

    /**
     * The two entry points a human ever types, and the only IT context in which either behaves the
     * way production does.
     *
     * <p>
     * With {@code app.security.mock=false} — i.e. everywhere real — {@code GET /login} is the whole
     * sign-in flow: {@code LoginController} bounces the browser to
     * {@code /oauth2/authorization/entra}, which is what starts the authorization-code exchange with
     * Entra. There is no other way in; {@code oauth2Login().loginPage("/login")} means Spring
     * Security's own generated login page is not registered, and the entry point for every gated URL
     * is {@code /login} itself. Lose the redirect — the natural shape of the edit is "just render
     * the login template in both modes", which compiles, starts and looks right — and the mock login
     * FORM is served in production instead: a username/password box wired to a
     * {@code MockAuthenticationProvider} that is not even on this chain, so nobody can sign in at
     * all. Every existing test of {@code /login} runs under {@code app.security.mock=true}
     * ({@code SecurityMockGatingIT} asserts it renders 200) and would stay green through exactly
     * that regression, because 200-with-a-form is what mock mode is supposed to do.
     *
     * <p>
     * {@code GET /} is the URL people bookmark, the one an ingress or uptime check hits, and the one
     * a browser lands on after any redirect that drops the path. It is not on any permit-list, so it
     * is authenticated first and then handed to {@code LoginController.index}. Nothing anywhere
     * requests it: {@code AllPagesRenderSmokeIT}'s path list omits it, and the one place it appears
     * in this class ({@code anApiKeyIsInertOutsideTheThreeApiPathFamilies}) only compares two
     * statuses to each other and would be equally happy with two 404s. Drop the mapping and the
     * origin URL becomes a 404 for every signed-in user, with the application still perfectly
     * reachable at {@code /chat}, so it reads as "the bookmark is stale" rather than as a bug.
     *
     * <p>
     * The anonymous stanza in the middle is what ties the two halves together: it proves {@code /}
     * really is gated (so the {@code ROLE_USER} redirect below is the controller's answer and not a
     * permitAll fall-through) and that the entry point aims at the very {@code /login} the first
     * stanza pins, which is the two-hop path a real first-time visitor takes.
     */
    @Test
    public void theBrowserEntryPointsRedirectToEntraAndToTheChatList() throws Exception {
        mockMvc.perform(get("/login")).andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/oauth2/authorization/entra"));

        mockMvc.perform(get("/")).andExpect(status().is3xxRedirection())
            .andExpect(result -> assertThat(result.getResponse().getRedirectedUrl())
                .as("/ is gated, and its entry point is the /login the stanza above pins")
                .endsWith("/login"));

        mockMvc
            .perform(get("/").with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/chat"));
    }

    /**
     * The {@code @Order(0)} actuator chain permits exactly three paths — {@code /actuator/health},
     * {@code /actuator/health/**} and {@code /actuator/info} — and authenticates everything else.
     * That deny-by-default half is the entire point of the chain, and it is the half nothing
     * asserts: {@code testPublicEndpointsAreAccessibleWithoutAuth} and {@code SecurityMockGatingIT}
     * between them only show that {@code /actuator/health} is anonymous, which stays true under
     * every widening of the permit list.
     *
     * <p>
     * It is a two-key lock, and only the other key is currently guarded.
     * {@code ApplicationYamlInvariantsTest} pins the management EXPOSURE list, so an endpoint that
     * is not exposed is unreachable regardless of security. This test pins the security side, so an
     * endpoint that later gets exposed is still authenticated. Widening the matcher to
     * {@code /actuator/**} is the natural, innocent-looking edit — it is what someone does when
     * actuator appears unreachable behind an ingress or a k8s probe, and it matches the permitAll
     * the browser chain already carries for the same prefix — and on its own it changes nothing
     * observable, because the sensitive endpoints are not exposed today. Combine it with a later
     * exposure change and {@code /actuator/env} publishes every resolved property: the Entra client
     * secret, {@code APP_SECRET_KEY}, the database password and the API key, to anyone who can
     * reach the port. Neither edit is wrong by itself, which is exactly why both keys have to be
     * held.
     *
     * <p>
     * The status codes are the assertion's discriminator, not decoration. Actuator runs on the
     * separate management port and is not mapped on the servlet context under test, so a PERMITTED
     * path reaches the dispatcher and comes back 404 — which is what the two probes assert. A
     * DENIED path never reaches the dispatcher and comes back 401/403. So "404" here means
     * "security let it through", and a permitAll regression flips these paths from 403 to 404
     * rather than to 200 — a distinction no test that merely asserts "not 200" would catch.
     */
    @Test
    public void everyActuatorEndpointBeyondTheProbesIsAuthenticated() throws Exception {
        // Permitted: the request gets past security and merely finds no handler on this context.
        mockMvc.perform(get("/actuator/health")).andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator/info")).andExpect(status().isNotFound());

        for (String path : new String[] {"/actuator", "/actuator/env",
            "/actuator/env/APP_SECRET_KEY", "/actuator/configprops", "/actuator/loggers",
            "/actuator/heapdump", "/actuator/prometheus", "/actuator/beans"}) {
            mockMvc.perform(get(path)).andExpect(result -> {
                int status = result.getResponse().getStatus();
                assertThat(status)
                    .as("anonymous %s must be rejected by the actuator chain; a 404 would mean"
                        + " security permitted it and only the exposure list is still hiding it",
                        path)
                    .isIn(401, 403);
            });
        }
    }

    /**
     * The three API-key chains ({@code /api/ingest/**}, {@code /api/document/**},
     * {@code /api/jobs/**}) are the CSRF-disabled ones, so their authentication gate is the only
     * thing standing in front of them. They must deny an anonymous caller outright — not redirect
     * to the login page like the browser chain, which for a machine client is an opaque 302 that
     * some HTTP clients follow into an HTML page and report as success. And they must still admit a
     * caller carrying a session role, because the admin UI's job-progress EventSource authenticates
     * with its cookie (which is why this chain is deliberately NOT stateless).
     */
    @Test
    public void theApiKeyChainsDenyAnonymousCallersButStillAdmitASessionRole() throws Exception {
        for (String path : new String[] {"/api/jobs/v1/" + UUID.randomUUID(),
            "/api/document/v1", "/api/ingest/v1/upload"}) {
            mockMvc.perform(get(path)).andExpect(result -> {
                int status = result.getResponse().getStatus();
                assertThat(status).as("anonymous %s must be denied outright, not redirected",
                        path)
                    .isIn(401, 403);
            });
        }

        // A cookie session carrying ROLE_ADMIN gets past the gate (the endpoint may then 404/400 on
        // its own terms — what matters is that it is no longer an auth rejection).
        mockMvc.perform(get("/api/document/v1")
            .with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
            .andExpect(result -> assertThat(result.getResponse().getStatus())
                .as("a session role must still reach the endpoint").isNotIn(401, 403));
    }

    /**
     * The status an unauthenticated machine client gets is part of the API contract, and the two
     * {@code /api/**} families answer the identical request differently on purpose — yet neither
     * status is written down anywhere in {@code SecurityConfig}. Both fall out of which configurers
     * a chain happens to register, so any edit that adds or removes one silently changes the
     * contract.
     *
     * <p>
     * {@code /api/chat/**} (the desktop sync API) registers {@code oauth2ResourceServer}, which
     * contributes {@code BearerTokenAuthenticationEntryPoint}: 401 plus
     * {@code WWW-Authenticate: Bearer}. That challenge is the whole mechanism by which a desktop
     * client distinguishes "your token expired, refresh and retry" from "you are not allowed here".
     * Downgrade it to a 403 and an expired token reads as a permanent denial — the client stops
     * refreshing, and the user is locked out until they happen to sign in again.
     *
     * <p>
     * The three API-key chains register NO entry-point contributor at all — only
     * {@code authorizeHttpRequests}, an optional {@code ApiKeyAuthenticationFilter} and
     * {@code csrf().disable()} — so {@code ExceptionHandlingConfigurer} falls back to
     * {@code Http403ForbiddenEntryPoint} and an anonymous caller gets a bare 403 with no challenge.
     * That is the right answer here and must stay: an {@code X-API-Key} client (the corpus scripts,
     * the eval harness) has no token endpoint to visit, so adding anything that contributes a 401
     * challenge — {@code httpBasic}, {@code formLogin}, an {@code exceptionHandling} customisation —
     * sends them into an auth flow that cannot succeed, and some HTTP clients will chase it.
     *
     * <p>
     * Worth knowing before someone "improves" this: 403 is also what a signed-in {@code ROLE_USER}
     * gets (see the sibling test below), so on these chains "not authenticated" and "authenticated
     * but wrong role" are indistinguishable from outside. Nothing pinned the exact codes until now —
     * {@code theApiKeyChainsDenyAnonymousCallersButStillAdmitASessionRole} deliberately asserts
     * {@code isIn(401, 403)} because it is about denial rather than about which code.
     */
    @Test
    public void anAnonymousCallToAnApiKeyChainIsForbiddenRatherThanUnauthorized() throws Exception {
        for (String path : new String[] {"/api/jobs/v1/" + UUID.randomUUID(), "/api/document/v1",
            "/api/ingest/v1/upload"}) {
            mockMvc.perform(get(path)).andExpect(result -> {
                assertThat(result.getResponse().getStatus())
                    .as("%s registers no entry point, so anonymous must land on "
                        + "Http403ForbiddenEntryPoint", path)
                    .isEqualTo(403);
                assertThat(result.getResponse().getHeader("WWW-Authenticate"))
                    .as("%s must not challenge a client for a credential it cannot obtain", path)
                    .isNull();
            });
        }

        // The bearer chain answers the SAME anonymous request the other way, and must keep doing so.
        mockMvc.perform(get("/api/chat/v1/conversations")).andExpect(status().isUnauthorized())
            .andExpect(result -> assertThat(result.getResponse().getHeader("WWW-Authenticate"))
                .as("oauth2ResourceServer contributes BearerTokenAuthenticationEntryPoint")
                .startsWith("Bearer"));
    }

    /**
     * The API key authenticates a REQUEST, never a SESSION.
     *
     * <p>
     * The X-API-Key chain is deliberately NOT stateless — the admin UI's job-progress EventSource
     * reaches {@code /api/jobs/**} with its cookie — so {@code SecurityContextHolderFilter} runs on
     * it and a session is in play. What keeps that safe is that Spring Security 6+/7 defaults to
     * explicit-save ({@code SecurityContextConfigurer} sets {@code requireExplicitSave = true}), so
     * the filter loads a stored context but never writes one back, and
     * {@code ApiKeyAuthenticationFilter}'s only write is a bare
     * {@code SecurityContextHolder.getContext().setAuthentication(auth)} with no
     * {@code SecurityContextRepository} anywhere near it.
     *
     * <p>
     * Lose either half — {@code requireExplicitSave(false)} on this chain, or a
     * {@code saveContext(...)} added to the filter to "make the key stick so the client doesn't have
     * to resend it" — and the effect is not a leak of authority but a THEFT of it: the browser's
     * stored {@code ROLE_ADMIN} context is overwritten in the session by the key's
     * {@code ROLE_INGEST}, so the admin's own subsequent page loads are 403ed by a chain that
     * believes they are a machine client, and every audited action from then on is attributed to
     * {@code api-user} rather than to the person at the keyboard. In the other direction, a
     * long-lived {@code ROLE_INGEST} cookie now exists that was minted by a header the browser never
     * has to send again.
     *
     * <p>
     * {@code ApiKeyAuthenticationFilterTest.aValidApiKeyDowngradesAnAmbientAdminSessionToRoleIngest}
     * pins the in-request overwrite, but it drives the filter with a Mockito
     * {@code HttpServletRequest} and never looks at a session, so the persistence half is invisible
     * to it — and it is the framework, not our code, that decides it, which is exactly the kind of
     * default that changes underneath a project on a major upgrade with no compile error.
     */
    @Test
    public void anApiKeyRequestLeavesNoIngestAuthenticationBehindInTheSession() throws Exception {
        MockHttpSession session = new MockHttpSession();

        mockMvc
            .perform(get("/api/document/v1").session(session).header("X-API-Key", API_KEY)
                .param("collection", "no-such-collection").param("tag", "no-such-tag"))
            // A 404 is the handler answering, so the chain authorised the request; an auth failure
            // on this chain is 401 or 403, never 404. The endpoint now refuses an unknown area
            // instead of returning an empty list, so 404 is the positive proof this test wants.
            .andExpect(result -> assertThat(result.getResponse().getStatus())
                .as("reached the handler: authenticated, then refused the unknown area")
                .isEqualTo(404));

        assertThat(
            session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
                .as("the key authenticates one request; nothing may be written back to the session")
                .isNull();

        // The behavioural half: the very next request on the SAME session, without the header, must
        // be an anonymous one again.
        mockMvc
            .perform(get("/api/document/v1").session(session)
                .param("collection", "no-such-collection").param("tag", "no-such-tag"))
            .andExpect(result -> assertThat(result.getResponse().getStatus())
                .as("a later request on the same session must not inherit ROLE_INGEST")
                .isIn(401, 403));
    }

    /**
     * These three chains are the corpus WRITE surface, and a plain {@code ROLE_USER} must not reach
     * them.
     *
     * <p>
     * They used to admit {@code ROLE_USER} alongside {@code ROLE_INGEST}/{@code ROLE_ADMIN}, which
     * meant any signed-in person could upload files and queue import jobs into ANY knowledge area
     * from their ordinary browser session — content that then becomes searchable by everyone and is
     * cited as authoritative, that spends money on summarisation and embedding, and that is not
     * written to the audit trail (only admin-screen imports are). The web UI never exposed it, so it
     * was reachable only by calling the endpoints directly, which is exactly why it went unnoticed.
     *
     * <p>
     * Nothing in the UI depends on the {@code USER} grant: {@code /api/document/**} has no browser
     * caller at all, and the only {@code /api/jobs/**} consumer is the progress stream on the
     * admin-only job page. {@code ROLE_INGEST} is kept because it is the authority the shared
     * {@code X-API-Key} principal carries — dropping it would 403 every corpus script and the eval
     * harness.
     */
    /**
     * A BAD credential must fail the request outright, never fall back to whatever else happens to
     * have authenticated it.
     *
     * <p>
     * The filter answers a non-blank mismatched key itself — 401, body {@code Invalid API Key},
     * {@code return} without calling {@code filterChain.doFilter} — so nothing downstream ever
     * runs. That short-circuit is the whole security value of the branch, and it is invisible to
     * {@code ApiKeyAuthenticationFilterTest.invalidKeyIsRejectedWithUnauthorizedAndDoesNotContinue}
     * in one specific direction: that test drives the filter in isolation from an empty context, so
     * "reject" and "reject unless something else would have said yes" are the same outcome. Turn
     * the {@code return} into a fall-through — the shape a well-meaning edit reaches for, so that a
     * browser admin is not locked out by a stale header — and a request carrying a WRONG key plus
     * an admin cookie sails through with ROLE_ADMIN. Key rotation then stops being enforceable:
     * every retired key keeps working from any authenticated browser, and the 401 that told a
     * machine client to re-read its config never arrives.
     *
     * <p>
     * The status is also the client's only diagnostic. 401 + {@code Invalid API Key} means "your
     * credential is wrong"; the bare 403 the same URL returns for a wrong ROLE means "you are not
     * allowed here". Collapse them and an operator cannot tell a typo'd key from a missing grant.
     */
    @Test
    public void aWrongApiKeyIsRejectedWithItsOwn401EvenWhenAValidAdminSessionIsPresent()
        throws Exception {
        mockMvc.perform(get("/api/document/v1").header("X-API-Key", "not-the-configured-key"))
            .andExpect(status().isUnauthorized())
            .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                .as("the filter writes its own body, so a client can tell a bad key from a bad role")
                .isEqualTo("Invalid API Key"));

        // The same wrong key, now on a request that WOULD have been admitted on its session alone.
        mockMvc
            .perform(get("/api/document/v1").header("X-API-Key", "not-the-configured-key")
                .with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
            .andExpect(result -> assertThat(result.getResponse().getStatus())
                .as("a bad credential must not be rescued by an ambient admin session")
                .isEqualTo(401));

        // Contrast: a wrong ROLE on the identical URL is a bare 403 with no body from the filter.
        mockMvc
            .perform(get("/api/document/v1")
                .with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
            .andExpect(status().isForbidden())
            .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                .as("the 403 is the chain's, not the filter's").doesNotContain("Invalid API Key"));
    }

    /**
     * An empty or whitespace-only {@code X-API-Key} is a NON-EVENT, not a failed authentication.
     *
     * <p>
     * {@code requestKey != null && !requestKey.isBlank()} is what makes it so. Drop the
     * {@code isBlank} half and a blank header takes the mismatch branch instead: an immediate 401
     * with the chain aborted. That breaks callers who never meant to present a key at all — a shell
     * script or container that templates an unset variable ({@code -H "X-API-Key: $API_KEY"}
     * expanding to nothing) would be hard-401ed on a header it did not really send, and the session
     * auth that would have worked is never consulted, because the filter has already returned.
     *
     * <p>
     * The unit-level sibling pins the filter's own behaviour on a blank header; this pins the
     * consequence that only the assembled chain can show — that the request keeps going far enough
     * for the session to be evaluated.
     */
    @Test
    public void aBlankApiKeyHeaderIsTreatedAsAbsentSoASessionOnTheSameRequestStillSucceeds()
        throws Exception {
        for (String blank : new String[] {"", "   "}) {
            mockMvc.perform(get("/api/document/v1").header("X-API-Key", blank))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                    .as("a blank key is no key: denied as anonymous (403), not rejected (401)")
                    .isEqualTo(403));

            mockMvc
                .perform(get("/api/document/v1").header("X-API-Key", blank)
                    .param("collection", "no-such-collection").param("tag", "no-such-tag")
                    .with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                // A 404 is the handler answering, so the chain authorised the request; an auth failure
                // on this chain is 401 or 403, never 404. The endpoint now refuses an unknown area
                // instead of returning an empty list, so 404 is the positive proof this test wants.
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                    .as("reached the handler: authenticated, then refused the unknown area")
                    .isEqualTo(404));
        }
    }

    /**
     * The API key buys exactly three path families and nothing else.
     *
     * <p>
     * {@code ApiKeyAuthenticationFilter} is installed on the {@code @Order(3)} chain only, so on
     * every other chain the header is inert: it authenticates nothing, and — just as importantly —
     * a WRONG one rejects nothing, because the filter that would answer 401 is not there to see it.
     * Move the {@code addFilterBefore} to the catch-all chain, or widen the securityMatcher, and a
     * single shared string minted for corpus scripts silently becomes a master credential for
     * {@code /chat}, {@code /document/**} and the whole {@code /admin/**} surface — with every
     * action attributed to the principal {@code api-user} rather than to a person.
     *
     * <p>
     * Both directions are asserted deliberately. Only checking that a valid key does not open
     * {@code /admin} would still pass if the filter ran there and merely failed to grant enough
     * authority; asserting that a garbage key is equally ignored proves the filter is absent.
     */
    @Test
    public void anApiKeyIsInertOutsideTheThreeApiPathFamilies() throws Exception {
        for (String path : new String[] {"/", "/chat", "/document/x", "/admin/documents"}) {
            int anonymous = mockMvc.perform(get(path)).andReturn().getResponse().getStatus();

            mockMvc.perform(get(path).header("X-API-Key", API_KEY))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                    .as("a VALID key must buy nothing at %s", path).isEqualTo(anonymous));

            mockMvc.perform(get(path).header("X-API-Key", "not-the-configured-key"))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                    .as("and a WRONG key must not be rejected at %s either — the filter that "
                        + "would answer 401 is not on this chain at all", path)
                    .isEqualTo(anonymous));
        }
    }

    /**
     * Structural containment, asserted against the assembled {@code FilterChainProxy} rather than
     * inferred from a handful of URLs.
     *
     * <p>
     * Exactly ONE chain may carry an {@code ApiKeyAuthenticationFilter}, and its securityMatcher
     * must cover the three corpus-write families and refuse everything else — in particular the
     * bearer chains ({@code /mcp/**}, {@code /api/chat/**}), whose callers authenticate with an
     * Entra token and must never be reachable with a static shared string, and the browser chain.
     * A URL-by-URL test can only sample; this reads the matcher itself, so a securityMatcher
     * widened to {@code /api/**} fails here even for a path nobody thought to probe.
     */
    @Test
    public void exactlyOneChainCarriesTheApiKeyFilterAndItsMatcherIsTheCorpusWriteSurface() {
        FilterChainProxy proxy = context.getBean("springSecurityFilterChain", FilterChainProxy.class);
        List<SecurityFilterChain> carrying = proxy.getFilterChains().stream()
            .filter(c -> c.getFilters().stream()
                .anyMatch(f -> f instanceof ApiKeyAuthenticationFilter))
            .toList();
        assertThat(carrying).as("the API key must be honoured on exactly one chain").hasSize(1);

        SecurityFilterChain apiKeyChain = carrying.get(0);
        for (String matched : new String[] {"/api/ingest/v1/upload", "/api/document/v1",
            "/api/jobs/v1/" + UUID.randomUUID()}) {
            assertThat(apiKeyChain.matches(new MockHttpServletRequest("GET", matched)))
                .as("%s is the corpus write surface the key exists for", matched).isTrue();
        }
        for (String excluded : new String[] {"/mcp", "/mcp/message", "/api/chat/v1/conversations",
            "/admin/documents", "/chat", "/"}) {
            assertThat(apiKeyChain.matches(new MockHttpServletRequest("GET", excluded)))
                .as("%s must be out of reach of a static shared key", excluded).isFalse();
        }
    }

    /**
     * A denied anonymous call to the machine API must not allocate server-side state.
     *
     * <p>
     * {@code ExceptionTranslationFilter.sendStartAuthentication} saves the request into a
     * {@code RequestCache} before commencing the entry point, and this chain — alone among the
     * machine-facing ones — never configures one, so it inherits the default
     * {@code HttpSessionRequestCache}: {@code AnyRequestMatcher}, {@code createSessionAllowed=true}.
     * The stateless chains are immune ({@code SessionManagementConfigurer} installs a
     * {@code NullRequestCache} for them); this one is deliberately session-capable because the
     * admin UI's job-progress EventSource arrives with a cookie.
     *
     * <p>
     * The consequence is that every unauthenticated hit on {@code /api/ingest/**},
     * {@code /api/document/**} or {@code /api/jobs/**} mints a session holding a
     * {@code DefaultSavedRequest} — URL, headers, cookies and query string, all attacker-supplied —
     * retained until the session times out, with no credential required. A scanner walking the API
     * surface is an unauthenticated memory-growth vector. The cache is also pure dead weight here:
     * this chain has no login flow, so a saved request is never resumed.
     */
    @Test
    public void aDeniedAnonymousCallToTheMachineApiDoesNotMintAnHttpSession() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/document/v1")).andExpect(status().isForbidden())
            .andReturn();

        assertThat(result.getRequest().getSession(false))
            .as("a denial on a machine API must not allocate a session for an anonymous caller")
            .isNull();
    }

    /**
     * The key must still be authenticated on the ASYNC dispatch, or the one streaming endpoint on
     * this chain is broken for exactly the credential the chain exists to serve.
     *
     * <p>
     * {@code GET /api/jobs/v1/{id}/progress} returns an {@code SseEmitter}, so the container
     * re-dispatches the request with {@code DispatcherType.ASYNC}. Three framework facts collide
     * there: {@code OncePerRequestFilter.shouldNotFilterAsyncDispatch()} is {@code true}, so
     * {@code ApiKeyAuthenticationFilter} is SKIPPED on that dispatch; {@code AuthorizationFilter} is
     * built with {@code filterAsyncDispatch=true}, so it authorizes it anyway; and
     * {@code SecurityContextHolderFilter} re-runs and reloads the context from the repository —
     * which is empty, because the filter's only write is a bare
     * {@code SecurityContextHolder.getContext().setAuthentication(auth)} that no repository ever
     * sees. Result: anonymous on the second dispatch, and the chain's own
     * {@code hasAnyRole(INGEST, ADMIN)} denies it.
     *
     * <p>
     * A browser subscriber is unaffected — its context reloads from the session — which is why this
     * has gone unnoticed: the admin UI is the only caller anyone watches. Under MockMvc the second
     * dispatch simply reads 403; in a real container the response is already committed, so
     * {@code sendError} throws and the SSE connection is torn down mid-stream.
     *
     * <p>
     * The fix is for the filter to persist the context it just authenticated into a
     * {@code RequestAttributeSecurityContextRepository} — the request-scoped one specifically, since
     * {@code anApiKeyRequestLeavesNoIngestAuthenticationBehindInTheSession} correctly forbids a
     * session write.
     */
    @Test
    public void anApiKeyAuthenticatedSseSubscriptionSurvivesTheAsyncDispatch() throws Exception {
        UUID collectionId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO collections (id, name) VALUES (?, ?)", collectionId,
            "SEC-GATING-ASYNC-COLLECTION");
        jdbcTemplate.update("""
            INSERT INTO ingestion_jobs
                (id, kind, source_ref, tags, collection_id, status, step, progress, attempts,
                 created_at)
            VALUES (?, 'kg-extract', 'kit/install.md', ARRAY['10.0'], ?, 'completed', 'extract',
                    100, 1, CURRENT_TIMESTAMP)
            """, jobId, collectionId);
        try {
            MvcResult started = mockMvc
                .perform(get("/api/jobs/v1/" + jobId + "/progress").header("X-API-Key", API_KEY))
                .andExpect(request().asyncStarted()).andReturn();

            mockMvc.perform(asyncDispatch(started))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                    .as("the key authenticated the request; the ASYNC dispatch is the SAME request")
                    .isNotEqualTo(403));
        } finally {
            jdbcTemplate.update("DELETE FROM ingestion_jobs WHERE id = ?", jobId);
            jdbcTemplate.update("DELETE FROM collections WHERE id = ?", collectionId);
        }
    }

    /**
     * The exact inverse of {@code anApiKeyRequestLeavesNoIngestAuthenticationBehindInTheSession}:
     * that test proves nothing is WRITTEN to the session, this one proves what is already there is
     * still READ.
     *
     * <p>
     * Both halves are needed, because the obvious way to satisfy the first is to make this chain
     * {@code STATELESS} — which swaps the repository for a bare
     * {@code RequestAttributeSecurityContextRepository} and silently 403s the admin UI's
     * job-progress {@code EventSource}, the one browser caller these three path families have. It
     * would fail on the admin's job page only, so nobody running the API scripts would notice.
     *
     * <p>
     * This drives a REAL {@code JSESSIONID}-backed context rather than
     * {@code oidcLogin()}: the MockMvc post-processor injects authentication through
     * {@code TestSecurityContextHolder}, bypassing the {@code SecurityContextRepository} entirely,
     * so every existing session test in this class would keep passing after the chain went
     * stateless.
     */
    @Test
    public void theApiKeyChainStillReadsAuthenticationFromARealBrowserSessionCookie()
        throws Exception {
        SecurityContext stored = SecurityContextHolder.createEmptyContext();
        stored.setAuthentication(new UsernamePasswordAuthenticationToken("browser-admin", "n/a",
            List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
            HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, stored);

        mockMvc
            .perform(get("/api/document/v1").session(session)
                .param("collection", "no-such-collection").param("tag", "no-such-tag"))
            // A 404 is the handler answering, so the chain authorised the request; an auth failure
            // on this chain is 401 or 403, never 404. The endpoint now refuses an unknown area
            // instead of returning an empty list, so 404 is the positive proof this test wants.
            .andExpect(result -> assertThat(result.getResponse().getStatus())
                .as("reached the handler: authenticated, then refused the unknown area")
                .isEqualTo(404));
    }

    /**
     * Every {@code securityMatcher} in {@code SecurityConfig} is a {@code /prefix/**} pattern, and
     * the whole chain layout depends on such a pattern ALSO matching the bare prefix. It does —
     * {@code PathPattern}'s trailing {@code /**} matches zero segments — but nothing anywhere
     * requests a bare path, so that dependency is invisible and one narrowing edit removes it.
     *
     * <p>
     * The MCP chain spells the bare form out ({@code securityMatcher("/mcp/**", "/mcp")}); the chat
     * API chain does not ({@code securityMatcher("/api/chat/**")}), and neither do the three API-key
     * families. Tighten any of them to something that requires a segment — {@code /api/chat/&#42;/&#42;&#42;},
     * or a version-scoped {@code /api/chat/v1/**} — and the bare path silently falls through to the
     * @Order default browser chain. That chain is cookie-authenticated, which means an ambient
     * signed-in browser session starts authenticating the desktop-sync API surface, and a machine
     * client gets an opaque 302 into an HTML login page (which some HTTP clients follow and report
     * as success) instead of the 401 that tells it to refresh its token.
     *
     * <p>
     * The status codes are the discriminator, not decoration, and each family answers differently
     * on purpose: the two bearer chains contribute an entry point that returns 401 with a
     * {@code Bearer} challenge, while the three API-key chains contribute none and fall back to
     * {@code Http403ForbiddenEntryPoint}. A fall-through to the browser chain is neither — it is a
     * 3xx, which is exactly what the control stanza at the end shows the same anonymous GET
     * produces there.
     *
     * <p>
     * {@code exactlyOneChainCarriesTheApiKeyFilterAndItsMatcherIsTheCorpusWriteSurface} asserts
     * which chain must NOT match the bare paths; nothing asserts which one must.
     */
    @Test
    public void aBarePrefixPathStaysOnItsOwnChainInsteadOfFallingThroughToTheBrowserChain()
        throws Exception {
        // Bearer chains (@Order(1) and @Order(2)): 401 plus a challenge, never a login redirect.
        for (String bare : new String[] {"/mcp", "/api/chat"}) {
            mockMvc.perform(get(bare)).andExpect(result -> {
                assertThat(result.getResponse().getStatus())
                    .as("bare %s must be answered by its own bearer chain, not by the cookie chain",
                        bare)
                    .isEqualTo(401);
                assertThat(result.getResponse().getHeader("WWW-Authenticate"))
                    .as("%s must still challenge for a bearer token", bare).startsWith("Bearer");
            });
        }

        // API-key chains (@Order(3)): a bare 403 from Http403ForbiddenEntryPoint, no challenge.
        for (String bare : new String[] {"/api/ingest", "/api/document", "/api/jobs"}) {
            mockMvc.perform(get(bare)).andExpect(result -> {
                assertThat(result.getResponse().getStatus())
                    .as("bare %s must stay on the corpus-write chain", bare).isEqualTo(403);
                assertThat(result.getResponse().getHeader("WWW-Authenticate"))
                    .as("%s must not send a machine client into an auth flow it cannot complete",
                        bare)
                    .isNull();
            });
        }

        // Control: the browser chain answers the SAME anonymous GET with a redirect, so any
        // fall-through above would have been visible as a 3xx rather than as 401/403.
        mockMvc.perform(get("/chat")).andExpect(status().is3xxRedirection());
    }

    @Test
    public void aPlainUserSessionCannotReachTheCorpusWriteApis() throws Exception {
        for (String path : new String[] {"/api/jobs/v1/" + UUID.randomUUID(),
            "/api/document/v1", "/api/ingest/v1/upload"}) {
            mockMvc
                .perform(get(path)
                    .with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                    .as("a plain ROLE_USER session must be refused at %s", path).isEqualTo(403));
        }
    }
}
