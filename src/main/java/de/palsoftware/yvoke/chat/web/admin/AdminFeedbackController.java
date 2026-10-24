package de.palsoftware.yvoke.chat.web.admin;

import de.palsoftware.yvoke.chat.core.repository.ChatAdminQueryRepository;
import de.palsoftware.yvoke.shared.security.AreaAccessContext;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

@Controller
@RequestMapping("/admin")
public class AdminFeedbackController {

    private final ChatAdminQueryRepository adminQueryRepository;

    public AdminFeedbackController(ChatAdminQueryRepository adminQueryRepository) {
        this.adminQueryRepository = adminQueryRepository;
    }

    @GetMapping("/feedback")
    public String feedbackDashboard(@RequestParam(required = false) String rating,
        @RequestParam(required = false) Boolean reviewed,
        @RequestParam(defaultValue = "all") String timeRange,
        @RequestParam(defaultValue = "newest") String sort,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
        Model model) {

        Set<String> areas = AreaAccessContext.allowedAdminAreasLower().orElse(null);
        long positiveCount = adminQueryRepository.countFeedbackByRating(1, areas);
        long negativeCount = adminQueryRepository.countFeedbackByRating(-1, areas);
        long totalCount = positiveCount + negativeCount;
        double positiveRatio = totalCount > 0 ? (positiveCount * 100.0) / totalCount : 0.0;

        long filteredCount =
            adminQueryRepository.countFilteredFeedback(rating, reviewed, timeRange, areas);
        int totalPages = (int) Math.ceil((double) filteredCount / size);
        List<ChatAdminQueryRepository.FeedbackComment> feedbackList = adminQueryRepository
            .listFeedback(rating, reviewed, timeRange, sort, size, page * size, areas);

        model.addAttribute("positiveCount", positiveCount);
        model.addAttribute("negativeCount", negativeCount);
        model.addAttribute("totalCount", totalCount);
        model.addAttribute("positiveRatio", positiveRatio);

        model.addAttribute("feedbackList", feedbackList);
        model.addAttribute("filteredCount", filteredCount);
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", totalPages);

        model.addAttribute("selectedRating", rating);
        model.addAttribute("selectedReviewed", reviewed);
        model.addAttribute("selectedTimeRange", timeRange);
        model.addAttribute("selectedSort", sort);

        return "admin/feedback";
    }

    @PostMapping("/feedback/{id}/toggle-reviewed")
    public String toggleReviewed(@PathVariable UUID id,
        @RequestParam(value = "reviewed", required = false) String reviewedParam, Model model) {
        Optional<String> area = adminQueryRepository.findFeedbackArea(id);
        if (!AreaAccessContext.canAdministerArea(area.orElse(null))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Feedback not found: " + id);
        }
        boolean reviewed =
            "true".equalsIgnoreCase(reviewedParam) || "on".equalsIgnoreCase(reviewedParam);
        adminQueryRepository.setFeedbackReviewed(id, reviewed);

        model.addAttribute("feedbackId", id.toString());
        model.addAttribute("reviewed", reviewed);
        return "admin/feedback :: reviewed-cell";
    }

    @PostMapping("/feedback/{id}/notes")
    public String updateNotes(@PathVariable UUID id,
        @RequestParam(value = "notes", required = false) String notes, Model model) {
        Optional<String> area = adminQueryRepository.findFeedbackArea(id);
        if (!AreaAccessContext.canAdministerArea(area.orElse(null))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Feedback not found: " + id);
        }
        adminQueryRepository.setFeedbackNotes(id, notes);

        model.addAttribute("feedbackId", id.toString());
        model.addAttribute("notes", notes);
        return "admin/feedback :: notes-cell";
    }
}
