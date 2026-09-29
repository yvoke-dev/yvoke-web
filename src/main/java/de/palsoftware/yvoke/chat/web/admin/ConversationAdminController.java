package de.palsoftware.yvoke.chat.web.admin;

import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository.ConversationFilter;
import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository.TimeFilter;
import de.palsoftware.yvoke.chat.core.service.ConversationAdminService;
import de.palsoftware.yvoke.chat.core.service.ConversationAdminService.ConversationAdminView;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

@Controller
@RequestMapping("/admin")
public class ConversationAdminController {

    private static final Logger log = LoggerFactory.getLogger(ConversationAdminController.class);

    private final ConversationAdminService conversationAdminService;
    private final Clock clock;

    @Autowired
    public ConversationAdminController(ConversationAdminService conversationAdminService) {
        this(conversationAdminService, Clock.systemUTC());
    }

    ConversationAdminController(ConversationAdminService conversationAdminService, Clock clock) {
        this.conversationAdminService = conversationAdminService;
        this.clock = clock;
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

        ConversationFilter filter;
        try {
            filter = ConversationAdminFilterParser.parse(rawUserIds, timeRange, rawFromDate,
                rawToDate, feedback, clock);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }

        ConversationAdminView view =
            conversationAdminService.getConversationAdminView(filter, page, size);

        List<String> selectedUserIds = new ArrayList<>();
        if (filter.includeAnonymous()) {
            selectedUserIds.add("anonymous");
        }
        for (UUID uid : filter.userIds()) {
            selectedUserIds.add(uid.toString());
        }

        model.addAttribute("conversations", view.conversations());
        model.addAttribute("userOptions", view.userOptions());
        model.addAttribute("selectedUserIds", selectedUserIds);
        model.addAttribute("selectedTimeRange", filter.timeFilter().preset());
        model.addAttribute("fromDate",
            filter.timeFilter().fromDate() != null
                ? filter.timeFilter().fromDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
                : "");
        model.addAttribute("toDate",
            filter.timeFilter().toDate() != null
                ? filter.timeFilter().toDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
                : "");
        model.addAttribute("selectedFeedback",
            filter.feedbackFilter().name().toLowerCase(Locale.ROOT));
        model.addAttribute("currentPage", view.currentPage());
        model.addAttribute("totalPages", view.totalPages());
        model.addAttribute("totalCount", view.totalCount());
        model.addAttribute("pageSize", view.pageSize());
        model.addAttribute("stats", view.stats());
        model.addAttribute("activeTab", "conversations");

        return "admin/conversations";
    }
}
