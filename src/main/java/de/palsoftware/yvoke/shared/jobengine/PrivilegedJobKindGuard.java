package de.palsoftware.yvoke.shared.jobengine;

import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

/**
 * Restricts job kinds whose handler runs with a connector's STORED admin credentials to
 * administrators.
 *
 * <p>
 * The API security chain grants {@code /api/ingest/**} and {@code /api/jobs/**} the same
 * {@code ROLE_INGEST/USER/ADMIN}, and the {@code confluence-*} handlers take an arbitrary page id
 * or crawl an entire space using the connector's service account. Without this guard any plain user
 * (or X-API-Key principal) could trigger a full crawl into a collection they name, spending
 * embeddings and occupying every worker thread, repeatably.
 *
 * <p>
 * Applied at the HTTP entry points rather than as an {@link EnqueueValidator}: the crawl fans out
 * one job per page from a worker/virtual thread that has no {@code SecurityContext} (documented
 * pitfall), and those internal enqueues are trusted.
 */
public final class PrivilegedJobKindGuard {

    /** Base-kind prefix of the connector kinds. */
    private static final String PRIVILEGED_KIND_PREFIX = "confluence";

    private static final String ADMIN_AUTHORITY = "ROLE_ADMIN";

    private PrivilegedJobKindGuard() {}

    /**
     * @throws ResponseStatusException {@code 403 FORBIDDEN} when {@code kind} is a privileged kind
     *         and the current authentication is not an administrator.
     */
    public static void requireAdminForPrivilegedKind(String kind) {
        if (!isPrivileged(kind)) {
            return;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean admin = authentication != null && authentication.isAuthenticated()
            && authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .anyMatch(ADMIN_AUTHORITY::equals);
        if (!admin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Job kind '" + baseKind(kind) + "' requires an administrator");
        }
    }

    public static boolean isPrivileged(String kind) {
        return baseKind(kind).startsWith(PRIVILEGED_KIND_PREFIX);
    }

    /** Kinds may carry a {@code ":<instance>"} suffix; the job engine routes on the base kind. */
    private static String baseKind(String kind) {
        return kind == null ? "" : kind.split(":", 2)[0].trim().toLowerCase(Locale.ROOT);
    }
}
