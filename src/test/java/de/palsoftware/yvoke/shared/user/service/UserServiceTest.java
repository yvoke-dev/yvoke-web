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

public class UserServiceTest {

    private UserRepository userRepository;
    private UserService userService;
    private SecurityContext originalContext;

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

        User mockUser = new User(java.util.UUID.randomUUID(), "mock-jwt-oid",
            "jwt-user@palsoftware.local", "Jwt User", java.time.Instant.now());
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
