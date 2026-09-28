package de.palsoftware.yvoke.chat.core.service;

import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository;
import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository.AdminConversation;
import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository.ConversationFilter;
import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository.ConversationOverviewStats;
import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository.ConversationUserOption;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service orchestrating conversation administration views under a single read-only transaction.
 * Enforces layering (Controllers → Services → Repositories) and guarantees a consistent snapshot
 * across statistics, conversation list, and user filter options.
 */
@Service
@Transactional(readOnly = true)
public class ConversationAdminService {

    private final ChatAdminQueryRepository chatAdminQueryRepository;

    public ConversationAdminService(ChatAdminQueryRepository chatAdminQueryRepository) {
        this.chatAdminQueryRepository = chatAdminQueryRepository;
    }

    public record ConversationAdminView(List<AdminConversation> conversations,
        List<ConversationUserOption> userOptions, ConversationOverviewStats stats, int currentPage,
        int totalPages, long totalCount, int pageSize) {}

    public ConversationAdminView getConversationAdminView(ConversationFilter filter, int page,
        int size) {
        int clampedSize = (size <= 0 || size > 100) ? 20 : size;
        ConversationOverviewStats stats = chatAdminQueryRepository.getConversationStats(filter);
        long totalCount = stats.totalConversations();
        int totalPages = totalCount == 0 ? 1 : (int) Math.ceil((double) totalCount / clampedSize);
        int clampedPage = Math.max(0, Math.min(page, Math.max(0, totalPages - 1)));
        long offset = (long) clampedPage * clampedSize;

        List<AdminConversation> conversations =
            chatAdminQueryRepository.listFilteredConversations(filter, clampedSize, offset);
        List<ConversationUserOption> userOptions =
            chatAdminQueryRepository.listConversationUserOptions(filter.userIds());

        return new ConversationAdminView(conversations, userOptions, stats, clampedPage, totalPages,
            totalCount, clampedSize);
    }
}
