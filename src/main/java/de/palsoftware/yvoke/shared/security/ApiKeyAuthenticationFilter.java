package de.palsoftware.yvoke.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    /**
     * Request-scoped only, deliberately. The key authenticates a REQUEST, never a session — but the
     * request is not over when this filter returns. {@code /api/jobs/{id}/progress} returns an
     * {@code SseEmitter}, so the container re-dispatches with {@code DispatcherType.ASYNC}, and
     * {@code OncePerRequestFilter.shouldNotFilterAsyncDispatch()} is {@code true}, so this filter
     * is skipped on that dispatch while {@code AuthorizationFilter} (built with
     * {@code filterAsyncDispatch=true}) still authorizes it. Writing only to
     * {@code SecurityContextHolder} therefore left the second dispatch anonymous and 403ed the
     * subscription — and because the response was already committed, the denial surfaced as "Unable
     * to handle the Spring Security Exception because the response is already committed" and tore
     * the stream down.
     *
     * <p>
     * This must NOT be the chain's own repository: that one is a
     * {@code DelegatingSecurityContextRepository} whose delegates include
     * {@code HttpSessionSecurityContextRepository}, and saving through it would persist
     * {@code ROLE_INGEST} into a browser admin's session — stealing their authority for every later
     * page load and attributing their actions to {@code api-user}. The request-attribute name is a
     * static constant on {@code RequestAttributeSecurityContextRepository}, so an instance owned
     * here writes exactly the attribute the chain's delegating repository reads back.
     */
    private static final SecurityContextRepository CONTEXT_REPOSITORY =
        new RequestAttributeSecurityContextRepository();

    private static final SimpleGrantedAuthority ROLE_INGEST_AUTHORITY =
        new SimpleGrantedAuthority("ROLE_INGEST");
    private static final SimpleGrantedAuthority AREA_INGEST_ALL_AUTHORITY =
        new SimpleGrantedAuthority("AREA_INGEST_*");

    private final String apiKeyHeader;
    private final byte[] globalKeyBytes;
    private final Map<String, byte[]> areaKeyBytes;

    public ApiKeyAuthenticationFilter(String apiKeyHeader, String apiKeyValue) {
        this(apiKeyHeader, apiKeyValue, Map.of());
    }

    public ApiKeyAuthenticationFilter(String apiKeyHeader, String apiKeyValue,
        Map<String, String> areaApiKeys) {
        this.apiKeyHeader = apiKeyHeader;
        this.globalKeyBytes = (apiKeyValue == null || apiKeyValue.isBlank()) ? null
            : apiKeyValue.getBytes(StandardCharsets.UTF_8);

        Map<String, byte[]> areaKeys = new LinkedHashMap<>();
        if (areaApiKeys != null) {
            for (Map.Entry<String, String> entry : areaApiKeys.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    areaKeys.put(entry.getKey(), entry.getValue().getBytes(StandardCharsets.UTF_8));
                }
            }
        }
        this.areaKeyBytes = Collections.unmodifiableMap(areaKeys);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
        FilterChain filterChain) throws ServletException, IOException {

        String requestKey = request.getHeader(apiKeyHeader);

        if (requestKey != null && !requestKey.isBlank()) {
            byte[] requestKeyBytes = requestKey.getBytes(StandardCharsets.UTF_8);
            boolean globalMatched = false;
            String matchedArea = null;

            if (globalKeyBytes != null) {
                if (MessageDigest.isEqual(globalKeyBytes, requestKeyBytes)) {
                    globalMatched = true;
                }
            }

            for (Map.Entry<String, byte[]> entry : areaKeyBytes.entrySet()) {
                if (MessageDigest.isEqual(entry.getValue(), requestKeyBytes)) {
                    if (matchedArea == null) {
                        matchedArea = entry.getKey();
                    }
                }
            }

            if (globalMatched) {
                UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken("api-key-client", null,
                        List.of(ROLE_INGEST_AUTHORITY, AREA_INGEST_ALL_AUTHORITY));
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(auth);
                SecurityContextHolder.setContext(context);
                CONTEXT_REPOSITORY.saveContext(context, request, response);
            } else if (matchedArea != null) {
                String upperArea = matchedArea.trim().toUpperCase(Locale.ROOT);
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    "api-key-client:" + matchedArea, null, List.of(ROLE_INGEST_AUTHORITY,
                        new SimpleGrantedAuthority("AREA_INGEST_" + upperArea)));
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(auth);
                SecurityContextHolder.setContext(context);
                CONTEXT_REPOSITORY.saveContext(context, request, response);
            } else {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.getWriter().write("Invalid API Key");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }
}
