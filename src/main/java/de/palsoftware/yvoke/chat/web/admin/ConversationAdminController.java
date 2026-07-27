package de.palsoftware.yvoke.chat.web.admin;

import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository;
import java.util.List;
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
    public String listConversations(@RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size, Model model) {
        log.info("ConversationAdminController: Accessing Conversations view");
        long totalCount = chatAdminQueryRepository.countConversations();
        int totalPages = (int) Math.ceil((double) totalCount / size);
        List<ChatAdminQueryRepository.AdminConversation> conversations =
            chatAdminQueryRepository.listConversations(size, page * size);

        model.addAttribute("conversations", conversations);
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("totalCount", totalCount);
        model.addAttribute("activeTab", "conversations");

        return "admin/conversations";
    }
}
