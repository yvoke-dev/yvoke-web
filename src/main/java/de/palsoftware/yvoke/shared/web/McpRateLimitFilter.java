package de.palsoftware.yvoke.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-principal token-bucket rate limiter for {@code /mcp} and {@code /mcp/**}.
 *
 * <p>
 * Implemented as a Servlet filter (not a Spring MVC {@code HandlerInterceptor}) because Spring AI's
 * Streamable HTTP MCP endpoint is served through a WebMvc.fn {@code RouterFunction} rather than a
 * {@code @Controller}. Wired into the MCP {@code SecurityFilterChain} after authentication so the
 * bucket is keyed on the authenticated Entra principal ({@code auth.getName()}).
 */
public class McpRateLimitFilter extends OncePerRequestFilter {

    private final GenerationRateLimiter rateLimiter;
    private final long refillPeriodSeconds;

    public McpRateLimitFilter(GenerationRateLimiter rateLimiter, long refillPeriodSeconds) {
        this.rateLimiter = rateLimiter;
        this.refillPeriodSeconds = Math.max(1L, refillPeriodSeconds);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
        FilterChain filterChain) throws ServletException, IOException {
        String principal = resolvePrincipal(request);
        if (!rateLimiter.tryAcquire(principal)) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setHeader("Retry-After", String.valueOf(refillPeriodSeconds));
            response.getWriter()
                .write("{\"error\":\"MCP rate limit exceeded. Please retry later.\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static String resolvePrincipal(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getName() != null
            && !"anonymousUser".equals(auth.getName())) {
            return "mcp:user:" + auth.getName();
        }
        return "mcp:ip:" + request.getRemoteAddr();
    }
}
