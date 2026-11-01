package de.palsoftware.yvoke.chat.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository;
import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository.FeedbackComment;
import java.time.OffsetDateTime;
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

class AdminFeedbackControllerTest {

    private ChatAdminQueryRepository queryRepository;
    private AdminFeedbackController controller;
    private Model model;

    @BeforeEach
    void setUp() {
        queryRepository = mock(ChatAdminQueryRepository.class);
        controller = new AdminFeedbackController(queryRepository);
        model = new ConcurrentModel();
        SecurityContextHolder.getContext()
            .setAuthentication(new TestingAuthenticationToken("admin", "pass", "ROLE_ADMIN"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void superAdminFeedbackDashboardPassesNullArea() {
        when(queryRepository.countFeedbackByRating(1, null)).thenReturn(5L);
        when(queryRepository.countFeedbackByRating(-1, null)).thenReturn(2L);
        when(queryRepository.countFilteredFeedback(null, null, "all", null)).thenReturn(7L);
        when(queryRepository.listFeedback(null, null, "all", "newest", 20, 0, null))
            .thenReturn(List.of());

        String view = controller.feedbackDashboard(null, null, "all", "newest", 0, 20, model);

        assertThat(view).isEqualTo("admin/feedback");
        assertThat(model.getAttribute("positiveCount")).isEqualTo(5L);
        assertThat(model.getAttribute("negativeCount")).isEqualTo(2L);
        assertThat(model.getAttribute("totalCount")).isEqualTo(7L);
        verify(queryRepository).countFeedbackByRating(1, null);
        verify(queryRepository).countFeedbackByRating(-1, null);
        verify(queryRepository).countFilteredFeedback(null, null, "all", null);
        verify(queryRepository).listFeedback(null, null, "all", "newest", 20, 0, null);
    }

    @Test
    void areaAdminFeedbackDashboardScopesToAdministeredArea() {
        SecurityContextHolder.getContext()
            .setAuthentication(new TestingAuthenticationToken("area_admin", "pass",
                List.of(new SimpleGrantedAuthority("ROLE_AREA_ADMIN"),
                    new SimpleGrantedAuthority("AREA_ADMIN_FINANCE"))));
        Set<String> areas = Set.of("finance");
        when(queryRepository.countFeedbackByRating(1, areas)).thenReturn(3L);
        when(queryRepository.countFeedbackByRating(-1, areas)).thenReturn(1L);
        when(queryRepository.countFilteredFeedback(null, null, "all", areas)).thenReturn(4L);
        when(queryRepository.listFeedback(null, null, "all", "newest", 20, 0, areas))
            .thenReturn(List.of());

        String view = controller.feedbackDashboard(null, null, "all", "newest", 0, 20, model);

        assertThat(view).isEqualTo("admin/feedback");
        assertThat(model.getAttribute("positiveCount")).isEqualTo(3L);
        assertThat(model.getAttribute("negativeCount")).isEqualTo(1L);
        assertThat(model.getAttribute("totalCount")).isEqualTo(4L);
        verify(queryRepository).countFeedbackByRating(1, areas);
        verify(queryRepository).countFeedbackByRating(-1, areas);
        verify(queryRepository).countFilteredFeedback(null, null, "all", areas);
        verify(queryRepository).listFeedback(null, null, "all", "newest", 20, 0, areas);
    }

    @Test
    void superAdminCanToggleReviewed() {
        UUID feedbackId = UUID.randomUUID();
        when(queryRepository.findFeedbackArea(feedbackId)).thenReturn(Optional.of("finance"));

        String view = controller.toggleReviewed(feedbackId, "true", model);

        assertThat(view).isEqualTo("admin/feedback :: reviewed-cell");
        assertThat(model.getAttribute("reviewed")).isEqualTo(true);
        verify(queryRepository).setFeedbackReviewed(feedbackId, true);
    }

    @Test
    void areaAdminCanToggleReviewedInAdministeredArea() {
        SecurityContextHolder.getContext()
            .setAuthentication(new TestingAuthenticationToken("area_admin", "pass",
                List.of(new SimpleGrantedAuthority("ROLE_AREA_ADMIN"),
                    new SimpleGrantedAuthority("AREA_ADMIN_FINANCE"))));
        UUID feedbackId = UUID.randomUUID();
        when(queryRepository.findFeedbackArea(feedbackId)).thenReturn(Optional.of("finance"));

        String view = controller.toggleReviewed(feedbackId, "true", model);

        assertThat(view).isEqualTo("admin/feedback :: reviewed-cell");
        assertThat(model.getAttribute("reviewed")).isEqualTo(true);
        verify(queryRepository).setFeedbackReviewed(feedbackId, true);
    }

    @Test
    void areaAdminCannotToggleReviewedInUnadministeredArea() {
        SecurityContextHolder.getContext()
            .setAuthentication(new TestingAuthenticationToken("area_admin", "pass",
                List.of(new SimpleGrantedAuthority("ROLE_AREA_ADMIN"),
                    new SimpleGrantedAuthority("AREA_ADMIN_FINANCE"))));
        UUID feedbackId = UUID.randomUUID();
        when(queryRepository.findFeedbackArea(feedbackId)).thenReturn(Optional.of("hr"));

        assertThatThrownBy(() -> controller.toggleReviewed(feedbackId, "true", model))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        verify(queryRepository, never()).setFeedbackReviewed(any(), anyBoolean());
    }

    @Test
    void areaAdminCanUpdateNotesInAdministeredArea() {
        SecurityContextHolder.getContext()
            .setAuthentication(new TestingAuthenticationToken("area_admin", "pass",
                List.of(new SimpleGrantedAuthority("ROLE_AREA_ADMIN"),
                    new SimpleGrantedAuthority("AREA_ADMIN_FINANCE"))));
        UUID feedbackId = UUID.randomUUID();
        when(queryRepository.findFeedbackArea(feedbackId)).thenReturn(Optional.of("finance"));

        String view = controller.updateNotes(feedbackId, "checked", model);

        assertThat(view).isEqualTo("admin/feedback :: notes-cell");
        assertThat(model.getAttribute("notes")).isEqualTo("checked");
        verify(queryRepository).setFeedbackNotes(feedbackId, "checked");
    }

    @Test
    void areaAdminCannotUpdateNotesInUnadministeredArea() {
        SecurityContextHolder.getContext()
            .setAuthentication(new TestingAuthenticationToken("area_admin", "pass",
                List.of(new SimpleGrantedAuthority("ROLE_AREA_ADMIN"),
                    new SimpleGrantedAuthority("AREA_ADMIN_FINANCE"))));
        UUID feedbackId = UUID.randomUUID();
        when(queryRepository.findFeedbackArea(feedbackId)).thenReturn(Optional.of("hr"));

        assertThatThrownBy(() -> controller.updateNotes(feedbackId, "checked", model))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        verify(queryRepository, never()).setFeedbackNotes(any(), anyString());
    }
}
