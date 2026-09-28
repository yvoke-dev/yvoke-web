package de.palsoftware.yvoke.chat.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository;
import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository.AdminConversation;
import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository.ConversationFilter;
import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository.ConversationOverviewStats;
import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository.ConversationUserOption;
import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository.UserConversationStats;
import de.palsoftware.yvoke.chat.core.service.ConversationAdminService.ConversationAdminView;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConversationAdminServiceTest {

    @Mock
    private ChatAdminQueryRepository chatAdminQueryRepository;

    private ConversationAdminService service;

    @BeforeEach
    void setUp() {
        service = new ConversationAdminService(chatAdminQueryRepository);
    }

    @Test
    void testGetConversationAdminView_success() {
        UUID userId = UUID.randomUUID();
        UUID convId = UUID.randomUUID();
        AdminConversation conv = new AdminConversation(convId, userId, "Alice", "alice@example.com",
            "Title", "web", OffsetDateTime.now(), OffsetDateTime.now(), 1, 0);
        ConversationUserOption userOption =
            new ConversationUserOption(userId, "Alice", "alice@example.com");
        UserConversationStats userStats =
            new UserConversationStats(userId, "Alice", "alice@example.com", 1L, 1L, 0L);
        ConversationOverviewStats stats =
            new ConversationOverviewStats(1L, 1L, 0L, 1L, List.of(userStats));

        ConversationFilter filter = ConversationFilter.empty();
        when(chatAdminQueryRepository.getConversationStats(filter)).thenReturn(stats);
        when(chatAdminQueryRepository.listFilteredConversations(eq(filter), eq(20), eq(0L)))
            .thenReturn(List.of(conv));
        when(chatAdminQueryRepository.listConversationUserOptions(eq(Set.of())))
            .thenReturn(List.of(userOption));

        ConversationAdminView view = service.getConversationAdminView(filter, 0, 20);

        assertThat(view).isNotNull();
        assertThat(view.conversations()).containsExactly(conv);
        assertThat(view.userOptions()).containsExactly(userOption);
        assertThat(view.stats()).isEqualTo(stats);
        assertThat(view.currentPage()).isEqualTo(0);
        assertThat(view.totalPages()).isEqualTo(1);
        assertThat(view.totalCount()).isEqualTo(1L);
        assertThat(view.pageSize()).isEqualTo(20);

        verify(chatAdminQueryRepository).getConversationStats(filter);
        verify(chatAdminQueryRepository).listFilteredConversations(filter, 20, 0L);
        verify(chatAdminQueryRepository).listConversationUserOptions(filter.userIds());
    }

    @Test
    void testGetConversationAdminView_clampingAndZeroItems() {
        ConversationFilter filter = ConversationFilter.empty();
        ConversationOverviewStats emptyStats =
            new ConversationOverviewStats(0L, 0L, 0L, 0L, List.of());
        when(chatAdminQueryRepository.getConversationStats(filter)).thenReturn(emptyStats);
        when(chatAdminQueryRepository.listFilteredConversations(eq(filter), eq(20), eq(0L)))
            .thenReturn(List.of());
        when(chatAdminQueryRepository.listConversationUserOptions(any())).thenReturn(List.of());

        // Negative page, extreme size
        ConversationAdminView view = service.getConversationAdminView(filter, -5, 999);

        assertThat(view.currentPage()).isEqualTo(0);
        assertThat(view.pageSize()).isEqualTo(20);
        assertThat(view.totalPages()).isEqualTo(1);
        assertThat(view.totalCount()).isEqualTo(0L);
        assertThat(view.conversations()).isEmpty();
    }
}
