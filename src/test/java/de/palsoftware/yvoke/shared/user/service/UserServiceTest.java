package de.palsoftware.yvoke.shared.user.service;

import de.palsoftware.yvoke.shared.user.model.*;
import de.palsoftware.yvoke.shared.user.repository.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.jwt.Jwt;
import org.mockito.ArgumentCaptor;
import java.time.Instant;
import java.util.UUID;

public class UserServiceTest {

    private UserRepository userRepository;
    private UserService userService;
    private SecurityContext originalContext;

    /**
     * A bearer token with NO {@code oid} claim must still resolve to a user — via {@code sub} — and
     * its email must fall back to the {@code email} claim when {@code preferred_username} is
     * absent.
     *
     * <p>
     * On an Entra ACCESS token both {@code oid} and {@code preferred_username} are OPTIONAL claims:
     * a personal Microsoft account, and any app registration that has not opted into the
     * optional-claims set, sends neither. {@code testGetCurrentUserWithJwt} stubs both, so it
     * drives the happy path only and every fallback branch here is unexecuted by the whole suite —
     * the mock {@code JwtDecoder} used by {@code app.security.mock=true} synthesises both claims
     * too, so this is not reachable locally either.
     *
     * <p>
     * If {@code sub} stopped backfilling the oid, {@code getCurrentUser} would return
     * {@code Optional.empty()} for those tokens: no {@code users} row is provisioned,
     * {@code UserArgumentResolver} has nothing to resolve, and every {@code /api/chat/v1} and MCP
     * request from that client 401s while the token itself is perfectly valid. The email half is
     * quieter and permanent — the row is created with a NULL email, so the cost dashboard's user
     * picker and top-users report show a blank entry for exactly the heavy MCP users an operator
     * most wants to identify.
     */
    @Test
    public void testGetCurrentUserFallsBackToSubAndEmailWhenEntraOmitsTheOptionalClaims() {
        Jwt jwt = mock(Jwt.class);
        when(jwt.getClaimAsString("oid")).thenReturn(null);
        when(jwt.getSubject()).thenReturn("desktop-sub-1234");
        when(jwt.getClaimAsString("preferred_username")).thenReturn(null);
        when(jwt.getClaimAsString("email")).thenReturn("desktop@contoso.com");
        when(jwt.getClaimAsString("name")).thenReturn(null);

        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getPrincipal()).thenReturn(jwt);
        SecurityContextHolder.getContext().setAuthentication(auth);

        // Stubbed for the SUB only: an unstubbed lookup yields Optional.empty(), so a resolved user
        // is what proves the fallback ran rather than any argument this test supplied.
        User provisioned = new User(UUID.randomUUID(), "desktop-sub-1234", "desktop@contoso.com",
            null, Instant.now());
        when(userRepository.findByEntraOid("desktop-sub-1234"))
            .thenReturn(Optional.of(provisioned));

        Optional<User> user = userService.getCurrentUser();

        assertThat(user).as("a token carrying no oid claim must still resolve to a user")
            .isPresent();

        ArgumentCaptor<String> oid = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> email = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> displayName = ArgumentCaptor.forClass(String.class);
        verify(userRepository).upsert(oid.capture(), email.capture(), displayName.capture());

