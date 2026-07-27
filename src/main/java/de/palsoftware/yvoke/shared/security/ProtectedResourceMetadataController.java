package de.palsoftware.yvoke.shared.security;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProtectedResourceMetadataController {

    private final String resourceUrl;
    private final String authorizationServerUrl;
    private final String requiredScope;

    public ProtectedResourceMetadataController(
        @Value("${app.security.mcp.resource-url}") String resourceUrl,
        @Value("${app.security.mcp.authorization-server-url}") String authorizationServerUrl,
        @Value("${app.security.mcp.required-scope}") String requiredScope) {
        this.resourceUrl = resourceUrl;
        this.authorizationServerUrl = authorizationServerUrl;
        this.requiredScope = requiredScope;
    }

    @GetMapping(value = "/.well-known/oauth-protected-resource",
        produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getMetadata() {
        return Map.of("resource", resourceUrl, "authorization_servers",
            List.of(authorizationServerUrl), "scopes_supported", List.of(requiredScope),
            "bearer_methods_supported", List.of("header"));
    }

    @GetMapping(value = "/.well-known/oauth-authorization-server",
        produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getAuthorizationServerMetadata() {
        String baseIssuer = authorizationServerUrl.replaceAll("/+$", "");
        if (!baseIssuer.endsWith("/v2.0")) {
            baseIssuer = baseIssuer + "/v2.0";
        }

        String tenantBase = baseIssuer.replace("/v2.0", "");
        String authEndpoint = tenantBase + "/oauth2/v2.0/authorize";
        String tokenEndpoint = tenantBase + "/oauth2/v2.0/token";

        return Map.of("issuer", baseIssuer, "authorization_endpoint", authEndpoint,
            "token_endpoint", tokenEndpoint, "jwks_uri",
            "https://login.microsoftonline.com/common/discovery/v2.0/keys",
            "response_types_supported", List.of("code"), "subject_types_supported",
            List.of("pairwise", "public"), "id_token_signing_alg_values_supported",
            List.of("RS256"), "code_challenge_methods_supported", List.of("S256"),
            "scopes_supported", List.of("openid", "profile", "offline_access", requiredScope));
    }

    @GetMapping(value = "/.well-known/openid-configuration",
        produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getOpenIdConfiguration() {
        return getAuthorizationServerMetadata();
    }
}
