package de.palsoftware.yvoke.shared.jobengine;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

/**
 * The connector kinds run with the STORED Confluence admin credentials, so every untrusted HTTP
 * entry point that can name a job kind must go through this guard. It is deliberately NOT an
 * {@code EnqueueValidator}: the crawl fans out one job per page from a worker thread that has no
 * {@code SecurityContext}, and those internal enqueues are trusted.
 */
class PrivilegedJobKindGuardTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateWith(String... roles) {
        SecurityContextHolder.getContext()
            .setAuthentication(new UsernamePasswordAuthenticationToken("principal", "n/a",
                Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList()));
    }

    private static void assertForbidden(String kind) {
        assertThatThrownBy(() -> PrivilegedJobKindGuard.requireAdminForPrivilegedKind(kind))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void confluenceKindsAreForbiddenForPlainUser() {
        authenticateWith("ROLE_USER");

        assertForbidden("confluence-import");
        assertForbidden("confluence-page-import");
    }

    @Test
    void confluenceKindsAreForbiddenForTheApiKeyIngestRole() {
        authenticateWith("ROLE_INGEST");

        assertForbidden("confluence-import");
    }

    @Test
    void instanceSuffixDoesNotEvadeTheCheck() {
        authenticateWith("ROLE_USER");

        assertForbidden("confluence-page-import:oim-space");
        assertForbidden("  CONFLUENCE-IMPORT  ");
    }

    @Test
    void unauthenticatedIsForbidden() {
        SecurityContextHolder.clearContext();

        assertForbidden("confluence-import");
    }

    @Test
    void adminIsAllowed() {
        authenticateWith("ROLE_ADMIN");

        assertThatCode(
            () -> PrivilegedJobKindGuard.requireAdminForPrivilegedKind("confluence-page-import"))
            .doesNotThrowAnyException();
    }

    @Test
    void nonPrivilegedKindsStayOpen() {
        authenticateWith("ROLE_USER");

        assertThatCode(() -> PrivilegedJobKindGuard.requireAdminForPrivilegedKind("standard"))
            .doesNotThrowAnyException();
        assertThatCode(() -> PrivilegedJobKindGuard.requireAdminForPrivilegedKind(null))
            .doesNotThrowAnyException();
    }
}