        assertThat(oid.getValue()).as("sub becomes the entra_oid when oid is absent")
            .isEqualTo("desktop-sub-1234");
        assertThat(email.getValue()).as("the email claim backfills a missing preferred_username")
            .isEqualTo("desktop@contoso.com");
        assertThat(displayName.getValue())
            .as("an absent name claim stays absent, it is not invented").isNull();
    }

    @BeforeEach
    public void setUp() {
        userRepository = mock(UserRepository.class);
        userService = new UserService(userRepository);
        originalContext = SecurityContextHolder.getContext();
        SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
    }

    @AfterEach
    public void tearDown() {
        SecurityContextHolder.setContext(originalContext);
    }

    @Test
    public void testGetCurrentUserDisplayNameWhenUnauthenticated() {
        SecurityContextHolder.clearContext();
        String name = userService.getCurrentUserDisplayName();
        assertThat(name).isEqualTo("Guest");
    }

    @Test
    public void testGetCurrentUserDisplayNameWithOidcUserHavingName() {
        OidcUser oidcUser = mock(OidcUser.class);
        when(oidcUser.getClaimAsString("name")).thenReturn("Eduard Pal");

        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getPrincipal()).thenReturn(oidcUser);
        SecurityContextHolder.getContext().setAuthentication(auth);

        String name = userService.getCurrentUserDisplayName();
        assertThat(name).isEqualTo("Eduard Pal");
    }

    @Test
    public void testGetCurrentUserDisplayNameWithOidcUserHavingPreferredUsername() {
        OidcUser oidcUser = mock(OidcUser.class);
        when(oidcUser.getClaimAsString("name")).thenReturn(null);
        when(oidcUser.getClaimAsString("preferred_username")).thenReturn("admin@cirrus-code.org");

        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getPrincipal()).thenReturn(oidcUser);
        SecurityContextHolder.getContext().setAuthentication(auth);

        String name = userService.getCurrentUserDisplayName();
        assertThat(name).isEqualTo("admin@cirrus-code.org");
    }

    @Test
    public void testGetCurrentUserDisplayNameWithOidcUserHavingEmail() {
        OidcUser oidcUser = mock(OidcUser.class);
        when(oidcUser.getClaimAsString("name")).thenReturn(null);
        when(oidcUser.getClaimAsString("preferred_username")).thenReturn("");
        when(oidcUser.getEmail()).thenReturn("admin@cirrus-code.org");

        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getPrincipal()).thenReturn(oidcUser);
        SecurityContextHolder.getContext().setAuthentication(auth);

        String name = userService.getCurrentUserDisplayName();
        assertThat(name).isEqualTo("admin@cirrus-code.org");
    }

    @Test
    public void testGetCurrentUserDisplayNameWithOAuth2User() {
        OAuth2User oauth2User = mock(OAuth2User.class);
        when(oauth2User.getAttribute("name")).thenReturn("OAuth2 Name");

        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getPrincipal()).thenReturn(oauth2User);
        SecurityContextHolder.getContext().setAuthentication(auth);

        String name = userService.getCurrentUserDisplayName();
        assertThat(name).isEqualTo("OAuth2 Name");
    }

    @Test
    public void testGetCurrentUserDisplayNameWithUserDetails() {
        UserDetails userDetails = mock(UserDetails.class);
        when(userDetails.getUsername()).thenReturn("details-user");

        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getPrincipal()).thenReturn(userDetails);
        SecurityContextHolder.getContext().setAuthentication(auth);

        String name = userService.getCurrentUserDisplayName();
        assertThat(name).isEqualTo("details-user");
    }

    @Test
    public void testGetCurrentUserDisplayNameFallback() {
        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getPrincipal()).thenReturn("some-string-principal");
        when(auth.getName()).thenReturn("fallback-name");
        SecurityContextHolder.getContext().setAuthentication(auth);

        String name = userService.getCurrentUserDisplayName();
        assertThat(name).isEqualTo("fallback-name");
    }

    @Test
    public void testGetCurrentUserWithJwt() {
        Jwt jwt = mock(Jwt.class);
        when(jwt.getClaimAsString("oid")).thenReturn("mock-jwt-oid");
        when(jwt.getClaimAsString("preferred_username")).thenReturn("jwt-user@palsoftware.local");
        when(jwt.getClaimAsString("name")).thenReturn("Jwt User");

        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getPrincipal()).thenReturn(jwt);
        SecurityContextHolder.getContext().setAuthentication(auth);

        User mockUser = new User(UUID.randomUUID(), "mock-jwt-oid", "jwt-user@palsoftware.local",
            "Jwt User", Instant.now());
        when(userRepository.findByEntraOid("mock-jwt-oid")).thenReturn(Optional.of(mockUser));

        Optional<User> user = userService.getCurrentUser();

        verify(userRepository).upsert("mock-jwt-oid", "jwt-user@palsoftware.local", "Jwt User");
        assertThat(user).isPresent();
        assertThat(user.get().displayName()).isEqualTo("Jwt User");
    }

    @Test
    public void testGetCurrentUserDisplayNameWithJwt() {
        Jwt jwt = mock(Jwt.class);
        when(jwt.getClaimAsString("name")).thenReturn("Jwt User Display");

        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getPrincipal()).thenReturn(jwt);
        SecurityContextHolder.getContext().setAuthentication(auth);

        String name = userService.getCurrentUserDisplayName();
        assertThat(name).isEqualTo("Jwt User Display");
    }
}
