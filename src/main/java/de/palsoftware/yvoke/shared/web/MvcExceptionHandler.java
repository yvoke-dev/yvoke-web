package de.palsoftware.yvoke.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Unified error handling for server-rendered MVC controllers (ARC-11 / MNT-10).
 *
 * <p>
 * Replaces the copy-pasted {@code try/catch}+flash blocks that every admin form handler used to
 * carry. When a classic browser form submission fails, the user is redirected back to the page that
 * hosted the form (its {@code Referer}) with a flash {@code error} message:
 * <ul>
 * <li><b>Validation</b> ({@link IllegalArgumentException}) keeps its own message — these are
 * intentional, user-facing rejections.</li>
 * <li><b>Unexpected</b> failures are logged server-side and surface only a fixed generic message,
 * never the raw exception detail (SEC-17).</li>
 * </ul>
 *
 * <p>
 * Only classic browser form posts are redirected. AJAX/htmx/fetch requests, downloads, GET view
 * renders and status-carrying {@link ResponseStatusException}s (e.g. a 404) are left with their
 * original behavior so nothing that relies on a fragment, a file body, or a real HTTP status code
 * regresses. {@code @RestController}s are handled separately by {@code ApiExceptionHandler}, which
 * is ordered ahead of this advice.
 */
@ControllerAdvice(annotations = Controller.class)
@Order(Ordered.LOWEST_PRECEDENCE)
public class MvcExceptionHandler {

    static final String GENERIC_ERROR_MESSAGE =
        "Something went wrong while processing your request. Please try again.";

    private static final String FALLBACK_REDIRECT = "redirect:/admin";

    private static final Logger log = LoggerFactory.getLogger(MvcExceptionHandler.class);

    /**
     * Preserve the framework's default status-page behavior for {@link ResponseStatusException}
     * (typically a 404 from an {@code orElseThrow}). Handling it explicitly here — rather than
     * letting the generic {@code Exception} handler swallow it — keeps a "not found" a real 404
     * instead of turning it into a redirect.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public void handleStatus(ResponseStatusException ex, HttpServletResponse response)
        throws IOException {
        int status = ex.getStatusCode().value();
        String reason = ex.getReason();
        if (reason == null || reason.isBlank()) {
            response.setStatus(status);
        } else {
            response.sendError(status, reason);
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public String handleValidation(IllegalArgumentException ex, HttpServletRequest request,
        RedirectAttributes redirectAttributes) throws IllegalArgumentException {
        if (!isNavigationalFormPost(request)) {
            throw ex;
        }
        String message = (ex.getMessage() != null && !ex.getMessage().isBlank()) ? ex.getMessage()
            : GENERIC_ERROR_MESSAGE;
        redirectAttributes.addFlashAttribute("error", message);
        return redirectBack(request);
    }

    @ExceptionHandler(Exception.class)
    public String handleUnexpected(Exception ex, HttpServletRequest request,
        RedirectAttributes redirectAttributes) throws Exception {
        // Let access-denied keep flowing to Spring Security; leave non-navigational requests
        // (GET renders, downloads, htmx/XHR/fetch) with their original error behavior.
        if (ex instanceof AccessDeniedException || !isNavigationalFormPost(request)) {
            throw ex;
        }
        log.error("Unhandled MVC exception on {} {}", request.getMethod(), request.getRequestURI(),
            ex);
        redirectAttributes.addFlashAttribute("error", GENERIC_ERROR_MESSAGE);
        return redirectBack(request);
    }

    /**
     * True only for classic browser form submissions: a state-changing method whose request is a
     * top-level HTML navigation (not htmx, not XHR, not a JSON/fetch call). Everything else keeps
     * its original error behavior.
     */
    private boolean isNavigationalFormPost(HttpServletRequest request) {
        String method = request.getMethod();
        boolean stateChanging = "POST".equals(method) || "PUT".equals(method)
            || "PATCH".equals(method) || "DELETE".equals(method);
        if (!stateChanging) {
            return false;
        }
        if (request.getHeader("HX-Request") != null) {
            return false;
        }
        if ("XMLHttpRequest".equalsIgnoreCase(request.getHeader("X-Requested-With"))) {
            return false;
        }
        String accept = request.getHeader("Accept");
        return accept != null && accept.toLowerCase(Locale.ROOT).contains("text/html");
    }

    /**
     * Redirect back to the page that hosted the form (its {@code Referer}), reduced to a
     * same-origin path + query so it can never become an open redirect. Falls back to the admin
     * landing page when no usable referer is present.
     */
    private String redirectBack(HttpServletRequest request) {
        String referer = request.getHeader("Referer");
        if (referer == null || referer.isBlank()) {
            return FALLBACK_REDIRECT;
        }
        try {
            URI uri = new URI(referer);
            String path = uri.getRawPath();
            if (path == null || path.isBlank()) {
                return FALLBACK_REDIRECT;
            }
            String query = uri.getRawQuery();
            return "redirect:" + path + (query != null ? "?" + query : "");
        } catch (URISyntaxException e) {
            return FALLBACK_REDIRECT;
        }
    }
}
