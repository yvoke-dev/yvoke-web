package de.palsoftware.yvoke.shared.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

import de.palsoftware.yvoke.shared.audit.repository.AuditLogRepository;
import de.palsoftware.yvoke.shared.jobengine.model.IngestionJob;
import de.palsoftware.yvoke.shared.jobengine.model.QueuedKindSummary;
import de.palsoftware.yvoke.shared.jobengine.ItTestJobHandler;
import de.palsoftware.yvoke.shared.jobengine.repository.JobRepository;
import de.palsoftware.yvoke.shared.jobengine.service.JobService;
import de.palsoftware.yvoke.shared.user.service.UserService;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;
import java.util.stream.IntStream;

public class AdminControllerTest {

    private JobRepository jobRepository;
    private JobService jobService;
    private AuditLogRepository auditLogRepository;
    private UserService userService;

    private AdminController adminController;
    private Model model;

    @BeforeEach
    public void setUp() {
        jobRepository = mock(JobRepository.class);
        jobService = mock(JobService.class);
        auditLogRepository = mock(AuditLogRepository.class);
        userService = mock(UserService.class);
        when(userService.getCurrentUser()).thenReturn(Optional.empty());

        adminController =
            new AdminController(jobRepository, jobService, auditLogRepository, userService);
        model = new ConcurrentModel();
    }

    @Test
    public void testIndexRedirects() {
        assertThat(adminController.index()).isEqualTo("redirect:/admin/documents");
    }

    @Test
    public void testListJobs() {
        when(jobRepository.listJobs(anyInt(), anyInt())).thenReturn(Collections.emptyList());
        when(jobRepository.countJobs()).thenReturn(5L);

        String view = adminController.listJobs(0, 20, model);

        assertThat(view).isEqualTo("admin/jobs");
        assertThat(model.getAttribute("jobs")).isNotNull();
        assertThat(model.getAttribute("totalCount")).isEqualTo(5L);
    }

    @Test
    public void testGetJobDetails() {
        UUID id = UUID.randomUUID();
        IngestionJob mockJob = mock(IngestionJob.class);
        when(mockJob.kind()).thenReturn(ItTestJobHandler.KIND);
        when(jobRepository.findById(id)).thenReturn(Optional.of(mockJob));

        String view = adminController.getJobDetails(id, model);

        assertThat(view).isEqualTo("admin/job-detail");
        assertThat(model.getAttribute("job")).isEqualTo(mockJob);
    }

    /**
     * The jobs page has to say WHAT is queued before an operator can decide to cancel it — and it
     * must come from one aggregate query, not a count per job row.
     */
    @Test
    public void testListJobsExposesQueuedWorkPerKind() {
        when(jobRepository.listJobs(anyInt(), anyInt())).thenReturn(Collections.emptyList());
        when(jobRepository.countJobs()).thenReturn(0L);
        when(jobRepository.listQueuedKinds())
            .thenReturn(List.of(new QueuedKindSummary("confluence-page-import:icc-wiki", 616L)));

        adminController.listJobs(0, 20, model);

        assertThat(model.getAttribute("queuedKinds"))
            .isEqualTo(List.of(new QueuedKindSummary("confluence-page-import:icc-wiki", 616L)));
        verify(jobRepository).listQueuedKinds();
    }

    @Test
    public void testCancelQueuedJobsReportsTheCountAndAuditsIt() {
        when(jobRepository.cancelQueued("confluence-page-import:icc-wiki"))
            .thenReturn(IntStream.range(0, 616).mapToObj(i -> UUID.randomUUID()).toList());
        RedirectAttributes redirect = new RedirectAttributesModelMap();

        String view = adminController.cancelQueuedJobs("confluence-page-import:icc-wiki", redirect);

        assertThat(view).isEqualTo("redirect:/admin/jobs");
        assertThat(redirect.getFlashAttributes().get("success").toString()).contains("616");
        verify(auditLogRepository).log("anonymous_admin", "CANCEL_QUEUED_JOBS",
            "confluence-page-import:icc-wiki", Map.of("cancelled", 616));
    }

