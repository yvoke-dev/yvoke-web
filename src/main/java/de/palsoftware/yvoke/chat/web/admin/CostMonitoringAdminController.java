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
import java.util.Collections;
import java.util.List;

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
        @RequestParam(required = false) List<String> selectedModels,
        @RequestParam(required = false) List<UUID> selectedUserIds,
        @RequestParam(required = false) List<String> selectedSources,
        @RequestParam(required = false) List<String> selectedPlaybooks,
        @RequestParam(required = false, defaultValue = "CONVERSATION") String viewLevel,
        Model model) {
        log.info("Accessing Cost Monitoring view");
        model.addAttribute("activeTab", "costs");

        LocalDate[] dates = resolveDatePreset(timePreset, startDate, endDate);
        startDate = dates[0];
        endDate = dates[1];

        if (!"RAW".equalsIgnoreCase(viewLevel) && !"CALL".equalsIgnoreCase(viewLevel)) {
            selectedSources = Collections.emptyList();
        }
        if (!"MESSAGE".equalsIgnoreCase(viewLevel)) {
            selectedPlaybooks = Collections.emptyList();
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
            selectedModels != null ? selectedModels : Collections.emptyList());
        model.addAttribute("selectedUserIds",
            selectedUserIds != null ? selectedUserIds : Collections.emptyList());
        model.addAttribute("selectedSources",
            selectedSources != null ? selectedSources : Collections.emptyList());
        model.addAttribute("selectedPlaybooks",
            selectedPlaybooks != null ? selectedPlaybooks : Collections.emptyList());
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
        @RequestParam(required = false) List<String> selectedModels,
        @RequestParam(required = false) List<UUID> selectedUserIds,
        @RequestParam(required = false) List<String> selectedSources,
        @RequestParam(required = false) List<String> selectedPlaybooks,
        @RequestParam(required = false, defaultValue = "CONVERSATION") String viewLevel,
        @RequestParam(required = false) String cursor, Model model) {

        LocalDate[] dates = resolveDatePreset(timePreset, startDate, endDate);
        startDate = dates[0];
        endDate = dates[1];

        if (!"RAW".equalsIgnoreCase(viewLevel) && !"CALL".equalsIgnoreCase(viewLevel)) {
            selectedSources = Collections.emptyList();
        }
        if (!"MESSAGE".equalsIgnoreCase(viewLevel)) {
            selectedPlaybooks = Collections.emptyList();
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
            selectedModels != null ? selectedModels : Collections.emptyList());
        model.addAttribute("selectedUserIds",
            selectedUserIds != null ? selectedUserIds : Collections.emptyList());
        model.addAttribute("selectedSources",
            selectedSources != null ? selectedSources : Collections.emptyList());
        model.addAttribute("selectedPlaybooks",
            selectedPlaybooks != null ? selectedPlaybooks : Collections.emptyList());
        model.addAttribute("viewLevel", viewLevel);
        model.addAttribute("subTab", "explorer");
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
