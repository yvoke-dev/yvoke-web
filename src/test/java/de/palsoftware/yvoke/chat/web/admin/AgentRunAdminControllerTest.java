package de.palsoftware.yvoke.chat.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.palsoftware.yvoke.chat.orchestration.AgentRunAdminViewService;
import de.palsoftware.yvoke.chat.orchestration.AgentRunAdminViews.RunDetail;
import de.palsoftware.yvoke.chat.orchestration.AgentRunAdminViews.RunDetailPage;
import de.palsoftware.yvoke.chat.orchestration.AgentRunAdminViews.RunSummary;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.web.server.ResponseStatusException;

class AgentRunAdminControllerTest {

    private AgentRunAdminViewService viewService;
    private AgentRunAdminController controller;
    private Model model;

    @BeforeEach
    void setUp() {
        viewService = mock(AgentRunAdminViewService.class);
        controller = new AgentRunAdminController(viewService);
        model = new ConcurrentModel();
        SecurityContextHolder.getContext()
            .setAuthentication(new TestingAuthenticationToken("admin", "pass", "ROLE_ADMIN"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void superAdminListAgentRunsPassesNullArea() {
        when(viewService.recentRuns(100, null)).thenReturn(List.of());

        String view = controller.listAgentRuns(100, model);

        assertThat(view).isEqualTo("admin/agent-runs");
        assertThat(model.getAttribute("activeTab")).isEqualTo("agent-runs");
        verify(viewService).recentRuns(100, null);
    }

    @Test
    void areaAdminListAgentRunsScopesToAdministeredArea() {
        SecurityContextHolder.getContext()
            .setAuthentication(new TestingAuthenticationToken("area_admin", "pass",
                List.of(new SimpleGrantedAuthority("ROLE_AREA_ADMIN"),
                    new SimpleGrantedAuthority("AREA_ADMIN_FINANCE"))));
        when(viewService.recentRuns(100, Set.of("finance"))).thenReturn(List.of());

        String view = controller.listAgentRuns(100, model);

        assertThat(view).isEqualTo("admin/agent-runs");
        verify(viewService).recentRuns(100, Set.of("finance"));
    }

    @Test
    void superAdminCanViewAgentRun() {
        UUID runId = UUID.randomUUID();
        RunDetail detail = new RunDetail(runId, UUID.randomUUID(), UUID.randomUUID(), "prof",
            "done", 0, 10, 20, 30, "ok", null, null, Instant.now(), Instant.now());
        RunDetailPage page = new RunDetailPage(detail, List.of());
        when(viewService.findRunArea(runId)).thenReturn(Optional.of("finance"));
        when(viewService.runDetail(runId)).thenReturn(Optional.of(page));

        String view = controller.viewAgentRun(runId, model);

        assertThat(view).isEqualTo("admin/agent-run-detail");
        assertThat(model.getAttribute("run")).isEqualTo(detail);
    }

    @Test
    void areaAdminCanViewRunInAdministeredArea() {
        SecurityContextHolder.getContext()
            .setAuthentication(new TestingAuthenticationToken("area_admin", "pass",
                List.of(new SimpleGrantedAuthority("ROLE_AREA_ADMIN"),
                    new SimpleGrantedAuthority("AREA_ADMIN_FINANCE"))));
        UUID runId = UUID.randomUUID();
        RunDetail detail = new RunDetail(runId, UUID.randomUUID(), UUID.randomUUID(), "prof",
            "done", 0, 10, 20, 30, "ok", null, null, Instant.now(), Instant.now());
        RunDetailPage page = new RunDetailPage(detail, List.of());
        when(viewService.findRunArea(runId)).thenReturn(Optional.of("finance"));
        when(viewService.runDetail(runId)).thenReturn(Optional.of(page));

        String view = controller.viewAgentRun(runId, model);

        assertThat(view).isEqualTo("admin/agent-run-detail");
        assertThat(model.getAttribute("run")).isEqualTo(detail);
    }

    @Test
    void areaAdminCannotViewRunInUnadministeredArea() {
        SecurityContextHolder.getContext()
            .setAuthentication(new TestingAuthenticationToken("area_admin", "pass",
                List.of(new SimpleGrantedAuthority("ROLE_AREA_ADMIN"),
                    new SimpleGrantedAuthority("AREA_ADMIN_FINANCE"))));
        UUID runId = UUID.randomUUID();
        when(viewService.findRunArea(runId)).thenReturn(Optional.of("hr"));

        assertThatThrownBy(() -> controller.viewAgentRun(runId, model))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
    }

    @Test
    void areaAdminCannotViewRunWithNullArea() {
        SecurityContextHolder.getContext()
            .setAuthentication(new TestingAuthenticationToken("area_admin", "pass",
                List.of(new SimpleGrantedAuthority("ROLE_AREA_ADMIN"),
                    new SimpleGrantedAuthority("AREA_ADMIN_FINANCE"))));
        UUID runId = UUID.randomUUID();
        when(viewService.findRunArea(runId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.viewAgentRun(runId, model))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
    }
}
