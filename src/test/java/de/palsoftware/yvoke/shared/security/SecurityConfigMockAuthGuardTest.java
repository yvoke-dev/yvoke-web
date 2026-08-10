package de.palsoftware.yvoke.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import de.palsoftware.yvoke.shared.user.service.UserService;
import java.util.List;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import java.lang.reflect.Method;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import java.time.Instant;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import java.io.IOException;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.lang.reflect.Constructor;

/**
 * SEC-09: mock authentication must fail CLOSED. It may only be enabled while an explicit
 * development profile ({@code dev}/{@code local}/{@code test}) is active; in any other environment
 * (including no active profile, or a production profile) the context must refuse to start rather
 * than silently trusting every credential.
 */
class SecurityConfigMockAuthGuardTest {

    /**
     * Every bearer test in this repository runs under the MOCK {@code JwtDecoder}, which validates
     * NOTHING — it fabricates claims from the string it is handed. So the real decoder's validator
     * chain, which is the entire authentication of {@code /mcp} and {@code /api/chat/**}, has never
     * been executed by any test at any tier, and the two ITs that exercise those paths would stay
     * green if it were deleted outright.
     *
     * <p>
     * The audience check is the one that matters most and is the easiest to lose: it is a
     * hand-written validator combined into the chain by ONE expression, while the issuer and expiry
     * checks arrive as a package from {@code JwtValidators.createDefaultWithIssuer}. Dropping it
     * makes every token Entra issues to that tenant — for Microsoft Graph, for Teams, for any other
     * app registration a user has consented to — a valid credential here, because such a token
     * carries the right issuer, a live expiry, a real signature from the same JWKS, and only its
     * {@code aud} says it was minted for somebody else. That is a full authentication bypass for
     * the corpus API, reachable by any employee, with no error and no log line.
     *
     * <p>
     * {@code audienceAccepted} above drives {@code AudienceValidator} in isolation and therefore
     * cannot see it stop being WIRED IN; this test reads the validator the decoder actually
     * carries. The accepted token is the vacuity guard — without it, a chain that rejected
     * everything (e.g. a mistyped issuer) would satisfy all three rejection assertions and read as
     * a pass.
     */
    @Test
    void theRealBearerDecoderRejectsAForeignAudienceAForeignIssuerAndAnExpiredToken()
        throws Exception {
        String issuer = "https://login.microsoftonline.com/tenant-id/v2.0";
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        SecurityConfig production = new SecurityConfig(mock(UserService.class), environment, false,
            "a-real-deployment-key", "Admin", "User", "api://oim-kb", "api://oim-kb/mcp.read",
            "https://login.microsoftonline.com/tenant-id/discovery/v2.0/keys", issuer,
            "http://localhost:8080/mcp");

        var validatorField = NimbusJwtDecoder.class.getDeclaredField("jwtValidator");
        validatorField.setAccessible(true);
        @SuppressWarnings("unchecked")
        OAuth2TokenValidator<Jwt> validator =
            (OAuth2TokenValidator<Jwt>) validatorField.get(production.jwtDecoder());

        Instant now = Instant.now();
        Jwt accepted = Jwt.withTokenValue("t").header("alg", "RS256").claim("sub", "s")
            .claim("iss", issuer).claim("aud", List.of("api://oim-kb"))
            .issuedAt(now.minusSeconds(60)).expiresAt(now.plusSeconds(600)).build();
        Jwt foreignAudience = Jwt.withTokenValue("t").header("alg", "RS256").claim("sub", "s")
            .claim("iss", issuer).claim("aud", List.of("https://graph.microsoft.com"))
            .issuedAt(now.minusSeconds(60)).expiresAt(now.plusSeconds(600)).build();
        Jwt foreignIssuer = Jwt.withTokenValue("t").header("alg", "RS256").claim("sub", "s")
            .claim("iss", "https://login.microsoftonline.com/another-tenant/v2.0")
            .claim("aud", List.of("api://oim-kb")).issuedAt(now.minusSeconds(60))
            .expiresAt(now.plusSeconds(600)).build();
        Jwt expired = Jwt.withTokenValue("t").header("alg", "RS256").claim("sub", "s")
            .claim("iss", issuer).claim("aud", List.of("api://oim-kb"))
            .issuedAt(now.minusSeconds(7200)).expiresAt(now.minusSeconds(3600)).build();

        assertThat(validator.validate(accepted).hasErrors())
            .as("a token minted for THIS api registration, by this tenant, still in date must pass "
                + "— otherwise the three rejections below prove nothing")
            .isFalse();
        assertThat(validator.validate(foreignAudience).hasErrors())
            .as("a token whose aud names another app is a credential for another app; accepting it "
                + "makes every token in the tenant a key to the corpus API")
            .isTrue();
        assertThat(validator.validate(foreignIssuer).hasErrors())
            .as("a token from another tenant must not authenticate here").isTrue();
        assertThat(validator.validate(expired).hasErrors())
            .as("an expired token must not authenticate here").isTrue();
    }

