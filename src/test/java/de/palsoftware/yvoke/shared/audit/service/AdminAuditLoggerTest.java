package de.palsoftware.yvoke.shared.audit.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.palsoftware.yvoke.shared.audit.repository.AuditLogRepository;
import de.palsoftware.yvoke.shared.user.model.User;
import de.palsoftware.yvoke.shared.user.service.UserService;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class AdminAuditLoggerTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void prefersResolvedUserEntraOidAndFallsBackToAuthenticationName() {
        AuditLogRepository repo = mock(AuditLogRepository.class);
        UserService userService = mock(UserService.class);
        AdminAuditLogger logger = new AdminAuditLogger(repo, userService);

        when(userService.getCurrentUser())
            .thenReturn(Optional.of(new User(UUID.randomUUID(), "oid-admin-1", "a@b", "A", null)));
        logger.log("SAVE_AREA", "OIM", Map.of("prototype", false));
        verify(repo).log("oid-admin-1", "SAVE_AREA", "OIM", Map.of("prototype", false));

        when(userService.getCurrentUser()).thenReturn(Optional.empty());
        SecurityContextHolder.getContext()
            .setAuthentication(new TestingAuthenticationToken("area-admin-sub", "x", "ROLE_ADMIN"));
        logger.log("DELETE_AREA", "OIM", Map.of());
        verify(repo).log("area-admin-sub", "DELETE_AREA", "OIM", Map.of());
    }

    @Test
    void constructorRejectsNullDependencies() {
        AuditLogRepository repo = mock(AuditLogRepository.class);
        UserService userService = mock(UserService.class);

        assertThatThrownBy(() -> new AdminAuditLogger(null, userService))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AdminAuditLogger(repo, null))
            .isInstanceOf(NullPointerException.class);
    }
}
