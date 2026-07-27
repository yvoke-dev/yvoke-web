package de.palsoftware.yvoke.shared.web.admin;

import de.palsoftware.yvoke.shared.audit.repository.AuditLogRepository;
import de.palsoftware.yvoke.shared.jobengine.model.IngestionJob;
import de.palsoftware.yvoke.shared.jobengine.repository.JobRepository;
import de.palsoftware.yvoke.shared.jobengine.service.JobService;
import de.palsoftware.yvoke.shared.jobengine.model.JobStep;
import de.palsoftware.yvoke.shared.user.model.User;
import de.palsoftware.yvoke.shared.user.service.UserService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Admin landing + cross-cutting infrastructure admin pages (job engine, audit log). Domain-specific
 * admin pages live in each domain's {@code <domain>.web.admin} package.
 */

@Controller
@RequestMapping("/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final JobRepository jobRepository;
    private final JobService jobService;
    private final AuditLogRepository auditLogRepository;
    private final UserService userService;

    public AdminController(JobRepository jobRepository, JobService jobService,
        AuditLogRepository auditLogRepository, UserService userService) {
        this.jobRepository = jobRepository;
        this.jobService = jobService;
        this.auditLogRepository = auditLogRepository;
        this.userService = userService;
    }

    private String getCurrentAdminOid() {
        return userService.getCurrentUser().map(User::entraOid).orElse("anonymous_admin");
    }

    @GetMapping
    public String index() {
        return "redirect:/admin/documents";
    }

    @GetMapping("/jobs")
    public String listJobs(@RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size, Model model) {
        log.info("AdminController: Accessing Jobs view");
        List<IngestionJob> jobs = jobRepository.listJobs(size, page * size);
        long totalCount = jobRepository.countJobs();
        int totalPages = (int) Math.ceil((double) totalCount / size);

        model.addAttribute("jobs", jobs);
        model.addAttribute("queuedKinds", jobRepository.listQueuedKinds());
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("totalCount", totalCount);

        return "admin/jobs";
    }

    @GetMapping("/jobs/{id}")
    public String getJobDetails(@PathVariable UUID id, Model model) {
        IngestionJob job = jobRepository.findById(id).orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found: " + id));
        model.addAttribute("job", job);
        List<JobStep> steps = jobService.getStepsForKind(job.kind());
        model.addAttribute("steps", steps);
        model.addAttribute("stepDbValues", steps.stream().map(JobStep::dbValue).toList());
        return "admin/job-detail";
    }

    @PostMapping("/jobs/{id}/stop")
    public String stopJob(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
        int rows = jobRepository.stopJob(id);

        if (rows > 0) {
            jobService.publishSnapshot(id);
            auditLogRepository.log(getCurrentAdminOid(), "STOP_JOB", id.toString(), Map.of());
            redirectAttributes.addFlashAttribute("success", "Job cancelled.");
        } else {
            redirectAttributes.addFlashAttribute("error",
                "Job could not be stopped (it already finished, failed or was cancelled).");
        }
        return "redirect:/admin/jobs/" + id;
    }

    /**
     * Bulk-cancels every QUEUED job of one kind. A crawl that fanned out hundreds of page jobs and
     * was then repointed leaves a queue an operator otherwise has to wait out; running jobs are
     * left alone (stop those individually).
     *
     * <p>
     * {@code /admin/**} is already {@code hasRole("ADMIN")} in the filter chain, which is where
     * authorization for this endpoint lives.
     */
    @PostMapping("/jobs/cancel-queued")
    public String cancelQueuedJobs(@RequestParam String kind,
        RedirectAttributes redirectAttributes) {
        List<UUID> cancelledIds;
        try {
            cancelledIds = jobRepository.cancelQueued(kind);
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", "Choose a job kind to cancel.");
            return "redirect:/admin/jobs";
        }

        int cancelled = cancelledIds.size();

        // The single-job Stop goes through JobService, which publishes. This path updated rows
        // directly, so any open job page kept streaming "Queued" with a working Stop button on an
        // already-cancelled job. The broker has no replay buffer, so this only reaches pages that
        // are open right now — exactly the ones showing the stale state.
        cancelledIds.forEach(jobService::publishSnapshot);

        auditLogRepository.log(getCurrentAdminOid(), "CANCEL_QUEUED_JOBS", kind,
            Map.of("cancelled", cancelled));

        if (cancelled > 0) {
            redirectAttributes.addFlashAttribute("success",
                "Cancelled " + cancelled + " queued job(s) of kind '" + kind + "'.");
        } else {
            redirectAttributes.addFlashAttribute("error",
                "No queued jobs of kind '" + kind + "' to cancel.");
        }
        return "redirect:/admin/jobs";
    }

    @GetMapping("/audit")
    public String listAuditLogs(@RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size, Model model) {
        model.addAttribute("logs", auditLogRepository.listLogs(size, page * size));
        long totalCount = auditLogRepository.countLogs();
        int totalPages = (int) Math.ceil((double) totalCount / size);

        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("totalCount", totalCount);

        return "admin/audit";
    }
}
