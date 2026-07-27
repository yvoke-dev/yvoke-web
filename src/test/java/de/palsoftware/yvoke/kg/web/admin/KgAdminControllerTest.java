package de.palsoftware.yvoke.kg.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.palsoftware.yvoke.kg.core.service.KgAdminViewService;
import de.palsoftware.yvoke.kg.core.service.KgConsolidator;
import de.palsoftware.yvoke.kg.core.repository.KgGraphReadRepository;
import de.palsoftware.yvoke.kg.core.repository.KgWriteRepository;
import de.palsoftware.yvoke.shared.audit.repository.AuditLogRepository;
import de.palsoftware.yvoke.shared.user.service.UserService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

public class KgAdminControllerTest {

    private KgAdminViewService kgAdminViewService;
    private KgGraphReadRepository kgReadRepository;
    private KgWriteRepository kgWriteRepository;
    private KgConsolidator kgConsolidator;
    private AuditLogRepository auditLogRepository;
    private UserService userService;

    private KgAdminController controller;
    private RedirectAttributes redirectAttributes;

    @BeforeEach
    public void setUp() {
        kgAdminViewService = mock(KgAdminViewService.class);
        kgReadRepository = mock(KgGraphReadRepository.class);
        kgWriteRepository = mock(KgWriteRepository.class);
        kgConsolidator = mock(KgConsolidator.class);
        auditLogRepository = mock(AuditLogRepository.class);
        userService = mock(UserService.class);

        when(userService.getCurrentUser()).thenReturn(Optional.empty());

        controller = new KgAdminController(kgAdminViewService, kgReadRepository, kgWriteRepository,
            kgConsolidator, auditLogRepository, userService);
        redirectAttributes = mock(RedirectAttributes.class);
    }

    @Test
    public void testConsolidateKg() {
        var mockStats = new KgConsolidator.ConsolidationStats(1, 2, 3, 4);
        when(kgConsolidator.consolidate("OIM", "9.3")).thenReturn(mockStats);

        String view = controller.consolidateKg("OIM", "9.3", redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/kg");
        verify(kgConsolidator).consolidate("OIM", "9.3");
        verify(auditLogRepository).log(eq("anonymous_admin"), eq("CONSOLIDATE_KG"), eq("OIM 9.3"),
            anyMap());
    }
}
