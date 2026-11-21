package de.palsoftware.yvoke.shared.user.service;

import de.palsoftware.yvoke.shared.user.model.User;
import de.palsoftware.yvoke.shared.user.repository.UserRepository;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

@Service
public class UserService {
    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    private static final String REQUEST_CACHE_ATTR = UserService.class.getName() + ".currentUser";

    private record CachedUser(String entraOid, Optional<User> user) {}

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public void syncUser(OidcUser oidcUser) {
        String entraOid = oidcUser.getClaimAsString("oid");
        if (entraOid == null) {
            entraOid = oidcUser.getSubject(); // Fallback to sub
        }

        String email = oidcUser.getClaimAsString("preferred_username");
        if (email == null) {
            email = oidcUser.getEmail();
        }

        String displayName = oidcUser.getClaimAsString("name");
        if (displayName == null) {
            displayName = oidcUser.getFullName();
        }

        if (entraOid == null) {
            log.warn("Cannot sync user: no OID or Sub claim found in OidcUser");
            return;
        }

        log.info("Syncing user to local DB: entraOid={}, email={}, displayName={}", entraOid, email,
            displayName);
        userRepository.upsert(entraOid, email, displayName);
    }

    public Optional<User> getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return Optional.empty();
        }

        Object principal = auth.getPrincipal();
        String entraOid = extractEntraOid(auth, principal);
        if (entraOid == null) {
            return Optional.empty();
        }

        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            Object cachedObj =
                attrs.getAttribute(REQUEST_CACHE_ATTR, RequestAttributes.SCOPE_REQUEST);
            if (cachedObj instanceof CachedUser cached
                && Objects.equals(cached.entraOid(), entraOid)) {
                return cached.user();
            }
        }

        Optional<User> resolved;
        if (principal instanceof Jwt jwt) {
            String email = jwt.getClaimAsString("preferred_username");
            if (email == null) {
                email = jwt.getClaimAsString("email");
            }
            String displayName = jwt.getClaimAsString("name");
            resolved = userRepository.upsertAndReturn(entraOid, email, displayName);
        } else {
            resolved = userRepository.findByEntraOid(entraOid);
        }

        if (attrs != null && resolved.isPresent()) {
            attrs.setAttribute(REQUEST_CACHE_ATTR, new CachedUser(entraOid, resolved),
                RequestAttributes.SCOPE_REQUEST);
        }
        return resolved;
    }

    private static String extractEntraOid(Authentication auth, Object principal) {
        if (principal instanceof OidcUser oidcUser) {
            String entraOid = oidcUser.getClaimAsString("oid");
            return entraOid != null ? entraOid : oidcUser.getSubject();
        }
        if (principal instanceof Jwt jwt) {
            String entraOid = jwt.getClaimAsString("oid");
            return entraOid != null ? entraOid : jwt.getSubject();
        }
        return auth.getName();
    }

    public String getCurrentUserDisplayName() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return "Guest";
        }
        Object principal = auth.getPrincipal();
        if (principal instanceof OidcUser oidcUser) {
            String name = oidcUser.getClaimAsString("name");
            if (name != null && !name.isBlank()) {
                return name;
            }
            String prefUsername = oidcUser.getClaimAsString("preferred_username");
            if (prefUsername != null && !prefUsername.isBlank()) {
                return prefUsername;
            }
            String email = oidcUser.getEmail();
            if (email != null && !email.isBlank()) {
                return email;
            }
        } else if (principal instanceof Jwt jwt) {
            String name = jwt.getClaimAsString("name");
            if (name != null && !name.isBlank()) {
                return name;
            }
            String prefUsername = jwt.getClaimAsString("preferred_username");
            if (prefUsername != null && !prefUsername.isBlank()) {
                return prefUsername;
            }
            String email = jwt.getClaimAsString("email");
            if (email != null && !email.isBlank()) {
                return email;
            }
            return jwt.getSubject();
        } else if (principal instanceof OAuth2User oauth2User) {
            String name = oauth2User.getAttribute("name");
            if (name != null && !name.isBlank()) {
                return name;
            }
            String prefUsername = oauth2User.getAttribute("preferred_username");
            if (prefUsername != null && !prefUsername.isBlank()) {
                return prefUsername;
            }
            String email = oauth2User.getAttribute("email");
            if (email != null && !email.isBlank()) {
                return email;
            }
        } else if (principal instanceof UserDetails userDetails) {
            return userDetails.getUsername();
        }
        return auth.getName();
    }
}