    private SecurityConfig buildWithApiKey(String apiKey) {
        MockEnvironment environment = new MockEnvironment().withProperty("unused", "x");
        environment.setActiveProfiles("test");
        return new SecurityConfig(mock(UserService.class), environment, false, apiKey, "Admin",
            "User", "api://oim-kb", "api://oim-kb/mcp.read", "https://example/keys",
            "https://example/issuer", "http://localhost:8080/mcp");
    }

    /**
     * The CSP and the templates are a two-place contract that only a human keeps aligned, and it
     * fails SILENTLY in both directions. Add a CDN to a template and the browser refuses the asset
     * with nothing but a console violation; drop an origin from
     * {@code SecurityConfig.CONTENT_SECURITY_POLICY} and the asset that was already there stops
     * loading. Neither shows up server-side: the page still renders, the response is still 200, and
     * every server-side test still passes.
     *
     * <p>
     * What actually breaks is invisible to the rest of the suite as well. Mermaid and KaTeX are
     * loaded from {@code cdn.jsdelivr.net} in {@code chat/layout.html} and are feature-detected, so
     * a blocked script degrades to raw fence text and raw {@code $…$} in answers rather than
     * erroring; Font Awesome comes from {@code cdnjs.cloudflare.com} on the playbook/prompt admin
     * pages, and a blocked stylesheet leaves unlabelled buttons. The browser e2e tier cannot see
     * any of it either — {@code AbstractE2E} aborts every non-localhost request, so those CDNs are
     * unreachable there by construction and an assertion would pass whether the origin is allowed
     * or not. {@code SecurityMockGatingIT} asserts only {@code frame-ancestors}/{@code object-src}/
     * {@code base-uri}, i.e. the three directives that have no relationship to the templates.
     *
     * <p>
     * Stylesheet hosts are checked against {@code font-src} as well as {@code style-src} on
     * purpose: a CDN stylesheet fetches its own webfonts from the same origin (KaTeX's glyphs, Font
     * Awesome's icon font), so allowing the CSS without the font is the half-fix that renders tofu
     * boxes where the icons should be.
     */
    @Test
    void everyCdnOriginTheTemplatesLoadIsAllowedByTheContentSecurityPolicy() throws Exception {
        Field policyField = SecurityConfig.class.getDeclaredField("CONTENT_SECURITY_POLICY");
        policyField.setAccessible(true);
        String policy = (String) policyField.get(null);

        BiFunction<String, String, String> directive = (name, pol) -> Arrays.stream(pol.split(";"))
            .map(String::trim).filter(d -> d.startsWith(name + " ")).findFirst().orElseThrow(
                () -> new AssertionError("the CSP declares no " + name + " directive: " + pol));

        Path templateRoot = Path.of("src/main/resources/templates");
        assertThat(Files.isDirectory(templateRoot))
            .as("templates must be readable from the module root, or this test proves nothing")
            .isTrue();

        Pattern remoteAsset =
            Pattern.compile("<(script|link)\\b[^>]*?\\b(?:src|href)=\"https://([^/\"]+)",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

        Set<String> scriptHosts = new LinkedHashSet<>();
        Set<String> styleHosts = new LinkedHashSet<>();
        try (Stream<Path> walked = Files.walk(templateRoot)) {
            for (Path template : walked.filter(p -> p.toString().endsWith(".html")).toList()) {
                Matcher matcher = remoteAsset.matcher(Files.readString(template));
                while (matcher.find()) {
                    ("script".equalsIgnoreCase(matcher.group(1)) ? scriptHosts : styleHosts)
                        .add(matcher.group(2));
                }
            }
        }

        assertThat(scriptHosts).as("no remote <script> found at all — the scan is broken, since "
            + "chat/layout.html loads mermaid and KaTeX from a CDN").isNotEmpty();
        assertThat(styleHosts).as("no remote <link> found at all — the scan is broken, since the "
            + "playbook/prompt admin pages load Font Awesome from a CDN").isNotEmpty();

        for (String host : scriptHosts) {
            assertThat(directive.apply("script-src", policy))
                .as("a template loads a <script> from %s; script-src must list it or the browser "
                    + "silently refuses it", host)
                .contains(host);
        }
        for (String host : styleHosts) {
            assertThat(directive.apply("style-src", policy))
                .as("a template loads a stylesheet from %s; style-src must list it", host)
                .contains(host);
            assertThat(directive.apply("font-src", policy))
                .as("the stylesheet from %s fetches its webfonts from the same origin, so font-src "
                    + "must list it too — otherwise the CSS loads and the glyphs do not", host)
                .contains(host);
        }
    }

    private static boolean apiKeyConfigured(SecurityConfig config) throws Exception {
        Method m = SecurityConfig.class.getDeclaredMethod("apiKeyConfigured");
        m.setAccessible(true);
        return (boolean) m.invoke(config);
    }

    /**
     * The shipped default {@code app.security.api-key} must count as UNCONFIGURED, so
     * {@code ApiKeyAuthenticationFilter} is never registered with it. Treating it as a real key
     * would mean every deployment that forgot to override the value ships a publicly-known
     * credential granting ROLE_INGEST on /api/ingest, /api/document and /api/jobs — i.e. the ingest
     * endpoints would be open to anyone who read the repo. Blank and null must be inert for the
     * same reason; only a genuinely set key arms the filter.
     */
    @Test
    void theShippedDefaultApiKeyIsTreatedAsUnconfigured() throws Exception {
        assertThat(apiKeyConfigured(buildWithApiKey("dev-key-12345")))
            .as("the documented default must never arm the API-key filter").isFalse();
        assertThat(apiKeyConfigured(buildWithApiKey(""))).isFalse();
        assertThat(apiKeyConfigured(buildWithApiKey("   "))).isFalse();
        assertThat(apiKeyConfigured(buildWithApiKey(null))).isFalse();
        assertThat(apiKeyConfigured(buildWithApiKey("a-real-deployment-key"))).isTrue();
    }

    @SuppressWarnings("unchecked")
    private static boolean audienceAccepted(String allowedAudience, List<String> tokenAudiences)
        throws Exception {
        Class<?> cls =
            Class.forName("de.palsoftware.yvoke.shared.security.SecurityConfig$AudienceValidator");
        Constructor<?> ctor = cls.getDeclaredConstructor(String.class);
        ctor.setAccessible(true);
        Object validator = ctor.newInstance(allowedAudience);

        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").claim("aud", tokenAudiences)
            .claim("sub", "s").build();
        Method validate = cls.getDeclaredMethod("validate", Jwt.class);
        validate.setAccessible(true);
        OAuth2TokenValidatorResult result =
            (OAuth2TokenValidatorResult) validate.invoke(validator, jwt);
        return !result.hasErrors();
    }

    /**
     * The audience check is the ONLY app-authored validator in the bearer path (issuer and expiry
     * come from Spring's {@code JwtValidators}), and it is what stops a token Entra minted for a
     * DIFFERENT resource in the same tenant from being replayed against {@code /mcp} and
     * {@code /api/chat/**}. If it ever failed open — an inverted branch, a null-audience early
     * return, a switch from exact list membership to {@code startsWith} — every bearer token from
     * any app in the tenant would decode successfully and fall through to the chain's authority
     * check alone, which a bare {@code ROLE_USER} satisfies. Nothing else in the repo would notice.
     */
    @Test
    void aTokenMintedForAnotherResourceIsRejectedByTheAudienceValidator() throws Exception {
        // Accepted: the configured value, and the same value without the api:// scheme.
        assertThat(audienceAccepted("api://oim-kb", List.of("api://oim-kb"))).isTrue();
        assertThat(audienceAccepted("api://oim-kb", List.of("oim-kb"))).isTrue();
        assertThat(audienceAccepted("api://oim-kb", List.of("other", "api://oim-kb"))).isTrue();

        // Rejected: another resource in the same tenant, no audience at all, and — the one a
        // prefix/substring rewrite would wrongly admit — an audience that merely STARTS WITH ours.
        assertThat(audienceAccepted("api://oim-kb", List.of("api://some-other-api"))).isFalse();
        assertThat(audienceAccepted("api://oim-kb", List.of())).isFalse();
        assertThat(audienceAccepted("api://oim-kb", List.of("api://oim-kb-evil"))).isFalse();
        assertThat(audienceAccepted("api://oim-kb", List.of("oim-kb-evil"))).isFalse();
    }

    private SecurityConfig build(boolean mockAuth, String... activeProfiles) {
        Environment environment = new MockEnvironment().withProperty("unused", "x");
        ((MockEnvironment) environment).setActiveProfiles(activeProfiles);
        return new SecurityConfig(mock(UserService.class), environment, mockAuth, "dev-key-12345",
            "Admin", "User", "api://oim-kb", "api://oim-kb/mcp.read", "https://example/keys",
            "https://example/issuer", "http://localhost:8080/mcp");
    }

    /**
     * Mock auth trusts EVERY bearer token: {@code jwtDecoder()} never verifies a signature, it
     * simply mints a token from the configured claims and hands it back. The one thing that keeps
     * that survivable on a developer machine is WHICH claims it mints — the roles claim decides,
     * via {@code jwtAuthenticationConverter}, whether the synthetic principal is {@code ROLE_USER}
     * or {@code ROLE_ADMIN}, and the admin branch also grants {@code ROLE_USER}, so seeding it with
     * {@code adminClaim} is a strict superset that no functional test would ever fail on.
     * Everything would still work, and better: every local MCP/desktop request, every eval run and
     * every scripted client would silently execute as an administrator against {@code /admin/**},
     * the corpus write surface and the cost dashboard, with no credential involved. Bugs that only
     * appear for ordinary users — a missing grant, an ownership check that was never exercised —
     * become invisible locally and ship.
     *
     * <p>
     * No existing test would notice. {@code testMcpEndpointAcceptsMockBearerToken} asserts only
     * that the mock token is not 401/403 on {@code /mcp}, which an admin token satisfies just as
     * well, and the mock form-login path builds its authorities in
     * {@code MockAuthenticationProvider} — a completely separate code path that this decoder never
     * touches. The second half of the test runs the real (private) converter, because the claim on
     * its own only matters through the authority it produces: it is the {@code ROLE_ADMIN} that is
     * the incident, not the string.
     */
    @Test
    void theMockJwtDecoderMintsAUserTokenAndNeverAnAdminOne() throws Exception {
        SecurityConfig config = build(true, "test");

        Jwt token = config.jwtDecoder().decode("anything");

        assertThat(token.getSubject()).as("the synthetic MCP principal is a fixed, recognisable id")
            .isEqualTo("mock-mcp-user-sub");
        assertThat(token.getClaimAsStringList("roles"))
            .as("the mock token must carry the USER claim and nothing else")
            .containsExactly("User");
        assertThat(token.getClaimAsStringList("roles"))
            .as("mocked auth must never hand out the admin claim").doesNotContain("Admin");
        assertThat(token.getAudience()).containsExactly("api://oim-kb");

        // The claim only matters through the authority it becomes: ROLE_ADMIN here is the incident.
        Method converterMethod =
            SecurityConfig.class.getDeclaredMethod("jwtAuthenticationConverter");
        converterMethod.setAccessible(true);
        JwtAuthenticationConverter converter =
            (JwtAuthenticationConverter) converterMethod.invoke(config);
        AbstractAuthenticationToken authentication = converter.convert(token);

        assertThat(authentication.getAuthorities()).extracting("authority")
            .as("a mock MCP caller is an ordinary user").contains("ROLE_USER");
        assertThat(authentication.getAuthorities()).extracting("authority")
            .as("a mock MCP caller must never be an administrator").doesNotContain("ROLE_ADMIN");
    }

    /**
     * The real (non-mock) browser login path is the ONLY place a {@code users} row is created for a
     * person signing in through Entra: {@code oidcUserService()} wraps Spring's
     * {@code OidcUserService} purely so that {@code userService.syncUser(oidcUser)} runs on every
     * successful OIDC login. Dropping that one call is completely silent — login still succeeds, no
     * exception is thrown, no 401/403 appears — but the local row is never written, so
     * {@code UserService.getCurrentUser()} returns {@code Optional.empty()} for that person forever
     * after. Every conversation they create is then persisted with {@code user_id = null}, and
     * {@code ChatConversationService.verifyConversationOwnership} compares ownership with
     * {@code Objects.equals(conversation.userId(), currentUserId)} — which is {@code true} for
     * {@code null == null}. Every unsynced user passes the ownership check on every null-owned
     * conversation, including each other's: a cross-user read of private chat threads produced by
     * deleting one line, with a green build.
     *
     * <p>
     * Nothing else covers it. Every {@code @SpringBootTest} in the repo runs with
     * {@code app.security.mock=true}, which takes the {@code MockAuthenticationProvider} branch of
     * {@code securityFilterChain} — a different call site with its own {@code syncUser} call — so
     * the OIDC lambda is never executed by the suite. This test drives the real lambda directly.
     * The {@code ClientRegistration} deliberately declares NO user-info endpoint URI, so
     * {@code OidcUserRequestUtils.shouldRetrieveUserInfo} is false and the delegate resolves the
     * principal from the id token alone — no network call, no stubbing of Spring internals. The
     * assertion that matters is the captured principal's {@code oid}: that claim is the key
     * {@code syncUser} upserts on, so syncing some other object would be just as broken as not
     * syncing at all.
     */
    @Test
    void anOidcLoginSyncsTheUserToTheLocalDatabase() throws Exception {
        UserService userService = mock(UserService.class);
        MockEnvironment environment = new MockEnvironment().withProperty("unused", "x");
        environment.setActiveProfiles("test");
        SecurityConfig config = new SecurityConfig(userService, environment, false, "dev-key-12345",
            "Admin", "User", "api://oim-kb", "api://oim-kb/mcp.read", "https://example/keys",
            "https://example/issuer", "http://localhost:8080/mcp");

        Method serviceMethod = SecurityConfig.class.getDeclaredMethod("oidcUserService");
        serviceMethod.setAccessible(true);
        @SuppressWarnings("unchecked")
        OAuth2UserService<OidcUserRequest, OidcUser> oidcUserService =
            (OAuth2UserService<OidcUserRequest, OidcUser>) serviceMethod.invoke(config);

        // No userInfoUri: OidcUserService.shouldRetrieveUserInfo() is false, so the delegate builds
        // the principal from the id token alone and this test makes no network call.
        ClientRegistration registration = ClientRegistration.withRegistrationId("entra")
            .clientId("client-id").clientSecret("client-secret")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("http://localhost:8080/login/oauth2/code/entra").scope("openid", "profile")
            .authorizationUri("https://example/authorize").tokenUri("https://example/token")
            .build();
        OidcIdToken idToken = OidcIdToken.withTokenValue("id-token").claim("sub", "entra-sub")
            .claim("oid", "entra-object-id").claim("name", "Ada Lovelace")
            .claim("preferred_username", "ada@example.com").build();
        OAuth2AccessToken accessToken = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
            "access-token", Instant.now(), Instant.now().plusSeconds(3600));

        OidcUser loaded =
            oidcUserService.loadUser(new OidcUserRequest(registration, accessToken, idToken));

        ArgumentCaptor<OidcUser> synced = ArgumentCaptor.forClass(OidcUser.class);
        verify(userService).syncUser(synced.capture());
        assertThat(synced.getValue().getClaimAsString("oid"))
            .as("syncUser must receive the freshly loaded principal, keyed on its Entra oid")
            .isEqualTo("entra-object-id");
        assertThat(synced.getValue().getClaimAsString("preferred_username"))
            .isEqualTo("ada@example.com");
        assertThat(loaded).as("the wrapper must return the delegate's principal unchanged")
            .isSameAs(synced.getValue());
    }

