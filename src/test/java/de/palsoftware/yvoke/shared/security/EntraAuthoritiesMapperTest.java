package de.palsoftware.yvoke.shared.security;



import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import java.util.stream.Collectors;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import de.palsoftware.yvoke.shared.user.service.UserService;
import java.lang.reflect.Method;
import org.mockito.Mockito;
import org.springframework.mock.env.MockEnvironment;

public class EntraAuthoritiesMapperTest {

    @Test
    public void testMapAuthoritiesWithAdminRole() {
        EntraAuthoritiesMapper mapper = new EntraAuthoritiesMapper("Admin", "User");

        OidcIdToken idToken = new OidcIdToken("token-value", Instant.now(),
            Instant.now().plusSeconds(3600), Map.of("roles", List.of("Admin")));
        OidcUserAuthority authority =
            new OidcUserAuthority(idToken, new OidcUserInfo(Map.of("sub", "admin-sub")));

        Collection<? extends GrantedAuthority> mapped =
            mapper.mapAuthorities(Collections.singletonList(authority));

        assertThat(mapped).extracting(GrantedAuthority::getAuthority).contains("ROLE_ADMIN",
            "ROLE_USER");
    }

    @Test
    public void testMapAuthoritiesWithUserRole() {
        EntraAuthoritiesMapper mapper = new EntraAuthoritiesMapper("Admin", "User");

        OidcIdToken idToken = new OidcIdToken("token-value", Instant.now(),
            Instant.now().plusSeconds(3600), Map.of("roles", List.of("User")));
        OidcUserAuthority authority =
            new OidcUserAuthority(idToken, new OidcUserInfo(Map.of("sub", "user-sub")));

        Collection<? extends GrantedAuthority> mapped =
            mapper.mapAuthorities(Collections.singletonList(authority));

        assertThat(mapped).extracting(GrantedAuthority::getAuthority).contains("ROLE_USER")
            .doesNotContain("ROLE_ADMIN");
    }

    /**
     * The roles→authority derivation exists TWICE — here (OIDC login, reading the ID token) and in
     * {@code SecurityConfig.jwtAuthenticationConverter} (bearer, reading the {@code roles} claim) —
     * with nothing tying them together. Changing one alone is a silent authorization skew: the same
     * Entra user would get {@code ROLE_ADMIN} through the browser and not over MCP, or vice versa.
     * The admin-implies-user rule is the load-bearing part, since {@code /chat/**} requires
     * {@code ROLE_USER} and an admin-only mapping would lock admins out of chat on one path only.
     * This drives both implementations over the same role inputs and asserts they agree.
     */
    @Test
    void theBearerConverterDerivesTheSameRoleAuthoritiesAsTheOidcMapper() throws Exception {
        for (List<String> roles : List.of(List.of("Admin"), List.of("User"), List.of("admin"),
            List.of("USER"), List.of("Admin", "User"), List.of("SomethingElse"),
            List.<String>of())) {

            Set<String> viaOidc = new EntraAuthoritiesMapper("Admin", "User")
                .mapAuthorities(List.of(new OidcUserAuthority(idTokenWithRoles(roles)))).stream()
                .map(GrantedAuthority::getAuthority).filter(a -> a.startsWith("ROLE_"))
                .collect(Collectors.toSet());

            Set<String> viaBearer = bearerRoleAuthorities(roles);

            assertThat(viaBearer).as("the two derivations disagree for roles=%s", roles)
                .isEqualTo(viaOidc);
        }
    }

