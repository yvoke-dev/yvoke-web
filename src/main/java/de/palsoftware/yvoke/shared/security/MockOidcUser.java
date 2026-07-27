package de.palsoftware.yvoke.shared.security;



import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

public class MockOidcUser implements OidcUser {
    private final String displayName;
    private final List<GrantedAuthority> authorities;
    private final OidcIdToken idToken;

    public MockOidcUser(String entraOid, String email, String displayName,
        List<GrantedAuthority> authorities) {
        this.displayName = displayName;
        this.authorities = authorities;

        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", entraOid);
        claims.put("oid", entraOid);
        claims.put("preferred_username", email);
        claims.put("name", displayName);
        this.idToken = new OidcIdToken("mock-token-value", Instant.now(),
            Instant.now().plusSeconds(3600), claims);
    }

    @Override
    public Map<String, Object> getClaims() {
        return idToken.getClaims();
    }

    @Override
    public OidcUserInfo getUserInfo() {
        return new OidcUserInfo(getClaims());
    }

    @Override
    public OidcIdToken getIdToken() {
        return idToken;
    }

    @Override
    public Map<String, Object> getAttributes() {
        return getClaims();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getName() {
        return displayName;
    }
}
