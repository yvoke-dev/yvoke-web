package de.palsoftware.yvoke.chat.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository;
import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository.AdminConversation;
import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository.ConversationFilter;
import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository.ConversationUserOption;
import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository.FeedbackFilter;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
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
    private ChatAdminQueryRepository chatAdminQueryRepository;

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

        when(chatAdminQueryRepository.countFilteredConversations(any(ConversationFilter.class)))
            .thenReturn(1L);
        when(chatAdminQueryRepository.listFilteredConversations(any(ConversationFilter.class),
            eq(20), eq(0))).thenReturn(List.of(sampleConv));
        when(chatAdminQueryRepository.listConversationUserOptions(any()))
            .thenReturn(List.of(userOption));

        mockMvc.perform(get("/admin/conversations")).andExpect(status().isOk())
            .andExpect(view().name("admin/conversations"))
            .andExpect(model().attribute("currentPage", 0))
            .andExpect(model().attribute("totalPages", 1))
            .andExpect(model().attribute("totalCount", 1L))
            .andExpect(model().attribute("activeTab", "conversations"))
            .andExpect(model().attribute("selectedTimeRange", "all"))
            .andExpect(model().attribute("selectedFeedback", "all"))
            .andExpect(model().attribute("fromDate", "")).andExpect(model().attribute("toDate", ""))
            .andExpect(model().attribute("selectedUserIds", Set.of()))
            .andExpect(model().attribute("conversations", List.of(sampleConv)))
            .andExpect(model().attribute("userOptions", List.of(userOption)));

        ArgumentCaptor<ConversationFilter> captor =
            ArgumentCaptor.forClass(ConversationFilter.class);
        verify(chatAdminQueryRepository).countFilteredConversations(captor.capture());
        ConversationFilter filter = captor.getValue();
        assertThat(filter.userIds()).isEmpty();
        assertThat(filter.includeAnonymous()).isFalse();
        assertThat(filter.timeFilter().preset()).isEqualTo("all");
        assertThat(filter.feedbackFilter()).isEqualTo(FeedbackFilter.ALL);
    }

    @Test
    void testFilteringByUsers_AnonymousAndUuid() throws Exception {
        UUID validUserId = UUID.randomUUID();
        when(chatAdminQueryRepository.countFilteredConversations(any(ConversationFilter.class)))
            .thenReturn(0L);
        when(chatAdminQueryRepository.listFilteredConversations(any(ConversationFilter.class),
            anyInt(), anyInt())).thenReturn(List.of());
        when(chatAdminQueryRepository.listConversationUserOptions(any())).thenReturn(List.of());

        mockMvc
            .perform(
                get("/admin/conversations").param("userIds", "anonymous", validUserId.toString()))
            .andExpect(status().isOk()).andExpect(
                model().attribute("selectedUserIds", Set.of("anonymous", validUserId.toString())));

        ArgumentCaptor<ConversationFilter> captor =
            ArgumentCaptor.forClass(ConversationFilter.class);
        verify(chatAdminQueryRepository).countFilteredConversations(captor.capture());
        ConversationFilter filter = captor.getValue();
        assertThat(filter.includeAnonymous()).isTrue();
        assertThat(filter.userIds()).containsExactly(validUserId);
        verify(chatAdminQueryRepository).listConversationUserOptions(Set.of(validUserId));
    }

    @Test
    void testFilteringByCustomDates_Valid() throws Exception {
        when(chatAdminQueryRepository.countFilteredConversations(any(ConversationFilter.class)))
            .thenReturn(0L);
        when(chatAdminQueryRepository.listFilteredConversations(any(ConversationFilter.class),
            anyInt(), anyInt())).thenReturn(List.of());
        when(chatAdminQueryRepository.listConversationUserOptions(any())).thenReturn(List.of());

        mockMvc
            .perform(get("/admin/conversations").param("timeRange", "custom")
                .param("fromDate", "2026-09-01").param("toDate", "2026-09-10"))
            .andExpect(status().isOk()).andExpect(model().attribute("selectedTimeRange", "custom"))
            .andExpect(model().attribute("fromDate", "2026-09-01"))
            .andExpect(model().attribute("toDate", "2026-09-10"));

        ArgumentCaptor<ConversationFilter> captor =
            ArgumentCaptor.forClass(ConversationFilter.class);
        verify(chatAdminQueryRepository).countFilteredConversations(captor.capture());
        ConversationFilter filter = captor.getValue();
        assertThat(filter.timeFilter().preset()).isEqualTo("custom");
        assertThat(filter.timeFilter().fromCutoff()).isNotNull();
        assertThat(filter.timeFilter().toCutoff()).isNotNull();
    }

    @Test
    void testFilteringByCustomDates_MalformedDateTolerance() throws Exception {
        when(chatAdminQueryRepository.countFilteredConversations(any(ConversationFilter.class)))
            .thenReturn(0L);
        when(chatAdminQueryRepository.listFilteredConversations(any(ConversationFilter.class),
            anyInt(), anyInt())).thenReturn(List.of());
        when(chatAdminQueryRepository.listConversationUserOptions(any())).thenReturn(List.of());

        // Malformed dates should NOT produce HTTP 400 MethodArgumentTypeMismatchException
        mockMvc
            .perform(get("/admin/conversations").param("timeRange", "custom")
                .param("fromDate", "not-a-valid-date").param("toDate", "2026/99/99"))
            .andExpect(status().isOk()).andExpect(model().attribute("fromDate", ""))
            .andExpect(model().attribute("toDate", ""));
    }

    @Test
    void testMalformedUuidTolerance() throws Exception {
        when(chatAdminQueryRepository.countFilteredConversations(any(ConversationFilter.class)))
            .thenReturn(0L);
        when(chatAdminQueryRepository.listFilteredConversations(any(ConversationFilter.class),
            anyInt(), anyInt())).thenReturn(List.of());
        when(chatAdminQueryRepository.listConversationUserOptions(any())).thenReturn(List.of());

        // Malformed UUIDs should be safely ignored and not throw 400/500
        mockMvc
            .perform(
                get("/admin/conversations").param("userIds", "not-a-valid-uuid", "another-bad-id"))
            .andExpect(status().isOk()).andExpect(model().attribute("selectedUserIds", Set.of()));

        ArgumentCaptor<ConversationFilter> captor =
            ArgumentCaptor.forClass(ConversationFilter.class);
        verify(chatAdminQueryRepository).countFilteredConversations(captor.capture());
        ConversationFilter filter = captor.getValue();
        assertThat(filter.userIds()).isEmpty();
        assertThat(filter.includeAnonymous()).isFalse();
    }

    @Test
    void testFeedbackFilterOptions() throws Exception {
        when(chatAdminQueryRepository.countFilteredConversations(any(ConversationFilter.class)))
            .thenReturn(0L);
        when(chatAdminQueryRepository.listFilteredConversations(any(ConversationFilter.class),
            anyInt(), anyInt())).thenReturn(List.of());
        when(chatAdminQueryRepository.listConversationUserOptions(any())).thenReturn(List.of());

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

        mockMvc.perform(get("/admin/conversations").param("feedback", "invalid-feedback"))
            .andExpect(status().isOk()).andExpect(model().attribute("selectedFeedback", "all"));
    }

    @Test
    void testPagination_NegativePageClampedToZero() throws Exception {
        when(chatAdminQueryRepository.countFilteredConversations(any(ConversationFilter.class)))
            .thenReturn(100L);
        when(chatAdminQueryRepository.listFilteredConversations(any(ConversationFilter.class),
            anyInt(), anyInt())).thenReturn(List.of());
        when(chatAdminQueryRepository.listConversationUserOptions(any())).thenReturn(List.of());

        mockMvc.perform(get("/admin/conversations").param("page", "-5")).andExpect(status().isOk())
            .andExpect(model().attribute("currentPage", 0));
        verify(chatAdminQueryRepository).listFilteredConversations(any(), eq(20), eq(0));
    }

    @Test
    void testPagination_InvalidSizeClampedToDefault() throws Exception {
        when(chatAdminQueryRepository.countFilteredConversations(any(ConversationFilter.class)))
            .thenReturn(100L);
        when(chatAdminQueryRepository.listFilteredConversations(any(ConversationFilter.class),
            anyInt(), anyInt())).thenReturn(List.of());
        when(chatAdminQueryRepository.listConversationUserOptions(any())).thenReturn(List.of());

        // Zero size clamped to 20
        mockMvc.perform(get("/admin/conversations").param("size", "0")).andExpect(status().isOk());
        verify(chatAdminQueryRepository).listFilteredConversations(any(), eq(20), eq(0));

        // Negative size clamped to 20
        mockMvc.perform(get("/admin/conversations").param("size", "-10"))
            .andExpect(status().isOk());

        // Size > 100 clamped to 20
        mockMvc.perform(get("/admin/conversations").param("size", "150"))
            .andExpect(status().isOk());
    }

    @Test
    void testPagination_ValidPageAndSize() throws Exception {
        when(chatAdminQueryRepository.countFilteredConversations(any(ConversationFilter.class)))
            .thenReturn(100L);
        when(chatAdminQueryRepository.listFilteredConversations(any(ConversationFilter.class),
            anyInt(), anyInt())).thenReturn(List.of());
        when(chatAdminQueryRepository.listConversationUserOptions(any())).thenReturn(List.of());

        mockMvc.perform(get("/admin/conversations").param("page", "2").param("size", "30"))
            .andExpect(status().isOk()).andExpect(model().attribute("currentPage", 2))
            .andExpect(model().attribute("totalPages", 4)); // ceil(100 / 30) = 4
        verify(chatAdminQueryRepository).listFilteredConversations(any(), eq(30), eq(60));
    }
}