    /**
     * The {@code else} branch — {@code oidcUserAuthority.getUserInfo().getClaim("roles")} — is the
     * only path that gives a user any authority in a tenant whose app registration emits
     * {@code roles} through the userinfo endpoint rather than in the ID token, which is a
     * per-registration setting nobody in this repo controls. Every other case in this class puts a
     * roles list IN the ID token (the empty-list case included: an empty list is still a present
     * list), so that branch has never executed. Lose it and such a tenant's users authenticate
     * successfully and then get no {@code ROLE_USER} at all: {@code /chat/**} is
     * {@code hasRole("USER")}, so every signed-in person is 403'd on the only page they came for,
     * and the log says nothing because nothing failed.
     *
     * <p>
     * The second stanza pins the direction of the fallback — the ID token WINS, userinfo is
     * consulted only when the ID token carries no roles list at all. Inverting that (or "helpfully"
     * merging both sources) silently promotes a plain user to {@code ROLE_ADMIN} whenever the two
     * claim sets disagree, which is exactly the shape a stale userinfo cache takes.
     *
     * <p>
     * The exact-set assertion also pins the {@code SCOPE_*} pass-through, which both existing tests
     * assert with {@code contains} and therefore cannot see disappear: {@code mapAuthorities}
     * REPLACES the authority collection, so dropping {@code addAll(authorities)} would strip the
     * OIDC scopes off every session.
     */
    @Test
    void rolesComeFromUserinfoOnlyWhenTheIdTokenCarriesNoRolesListAtAll() {
        EntraAuthoritiesMapper mapper = new EntraAuthoritiesMapper("Admin", "User");

        OidcUserAuthority userinfoOnly = new OidcUserAuthority(
            new OidcIdToken("tok", Instant.now(), Instant.now().plusSeconds(300),
                Map.of("sub", "no-roles-in-id-token")),
            new OidcUserInfo(Map.of("sub", "no-roles-in-id-token", "roles", List.of("Admin"))));

        Set<String> mapped =
            mapper.mapAuthorities(List.of(userinfoOnly, new SimpleGrantedAuthority("SCOPE_openid")))
                .stream().map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_") || a.startsWith("SCOPE_"))
                .collect(Collectors.toSet());

        assertThat(mapped)
            .as("a tenant that emits roles only via userinfo must still get its role authorities, "
                + "and the OIDC scopes must survive the mapping")
            .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_USER", "SCOPE_openid");

        OidcUserAuthority bothSources = new OidcUserAuthority(
            new OidcIdToken("tok", Instant.now(), Instant.now().plusSeconds(300),
                Map.of("sub", "both", "roles", List.of("User"))),
            new OidcUserInfo(Map.of("sub", "both", "roles", List.of("Admin"))));

        assertThat(mapper.mapAuthorities(List.of(bothSources)).stream()
            .map(GrantedAuthority::getAuthority).filter(a -> a.startsWith("ROLE_")).toList())
            .as("the ID token is authoritative: userinfo must not be consulted, let alone "
                + "merged, when the ID token already carries a roles list")
            .containsExactly("ROLE_USER");
    }

    private static OidcIdToken idTokenWithRoles(List<String> roles) {
        return new OidcIdToken("tok", Instant.now(), Instant.now().plusSeconds(300),
            Map.of("sub", "s", "roles", roles));
    }

    /** Drives the real bearer converter out of SecurityConfig rather than restating its logic. */
    private static Set<String> bearerRoleAuthorities(List<String> roles) throws Exception {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test");
        SecurityConfig config = new SecurityConfig(Mockito.mock(UserService.class), env, false,
            "a-real-key", "Admin", "User", "api://oim-kb", "api://oim-kb/mcp.read",
            "https://example/keys", "https://example/issuer", "http://localhost:8080/mcp");

        Method m = SecurityConfig.class.getDeclaredMethod("jwtAuthenticationConverter");
        m.setAccessible(true);
        JwtAuthenticationConverter converter = (JwtAuthenticationConverter) m.invoke(config);

        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").claim("sub", "s")
            .claim("roles", roles).build();
        return converter.convert(jwt).getAuthorities().stream().map(GrantedAuthority::getAuthority)
            .filter(a -> a.startsWith("ROLE_")).collect(Collectors.toSet());
    }
}
