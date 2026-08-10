package de.palsoftware.yvoke.shared.security;



import de.palsoftware.yvoke.shared.user.service.UserService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private static final String DEFAULT_DEV_API_KEY = "dev-key-12345";

    /**
     * Content-Security-Policy for the interactive (cookie/session) chain (SEC-12). {@code
     * frame-ancestors}/{@code object-src}/{@code base-uri} are hard restrictions with no impact on
     * current functionality. {@code script-src}/{@code style-src} still allow
     * {@code 'unsafe-inline'} (and {@code 'unsafe-eval'} for the Mermaid UMD bundle) because the
     * chat client is currently a large inline script (MNT-02); once that is extracted into static
     * modules (Wave 3.1) these can be tightened to nonces. External origins are pinned to the CDNs
     * the templates actually load.
     */
    private static final String CONTENT_SECURITY_POLICY = String.join("; ", "default-src 'self'",
        // unpkg.com removed: htmx is vendored (static/js/htmx.min.js). jsdelivr remains only for
        // mermaid and KaTeX, which are feature-detected and degrade if unreachable.
        "script-src 'self' 'unsafe-inline' 'unsafe-eval' https://cdn.jsdelivr.net",
        "style-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net https://cdnjs.cloudflare.com",
        "img-src 'self' data:",
        "font-src 'self' data: https://cdn.jsdelivr.net https://cdnjs.cloudflare.com",
        "connect-src 'self'", "object-src 'none'", "base-uri 'self'", "frame-ancestors 'none'");

    private final UserService userService;
    private final boolean mockAuth;
    private final String apiKey;
    private final String adminClaim;
    private final String userClaim;
    private final String allowedAudience;
    private final String requiredScope;
    private final String relativeScope;
    private final String jwkSetUri;
    private final String issuerUri;
    private final String resourceUrl;

    public SecurityConfig(UserService userService, Environment environment,
        @Value("${app.security.mock}") boolean mockAuth,
        @Value("${app.security.api-key}") String apiKey,
        @Value("${app.security.roles.admin-claim}") String adminClaim,
        @Value("${app.security.roles.user-claim}") String userClaim,
        @Value("${app.security.mcp.allowed-audience}") String allowedAudience,
        @Value("${app.security.mcp.required-scope}") String requiredScope,
        @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
        @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
        @Value("${app.security.mcp.resource-url}") String resourceUrl) {
        this.userService = userService;
        this.mockAuth = mockAuth;
        this.apiKey = apiKey;
        this.adminClaim = adminClaim;
        this.userClaim = userClaim;
        this.allowedAudience = allowedAudience;
        this.requiredScope = requiredScope;
        this.relativeScope = requiredScope.contains("/")
            ? requiredScope.substring(requiredScope.lastIndexOf("/") + 1)
            : requiredScope;
        this.jwkSetUri = jwkSetUri;
        this.issuerUri = issuerUri;
        this.resourceUrl = resourceUrl;

        if (mockAuth) {
            // Fail CLOSED: mocked auth trusts every credential, so it must never be reachable in a
            // real deployment. Enable it only when an explicit development profile is active; any
            // other environment — including one with NO active profile — refuses to start. k8s sets
            // app.security.mock=false; this is defence in depth against a misconfigured deployment.
            if (!DevProfiles.anyActive(environment)) {
                throw new IllegalStateException(
                    "app.security.mock=true is only permitted while one "
                        + "of the development profiles " + DevProfiles.NAMES + " is active. Active "
                        + "profiles: " + Arrays.toString(environment.getActiveProfiles())
                        + ". Authentication mocking must never run outside local development.");
            }
            log.warn("====================================================================");
            log.warn("app.security.mock=true: authentication is MOCKED. Any /login with");
            log.warn("username 'admin'/'user' (any password) is accepted and ALL bearer");
            log.warn("tokens are trusted. This is for LOCAL DEVELOPMENT ONLY — never enable");
            log.warn("it in a deployed/production environment.");
            log.warn("====================================================================");
        }
    }

    @Order(0)
    @Bean
    public SecurityFilterChain actuatorSecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/actuator/**", "/actuator")
            // Only the liveness/readiness/info probes are anonymous. Anything else (env,
            // loggers,
            // heapdump, prometheus, …) requires authentication, so adding it to the exposure
            // list
            // later cannot silently leak — it must be deliberately authorized. Sensitive
            // endpoints
            // are additionally kept off the public port via management.server.port=9090.
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info")
                .permitAll().anyRequest().authenticated())
            .csrf(csrf -> csrf.disable()).sessionManagement(
                session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    @Order(1)
    @Bean
    public SecurityFilterChain mcpSecurityFilterChain(HttpSecurity http) throws Exception {
        String resourceMetadataUrl =
            resourceUrl.replace("/mcp", "") + "/.well-known/oauth-protected-resource";
        http.securityMatcher("/mcp/**", "/mcp")
            .authorizeHttpRequests(authorize -> authorize.anyRequest().hasAnyAuthority(
                "SCOPE_" + requiredScope, "SCOPE_" + relativeScope, "ROLE_USER", "ROLE_ADMIN"))
            // Bearer-only: never read/write an HTTP session on this chain, so an ambient browser
            // session (from the cookie chain) cannot carry its authorities into MCP (SEC-13).
            .sessionManagement(
                session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setHeader("WWW-Authenticate",
                        "Bearer realm=\"mcp\", resource_metadata=\"" + resourceMetadataUrl + "\"");
                }));

        http.csrf(csrf -> csrf.disable());

        return http.build();
    }

    @Order(2)
    @Bean
    public SecurityFilterChain chatApiSecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/api/chat/**")
            .authorizeHttpRequests(authorize -> authorize.anyRequest().hasAnyAuthority(
                "SCOPE_" + requiredScope, "SCOPE_" + relativeScope, "ROLE_USER", "ROLE_ADMIN"))
            .sessionManagement(
                session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));

        http.csrf(csrf -> csrf.disable());

        return http.build();
    }

    @Order(3)
    @Bean
    public SecurityFilterChain apiKeySecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/api/ingest/**", "/api/document/**", "/api/jobs/**")
            .authorizeHttpRequests(authorize -> authorize
                // ROLE_INGEST is the scoped authority granted to the X-API-Key principal (see
                // ApiKeyAuthenticationFilter); ROLE_ADMIN is how the admin UI's job-progress
                // EventSource reaches this chain with its cookie session.
                //
                // ROLE_USER is deliberately NOT admitted. This is the corpus WRITE surface: a plain
                // signed-in person could otherwise upload files and queue import jobs into ANY
                // knowledge area straight from their browser session — content that then becomes
                // searchable by everyone and is cited as authoritative, that spends money on
                // summarisation and embedding, and that is never audited (only admin-screen imports
                // are). No UI depends on the USER grant: /api/document/** has no browser caller and
                // the only /api/jobs/** consumer is the admin-only job page.
                .anyRequest().hasAnyRole("INGEST", "ADMIN"));
        // NOTE: this chain is intentionally NOT stateless — the admin UI reaches these endpoints
        // with its cookie session (e.g. the job-progress EventSource on /api/jobs/v1/**/progress).
        // CSRF is disabled here for the non-browser X-API-Key clients; the residual CSRF exposure
        // for
        // the cookie path is mitigated by the SameSite=Lax session cookie (SEC-14), which stops the
        // browser from attaching the session to cross-site requests.

        if (apiKeyConfigured()) {
            http.addFilterBefore(new ApiKeyAuthenticationFilter("X-API-Key", apiKey),
                UsernamePasswordAuthenticationFilter.class);
        } else {
            log.warn(
                "app.security.api-key is unset or left at the default placeholder; the X-API-Key "
                    + "REST endpoints (/api/ingest, /api/document, /api/jobs) accept session auth only until a real key is configured.");
        }

        http.csrf(csrf -> csrf.disable());

        // No request cache on this chain. ExceptionTranslationFilter saves the denied request into
        // the RequestCache before commencing the entry point, and without this line that is the
        // default HttpSessionRequestCache (AnyRequestMatcher, createSessionAllowed=true) — so every
        // anonymous or wrong-role hit on the corpus WRITE surface allocated a server-side session
        // holding a DefaultSavedRequest (URL, headers, cookies, query string, all caller-supplied)
        // for the session timeout, with no credential required: unauthenticated memory growth from
        // anything that walks the API. The stateless chains get a NullRequestCache for free; this
        // one is deliberately session-capable (the admin job-progress EventSource arrives with a
        // cookie), so it has to say so explicitly. Nothing is lost — the chain has no login flow,
        // so a saved request is never resumed.
        http.requestCache(cache -> cache.disable());

        return http.build();
    }

    private boolean apiKeyConfigured() {
        return apiKey != null && !apiKey.isBlank() && !DEFAULT_DEV_API_KEY.equals(apiKey);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        HttpSessionRequestCache requestCache = new HttpSessionRequestCache();
        requestCache.setRequestMatcher(request -> {
            if (!"GET".equalsIgnoreCase(request.getMethod())) {
                return false;
            }
            String uri = request.getRequestURI();
            if (uri.contains(".well-known") || uri.contains("com.chrome.devtools")
                || uri.endsWith(".json")) {
                return false;
            }
            String accept = request.getHeader("Accept");
            return accept != null && accept.contains("text/html");
        });

        http.headers(headers -> headers
            .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY)));

        http.requestCache(cache -> cache.requestCache(requestCache))
            .authorizeHttpRequests(authorize -> authorize
                // Public paths: static assets, actuator, login page, RFC 9728 endpoint
                .requestMatchers("/css/**", "/js/**", "/images/**", "/favicon.ico").permitAll()
                .requestMatchers("/actuator/**", "/actuator").permitAll()
                .requestMatchers("/login", "/logged-out", "/error", "/.well-known/**").permitAll()
                // Gated paths
                .requestMatchers("/admin/**").hasRole("ADMIN").requestMatchers("/chat/**")
                .hasRole("USER").requestMatchers("/document/**").hasRole("USER").anyRequest()
                .authenticated());

        if (mockAuth) {
            http.authenticationProvider(new MockAuthenticationProvider(userService))
                .formLogin(form -> form.loginPage("/login").loginProcessingUrl("/login")
                    .successHandler(new CustomAuthenticationSuccessHandler()).permitAll());
        } else {
            http.oauth2Login(oauth2 -> oauth2.loginPage("/login")
                .successHandler(new CustomAuthenticationSuccessHandler())
                .userInfoEndpoint(userInfo -> userInfo.oidcUserService(oidcUserService())
                    .userAuthoritiesMapper(authoritiesMapper())));
        }

        http.logout(logout -> logout.logoutUrl("/logout").logoutSuccessUrl("/logged-out")
            .invalidateHttpSession(true).clearAuthentication(true).permitAll());

        // CSRF protection stays ENABLED on this cookie/session chain (Spring's default). The token
        // is
        // injected into <form th:action> POSTs automatically and exposed via <meta name="_csrf">
        // for
        // fetch/htmx calls (see the layout head fragments). The stateless API/MCP/desktop chains
        // above
        // are token-authenticated and remain CSRF-exempt.

        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        if (mockAuth) {
            return token -> {
                Map<String, Object> headers = Map.of("alg", "none");
                Map<String, Object> claims = Map.of("sub", "mock-mcp-user-sub", "oid",
                    "mock-mcp-user-oid", "preferred_username", "mock-mcp-user@palsoftware.local",
                    "name", "Mock MCP User", "aud", List.of(allowedAudience), "scp",
                    List.of(requiredScope), "roles", List.of(userClaim));
                return new Jwt(token, Instant.now(), Instant.now().plusSeconds(3600), headers,
                    claims);
            };
        } else {
            NimbusJwtDecoder jwtDecoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
            OAuth2TokenValidator<Jwt> audienceValidator = new AudienceValidator(allowedAudience);
            OAuth2TokenValidator<Jwt> withIssuer = JwtValidators.createDefaultWithIssuer(issuerUri);
            OAuth2TokenValidator<Jwt> combinedValidator =
                new DelegatingOAuth2TokenValidator<>(withIssuer, audienceValidator);
            jwtDecoder.setJwtValidator(combinedValidator);
            return jwtDecoder;
        }
    }

    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter converter = new JwtGrantedAuthoritiesConverter();
        JwtAuthenticationConverter jwtConverter = new JwtAuthenticationConverter();
        jwtConverter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<GrantedAuthority> authorities = new ArrayList<>(converter.convert(jwt));

            // Map roles claim if present
            Object rolesClaim = jwt.getClaim("roles");
            if (rolesClaim instanceof List<?> rolesList) {
                for (Object roleObj : rolesList) {
                    if (roleObj instanceof String roleStr) {
                        if (adminClaim.equalsIgnoreCase(roleStr)) {
                            authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
                            authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
                        } else if (userClaim.equalsIgnoreCase(roleStr)) {
                            authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
                        }
                    }
                }
            }
            return authorities;
        });
        return jwtConverter;
    }

    @Bean
    public GrantedAuthoritiesMapper authoritiesMapper() {
        return new EntraAuthoritiesMapper(adminClaim, userClaim);
    }

    private OAuth2UserService<OidcUserRequest, OidcUser> oidcUserService() {
        OidcUserService delegate = new OidcUserService();
        return userRequest -> {
            OidcUser oidcUser = delegate.loadUser(userRequest);
            userService.syncUser(oidcUser);
            return oidcUser;
        };
    }

    private static class AudienceValidator implements OAuth2TokenValidator<Jwt> {
        private final String allowedAudience;

        public AudienceValidator(String allowedAudience) {
            this.allowedAudience = allowedAudience;
        }

        @Override
        public OAuth2TokenValidatorResult validate(Jwt jwt) {
            List<String> audiences = jwt.getAudience();
            if (audiences != null) {
                String cleanAudience =
                    allowedAudience.startsWith("api://") ? allowedAudience.substring(6)
                        : allowedAudience;
                if (audiences.contains(allowedAudience) || audiences.contains(cleanAudience)) {
                    return OAuth2TokenValidatorResult.success();
                }
            }
            return OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", "The required audience is missing", null));
        }
    }

    private static class MockAuthenticationProvider implements AuthenticationProvider {
        private final UserService userService;

        public MockAuthenticationProvider(UserService userService) {
            this.userService = userService;
        }

        @Override
        public Authentication authenticate(Authentication authentication)
            throws AuthenticationException {
            String username = authentication.getName();
            String password = authentication.getCredentials().toString();

            boolean isAdmin = "admin".equals(username);
            boolean isUser = "user".equals(username);

            if (!isAdmin && !isUser) {
                throw new BadCredentialsException("Invalid username or password");
            }

            List<GrantedAuthority> authorities = new ArrayList<>();
            authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
            if (isAdmin) {
                authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
            }

            String entraOid = isAdmin ? "mock-admin-oid" : "mock-user-oid";
            String email = isAdmin ? "mock-admin@palsoftware.local" : "mock-user@palsoftware.local";
            String displayName = isAdmin ? "Mock Admin" : "Mock User";

            MockOidcUser oidcUser = new MockOidcUser(entraOid, email, displayName, authorities);
            userService.syncUser(oidcUser);

            return new UsernamePasswordAuthenticationToken(oidcUser, password, authorities);
        }

        @Override
        public boolean supports(Class<?> authentication) {
            return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
        }
    }

    public static class CustomAuthenticationSuccessHandler
        extends SavedRequestAwareAuthenticationSuccessHandler {
        @Override
        public void onAuthenticationSuccess(HttpServletRequest request,
            HttpServletResponse response, Authentication authentication)
            throws ServletException, IOException {
            SavedRequest savedRequest = new HttpSessionRequestCache().getRequest(request, response);
            if (savedRequest != null) {
                super.onAuthenticationSuccess(request, response, authentication);
                return;
            }

            boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));

            if (isAdmin) {
                getRedirectStrategy().sendRedirect(request, response, "/admin");
            } else {
                getRedirectStrategy().sendRedirect(request, response, "/chat");
            }
        }
    }
}
