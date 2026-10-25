package de.palsoftware.yvoke.shared.security;



import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;

public class EntraAuthoritiesMapper implements GrantedAuthoritiesMapper {
    private static final Logger log = LoggerFactory.getLogger(EntraAuthoritiesMapper.class);

    private final String adminClaimValue;
    private final String userClaimValue;

    public EntraAuthoritiesMapper(String adminClaimValue, String userClaimValue) {
        this.adminClaimValue = adminClaimValue;
        this.userClaimValue = userClaimValue;
    }

    @Override
    public Collection<? extends GrantedAuthority> mapAuthorities(
        Collection<? extends GrantedAuthority> authorities) {
        Set<GrantedAuthority> mappedAuthorities = new HashSet<>();

        // Keep standard OIDC client authorities (e.g. SCOPE_openid)
        mappedAuthorities.addAll(authorities);

        for (GrantedAuthority authority : authorities) {
            if (authority instanceof OidcUserAuthority oidcUserAuthority) {
                Object rolesClaim = oidcUserAuthority.getIdToken().getClaim("roles");
                if (rolesClaim instanceof List<?> rolesList) {
                    mappedAuthorities.addAll(AreaAccessContext.mapRoleClaimsToAuthorities(rolesList,
                        adminClaimValue, userClaimValue));
                } else if (oidcUserAuthority.getUserInfo() != null) {
                    // Check userinfo claims if not in ID Token
                    Object uiRoles = oidcUserAuthority.getUserInfo().getClaim("roles");
                    if (uiRoles instanceof List<?> rolesList) {
                        mappedAuthorities.addAll(AreaAccessContext.mapRoleClaimsToAuthorities(
                            rolesList, adminClaimValue, userClaimValue));
                    }
                }
            }
        }

        log.debug("Final mapped authorities: {}", mappedAuthorities);
        return mappedAuthorities;
    }
}
