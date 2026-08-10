package de.palsoftware.yvoke.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
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

    private final String apiKeyHeader;
    private final byte[] apiKeyBytes;

    public ApiKeyAuthenticationFilter(String apiKeyHeader, String apiKeyValue) {
        this.apiKeyHeader = apiKeyHeader;
        this.apiKeyBytes =
            apiKeyValue == null ? new byte[0] : apiKeyValue.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
        FilterChain filterChain) throws ServletException, IOException {

        String requestKey = request.getHeader(apiKeyHeader);

        if (requestKey != null && !requestKey.isBlank()) {
            if (MessageDigest.isEqual(apiKeyBytes, requestKey.getBytes(StandardCharsets.UTF_8))) {
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    "api-user", null, List.of(new SimpleGrantedAuthority("ROLE_INGEST")));
                // A FRESH context, not getContext().setAuthentication(...): the latter mutates
                // whatever SecurityContextHolderFilter loaded, which on this deliberately
                // session-capable chain can be the very object held in a browser admin's session.
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
