package de.palsoftware.yvoke.shared.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import java.util.UUID;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.beans.factory.annotation.Value;
import java.lang.reflect.Field;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = "app.security.mock=true")
public class SecurityMockGatingIT {

    @Autowired
    private WebApplicationContext context;

    /**
     * The value this context actually resolved for {@code app.security.api-key}. It sets only
     * {@code app.security.mock=true}, so it inherits application.yml's
     * {@code ${APP_SECURITY_API_KEY:dev-key-12345}} — the placeholder that {@code SecurityConfig}
     * treats as UNCONFIGURED, which is why {@code ApiKeyAuthenticationFilter} is not on the chain
     * in this context.
     */
    @Value("${app.security.api-key}")
    private String configuredApiKey;

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
        MockHttpSession session = (MockHttpSession) mockMvc.perform(get("/chat").header("Accept", "text/html"))
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

    /**
     * The request cache decides where a user lands AFTER logging in, and it is fed by whatever
     * request last hit the authentication entry point — including requests the browser issued on
     * its own initiative. Chrome, whenever DevTools is open, fires
     * {@code GET /.well-known/appspecific/com.chrome.devtools.json} at every origin it loads, and it
     * sends the surrounding navigation's {@code Accept} header with it. A matcher that saved every
     * {@code text/html} GET would therefore save that probe, and the very next sign-in would resume
     * to it: the developer submits the login form and lands on a 404 (or a bare JSON body) instead
     * of the application, with nothing on screen or in the log to explain it — the classic "login
     * appears to have failed but the session is fine" report that costs an afternoon. The identical
     * failure reaches any gated URL ending in {@code .json} that is opened as a top-level navigation
     * (an export link pasted into the address bar, a data URL followed from a bookmark), which is
     * what the second stanza pins.
     *
     * <p>
     * The two stanzas share ONE session on purpose: a saved request survives in the session until
     * it is consumed, so if either request were saved it would still be the pending resume target
     * when the login below succeeds. And note which clause actually carries the weight —
     * {@code /.well-known/**} is {@code permitAll} on this chain, so a probe under it never reaches
     * the entry point and never reaches the matcher at all; the {@code .json} suffix clause is the
     * only one of the three that can change an outcome today, which is precisely why deleting it
     * looks like harmless redundancy.
     *
     * <p>
     * {@code testFormLoginRedirectsToSavedRequest} proves the save path works and would stay green
     * through any widening of the matcher, because the request it saves ({@code /chat}) is one the
     * matcher is supposed to accept. Only a request the matcher must REFUSE can show the refusal is
     * still there.
     */
    @Test
    public void aDevtoolsProbeIsNotSavedAsTheRequestToResumeAfterLogin() throws Exception {
        String browserAccept = "text/html,application/xhtml+xml";

        MockHttpSession session = (MockHttpSession) mockMvc
            .perform(get("/.well-known/appspecific/com.chrome.devtools.json").header("Accept",
                browserAccept))
            .andReturn().getRequest().getSession();

        // A gated URL that only the ".json" clause excludes: same session, so a save by either
        // request would still be pending when the login below succeeds.
        mockMvc
            .perform(get("/admin/documents/export.json").session(session).header("Accept",
                browserAccept))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));

        // /admin, not http://localhost/admin/documents/export.json?continue — nothing was saved.
        mockMvc
            .perform(post("/login").with(csrf()).session(session).param("username", "admin")
                .param("password", "password"))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin"));
    }

    /**
     * The shipped {@code app.security.api-key} placeholder must arm NOTHING. This context sets only
     * {@code app.security.mock=true}, so it inherits {@code dev-key-12345} from application.yml —
     * exactly what any deployment that never set {@code APP_SECURITY_API_KEY} runs with — and it is
     * the one existing Spring context in which {@code SecurityConfig} takes the
     * {@code else} branch and does not install {@code ApiKeyAuthenticationFilter} at all.
     *
     * <p>
     * If that branch is ever taken the other way, a string printed in application.yml and in this
     * repository becomes a live credential granting {@code ROLE_INGEST} on the corpus WRITE surface
     * ({@code /api/ingest}, {@code /api/document}, {@code /api/jobs}) — CSRF-disabled chains whose
     * authentication gate is the only thing in front of them. Anyone who has read the repo could
     * then upload files and queue import jobs into any knowledge area: content that becomes
     * searchable by everyone and is cited as authoritative, that spends money on summarisation and
     * embedding, and that is never audited.
     *
     * <p>
     * {@code SecurityConfigMockAuthGuardTest.theShippedDefaultApiKeyIsTreatedAsUnconfigured} pins
     * the PREDICATE by reflection, but nothing pins the WIRING — that the predicate is what gates
     * the {@code addFilterBefore} call. Rewriting the {@code if} into the obvious "simplify the null
     * check" shape ({@code apiKey != null && !apiKey.isBlank()}) leaves that unit test green,
     * because {@code apiKeyConfigured()} still exists on disk and still returns the right answer —
     * it is simply no longer consulted. And {@code SecurityGatingIT} runs with a real key, so it
     * only ever exercises the installed branch; the not-installed branch has no HTTP coverage
     * anywhere.
     *
     * <p>
     * The first assertion is a non-vacuity guard, not decoration: with {@code APP_SECURITY_API_KEY}
     * exported in a developer's shell the IT environment is not hermetic, the filter WOULD be
     * installed here, and every assertion below would be testing something else — that must fail
     * loudly rather than pass for the wrong reason. The last stanza is the other half of the claim:
     * a wrong key must also be 403, because 401 (with body {@code Invalid API Key}) is
     * {@code ApiKeyAuthenticationFilter}'s own short-circuit, so seeing 403 proves the filter is
     * absent from the chain rather than merely rejecting this particular value. Nothing here reaches
     * a controller or the database — the denial happens in the filter chain.
     */
    @Test
    public void theShippedPlaceholderApiKeyDoesNotAuthenticateOverHttp() throws Exception {
        assertThat(configuredApiKey)
            .as("this context must inherit the shipped placeholder; with APP_SECURITY_API_KEY set "
                + "in the environment the filter would be installed and this test would prove "
                + "nothing")
            .isEqualTo("dev-key-12345");

        mockMvc.perform(get("/api/document/v1").header("X-API-Key", configuredApiKey))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/ingest/v1/upload").header("X-API-Key", configuredApiKey))
            .andExpect(status().isForbidden());
        mockMvc
            .perform(get("/api/jobs/v1/" + UUID.randomUUID()).header("X-API-Key", configuredApiKey))
            .andExpect(status().isForbidden());

        // 401 is ApiKeyAuthenticationFilter's own rejection, so a 403 for an arbitrary value proves
        // the filter is not in this chain at all — the other half of the claim.
        mockMvc.perform(get("/api/document/v1").header("X-API-Key", "an-arbitrary-value"))
            .andExpect(result -> assertThat(result.getResponse().getStatus())
                .as("a 401 here would mean the filter is installed with the placeholder")
                .isEqualTo(403));
    }

    @Test
    public void testLogoutRedirectsToLoggedOutPage() throws Exception {
        mockMvc.perform(post("/logout").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/logged-out"));
    }

    /**
     * Logging out has to destroy the session, not merely navigate away from it.
     *
     * <p>
     * {@code testLogoutRedirectsToLoggedOutPage} above asserts one thing — the {@code Location}
     * header — and it is precisely the thing that keeps working when logout stops working. The
     * damaging edit is {@code invalidateHttpSession(false)}, and it is a reasonable-looking edit:
     * it is what you reach for when you want a flash message ("You have been signed out") to survive
     * into {@code /logged-out}, or when a redirect loop makes session invalidation look like the
     * culprit. Nothing about it fails. The redirect is identical, the user sees the logged-out page,
     * and the session object they were authenticated with is still alive on the server for the rest
     * of its timeout — so the next person on a shared or kiosk machine who hits Back, and anyone who
     * captured or replays the {@code JSESSIONID}, resumes the previous user's chats, conversations
     * and admin screens under their identity.
     *
     * <p>
     * The two assertions are deliberately different claims. {@code session.isInvalid()} is the
     * server-side destruction itself. The replay stanza is the consequence: a request presenting the
     * same session must not be handed that session back — under Spring's
     * {@code MockHttpServletRequest} an invalidated session is discarded on the next
     * {@code getSession(false)}, which is exactly the container behaviour that makes the cookie
     * worthless. Note that {@code SecurityContextLogoutHandler} ALSO writes an empty context through
     * its own {@code HttpSessionSecurityContextRepository}, which removes the security attribute even
     * when invalidation is off — so "the replayed request is anonymous" alone would pass straight
     * through the regression, and identity of the session object is what actually distinguishes the
     * two.
     *
     * <p>
     * This drives a REAL form login rather than the {@code user(...)} post-processor on purpose: that
     * post-processor injects authentication through {@code TestSecurityContextHolder} and never
     * touches a {@code SecurityContextRepository}, so there would be no session-borne authentication
     * to destroy and the test would prove nothing. The pre-assertion on
     * {@code SPRING_SECURITY_CONTEXT} is the guard that this really happened.
     */
    @Test
    public void loggingOutInvalidatesTheSessionNotJustTheRedirectTarget() throws Exception {
        MockHttpSession session = (MockHttpSession) mockMvc
            .perform(post("/login").with(csrf()).param("username", "user").param("password",
                "password"))
            .andExpect(redirectedUrl("/chat")).andReturn().getRequest().getSession();

        assertThat(session).as("the form login must have established a session").isNotNull();
        assertThat(session
            .getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
                .as("the authenticated context must live IN the session, or there is nothing for "
                    + "logout to destroy and the assertions below are vacuous")
                .isNotNull();

        mockMvc.perform(post("/logout").with(csrf()).session(session))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/logged-out"));

        assertThat(session.isInvalid())
            .as("logout must destroy the session server-side; leaving it alive lets the next person "
                + "on the machine (or anyone replaying the cookie) resume the previous user")
            .isTrue();

        MvcResult replayed =
            mockMvc.perform(get("/chat").session(session)).andExpect(redirectedUrl("/login"))
                .andReturn();
        assertThat(replayed.getRequest().getSession(false))
            .as("a request presenting the logged-out session must not be given that session back")
            .isNotSameAs(session);
    }

    /**
     * RENAMED from the proposed {@code theShippedPlaceholderApiKeyIsNotALiveCredentialOverHttp},
     * which stated the same claim as the sibling test above and would have been a second copy of it.
     * The precondition it asked for now lives in that test, where it belongs as the vacuity guard;
     * this test pins the distinct invariant underneath it, which nothing anywhere pins today.
     *
     * <p>
     * Two independent files have to agree on one literal for the placeholder to stay inert:
     * application.yml ships {@code api-key: ${APP_SECURITY_API_KEY:dev-key-12345}} and
     * {@code SecurityConfig.DEFAULT_DEV_API_KEY} is the constant {@code apiKeyConfigured()} compares
     * against. Only a human keeps them in sync. Rotate the yml default alone — the natural reflex
     * when the old placeholder looks stale, or when someone "makes the dev key less guessable" —
     * and {@code apiKeyConfigured()} starts returning true for a value that is still a hardcoded
     * default: {@code ApiKeyAuthenticationFilter} is installed in every deployment that never set
     * {@code APP_SECURITY_API_KEY}, armed with a credential committed to this repository, granting
     * {@code ROLE_INGEST} over the CSRF-disabled corpus write surface. Nothing fails to compile,
     * nothing fails to start, and the startup warning that says the endpoints "accept session auth
     * only" stops being printed — which reads as an improvement.
     *
     * <p>
     * No existing test compares the two. {@code SecurityConfigMockAuthGuardTest} constructs
     * {@code SecurityConfig} with the literal {@code "dev-key-12345"} it hardcodes itself, so it
     * never observes what application.yml actually ships and stays green through any rotation of the
     * default. This IT is the only place both values are in scope at once: {@code configuredApiKey}
     * is what the running context resolved, and the constant is read from the class that decides.
     *
     * <p>
     * A developer with {@code APP_SECURITY_API_KEY} exported in their shell will also fail this —
     * deliberately. The IT environment is then not hermetic, and the sibling test above is silently
     * exercising the installed branch; that must be visible rather than absorbed.
     */
    @Test
    public void theShippedApiKeyDefaultIsTheExactStringSecurityConfigTreatsAsUnconfigured()
        throws Exception {
        Field constant = SecurityConfig.class.getDeclaredField("DEFAULT_DEV_API_KEY");
        constant.setAccessible(true);
        String treatedAsUnconfigured = (String) constant.get(null);

        assertThat(configuredApiKey)
            .as("application.yml's app.security.api-key default and "
                + "SecurityConfig.DEFAULT_DEV_API_KEY must stay the same string, or the shipped "
                + "placeholder becomes a live ROLE_INGEST credential in every deployment that "
                + "never set APP_SECURITY_API_KEY")
            .isEqualTo(treatedAsUnconfigured);
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
