package de.palsoftware.yvoke.chat.web.admin;

import de.palsoftware.yvoke.chat.core.service.CostCalculationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

@Controller
@RequestMapping("/admin/costs")
public class CostMonitoringAdminController {

    private static final Logger log = LoggerFactory.getLogger(CostMonitoringAdminController.class);

    private final CostCalculationService costCalculationService;

    public CostMonitoringAdminController(CostCalculationService costCalculationService) {
        this.costCalculationService = costCalculationService;
    }

    @GetMapping
    public String getCostMonitoringPage(
        @RequestParam(required = false, defaultValue = "this_month") String timePreset,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
        @RequestParam(required = false) java.util.List<String> selectedModels,
        @RequestParam(required = false) java.util.List<UUID> selectedUserIds,
        @RequestParam(required = false) java.util.List<String> selectedSources,
        @RequestParam(required = false) java.util.List<String> selectedPlaybooks,
        @RequestParam(required = false, defaultValue = "CONVERSATION") String viewLevel,
        Model model) {
        log.info("Accessing Cost Monitoring view");
        model.addAttribute("activeTab", "costs");

        LocalDate[] dates = resolveDatePreset(timePreset, startDate, endDate);
        startDate = dates[0];
        endDate = dates[1];

        if (!"RAW".equalsIgnoreCase(viewLevel) && !"CALL".equalsIgnoreCase(viewLevel)) {
            selectedSources = java.util.Collections.emptyList();
        }
        if (!"MESSAGE".equalsIgnoreCase(viewLevel)) {
            selectedPlaybooks = java.util.Collections.emptyList();
        }

        model.addAttribute("explorerReport",
            costCalculationService.getFilteredExplorerReport(viewLevel, startDate, endDate,
                selectedModels, selectedUserIds, selectedSources, selectedPlaybooks));
        model.addAttribute("availableModels", costCalculationService.getAvailableModels());
        model.addAttribute("availableUsers", costCalculationService.getAvailableUsers());
        model.addAttribute("availableSources", costCalculationService.getAvailableSources());
        model.addAttribute("availablePlaybooks", costCalculationService.getAvailablePlaybooks());
        model.addAttribute("timePreset", timePreset);
        model.addAttribute("startDate", startDate);
        model.addAttribute("endDate", endDate);
        model.addAttribute("selectedModels",
            selectedModels != null ? selectedModels : java.util.Collections.emptyList());
        model.addAttribute("selectedUserIds",
            selectedUserIds != null ? selectedUserIds : java.util.Collections.emptyList());
        model.addAttribute("selectedSources",
            selectedSources != null ? selectedSources : java.util.Collections.emptyList());
        model.addAttribute("selectedPlaybooks",
            selectedPlaybooks != null ? selectedPlaybooks : java.util.Collections.emptyList());
        model.addAttribute("viewLevel", viewLevel);
        model.addAttribute("subTab", "explorer");
        return "chat/admin/cost-monitoring";
    }

    @GetMapping("/fragment/explorer")
    public String getExplorerFragment(
        @RequestParam(required = false, defaultValue = "this_month") String timePreset,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
        @RequestParam(required = false) java.util.List<String> selectedModels,
        @RequestParam(required = false) java.util.List<UUID> selectedUserIds,
        @RequestParam(required = false) java.util.List<String> selectedSources,
        @RequestParam(required = false) java.util.List<String> selectedPlaybooks,
        @RequestParam(required = false, defaultValue = "CONVERSATION") String viewLevel,
        @RequestParam(required = false) String cursor, Model model) {

        LocalDate[] dates = resolveDatePreset(timePreset, startDate, endDate);
        startDate = dates[0];
        endDate = dates[1];

        if (!"RAW".equalsIgnoreCase(viewLevel) && !"CALL".equalsIgnoreCase(viewLevel)) {
            selectedSources = java.util.Collections.emptyList();
        }
        if (!"MESSAGE".equalsIgnoreCase(viewLevel)) {
            selectedPlaybooks = java.util.Collections.emptyList();
        }

        model.addAttribute("explorerReport",
            costCalculationService.getFilteredExplorerReport(viewLevel, startDate, endDate,
                selectedModels, selectedUserIds, selectedSources, selectedPlaybooks, cursor));
        model.addAttribute("availableModels", costCalculationService.getAvailableModels());
        model.addAttribute("availableUsers", costCalculationService.getAvailableUsers());
        model.addAttribute("availableSources", costCalculationService.getAvailableSources());
        model.addAttribute("availablePlaybooks", costCalculationService.getAvailablePlaybooks());
        model.addAttribute("timePreset", timePreset);
        model.addAttribute("startDate", startDate);
        model.addAttribute("endDate", endDate);
        model.addAttribute("selectedModels",
            selectedModels != null ? selectedModels : java.util.Collections.emptyList());
        model.addAttribute("selectedUserIds",
            selectedUserIds != null ? selectedUserIds : java.util.Collections.emptyList());
        model.addAttribute("selectedSources",
            selectedSources != null ? selectedSources : java.util.Collections.emptyList());
        model.addAttribute("selectedPlaybooks",
            selectedPlaybooks != null ? selectedPlaybooks : java.util.Collections.emptyList());
        model.addAttribute("viewLevel", viewLevel);
        model.addAttribute("subTab", "explorer");
        return "chat/admin/cost-monitoring :: cost-fragment";
    }

