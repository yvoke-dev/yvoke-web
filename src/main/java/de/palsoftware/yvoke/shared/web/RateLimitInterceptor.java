package de.palsoftware.yvoke.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Applies {@link GenerationRateLimiter} to the expensive generation / ingest endpoints, keyed by
 * the authenticated principal (falling back to the client IP for unauthenticated calls). Runs in
 * {@code preHandle}, i.e. before the controller and — crucially for SSE — before the streaming
 * response is committed, so an over-limit request becomes a clean HTTP 429 (SEC-03).
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final GenerationRateLimiter rateLimiter;

    public RateLimitInterceptor(GenerationRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
        Object handler) {
        if (!rateLimiter.tryAcquire(resolvePrincipal(request))) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "Rate limit exceeded. Please wait before sending more requests.");
        }
        return true;
    }

    private static String resolvePrincipal(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getName() != null
            && !"anonymousUser".equals(auth.getName())) {
            return "user:" + auth.getName();
        }
        return "ip:" + request.getRemoteAddr();
    }
}
