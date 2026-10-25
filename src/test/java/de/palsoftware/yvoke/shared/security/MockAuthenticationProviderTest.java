package de.palsoftware.yvoke.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import de.palsoftware.yvoke.shared.user.service.UserService;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

class MockAuthenticationProviderTest {

    private UserService userService;
    private MockAuthenticationProvider provider;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        provider = new MockAuthenticationProvider(userService, "Admin", "User");
    }

    private static List<String> authorityStrings(
        Collection<? extends GrantedAuthority> authorities) {
        return authorities.stream().map(GrantedAuthority::getAuthority).toList();
    }

    @Test
    void authenticateWithSuperadminClaim() {
        Authentication auth = new UsernamePasswordAuthenticationToken("admin", "Admin");
        Authentication result = provider.authenticate(auth);

        assertThat(result.isAuthenticated()).isTrue();
        List<String> auths = authorityStrings(result.getAuthorities());
        assertThat(auths).contains(AreaAccessContext.ROLE_ADMIN, AreaAccessContext.ROLE_AREA_ADMIN,
            AreaAccessContext.ROLE_USER, AreaAccessContext.AREA_ADMIN_ALL,
            AreaAccessContext.AREA_USER_ALL);

        assertThat(result.getPrincipal()).isInstanceOf(MockOidcUser.class);
        MockOidcUser oidcUser = (MockOidcUser) result.getPrincipal();
        assertThat(oidcUser.getClaims().get("roles")).isEqualTo(List.of("Admin"));

        ArgumentCaptor<MockOidcUser> captor = ArgumentCaptor.forClass(MockOidcUser.class);
        verify(userService).syncUser(captor.capture());
        assertThat(captor.getValue().getEmail()).isEqualTo("admin@palsoftware.local");
    }

    @Test
    void authenticateWithGlobalUserClaim() {
        Authentication auth = new UsernamePasswordAuthenticationToken("user", "User");
        Authentication result = provider.authenticate(auth);

        List<String> auths = authorityStrings(result.getAuthorities());
        assertThat(auths).contains(AreaAccessContext.ROLE_USER, AreaAccessContext.AREA_USER_ALL);
        assertThat(auths).doesNotContain(AreaAccessContext.ROLE_ADMIN,
            AreaAccessContext.ROLE_AREA_ADMIN, AreaAccessContext.AREA_ADMIN_ALL);

        MockOidcUser oidcUser = (MockOidcUser) result.getPrincipal();
        assertThat(oidcUser.getClaims().get("roles")).isEqualTo(List.of("User"));
    }

    @Test
    void authenticateWithAreaAdminClaim() {
        Authentication auth = new UsernamePasswordAuthenticationToken("oim-admin", "OIM.Admin");
        Authentication result = provider.authenticate(auth);

        List<String> auths = authorityStrings(result.getAuthorities());
        assertThat(auths).containsExactlyInAnyOrder(AreaAccessContext.ROLE_AREA_ADMIN,
            AreaAccessContext.ROLE_USER, "AREA_ADMIN_OIM", "AREA_USER_OIM");
        assertThat(auths).doesNotContain(AreaAccessContext.ROLE_ADMIN,
            AreaAccessContext.AREA_ADMIN_ALL, AreaAccessContext.AREA_USER_ALL);
    }

    @Test
    void authenticateWithAreaUserClaim() {
        Authentication auth = new UsernamePasswordAuthenticationToken("oim-user", "OIM.User");
        Authentication result = provider.authenticate(auth);

        List<String> auths = authorityStrings(result.getAuthorities());
        assertThat(auths).containsExactlyInAnyOrder(AreaAccessContext.ROLE_USER, "AREA_USER_OIM");
        assertThat(auths).doesNotContain(AreaAccessContext.ROLE_ADMIN,
            AreaAccessContext.ROLE_AREA_ADMIN);
    }

    @Test
    void authenticateWithMultiRoleClaims() {
        Authentication auth =
            new UsernamePasswordAuthenticationToken("alice", "OIM.Admin, PingID.User");
        Authentication result = provider.authenticate(auth);

        List<String> auths = authorityStrings(result.getAuthorities());
        assertThat(auths).contains(AreaAccessContext.ROLE_AREA_ADMIN, AreaAccessContext.ROLE_USER,
            "AREA_ADMIN_OIM", "AREA_USER_OIM", "AREA_USER_PINGID");
        assertThat(auths).doesNotContain("AREA_ADMIN_PINGID", AreaAccessContext.ROLE_ADMIN);

        MockOidcUser oidcUser = (MockOidcUser) result.getPrincipal();
        assertThat(oidcUser.getClaims().get("roles"))
            .isEqualTo(List.of("OIM.Admin", "PingID.User"));
    }

    @Test
    void authenticateBackwardCompatibilityForLegacyUsername() {
        Authentication adminAuth = new UsernamePasswordAuthenticationToken("admin", "password");
        Authentication adminResult = provider.authenticate(adminAuth);
        assertThat(authorityStrings(adminResult.getAuthorities()))
            .contains(AreaAccessContext.ROLE_ADMIN);

        Authentication userAuth = new UsernamePasswordAuthenticationToken("user", "password");
        Authentication userResult = provider.authenticate(userAuth);
        assertThat(authorityStrings(userResult.getAuthorities()))
            .contains(AreaAccessContext.ROLE_USER);
        assertThat(authorityStrings(userResult.getAuthorities()))
            .doesNotContain(AreaAccessContext.ROLE_ADMIN);

        Authentication adminEmptyPass = new UsernamePasswordAuthenticationToken("admin", "");
        Authentication adminEmptyResult = provider.authenticate(adminEmptyPass);
        assertThat(authorityStrings(adminEmptyResult.getAuthorities()))
            .contains(AreaAccessContext.ROLE_ADMIN);
    }

    @Test
    void authenticateWithUsernameAsRoleWhenPasswordIsLegacyPassword() {
        Authentication auth = new UsernamePasswordAuthenticationToken("OIM.Admin", "password");
        Authentication result = provider.authenticate(auth);

        List<String> auths = authorityStrings(result.getAuthorities());
        assertThat(auths).contains("AREA_ADMIN_OIM");
    }

    @Test
    void authenticateWithInvalidClaimsThrowsBadCredentials() {
        Authentication invalidPass =
            new UsernamePasswordAuthenticationToken("bob", "Guest,Anonymous");
        assertThatThrownBy(() -> provider.authenticate(invalidPass))
            .isInstanceOf(BadCredentialsException.class)
            .hasMessageContaining("No valid role authorities");

        Authentication unknownUser = new UsernamePasswordAuthenticationToken("unknown", "password");
        assertThatThrownBy(() -> provider.authenticate(unknownUser))
            .isInstanceOf(BadCredentialsException.class);

        Authentication emptyBoth = new UsernamePasswordAuthenticationToken("", "");
        assertThatThrownBy(() -> provider.authenticate(emptyBoth))
            .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void customAdminAndUserClaims() {
        MockAuthenticationProvider customProvider =
            new MockAuthenticationProvider(userService, "Superuser", "StandardUser");

        Authentication adminAuth = new UsernamePasswordAuthenticationToken("admin", "Superuser");
        Authentication adminResult = customProvider.authenticate(adminAuth);
        assertThat(authorityStrings(adminResult.getAuthorities()))
            .contains(AreaAccessContext.ROLE_ADMIN);

        Authentication userAuth = new UsernamePasswordAuthenticationToken("user", "StandardUser");
        Authentication userResult = customProvider.authenticate(userAuth);
        assertThat(authorityStrings(userResult.getAuthorities()))
            .contains(AreaAccessContext.ROLE_USER);
    }

    @Test
    void supportsOnlyUsernamePasswordAuthenticationToken() {
        assertThat(provider.supports(UsernamePasswordAuthenticationToken.class)).isTrue();
        assertThat(provider.supports(TestingAuthenticationToken.class)).isFalse();
    }
}
