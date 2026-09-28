package de.palsoftware.yvoke.chat.web.admin;

import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository;
import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository.AdminConversation;
import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository.ConversationFilter;
import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository.ConversationUserOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/admin")
public class ConversationAdminController {

    private static final Logger log = LoggerFactory.getLogger(ConversationAdminController.class);

    private final ChatAdminQueryRepository chatAdminQueryRepository;

    public ConversationAdminController(ChatAdminQueryRepository chatAdminQueryRepository) {
        this.chatAdminQueryRepository = chatAdminQueryRepository;
    }

    @GetMapping("/conversations")
    public String listConversations(
        @RequestParam(name = "userIds", required = false) List<String> rawUserIds,
        @RequestParam(name = "timeRange", defaultValue = "all") String timeRange,
        @RequestParam(name = "fromDate", required = false) String rawFromDate,
        @RequestParam(name = "toDate", required = false) String rawToDate,
        @RequestParam(name = "feedback", defaultValue = "all") String feedback,
        @RequestParam(name = "page", defaultValue = "0") int page,
        @RequestParam(name = "size", defaultValue = "20") int size, Model model) {

        log.info("ConversationAdminController: Accessing Conversations view");

        page = Math.max(0, page);
        if (size <= 0 || size > 100) {
            size = 20;
        }

        LocalDate parsedFromDate = parseDate(rawFromDate);
        LocalDate parsedToDate = parseDate(rawToDate);

        ConversationFilter filter =
            ConversationFilter.of(rawUserIds, timeRange, parsedFromDate, parsedToDate, feedback);

        List<ConversationUserOption> userOptions =
            chatAdminQueryRepository.listConversationUserOptions(filter.userIds());
        long totalCount = chatAdminQueryRepository.countFilteredConversations(filter);
        int totalPages = (int) Math.ceil((double) totalCount / size);
        List<AdminConversation> conversations =
            chatAdminQueryRepository.listFilteredConversations(filter, size, page * size);

        Set<String> selectedUserIds = new LinkedHashSet<>();
        if (filter.includeAnonymous()) {
            selectedUserIds.add("anonymous");
        }
        for (UUID uid : filter.userIds()) {
            selectedUserIds.add(uid.toString());
        }

        model.addAttribute("conversations", conversations);
        model.addAttribute("userOptions", userOptions);
        model.addAttribute("selectedUserIds", selectedUserIds);
        model.addAttribute("selectedTimeRange", filter.timeFilter().preset());
        model.addAttribute("fromDate",
            parsedFromDate != null ? parsedFromDate.format(DateTimeFormatter.ISO_LOCAL_DATE) : "");
        model.addAttribute("toDate",
            parsedToDate != null ? parsedToDate.format(DateTimeFormatter.ISO_LOCAL_DATE) : "");
        model.addAttribute("selectedFeedback", filter.feedbackFilter().name().toLowerCase());
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("totalCount", totalCount);
        model.addAttribute("activeTab", "conversations");

        return "admin/conversations";
    }

    private LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim(), DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