    @GetMapping("/fragment/overview")
    public String getOverviewFragment(
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate, Model model) {
        // One pricing snapshot for all four overview reports (PRF-14) instead of four reads.
        CostCalculationService.OverviewReport overview =
            costCalculationService.getOverviewReport(startDate, endDate, 10);
        model.addAttribute("report", overview.report());
        model.addAttribute("topUsers", overview.topUsers());
        model.addAttribute("topConversations", overview.topConversations());
        model.addAttribute("masProfilesList", overview.masProfiles());
        model.addAttribute("startDate", startDate);
        model.addAttribute("endDate", endDate);
        model.addAttribute("subTab", "overview");
        return "chat/admin/cost-monitoring :: cost-fragment";
    }

    @GetMapping("/fragment/pricing")
    public String getPricingFragment(Model model) {
        model.addAttribute("prices", costCalculationService.getAllModelPricing());
        model.addAttribute("subTab", "pricing");
        return "chat/admin/cost-monitoring :: cost-fragment";
    }

    @RequestMapping(value = "/pricing/save",
        method = org.springframework.web.bind.annotation.RequestMethod.POST)
    public String savePricing(@RequestParam String modelName,
        @RequestParam java.math.BigDecimal prompt, @RequestParam java.math.BigDecimal completion,
        @RequestParam java.math.BigDecimal cached, @RequestParam java.math.BigDecimal thought,
        Model model) {
        costCalculationService.updateModelPricing(modelName, prompt, completion, cached, thought);
        return getPricingFragment(model);
    }

    @GetMapping("/fragment/user")
    public String getUserFragment(@RequestParam(required = false) UUID userId,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate, Model model) {
        if (userId != null) {
            model.addAttribute("report",
                costCalculationService.calculateCostByUser(userId, startDate, endDate));
        }
        model.addAttribute("availableUsers", costCalculationService.getAvailableUsers());
        model.addAttribute("userId", userId);
        model.addAttribute("startDate", startDate);
        model.addAttribute("endDate", endDate);
        model.addAttribute("subTab", "users");
        return "chat/admin/cost-monitoring :: cost-fragment";
    }

    @GetMapping("/fragment/conversation")
    public String getConversationFragment(@RequestParam(required = false) UUID conversationId,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate, Model model) {
        if (conversationId != null) {
            model.addAttribute("report", costCalculationService
                .calculateCostByConversation(conversationId, startDate, endDate));
            model.addAttribute("messageDetails",
                costCalculationService.getMessagesByConversation(conversationId));
        }
        model.addAttribute("availableConversations",
            costCalculationService.getAvailableConversations());
        model.addAttribute("conversationId", conversationId);
        model.addAttribute("startDate", startDate);
        model.addAttribute("endDate", endDate);
        model.addAttribute("subTab", "conversations");
        return "chat/admin/cost-monitoring :: cost-fragment";
    }

    @GetMapping("/fragment/mas-profile")
    public String getMasProfileFragment(@RequestParam(required = false) String profileName,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate, Model model) {
        if (profileName != null && !profileName.isBlank()) {
            model.addAttribute("report",
                costCalculationService.calculateCostByMasProfile(profileName, startDate, endDate));
        }
        model.addAttribute("availableMasProfiles",
            costCalculationService.getAvailableMasProfiles());
        model.addAttribute("profileName", profileName);
        model.addAttribute("startDate", startDate);
        model.addAttribute("endDate", endDate);
        model.addAttribute("subTab", "mas-profiles");
        return "chat/admin/cost-monitoring :: cost-fragment";
    }

    private LocalDate[] resolveDatePreset(String timePreset, LocalDate startDate,
        LocalDate endDate) {
        if ("this_month".equalsIgnoreCase(timePreset)) {
            LocalDate now = LocalDate.now();
            return new LocalDate[] {now.withDayOfMonth(1), now.withDayOfMonth(now.lengthOfMonth())};
        } else if ("last_month".equalsIgnoreCase(timePreset)) {
            LocalDate lastMonth = LocalDate.now().minusMonths(1);
            return new LocalDate[] {lastMonth.withDayOfMonth(1),
                lastMonth.withDayOfMonth(lastMonth.lengthOfMonth())};
        } else if ("all".equalsIgnoreCase(timePreset)) {
            return new LocalDate[] {null, null};
        }
        return new LocalDate[] {startDate, endDate};
    }
}
