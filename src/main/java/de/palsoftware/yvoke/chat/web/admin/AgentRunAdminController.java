package de.palsoftware.yvoke.chat.web.admin;

import de.palsoftware.yvoke.chat.orchestration.AgentRunAdminViewService;
import de.palsoftware.yvoke.chat.orchestration.AgentRunAdminViews.RunDetailPage;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/** Read-only viewer for multi-agent orchestration runs (agent_runs + agent_steps). */
@Controller
@RequestMapping("/admin")
public class AgentRunAdminController {

    private static final Logger log = LoggerFactory.getLogger(AgentRunAdminController.class);

    private final AgentRunAdminViewService agentRunAdminViewService;

    public AgentRunAdminController(AgentRunAdminViewService agentRunAdminViewService) {
        this.agentRunAdminViewService = agentRunAdminViewService;
    }

    @GetMapping("/agent-runs")
    public String listAgentRuns(@RequestParam(defaultValue = "100") int limit, Model model) {
        log.info("AgentRunAdminController: listing recent agent runs");
        model.addAttribute("runs", agentRunAdminViewService.recentRuns(limit));
        model.addAttribute("activeTab", "agent-runs");
        return "admin/agent-runs";
    }

    @GetMapping("/agent-runs/{id}")
    public String viewAgentRun(@PathVariable UUID id, Model model) {
        RunDetailPage page = agentRunAdminViewService.runDetail(id).orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent run not found: " + id));
        model.addAttribute("run", page.run());
        model.addAttribute("steps", page.steps());
        model.addAttribute("activeTab", "agent-runs");
        return "admin/agent-run-detail";
    }
}