    /**
     * Every path that changes job rows behind the engine's back MUST publish a snapshot per row, or
     * open job pages keep streaming a state that no longer exists.
     *
     * <p>
     * The single-job Stop goes through {@code JobService.stopJob}-and-publish, so it is covered by
     * {@code testStopJobPublishesTheNewStatusAndAudits}. This endpoint does not: it calls
     * {@code jobRepository.cancelQueued(kind)}, one bulk UPDATE that flips hundreds of rows to
     * {@code cancelled} without the engine ever seeing them. A job-detail page open on any of those
     * rows subscribed to {@link de.palsoftware.yvoke.shared.jobengine.service.JobProgressBroker}
     * and is waiting for a terminal frame that now will never be produced — the job is cancelled,
     * so no worker will ever run it and emit one. The page therefore shows "Queued" with a live
     * Stop button on an already-cancelled job, indefinitely, and the operator's next click reports
     * "Job could not be stopped" for no visible reason.
     *
     * <p>
     * The broker has NO replay buffer ({@code publish} fans out to currently-subscribed emitters
     * only), so the snapshot has to be published here, at cancel time — there is no later moment
     * that can repair it. And it has to be per id: publishing once, or for the first id, silently
     * leaves every other open page stale, which is exactly the shape a refactor to "publish the
     * summary" would take.
     *
     * <p>
     * The existing cancel tests assert only the flash message and the audit row, both of which are
     * derived from {@code cancelledIds.size()} — so they pass whether the ids are used for anything
     * else or thrown away.
     */
    @Test
    public void bulkCancellingQueuedWorkPublishesASnapshotForEveryCancelledJob() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        when(jobRepository.cancelQueued("confluence-page-import:icc-wiki"))
            .thenReturn(List.of(first, second, third));
        RedirectAttributes redirect = new RedirectAttributesModelMap();

        adminController.cancelQueuedJobs("confluence-page-import:icc-wiki", redirect);

        verify(jobService).publishSnapshot(first);
        verify(jobService).publishSnapshot(second);
        verify(jobService).publishSnapshot(third);
        // Exactly those three and nothing else: a page left un-notified is the whole bug.
        verifyNoMoreInteractions(jobService);
    }

    @Test
    public void testCancelQueuedJobsSaysSoWhenNothingWasQueued() {
        when(jobRepository.cancelQueued("kg-extract")).thenReturn(List.of());
        RedirectAttributes redirect = new RedirectAttributesModelMap();

        adminController.cancelQueuedJobs("kg-extract", redirect);

        assertThat(redirect.getFlashAttributes()).containsKey("error");
        assertThat(redirect.getFlashAttributes()).doesNotContainKey("success");
    }

    @Test
    public void testCancelQueuedJobsRejectsABlankKindWithoutAuditing() {
        // A blank kind would otherwise mean "every queued job of every kind" — never what the
        // button meant. The repository refuses it; the page must show that, not a 500.
        when(jobRepository.cancelQueued(" ")).thenThrow(new IllegalArgumentException("blank"));
        RedirectAttributes redirect = new RedirectAttributesModelMap();

        String view = adminController.cancelQueuedJobs(" ", redirect);

        assertThat(view).isEqualTo("redirect:/admin/jobs");
        assertThat(redirect.getFlashAttributes()).containsKey("error");
        verifyNoInteractions(auditLogRepository);
    }

    @Test
    public void testStopJobPublishesTheNewStatusAndAudits() {
        UUID id = UUID.randomUUID();
        when(jobRepository.stopJob(id)).thenReturn(1);
        RedirectAttributes redirect = new RedirectAttributesModelMap();

        String view = adminController.stopJob(id, redirect);

        assertThat(view).isEqualTo("redirect:/admin/jobs/" + id);
        assertThat(redirect.getFlashAttributes()).containsKey("success");
        verify(jobService).publishSnapshot(id);
        verify(auditLogRepository).log("anonymous_admin", "STOP_JOB", id.toString(), Map.of());
    }

    @Test
    public void testStopJobOnATerminalJobIsReportedAsAnError() {
        UUID id = UUID.randomUUID();
        when(jobRepository.stopJob(id)).thenReturn(0);
        RedirectAttributes redirect = new RedirectAttributesModelMap();

        adminController.stopJob(id, redirect);

        assertThat(redirect.getFlashAttributes()).containsKey("error");
        verify(jobService, never()).publishSnapshot(id);
        verifyNoInteractions(auditLogRepository);
    }

    @Test
    public void testListAuditLogs() {
        when(auditLogRepository.listLogs(anyInt(), anyInt())).thenReturn(Collections.emptyList());
        when(auditLogRepository.countLogs()).thenReturn(10L);

        String view = adminController.listAuditLogs(0, 20, model);

        assertThat(view).isEqualTo("admin/audit");
        assertThat(model.getAttribute("logs")).isNotNull();
        assertThat(model.getAttribute("totalCount")).isEqualTo(10L);
    }
}
