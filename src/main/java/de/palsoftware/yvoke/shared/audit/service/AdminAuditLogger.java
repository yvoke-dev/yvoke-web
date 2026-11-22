package de.palsoftware.yvoke.shared.audit.service;

import de.palsoftware.yvoke.shared.audit.repository.AuditLogRepository;
import de.palsoftware.yvoke.shared.user.service.UserService;
import java.util.Map;
import java.util.Objects;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Resolves the current admin actor and writes structured audit log entries for privileged
 * mutations.
 */
@Component
public class AdminAuditLogger {

    private final AuditLogRepository auditLogRepository;
    private final UserService userService;

    public AdminAuditLogger(AuditLogRepository auditLogRepository, UserService userService) {
        this.auditLogRepository =
                Objects.requireNonNull(auditLogRepository, "auditLogRepository must not be null");
        this.userService = Objects.requireNonNull(userService, "userService must not be null");
    }

    public void log(String action, String target, Map<String, Object> details) {
        auditLogRepository.log(resolveActor(), action, target, details);
    }

    private String resolveActor() {
        var current = userService.getCurrentUser();
        if (current.isPresent()) {
            return current.get().entraOid();
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null
                && auth.isAuthenticated()
                && auth.getName() != null
                && !auth.getName().isBlank()) {
            return auth.getName();
        }
        return "anonymous_admin";
    }
}
