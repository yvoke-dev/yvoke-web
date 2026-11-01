package de.palsoftware.yvoke.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

class AreaAccessContextTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static Authentication authWith(String... authorities) {
        List<SimpleGrantedAuthority> auths =
            Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList();
        return new UsernamePasswordAuthenticationToken("test-user", "n/a", auths);
    }

    private static void setSecurityContext(String... authorities) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authWith(authorities));
        SecurityContextHolder.setContext(context);
    }

    @Test
    void isSuperAdmin_returnsTrueForRoleAdminOrWildcardAdmin() {
        assertThat(AreaAccessContext.isSuperAdmin(authWith(AreaAccessContext.ROLE_ADMIN))).isTrue();
        assertThat(AreaAccessContext.isSuperAdmin(authWith(AreaAccessContext.AREA_ADMIN_ALL)))
            .isTrue();
        assertThat(AreaAccessContext.isSuperAdmin(authWith(AreaAccessContext.ROLE_AREA_ADMIN)))
            .isFalse();
        assertThat(AreaAccessContext.isSuperAdmin(authWith(AreaAccessContext.ROLE_USER))).isFalse();
        assertThat(AreaAccessContext.isSuperAdmin((Authentication) null)).isFalse();

        setSecurityContext(AreaAccessContext.ROLE_ADMIN);
        assertThat(AreaAccessContext.isSuperAdmin()).isTrue();
    }

    @Test
    void hasAllUserAreas_returnsTrueForRoleAdminOrWildcardUser() {
        assertThat(AreaAccessContext.hasAllUserAreas(authWith(AreaAccessContext.ROLE_ADMIN)))
            .isTrue();
        assertThat(AreaAccessContext.hasAllUserAreas(authWith(AreaAccessContext.AREA_USER_ALL)))
            .isTrue();
        assertThat(
            AreaAccessContext.hasAllUserAreas(authWith(AreaAccessContext.AREA_USER_PREFIX + "OIM")))
            .isFalse();
        assertThat(AreaAccessContext.hasAllUserAreas((Authentication) null)).isFalse();

        setSecurityContext(AreaAccessContext.AREA_USER_ALL);
        assertThat(AreaAccessContext.hasAllUserAreas()).isTrue();
    }

    @Test
    void canAccessUserArea_handlesWildcardAndExactAreaCaseInsensitively() {
        Authentication superAdmin = authWith(AreaAccessContext.ROLE_ADMIN);
        assertThat(AreaAccessContext.canAccessUserArea(superAdmin, "OIM")).isTrue();
        assertThat(AreaAccessContext.canAccessUserArea(superAdmin, "PingID")).isTrue();

        Authentication globalUser = authWith(AreaAccessContext.AREA_USER_ALL);
        assertThat(AreaAccessContext.canAccessUserArea(globalUser, "OIM")).isTrue();
        assertThat(AreaAccessContext.canAccessUserArea(globalUser, "PingID")).isTrue();

        Authentication oimUser = authWith(AreaAccessContext.AREA_USER_PREFIX + "OIM");
        assertThat(AreaAccessContext.canAccessUserArea(oimUser, "OIM")).isTrue();
        assertThat(AreaAccessContext.canAccessUserArea(oimUser, "oim")).isTrue();
        assertThat(AreaAccessContext.canAccessUserArea(oimUser, "  OIM  ")).isTrue();
        assertThat(AreaAccessContext.canAccessUserArea(oimUser, "PingID")).isFalse();
        assertThat(AreaAccessContext.canAccessUserArea(oimUser, null)).isFalse();
        assertThat(AreaAccessContext.canAccessUserArea(oimUser, "")).isFalse();
        assertThat(AreaAccessContext.canAccessUserArea(oimUser, "   ")).isFalse();
        assertThat(AreaAccessContext.canAccessUserArea(oimUser, "*")).isFalse();

        assertThat(AreaAccessContext.canAccessUserArea((Authentication) null, "OIM")).isFalse();
    }

    @Test
    void canAdministerArea_handlesWildcardAndExactArea() {
        Authentication superAdmin = authWith(AreaAccessContext.ROLE_ADMIN);
        assertThat(AreaAccessContext.canAdministerArea(superAdmin, "OIM")).isTrue();
        assertThat(AreaAccessContext.canAdministerArea(superAdmin, "AnyArea")).isTrue();

        Authentication oimAdmin = authWith(AreaAccessContext.AREA_ADMIN_PREFIX + "OIM");
        assertThat(AreaAccessContext.canAdministerArea(oimAdmin, "OIM")).isTrue();
        assertThat(AreaAccessContext.canAdministerArea(oimAdmin, "oim")).isTrue();
        assertThat(AreaAccessContext.canAdministerArea(oimAdmin, "PingID")).isFalse();
        assertThat(AreaAccessContext.canAdministerArea(oimAdmin, null)).isFalse();
        assertThat(AreaAccessContext.canAdministerArea(oimAdmin, "")).isFalse();

        Authentication oimUser = authWith(AreaAccessContext.AREA_USER_PREFIX + "OIM");
        assertThat(AreaAccessContext.canAdministerArea(oimUser, "OIM")).isFalse();

        assertThat(AreaAccessContext.canAdministerArea((Authentication) null, "OIM")).isFalse();
    }

    @Test
    void canIngestArea_handlesWildcardAndAreaKey() {
        Authentication globalIngest = authWith(AreaAccessContext.AREA_INGEST_ALL);
        assertThat(AreaAccessContext.canIngestArea(globalIngest, "OIM")).isTrue();
        assertThat(AreaAccessContext.canIngestArea(globalIngest, "PingID")).isTrue();

        Authentication superAdmin = authWith(AreaAccessContext.ROLE_ADMIN);
        assertThat(AreaAccessContext.canIngestArea(superAdmin, "OIM")).isTrue();

        Authentication oimIngest = authWith(AreaAccessContext.AREA_INGEST_PREFIX + "OIM");
        assertThat(AreaAccessContext.canIngestArea(oimIngest, "OIM")).isTrue();
        assertThat(AreaAccessContext.canIngestArea(oimIngest, "oim")).isTrue();
        assertThat(AreaAccessContext.canIngestArea(oimIngest, "PingID")).isFalse();
        assertThat(AreaAccessContext.canIngestArea(oimIngest, null)).isFalse();
        assertThat(AreaAccessContext.canIngestArea(oimIngest, "")).isFalse();

        assertThat(AreaAccessContext.canIngestArea((Authentication) null, "OIM")).isFalse();
    }

    @Test
    void allowedUserAreasLower_returnsEmptyForWildcardAndLowercaseSetForScoped() {
        Authentication superAdmin = authWith(AreaAccessContext.ROLE_ADMIN);
        assertThat(AreaAccessContext.allowedUserAreasLower(superAdmin)).isEmpty();

        Authentication globalUser = authWith(AreaAccessContext.AREA_USER_ALL);
        assertThat(AreaAccessContext.allowedUserAreasLower(globalUser)).isEmpty();

        Authentication multiAreaUser = authWith(AreaAccessContext.AREA_USER_PREFIX + "OIM",
            AreaAccessContext.AREA_USER_PREFIX + "PINGID");
        assertThat(AreaAccessContext.allowedUserAreasLower(multiAreaUser))
            .contains(Set.of("oim", "pingid"));

        Authentication noAreas = authWith(AreaAccessContext.ROLE_USER);
        assertThat(AreaAccessContext.allowedUserAreasLower(noAreas))
            .contains(Collections.emptySet());

        assertThat(AreaAccessContext.allowedUserAreasLower((Authentication) null))
            .contains(Collections.emptySet());
    }

    @Test
    void allowedAdminAreasLower_returnsEmptyForSuperadminAndLowercaseSetForAreaAdmin() {
        Authentication superAdmin = authWith(AreaAccessContext.ROLE_ADMIN);
        assertThat(AreaAccessContext.allowedAdminAreasLower(superAdmin)).isEmpty();

        Authentication oimAdmin = authWith(AreaAccessContext.AREA_ADMIN_PREFIX + "OIM");
        assertThat(AreaAccessContext.allowedAdminAreasLower(oimAdmin)).contains(Set.of("oim"));

        Authentication globalUser =
            authWith(AreaAccessContext.ROLE_USER, AreaAccessContext.AREA_USER_ALL);
        assertThat(AreaAccessContext.allowedAdminAreasLower(globalUser))
            .contains(Collections.emptySet());

        assertThat(AreaAccessContext.allowedAdminAreasLower((Authentication) null))
            .contains(Collections.emptySet());
    }

    @Test
    void allowedIngestAreasLower_returnsEmptyForWildcardAndLowercaseSetForAreaKey() {
        Authentication wildcardIngest = authWith(AreaAccessContext.AREA_INGEST_ALL);
        assertThat(AreaAccessContext.allowedIngestAreasLower(wildcardIngest)).isEmpty();

        Authentication superAdmin = authWith(AreaAccessContext.ROLE_ADMIN);
        assertThat(AreaAccessContext.allowedIngestAreasLower(superAdmin)).isEmpty();

        Authentication oimIngest = authWith(AreaAccessContext.AREA_INGEST_PREFIX + "OIM");
        assertThat(AreaAccessContext.allowedIngestAreasLower(oimIngest)).contains(Set.of("oim"));

        Authentication noIngest = authWith(AreaAccessContext.ROLE_USER);
        assertThat(AreaAccessContext.allowedIngestAreasLower(noIngest))
            .contains(Collections.emptySet());

        assertThat(AreaAccessContext.allowedIngestAreasLower((Authentication) null))
            .contains(Collections.emptySet());
    }

    @Test
    void filterByUserArea_filtersCollectionsAccurately() {
        record Item(String id, String area) {}
        List<Item> items = List.of(
            new Item("1", "OIM"),
            new Item("2", "PingID"),
            new Item("3", "Other")
        );

        Authentication superAdmin = authWith(AreaAccessContext.ROLE_ADMIN);
        assertThat(AreaAccessContext.filterByUserArea(superAdmin, items, Item::area))
            .containsExactlyElementsOf(items);

        Authentication oimUser = authWith(AreaAccessContext.AREA_USER_PREFIX + "OIM");
        assertThat(AreaAccessContext.filterByUserArea(oimUser, items, Item::area))
            .extracting(Item::id)
            .containsExactly("1");

        Authentication emptyUser = authWith(AreaAccessContext.ROLE_USER);
        assertThat(AreaAccessContext.filterByUserArea(emptyUser, items, Item::area)).isEmpty();
        assertThat(AreaAccessContext.filterByUserArea(superAdmin, Collections.emptyList(), Item::area)).isEmpty();
    }

    @Test
    void filterByAdminArea_filtersCollectionsAccurately() {
        record Item(String id, String area) {}
        List<Item> items = List.of(
            new Item("1", "OIM"),
            new Item("2", "PingID")
        );

        Authentication superAdmin = authWith(AreaAccessContext.ROLE_ADMIN);
        assertThat(AreaAccessContext.filterByAdminArea(superAdmin, items, Item::area))
            .containsExactlyElementsOf(items);

        Authentication oimAdmin = authWith(AreaAccessContext.AREA_ADMIN_PREFIX + "OIM");
        assertThat(AreaAccessContext.filterByAdminArea(oimAdmin, items, Item::area))
            .extracting(Item::id)
            .containsExactly("1");
    }

    @Test
    void mapRoleClaimsToAuthorities_parsesGlobalAndAreaRolesAccurately() {
        Set<GrantedAuthority> adminAuths =
            AreaAccessContext.mapRoleClaimsToAuthorities(List.of("Admin"), "Admin", "User");
        assertThat(adminAuths).extracting(GrantedAuthority::getAuthority).containsExactlyInAnyOrder(
            AreaAccessContext.ROLE_ADMIN, AreaAccessContext.ROLE_AREA_ADMIN,
            AreaAccessContext.ROLE_USER, AreaAccessContext.AREA_ADMIN_ALL,
            AreaAccessContext.AREA_USER_ALL);

        Set<GrantedAuthority> userAuths =
            AreaAccessContext.mapRoleClaimsToAuthorities(List.of("User"), "Admin", "User");
        assertThat(userAuths).extracting(GrantedAuthority::getAuthority).containsExactlyInAnyOrder(
            AreaAccessContext.ROLE_USER, AreaAccessContext.AREA_USER_ALL);

        Set<GrantedAuthority> oimAdminAuths =
            AreaAccessContext.mapRoleClaimsToAuthorities(List.of("oim.admin"), "Admin", "User");
        assertThat(oimAdminAuths).extracting(GrantedAuthority::getAuthority)
            .containsExactlyInAnyOrder(AreaAccessContext.ROLE_AREA_ADMIN,
                AreaAccessContext.ROLE_USER, "AREA_ADMIN_OIM", "AREA_USER_OIM");

        Set<GrantedAuthority> oimUserAuths =
            AreaAccessContext.mapRoleClaimsToAuthorities(List.of("OIM.User"), "Admin", "User");
        assertThat(oimUserAuths).extracting(GrantedAuthority::getAuthority)
            .containsExactlyInAnyOrder(AreaAccessContext.ROLE_USER, "AREA_USER_OIM");

        Set<GrantedAuthority> multiAuths = AreaAccessContext
            .mapRoleClaimsToAuthorities(List.of("OIM.Admin", "PingID.User"), "Admin", "User");
        assertThat(multiAuths).extracting(GrantedAuthority::getAuthority).containsExactlyInAnyOrder(
            AreaAccessContext.ROLE_AREA_ADMIN, AreaAccessContext.ROLE_USER, "AREA_ADMIN_OIM",
            "AREA_USER_OIM", "AREA_USER_PINGID");

        Set<GrantedAuthority> malformed = AreaAccessContext.mapRoleClaimsToAuthorities(
            List.of(".Admin", "*.Admin", "OIM.Viewer", "Guest", "   ", ""), "Admin", "User");
        assertThat(malformed).isEmpty();

        assertThat(AreaAccessContext.mapRoleClaimsToAuthorities(null, "Admin", "User")).isEmpty();
        assertThat(
            AreaAccessContext.mapRoleClaimsToAuthorities(Collections.emptyList(), "Admin", "User"))
            .isEmpty();
    }

    @Test
    void mapRoleClaimsToAuthorities_adversarialClaimVariations() {
        // Whitespace trimming within and around claims
        Set<GrantedAuthority> trimmedAdmin =
            AreaAccessContext.mapRoleClaimsToAuthorities(List.of("  Admin  "), "Admin", "User");
        assertThat(trimmedAdmin).extracting(GrantedAuthority::getAuthority)
            .contains(AreaAccessContext.ROLE_ADMIN, AreaAccessContext.ROLE_USER);

        Set<GrantedAuthority> tabUser =
            AreaAccessContext.mapRoleClaimsToAuthorities(List.of("\tUser\n"), "Admin", "User");
        assertThat(tabUser).extracting(GrantedAuthority::getAuthority).containsExactlyInAnyOrder(
            AreaAccessContext.ROLE_USER, AreaAccessContext.AREA_USER_ALL);

        Set<GrantedAuthority> trimmedOimAdmin = AreaAccessContext
            .mapRoleClaimsToAuthorities(List.of("  \tOIM.Admin\n "), "Admin", "User");
        assertThat(trimmedOimAdmin).extracting(GrantedAuthority::getAuthority)
            .containsExactlyInAnyOrder(AreaAccessContext.ROLE_AREA_ADMIN,
                AreaAccessContext.ROLE_USER, "AREA_ADMIN_OIM", "AREA_USER_OIM");

        // Case variations
        Set<GrantedAuthority> mixedCase = AreaAccessContext
            .mapRoleClaimsToAuthorities(List.of("oIm.AdMiN", "pInGiD.uSeR"), "Admin", "User");
        assertThat(mixedCase).extracting(GrantedAuthority::getAuthority).containsExactlyInAnyOrder(
            AreaAccessContext.ROLE_AREA_ADMIN, AreaAccessContext.ROLE_USER, "AREA_ADMIN_OIM",
            "AREA_USER_OIM", "AREA_USER_PINGID");

        // Hyphens and underscores in area names
        Set<GrantedAuthority> complexNames = AreaAccessContext.mapRoleClaimsToAuthorities(
            List.of("Identity-Governance.Admin", "Area_42.User"), "Admin", "User");
        assertThat(complexNames).extracting(GrantedAuthority::getAuthority)
            .containsExactlyInAnyOrder(AreaAccessContext.ROLE_AREA_ADMIN,
                AreaAccessContext.ROLE_USER, "AREA_ADMIN_IDENTITY-GOVERNANCE",
                "AREA_USER_IDENTITY-GOVERNANCE", "AREA_USER_AREA_42");

        // Non-string objects in claims list (JWT claims parser robustness)
        Set<GrantedAuthority> nonStrings = AreaAccessContext.mapRoleClaimsToAuthorities(
            List.of(12345, Boolean.TRUE, new Object()), "Admin", "User");
        assertThat(nonStrings).isEmpty();

        // Null claim entry in list
        Set<GrantedAuthority> withNull = AreaAccessContext
            .mapRoleClaimsToAuthorities(Collections.singletonList(null), "Admin", "User");
        assertThat(withNull).isEmpty();

        // Custom adminClaim and userClaim
        Set<GrantedAuthority> customClaims = AreaAccessContext.mapRoleClaimsToAuthorities(
            List.of("CustomSuperAdmin", "CustomRegularUser"), "CustomSuperAdmin",
            "CustomRegularUser");
        assertThat(customClaims).extracting(GrantedAuthority::getAuthority).contains(
            AreaAccessContext.ROLE_ADMIN, AreaAccessContext.AREA_ADMIN_ALL,
            AreaAccessContext.ROLE_USER, AreaAccessContext.AREA_USER_ALL);

        // Null / blank adminClaim and userClaim should not throw and should not grant global roles
        Set<GrantedAuthority> nullConfigs = AreaAccessContext
            .mapRoleClaimsToAuthorities(List.of("Admin", "User", "OIM.Admin"), null, "");
        assertThat(nullConfigs).extracting(GrantedAuthority::getAuthority)
            .containsExactlyInAnyOrder(AreaAccessContext.ROLE_AREA_ADMIN,
                AreaAccessContext.ROLE_USER, "AREA_ADMIN_OIM", "AREA_USER_OIM");
    }

    @Test
    void mapRoleClaimsToAuthorities_adversarialDenyByDefault() {
        List<String> malformedClaims = List.of(".Admin", ".User", "*.Admin", "*.User", "   .Admin",
            "   .User", " * .Admin", " * .User", "OIM.Viewer", "OIM.Editor", "Guest", "Anonymous",
            "Admin.extra", "User.extra", "OIM.Admin.extra", "OIM.User.extra", "OIM..Admin",
            "OIM..User", "..Admin", "..User", "   ", "");

        for (String malformed : malformedClaims) {
            Set<GrantedAuthority> authorities =
                AreaAccessContext.mapRoleClaimsToAuthorities(List.of(malformed), "Admin", "User");
            assertThat(authorities)
                .as("Malformed claim '%s' must grant zero authorities", malformed).isEmpty();
        }
    }

    @Test
    void multiRoleCombinations_adversarialUnions() {
        // Union containing malformed + valid
        Set<GrantedAuthority> mixed = AreaAccessContext.mapRoleClaimsToAuthorities(
            List.of("Guest", "OIM.User", "Invalid", "*.Admin"), "Admin", "User");
        assertThat(mixed).extracting(GrantedAuthority::getAuthority)
            .containsExactlyInAnyOrder(AreaAccessContext.ROLE_USER, "AREA_USER_OIM");

        // Redundant assignments (Area Admin + Area User for same area)
        Set<GrantedAuthority> redundant = AreaAccessContext
            .mapRoleClaimsToAuthorities(List.of("OIM.Admin", "OIM.User"), "Admin", "User");
        assertThat(redundant).extracting(GrantedAuthority::getAuthority).containsExactlyInAnyOrder(
            AreaAccessContext.ROLE_AREA_ADMIN, AreaAccessContext.ROLE_USER, "AREA_ADMIN_OIM",
            "AREA_USER_OIM");

        // Global User + Area Admin
        Authentication userPlusAreaAdmin =
            authWith(AreaAccessContext.ROLE_USER, AreaAccessContext.AREA_USER_ALL,
                AreaAccessContext.ROLE_AREA_ADMIN, AreaAccessContext.AREA_ADMIN_PREFIX + "OIM",
                AreaAccessContext.AREA_USER_PREFIX + "OIM");
        assertThat(AreaAccessContext.isSuperAdmin(userPlusAreaAdmin)).isFalse();
        assertThat(AreaAccessContext.hasAllUserAreas(userPlusAreaAdmin)).isTrue();
        assertThat(AreaAccessContext.canAccessUserArea(userPlusAreaAdmin, "PingID")).isTrue();
        assertThat(AreaAccessContext.canAdministerArea(userPlusAreaAdmin, "OIM")).isTrue();
        assertThat(AreaAccessContext.canAdministerArea(userPlusAreaAdmin, "PingID")).isFalse();
        assertThat(AreaAccessContext.allowedUserAreasLower(userPlusAreaAdmin)).isEmpty();
        assertThat(AreaAccessContext.allowedAdminAreasLower(userPlusAreaAdmin))
            .contains(Set.of("oim"));

        // Superadmin + Area User (Superadmin overrides scoped)
        Authentication adminPlusAreaUser =
            authWith(AreaAccessContext.ROLE_ADMIN, AreaAccessContext.ROLE_AREA_ADMIN,
                AreaAccessContext.ROLE_USER, AreaAccessContext.AREA_ADMIN_ALL,
                AreaAccessContext.AREA_USER_ALL, AreaAccessContext.AREA_USER_PREFIX + "OIM");
        assertThat(AreaAccessContext.isSuperAdmin(adminPlusAreaUser)).isTrue();
        assertThat(AreaAccessContext.hasAllUserAreas(adminPlusAreaUser)).isTrue();
        assertThat(AreaAccessContext.allowedUserAreasLower(adminPlusAreaUser)).isEmpty();
        assertThat(AreaAccessContext.allowedAdminAreasLower(adminPlusAreaUser)).isEmpty();
    }

    @Test
    void inspectionMethods_adversarialEdgeCases() {
        Authentication oimAdmin = authWith(
            AreaAccessContext.ROLE_AREA_ADMIN,
            AreaAccessContext.ROLE_USER,
            AreaAccessContext.AREA_ADMIN_PREFIX + "OIM",
            AreaAccessContext.AREA_USER_PREFIX + "OIM"
        );

        // Blank, empty, null, and wildcard areas for scoped admin
        assertThat(AreaAccessContext.canAdministerArea(oimAdmin, null)).isFalse();
        assertThat(AreaAccessContext.canAdministerArea(oimAdmin, "")).isFalse();
        assertThat(AreaAccessContext.canAdministerArea(oimAdmin, "   ")).isFalse();
        assertThat(AreaAccessContext.canAdministerArea(oimAdmin, "*")).isFalse();
        assertThat(AreaAccessContext.canAccessUserArea(oimAdmin, null)).isFalse();
        assertThat(AreaAccessContext.canAccessUserArea(oimAdmin, "")).isFalse();
        assertThat(AreaAccessContext.canAccessUserArea(oimAdmin, "   ")).isFalse();
        assertThat(AreaAccessContext.canAccessUserArea(oimAdmin, "*")).isFalse();

        // Null authentication for all inspection methods
        assertThat(AreaAccessContext.isSuperAdmin((Authentication) null)).isFalse();
        assertThat(AreaAccessContext.hasAllUserAreas((Authentication) null)).isFalse();
        assertThat(AreaAccessContext.canAccessUserArea((Authentication) null, "OIM")).isFalse();
        assertThat(AreaAccessContext.canAdministerArea((Authentication) null, "OIM")).isFalse();
        assertThat(AreaAccessContext.canIngestArea((Authentication) null, "OIM")).isFalse();
        assertThat(AreaAccessContext.allowedUserAreasLower((Authentication) null)).contains(Collections.emptySet());
        assertThat(AreaAccessContext.allowedAdminAreasLower((Authentication) null)).contains(Collections.emptySet());
        assertThat(AreaAccessContext.allowedIngestAreasLower((Authentication) null)).contains(Collections.emptySet());
        assertThat(AreaAccessContext.filterByUserArea((Authentication) null, List.of("OIM"), Function.identity())).isEmpty();
        assertThat(AreaAccessContext.filterByAdminArea((Authentication) null, List.of("OIM"), Function.identity())).isEmpty();

        // Filtering with null elements and null area values
        record Item(String id, String area) {}
        List<Item> itemsWithNulls = Arrays.asList(
            new Item("valid-oim", "OIM"),
            new Item("valid-oim-spaces", "  oim  "),
            new Item("null-area", null),
            new Item("blank-area", "   "),
            null
        );

        List<Item> filteredUser = AreaAccessContext.filterByUserArea(oimAdmin, itemsWithNulls, Item::area);
        assertThat(filteredUser).extracting(Item::id).containsExactly("valid-oim", "valid-oim-spaces");

        List<Item> filteredAdmin = AreaAccessContext.filterByAdminArea(oimAdmin, itemsWithNulls, Item::area);
        assertThat(filteredAdmin).extracting(Item::id).containsExactly("valid-oim", "valid-oim-spaces");
    }
}

