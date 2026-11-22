package de.palsoftware.yvoke.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class TurnAreaScopeTest {

    @AfterEach
    void tearDown() {
        TurnAreaScopeHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void turnScopeNarrowsGlobalUserToSinglePlaybookAreaAndDeniesNullArea() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                "global",
                                "n/a",
                                List.of(
                                        new SimpleGrantedAuthority(AreaAccessContext.ROLE_USER),
                                        new SimpleGrantedAuthority(
                                                AreaAccessContext.AREA_USER_ALL))));
        TurnAreaScopeHolder.set(Set.of("oim"));

        assertThat(AreaAccessContext.canAccessEffectiveUserArea("OIM")).isTrue();
        assertThat(AreaAccessContext.canAccessEffectiveUserArea("PingID")).isFalse();
        assertThat(AreaAccessContext.canAccessEffectiveUserArea(null)).isFalse();
        assertThat(AreaAccessContext.canAccessEffectiveUserArea("  ")).isFalse();
    }

    @Test
    void turnScopeNeverWidensBeyondAuthenticatedUserAreas() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                "oim-only",
                                "n/a",
                                List.of(
                                        new SimpleGrantedAuthority(AreaAccessContext.ROLE_USER),
                                        new SimpleGrantedAuthority(
                                                AreaAccessContext.AREA_USER_PREFIX + "OIM"))));
        TurnAreaScopeHolder.set(Set.of("*"));

        assertThat(AreaAccessContext.canAccessEffectiveUserArea("OIM")).isTrue();
        assertThat(AreaAccessContext.canAccessEffectiveUserArea("PingID")).isFalse();
        assertThat(AreaAccessContext.canAccessEffectiveUserArea(null)).isFalse();
    }

    @Test
    void withoutTurnScopeAreaRestrictedCallerIsDeniedUnassignedNullArea() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                "oim-only",
                                "n/a",
                                List.of(
                                        new SimpleGrantedAuthority(AreaAccessContext.ROLE_USER),
                                        new SimpleGrantedAuthority(
                                                AreaAccessContext.AREA_USER_PREFIX + "OIM"))));

        assertThat(AreaAccessContext.canAccessEffectiveUserArea("OIM")).isTrue();
        assertThat(AreaAccessContext.canAccessEffectiveUserArea(null)).isFalse();
        assertThat(AreaAccessContext.canAccessEffectiveUserArea("")).isFalse();
    }

    @Test
    void backgroundThreadWithoutSecurityContextHonorsBoundTurnScope() {
        SecurityContextHolder.clearContext();
        TurnAreaScopeHolder.set(Set.of("pingid"));

        assertThat(AreaAccessContext.canAccessEffectiveUserArea("PingID")).isTrue();
        assertThat(AreaAccessContext.canAccessEffectiveUserArea("OIM")).isFalse();
        assertThat(AreaAccessContext.canAccessEffectiveUserArea(null)).isFalse();
    }
}
