package de.palsoftware.yvoke.shared.security;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Shared authority constants, claim parsing, and area-scoped authorization helpers.
 *
 * <p>
 * This utility operates exclusively on standard Spring Security abstractions and strings. It
 * contains zero imports from domain packages, strictly obeying architectural boundaries.
 * </p>
 */
public final class AreaAccessContext {

    // Spring Security Role Authorities
    public static final String ROLE_ADMIN = "ROLE_ADMIN";
    public static final String ROLE_AREA_ADMIN = "ROLE_AREA_ADMIN";
    public static final String ROLE_USER = "ROLE_USER";
    public static final String ROLE_INGEST = "ROLE_INGEST";

    // Area-Scoped Authorities
    public static final String AREA_USER_ALL = "AREA_USER_*";
    public static final String AREA_ADMIN_ALL = "AREA_ADMIN_*";
    public static final String AREA_INGEST_ALL = "AREA_INGEST_*";

    public static final String AREA_USER_PREFIX = "AREA_USER_";
    public static final String AREA_ADMIN_PREFIX = "AREA_ADMIN_";
    public static final String AREA_INGEST_PREFIX = "AREA_INGEST_";

    private static final Pattern AREA_ADMIN_PATTERN = Pattern.compile("(?i)^([^.\\s]+)\\.admin$");
    private static final Pattern AREA_USER_PATTERN = Pattern.compile("(?i)^([^.\\s]+)\\.user$");

    private AreaAccessContext() {}