    /**
     * The value {@code src/main/resources/application.yml} ACTUALLY ships as the default for
     * {@code app.security.api-key}, read from the file itself instead of from a literal copied into
     * this test. Loaded with {@link YamlPropertySourceLoader} into a {@link MockEnvironment} —
     * which, unlike {@code StandardEnvironment}, contributes no {@code systemEnvironment} property
     * source, so a developer's exported {@code APP_SECURITY_API_KEY} cannot change the answer — and
     * read back through {@code getProperty}, whose nested-placeholder resolution yields the
     * {@code ${...:default}} fallback. The file is a single YAML document, so the load is
     * unambiguous; that is asserted rather than assumed.
     */
    private static String shippedApiKeyDefault() throws IOException {
        List<PropertySource<?>> loaded = new YamlPropertySourceLoader().load("application.yml",
            new ClassPathResource("application.yml"));
        assertThat(loaded).as("application.yml must be a single YAML document on the classpath")
            .hasSize(1);
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addLast(loaded.get(0));
        String shipped = environment.getProperty("app.security.api-key");
        assertThat(shipped).as("application.yml must declare app.security.api-key").isNotNull();
        return shipped;
    }

    /**
     * The default {@code application.yml} really ships must be a value the gate REJECTS. The
     * sibling {@code theShippedDefaultApiKeyIsTreatedAsUnconfigured} writes the literal
     * {@code "dev-key-12345"} on the test side and compares it with the same literal on the
     * production side ({@code SecurityConfig.DEFAULT_DEV_API_KEY}), so the two agree by
     * construction and neither of them ever opens {@code application.yml}. Edit line 107 of that
     * file alone — an entirely plausible change ("make the dev key less confusing", "rotate the
     * placeholder") — and the shipped default becomes a value {@code apiKeyConfigured()} accepts as
     * real: {@code ApiKeyAuthenticationFilter} then arms in every environment that never set
     * {@code APP_SECURITY_API_KEY}, granting ROLE_INGEST on /api/ingest, /api/document and
     * /api/jobs to anyone holding a key that is readable straight out of the repository. Nothing
     * fails and nothing warns — the startup warning that is supposed to say "unconfigured" simply
     * stops being logged, and {@code SecurityGatingIT} cannot see it either because it pins its own
     * {@code app.security.api-key} property.
     *
     * <p>
     * Deliberately NOT asserted: that the shipped value equals a hard-coded literal. Pinning it
     * that way would recreate the very "both sides agree by construction" weakness this test exists
     * to remove, and would also fail a legitimate coordinated rotation of the yml default and
     * {@code DEFAULT_DEV_API_KEY} together. The invariant is the behaviour: whatever
     * {@code application.yml} ships must leave the filter off.
     */
    @Test
    void theDefaultApiKeyInApplicationYmlIsTheOneTheGateRejects() throws Exception {
        String shipped = shippedApiKeyDefault();

        assertThat(shipped).as("app.security.api-key must keep a placeholder default").isNotBlank();
        assertThat(apiKeyConfigured(buildWithApiKey(shipped)))
            .as("application.yml ships <%s> as the app.security.api-key default; "
                + "SecurityConfig must treat it as UNCONFIGURED and leave the filter off", shipped)
            .isFalse();
    }

