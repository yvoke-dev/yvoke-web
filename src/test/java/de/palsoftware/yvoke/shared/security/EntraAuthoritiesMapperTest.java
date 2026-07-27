package de.palsoftware.yvoke.shared.security;



import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;

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
}