    /**
     * Normalizes an area name into an uppercase key for authority strings. Returns empty string if
     * the area is null or blank.
     */
    public static String normalizeAreaKey(String area) {
        if (area == null || area.isBlank()) {
            return "";
        }
        return area.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * Maps raw Entra role claims to their derived Spring Security and area authorities.
     */
    public static Set<GrantedAuthority> mapRoleClaimsToAuthorities(Collection<?> rawClaims,
        String adminClaim, String userClaim) {
        if (rawClaims == null || rawClaims.isEmpty()) {
            return Collections.emptySet();
        }

        String trimmedAdminClaim = (adminClaim != null) ? adminClaim.trim() : "";
        String trimmedUserClaim = (userClaim != null) ? userClaim.trim() : "";

        Set<GrantedAuthority> authorities = new HashSet<>();

        for (Object claimObj : rawClaims) {
            if (!(claimObj instanceof String rawClaim)) {
                continue;
            }
            String claim = rawClaim.trim();
            if (claim.isEmpty()) {
                continue;
            }

            // 1. Global Admin Claim
            if (!trimmedAdminClaim.isEmpty() && claim.equalsIgnoreCase(trimmedAdminClaim)) {
                authorities.add(new SimpleGrantedAuthority(ROLE_ADMIN));
                authorities.add(new SimpleGrantedAuthority(ROLE_AREA_ADMIN));
                authorities.add(new SimpleGrantedAuthority(ROLE_USER));
                authorities.add(new SimpleGrantedAuthority(AREA_ADMIN_ALL));
                authorities.add(new SimpleGrantedAuthority(AREA_USER_ALL));
                continue;
            }

            // 2. Global User Claim
            if (!trimmedUserClaim.isEmpty() && claim.equalsIgnoreCase(trimmedUserClaim)) {
                authorities.add(new SimpleGrantedAuthority(ROLE_USER));
                authorities.add(new SimpleGrantedAuthority(AREA_USER_ALL));
                continue;
            }

            // 3. <Area>.Admin Claim
            Matcher adminMatcher = AREA_ADMIN_PATTERN.matcher(claim);
            if (adminMatcher.matches()) {
                String areaPrefix = adminMatcher.group(1).trim();
                if (!areaPrefix.isBlank() && !"*".equals(areaPrefix)) {
                    String normalized = normalizeAreaKey(areaPrefix);
                    authorities.add(new SimpleGrantedAuthority(ROLE_AREA_ADMIN));
                    authorities.add(new SimpleGrantedAuthority(ROLE_USER));
                    authorities.add(new SimpleGrantedAuthority(AREA_ADMIN_PREFIX + normalized));
                    authorities.add(new SimpleGrantedAuthority(AREA_USER_PREFIX + normalized));
                    continue;
                }
            }

            // 4. <Area>.User Claim
            Matcher userMatcher = AREA_USER_PATTERN.matcher(claim);
            if (userMatcher.matches()) {
                String areaPrefix = userMatcher.group(1).trim();
                if (!areaPrefix.isBlank() && !"*".equals(areaPrefix)) {
                    String normalized = normalizeAreaKey(areaPrefix);
                    authorities.add(new SimpleGrantedAuthority(ROLE_USER));
                    authorities.add(new SimpleGrantedAuthority(AREA_USER_PREFIX + normalized));
                    continue;
                }
            }

            // 5. Unrecognized claims grant nothing (deny-by-default)
        }

        return authorities;
    }

    public static boolean hasAuthority(Authentication auth, String authority) {
        if (auth == null || authority == null || authority.isBlank()) {
            return false;
        }
        return auth.getAuthorities().stream().anyMatch(a -> authority.equals(a.getAuthority()));
    }

    public static boolean hasAuthority(String authority) {
        return hasAuthority(getAuthentication(), authority);
    }

    public static boolean isSuperAdmin(Authentication auth) {
        return hasAuthority(auth, ROLE_ADMIN) || hasAuthority(auth, AREA_ADMIN_ALL);
    }

    public static boolean isSuperAdmin() {
        return isSuperAdmin(getAuthentication());
    }

    public static boolean hasAllUserAreas(Authentication auth) {
        return isSuperAdmin(auth) || hasAuthority(auth, AREA_USER_ALL);
    }

    public static boolean hasAllUserAreas() {
        return hasAllUserAreas(getAuthentication());
    }

    public static boolean canAccessUserArea(Authentication auth, String area) {
        if (auth == null) {
            return false;
        }
        if (hasAllUserAreas(auth)) {
            return true;
        }
        if (area == null || area.isBlank()) {
            return false;
        }
        String key = normalizeAreaKey(area);
        if (key.isEmpty() || "*".equals(key)) {
            return false;
        }
        return hasAuthority(auth, AREA_USER_PREFIX + key);
    }

    public static boolean canAccessUserArea(String area) {
        return canAccessUserArea(getAuthentication(), area);
    }

    public static boolean canAdministerArea(Authentication auth, String area) {
        if (auth == null) {
            return false;
        }
        if (isSuperAdmin(auth)) {
            return true;
        }
        if (area == null || area.isBlank()) {
            return false;
        }
        String key = normalizeAreaKey(area);
        if (key.isEmpty() || "*".equals(key)) {
            return false;
        }
        return hasAuthority(auth, AREA_ADMIN_PREFIX + key);
    }

    public static boolean canAdministerArea(String area) {
        return canAdministerArea(getAuthentication(), area);
    }

    public static boolean canIngestArea(Authentication auth, String area) {
        if (auth == null) {
            return false;
        }
        if (hasAuthority(auth, AREA_INGEST_ALL) || isSuperAdmin(auth)) {
            return true;
        }
        if (area == null || area.isBlank()) {
            return false;
        }
        String key = normalizeAreaKey(area);
        if (key.isEmpty() || "*".equals(key)) {
            return false;
        }
        return hasAuthority(auth, AREA_INGEST_PREFIX + key);
    }

    public static boolean canIngestArea(String area) {
        return canIngestArea(getAuthentication(), area);
    }

    /**
     * Returns Optional.empty() if caller has access to ALL user areas (unrestricted). Otherwise
     * returns Optional.of(Set<String>) of authorized lowercase area keys.
     */
    public static Optional<Set<String>> allowedUserAreasLower(Authentication auth) {
        if (auth == null) {
            return Optional.of(Collections.emptySet());
        }
        if (hasAllUserAreas(auth)) {
            return Optional.empty();
        }
        Set<String> areas = new HashSet<>();
        for (GrantedAuthority ga : auth.getAuthorities()) {
            String authority = ga.getAuthority();
            if (authority != null && authority.startsWith(AREA_USER_PREFIX)) {
                String raw = authority.substring(AREA_USER_PREFIX.length());
                if (!"*".equals(raw) && !raw.isBlank()) {
                    areas.add(raw.toLowerCase(Locale.ROOT));
                }
            }
        }
        return Optional.of(Collections.unmodifiableSet(areas));
    }

    public static Optional<Set<String>> allowedUserAreasLower() {
        return allowedUserAreasLower(getAuthentication());
    }

    /**
     * Returns Optional.empty() if caller is Superadmin (unrestricted admin). Otherwise returns
     * Optional.of(Set<String>) of administered lowercase area keys.
     */
    public static Optional<Set<String>> allowedAdminAreasLower(Authentication auth) {
        if (auth == null) {
            return Optional.of(Collections.emptySet());
        }
        if (isSuperAdmin(auth)) {
            return Optional.empty();
        }
        Set<String> areas = new HashSet<>();
        for (GrantedAuthority ga : auth.getAuthorities()) {
            String authority = ga.getAuthority();
            if (authority != null && authority.startsWith(AREA_ADMIN_PREFIX)) {
                String raw = authority.substring(AREA_ADMIN_PREFIX.length());
                if (!"*".equals(raw) && !raw.isBlank()) {
                    areas.add(raw.toLowerCase(Locale.ROOT));
                }
            }
        }
        return Optional.of(Collections.unmodifiableSet(areas));
    }

    public static Optional<Set<String>> allowedAdminAreasLower() {
        return allowedAdminAreasLower(getAuthentication());
    }

    /**
     * Returns Optional.empty() if caller has wildcard ingest access. Otherwise returns
     * Optional.of(Set<String>) of authorized lowercase ingest area keys.
     */
    public static Optional<Set<String>> allowedIngestAreasLower(Authentication auth) {
        if (auth == null) {
            return Optional.of(Collections.emptySet());
        }
        if (hasAuthority(auth, AREA_INGEST_ALL) || isSuperAdmin(auth)) {
            return Optional.empty();
        }
        Set<String> areas = new HashSet<>();
        for (GrantedAuthority ga : auth.getAuthorities()) {
            String authority = ga.getAuthority();
            if (authority != null && authority.startsWith(AREA_INGEST_PREFIX)) {
                String raw = authority.substring(AREA_INGEST_PREFIX.length());
                if (!"*".equals(raw) && !raw.isBlank()) {
                    areas.add(raw.toLowerCase(Locale.ROOT));
                }
            }
        }
        return Optional.of(Collections.unmodifiableSet(areas));
    }

    public static Optional<Set<String>> allowedIngestAreasLower() {
        return allowedIngestAreasLower(getAuthentication());
    }

    public static <T> List<T> filterByUserArea(Authentication auth, Collection<T> items,
        Function<T, String> areaExtractor) {
        if (items == null || items.isEmpty()) {
            return Collections.emptyList();
        }
        Optional<Set<String>> allowed = allowedUserAreasLower(auth);
        if (allowed.isEmpty()) {
            return items.stream().filter(Objects::nonNull).toList();
        }
        Set<String> allowedSet = allowed.get();
        if (allowedSet.isEmpty()) {
            return Collections.emptyList();
        }
        return items.stream().filter(item -> {
            if (item == null) {
                return false;
            }
            String area = areaExtractor.apply(item);
            return area != null && allowedSet.contains(area.trim().toLowerCase(Locale.ROOT));
        }).toList();
    }

    public static <T> List<T> filterByUserArea(Collection<T> items,
        Function<T, String> areaExtractor) {
        return filterByUserArea(getAuthentication(), items, areaExtractor);
    }

    public static <T> List<T> filterByAdminArea(Authentication auth, Collection<T> items,
        Function<T, String> areaExtractor) {
        if (items == null || items.isEmpty()) {
            return Collections.emptyList();
        }
        Optional<Set<String>> allowed = allowedAdminAreasLower(auth);
        if (allowed.isEmpty()) {
            return items.stream().filter(Objects::nonNull).toList();
        }
        Set<String> allowedSet = allowed.get();
        if (allowedSet.isEmpty()) {
            return Collections.emptyList();
        }
        return items.stream().filter(item -> {
            if (item == null) {
                return false;
            }
            String area = areaExtractor.apply(item);
            return area != null && allowedSet.contains(area.trim().toLowerCase(Locale.ROOT));
        }).toList();
    }

    public static <T> List<T> filterByAdminArea(Collection<T> items,
        Function<T, String> areaExtractor) {
        return filterByAdminArea(getAuthentication(), items, areaExtractor);
    }

    private static Authentication getAuthentication() {
        SecurityContext context = SecurityContextHolder.getContext();
        return context != null ? context.getAuthentication() : null;
    }
}
