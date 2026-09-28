package de.palsoftware.yvoke.chat.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository.AdminConversation;
import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository.ConversationFilter;
import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository.ConversationOverviewStats;
import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository.ConversationUserOption;
import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository.FeedbackFilter;
import de.palsoftware.yvoke.chat.core.service.ConversationAdminService;
import de.palsoftware.yvoke.chat.core.service.ConversationAdminService.ConversationAdminView;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class ConversationAdminControllerTest {

    @Mock
    private ConversationAdminService conversationAdminService;

    @InjectMocks
    private ConversationAdminController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void testDefaultParameters_Success() throws Exception {
        UUID convId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AdminConversation sampleConv =
            new AdminConversation(convId, userId, "Alice", "alice@example.com", "Help needed",
                "web", OffsetDateTime.now(), OffsetDateTime.now(), 2, 0);
        ConversationUserOption userOption =
            new ConversationUserOption(userId, "Alice", "alice@example.com");

        ConversationOverviewStats stats = new ConversationOverviewStats(1L, 2L, 0L, List.of());
        ConversationAdminView mockView = new ConversationAdminView(List.of(sampleConv),
            List.of(userOption), stats, 0, 1, 1L, 20);

        when(conversationAdminService.getConversationAdminView(any(ConversationFilter.class), eq(0),
            eq(20))).thenReturn(mockView);

        mockMvc.perform(get("/admin/conversations")).andExpect(status().isOk())
            .andExpect(view().name("admin/conversations"))
            .andExpect(model().attribute("currentPage", 0))
            .andExpect(model().attribute("totalPages", 1))
            .andExpect(model().attribute("totalCount", 1L))
            .andExpect(model().attribute("pageSize", 20))
            .andExpect(model().attribute("activeTab", "conversations"))
            .andExpect(model().attribute("selectedTimeRange", "all"))
            .andExpect(model().attribute("selectedFeedback", "all"))
            .andExpect(model().attribute("fromDate", "")).andExpect(model().attribute("toDate", ""))
            .andExpect(model().attribute("selectedUserIds", List.of()))
            .andExpect(model().attribute("conversations", List.of(sampleConv)))
            .andExpect(model().attribute("userOptions", List.of(userOption)))
            .andExpect(model().attribute("stats", stats));

        ArgumentCaptor<ConversationFilter> captor =
            ArgumentCaptor.forClass(ConversationFilter.class);
        verify(conversationAdminService).getConversationAdminView(captor.capture(), eq(0), eq(20));
        ConversationFilter filter = captor.getValue();
        assertThat(filter.userIds()).isEmpty();
        assertThat(filter.includeAnonymous()).isFalse();
        assertThat(filter.timeFilter().preset()).isEqualTo("all");
        assertThat(filter.feedbackFilter()).isEqualTo(FeedbackFilter.ALL);
    }

    @Test
    void testFilteringByUsers_AnonymousAndUuid() throws Exception {
        UUID validUserId = UUID.randomUUID();
        ConversationOverviewStats emptyStats = new ConversationOverviewStats(0L, 0L, 0L, List.of());
        ConversationAdminView mockView =
            new ConversationAdminView(List.of(), List.of(), emptyStats, 0, 1, 0L, 20);
        when(conversationAdminService.getConversationAdminView(any(ConversationFilter.class), eq(0),
            eq(20))).thenReturn(mockView);

        mockMvc
            .perform(
                get("/admin/conversations").param("userIds", "anonymous", validUserId.toString()))
            .andExpect(status().isOk()).andExpect(
                model().attribute("selectedUserIds", List.of("anonymous", validUserId.toString())));

        ArgumentCaptor<ConversationFilter> captor =
            ArgumentCaptor.forClass(ConversationFilter.class);
        verify(conversationAdminService).getConversationAdminView(captor.capture(), eq(0), eq(20));
        ConversationFilter filter = captor.getValue();
        assertThat(filter.includeAnonymous()).isTrue();
        assertThat(filter.userIds()).containsExactly(validUserId);
    }

    @Test
    void testFilteringByCustomDates_Valid() throws Exception {
        ConversationOverviewStats emptyStats = new ConversationOverviewStats(0L, 0L, 0L, List.of());
        ConversationAdminView mockView =
            new ConversationAdminView(List.of(), List.of(), emptyStats, 0, 1, 0L, 20);
        when(conversationAdminService.getConversationAdminView(any(ConversationFilter.class), eq(0),
            eq(20))).thenReturn(mockView);

        mockMvc
            .perform(get("/admin/conversations").param("timeRange", "custom")
                .param("fromDate", "2026-09-01").param("toDate", "2026-09-10"))
            .andExpect(status().isOk()).andExpect(model().attribute("selectedTimeRange", "custom"))
            .andExpect(model().attribute("fromDate", "2026-09-01"))
            .andExpect(model().attribute("toDate", "2026-09-10"));

        ArgumentCaptor<ConversationFilter> captor =
            ArgumentCaptor.forClass(ConversationFilter.class);
        verify(conversationAdminService).getConversationAdminView(captor.capture(), eq(0), eq(20));
        ConversationFilter filter = captor.getValue();
        assertThat(filter.timeFilter().preset()).isEqualTo("custom");
        assertThat(filter.timeFilter().fromCutoff()).isNotNull();
        assertThat(filter.timeFilter().toCutoff()).isNotNull();
    }

    @Test
    void testValidation_MalformedDateRejectedWith400() throws Exception {
        mockMvc.perform(get("/admin/conversations").param("timeRange", "custom").param("fromDate",
            "not-a-valid-date")).andExpect(status().isBadRequest());
    }

    @Test
    void testValidation_MalformedUuidRejectedWith400() throws Exception {
        mockMvc.perform(get("/admin/conversations").param("userIds", "not-a-valid-uuid"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void testValidation_UnknownFeedbackFilterRejectedWith400() throws Exception {
        mockMvc.perform(get("/admin/conversations").param("feedback", "garbage-feedback"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void testValidation_UnknownTimeRangePresetRejectedWith400() throws Exception {
        mockMvc.perform(get("/admin/conversations").param("timeRange", "garbage-timerange"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void testFeedbackFilterOptions_Valid() throws Exception {
        ConversationOverviewStats emptyStats = new ConversationOverviewStats(0L, 0L, 0L, List.of());
        ConversationAdminView mockView =
            new ConversationAdminView(List.of(), List.of(), emptyStats, 0, 1, 0L, 20);
        when(conversationAdminService.getConversationAdminView(any(ConversationFilter.class), eq(0),
            eq(20))).thenReturn(mockView);

        mockMvc.perform(get("/admin/conversations").param("feedback", "positive"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("selectedFeedback", "positive"));

        mockMvc.perform(get("/admin/conversations").param("feedback", "negative"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("selectedFeedback", "negative"));

        mockMvc.perform(get("/admin/conversations").param("feedback", "none"))
            .andExpect(status().isOk()).andExpect(model().attribute("selectedFeedback", "none"));

        mockMvc.perform(get("/admin/conversations").param("feedback", "any"))
            .andExpect(status().isOk()).andExpect(model().attribute("selectedFeedback", "any"));
    }

    @Test
    void testPaginationParametersDelegatedToService() throws Exception {
        ConversationOverviewStats emptyStats =
            new ConversationOverviewStats(100L, 0L, 0L, List.of());
        ConversationAdminView mockView =
            new ConversationAdminView(List.of(), List.of(), emptyStats, 2, 4, 100L, 30);
        when(conversationAdminService.getConversationAdminView(any(ConversationFilter.class), eq(2),
            eq(30))).thenReturn(mockView);

        mockMvc.perform(get("/admin/conversations").param("page", "2").param("size", "30"))
            .andExpect(status().isOk()).andExpect(model().attribute("currentPage", 2))
            .andExpect(model().attribute("pageSize", 30))
            .andExpect(model().attribute("totalPages", 4));
        verify(conversationAdminService).getConversationAdminView(any(), eq(2), eq(30));
    }
}
