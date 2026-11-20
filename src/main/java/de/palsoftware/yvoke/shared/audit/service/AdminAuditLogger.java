package de.palsoftware.yvoke.shared.audit.service;

import de.palsoftware.yvoke.shared.audit.repository.AuditLogRepository;
import de.palsoftware.yvoke.shared.user.model.User;
import de.palsoftware.yvoke.shared.user.service.UserService;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Resolves the current admin actor and writes structured audit log entries for privileged
 * mutations.
 */
@Component
public class AdminAuditLogger {

    private static final AdminAuditLogger NOOP = new AdminAuditLogger(null, null);

    private final AuditLogRepository auditLogRepository;
    private final UserService userService;

    @Autowired
    public AdminAuditLogger(AuditLogRepository auditLogRepository, UserService userService) {
        this.auditLogRepository = auditLogRepository;
        this.userService = userService;
    }

    public static AdminAuditLogger noop() {
        return NOOP;
    }

    public void log(String action, String target, Map<String, Object> details) {
        if (auditLogRepository == null) {
            return;
        }
        auditLogRepository.log(resolveActor(), action, target, details);
    }

    private String resolveActor() {
        if (userService != null) {
            var current = userService.getCurrentUser();
            if (current.isPresent()) {
                return current.get().entraOid();
            }
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getName() != null
            && !auth.getName().isBlank()) {
            return auth.getName();
        }
        return "anonymous_admin";
    }
}
