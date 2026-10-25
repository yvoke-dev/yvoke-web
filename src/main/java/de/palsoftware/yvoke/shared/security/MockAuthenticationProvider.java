package de.palsoftware.yvoke.shared.security;

import de.palsoftware.yvoke.shared.user.service.UserService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;

/**
 * Authentication provider for mock/local development environments.
 *
 * <p>
 * Supports simulated Entra ID role claims passed in the password field (comma-separated, e.g.
 * {@code "OIM.Admin,PingID.User"}) or legacy mock credentials ({@code "admin"} / {@code "user"}).
 * </p>
 */
public class MockAuthenticationProvider implements AuthenticationProvider {

    private final UserService userService;
    private final String adminClaim;
    private final String userClaim;

    public MockAuthenticationProvider(UserService userService, String adminClaim,
        String userClaim) {
        this.userService = Objects.requireNonNull(userService, "userService must not be null");
        this.adminClaim =
            (adminClaim != null && !adminClaim.isBlank()) ? adminClaim.trim() : "Admin";
        this.userClaim = (userClaim != null && !userClaim.isBlank()) ? userClaim.trim() : "User";
    }

    public MockAuthenticationProvider(UserService userService) {
        this(userService, "Admin", "User");
    }

    @Override
    public Authentication authenticate(Authentication authentication)
        throws AuthenticationException {
        String username = authentication.getName();
        String password = authentication.getCredentials() != null
            ? authentication.getCredentials().toString().trim()
            : "";

        List<String> rawClaims = resolveRoleClaims(username, password);

        Collection<GrantedAuthority> authorities =
            AreaAccessContext.mapRoleClaimsToAuthorities(rawClaims, adminClaim, userClaim);

        if (authorities == null || authorities.isEmpty()) {
            throw new BadCredentialsException(
                "No valid role authorities granted for provided credentials");
        }

        String cleanUsername =
            (username != null && !username.isBlank()) ? username.trim() : "mock-user";
        String entraOid =
            "mock-" + cleanUsername.toLowerCase().replaceAll("[^a-z0-9_-]", "-") + "-oid";
        String email =
            cleanUsername.toLowerCase().replaceAll("[^a-z0-9_.-]", "-") + "@palsoftware.local";
        String displayName = "Mock " + cleanUsername;

        MockOidcUser oidcUser =
            new MockOidcUser(entraOid, email, displayName, new ArrayList<>(authorities), rawClaims);

        userService.syncUser(oidcUser);

        return new UsernamePasswordAuthenticationToken(oidcUser, password, authorities);
    }

    private List<String> resolveRoleClaims(String username, String password) {
        // If password is provided and is NOT the legacy literal "password"
        if (!password.isEmpty() && !"password".equalsIgnoreCase(password)) {
            return Arrays.stream(password.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .toList();
        }

        // Backward-compatibility: if password is empty or "password", map legacy usernames
        if ("admin".equalsIgnoreCase(username)) {
            return List.of(adminClaim);
        }
        if ("user".equalsIgnoreCase(username)) {
            return List.of(userClaim);
        }

        // If username itself contains a valid role pattern (e.g. "OIM.Admin")
        if (username != null && !username.isBlank()) {
            String trimmed = username.trim();
            if (trimmed.equalsIgnoreCase(adminClaim) || trimmed.equalsIgnoreCase(userClaim)
                || trimmed.toLowerCase().endsWith(".admin")
                || trimmed.toLowerCase().endsWith(".user")) {
                return List.of(trimmed);
            }
        }

        return List.of();
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