    @Test
    void mockAuthWithoutAnyProfileIsRejected() {
        assertThatThrownBy(() -> build(true)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("app.security.mock");
    }

    /**
     * {@code additional-spring-configuration-metadata.json} is the THIRD hand-maintained copy of
     * this one string (after {@code application.yml} line 107 and
     * {@code SecurityConfig.DEFAULT_DEV_API_KEY}), and it is the copy a human reads first: it is
     * what IDE autocomplete shows for {@code app.security.api-key}, and the string an operator is
     * most likely to copy out of the documentation. If it drifts, the documented claim that this
     * default is "treated as unconfigured" becomes false for the very value an operator copies out
     * of the documentation — they set the documented key, believe the ingest endpoints are
     * key-protected, and in fact {@code apiKeyConfigured()} either still refuses to arm the filter
     * or arms it on a published string.
     *
     * <p>
     * Be honest about the blast radius: unlike the sibling checks, drift here changes no runtime
     * behaviour on its own — it only misleads whoever reads the documentation. That is why this is
     * two assertions next to the {@code application.yml} check rather than an IT of its own. No
     * other test in the repository opens this metadata file at all, so nothing else would notice.
     */
    @Test
    void theDocumentedDefaultApiKeyMatchesTheValueApplicationYmlActuallyShips() throws Exception {
        JsonNode metadata = new ObjectMapper().readTree(
            new ClassPathResource("META-INF/additional-spring-configuration-metadata.json")
                .getInputStream());
        JsonNode documented = null;
        for (JsonNode property : metadata.path("properties")) {
            if ("app.security.api-key".equals(property.path("name").asText())) {
                documented = property;
            }
        }
        assertThat(documented)
            .as("app.security.api-key must stay documented in the configuration metadata")
            .isNotNull();

        String documentedDefault = documented.path("defaultValue").asText();
        assertThat(documentedDefault)
            .as("the documented default must be the one application.yml really ships")
            .isEqualTo(shippedApiKeyDefault());
        assertThat(apiKeyConfigured(buildWithApiKey(documentedDefault)))
            .as("the documented default must be rejected by apiKeyConfigured()").isFalse();
    }

    @Test
    void mockAuthUnderProductionProfileIsRejected() {
        assertThatThrownBy(() -> build(true, "prod")).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("app.security.mock");
    }

    @Test
    void mockAuthUnderTestProfileIsAllowed() {
        assertThatCode(() -> build(true, "test")).doesNotThrowAnyException();
    }

    @Test
    void mockAuthUnderLocalProfileIsAllowed() {
        assertThatCode(() -> build(true, "local")).doesNotThrowAnyException();
    }

    @Test
    void realAuthOutsideDevProfilesIsAllowed() {
        SecurityConfig config = build(false, "prod");
        assertThat(config).isNotNull();
    }
}
