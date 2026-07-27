package de.palsoftware.yvoke.web.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import de.palsoftware.yvoke.collection.core.repository.CollectionRepository;
import de.palsoftware.yvoke.shared.jobengine.model.EnqueueRequest;
import de.palsoftware.yvoke.shared.jobengine.model.JobCounts;
import de.palsoftware.yvoke.shared.jobengine.model.JobStep;
import de.palsoftware.yvoke.shared.jobengine.repository.JobRepository;
import de.palsoftware.yvoke.shared.jobengine.service.JobService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * J6 — the admin job-detail page updated by <em>live</em> SSE progress. The background worker is
 * disabled for all e2e contexts (in {@link AbstractE2E}), so the test itself drives the job row and
 * publishes snapshots through the genuine {@code JobProgressBroker → SseEmitter → EventSource → DOM}
 * path (no custom handler, no poll timing) — and crucially, no worker in any shared-DB context can
 * claim the queued job out from under it. Reuses the base e2e context (no distinct property combo).
 */
class AdminJobProgressE2EIT extends AbstractE2E {

  @Autowired private CollectionRepository collectionRepository;
  @Autowired private JobService jobService;
  @Autowired private JobRepository jobRepository;

  @Test
  void jobDetailPageReflectsLiveSseProgress() {
    String collection = "e2e-jobs-" + UUID.randomUUID();
    collectionRepository.create(collection, "e2e progress test");
    // kind "noop" has no handler — harmless because the worker is disabled.
    UUID jobId =
        jobService
            .enqueue(
                new EnqueueRequest(
                    "noop", "e2e://" + UUID.randomUUID(), List.of(), collection, Map.of()))
            .jobId();

    loginAs("admin");

    // The page opens an EventSource because the server-rendered status is still non-terminal.
    page.waitForRequest(
        r -> r.url().contains("/api/jobs/v1/") && r.url().endsWith("/progress"),
        () -> page.navigate(url("/admin/jobs/" + jobId)));

    assertThat(page.locator("#statusBadge")).containsText("Queued");
    assertThat(page.locator("#progressText")).hasText("0%");

    // Live intermediate tick; loop a few times to beat the subscribe micro-race.
    for (int i = 0; i < 5; i++) {
      jobRepository.updateProgress(jobId, JobStep.EMBED, 50);
      jobService.publishSnapshot(jobId); // -> broker.publish -> SSE -> DOM
      page.waitForTimeout(100);
    }
    assertThat(page.locator("#progressText")).hasText("50%");

    // Terminal event: the server-rendered "Queued" can only flip to "Completed" via the EventSource.
    // Republish a few times (like the intermediate tick) so a mid-reconnect EventSource still sees it.
    jobRepository.markCompleted(jobId, new JobCounts(1, 7, 0, 0, 0));
    for (int i = 0; i < 5; i++) {
      jobService.publishSnapshot(jobId);
      page.waitForTimeout(100);
    }

    assertThat(page.locator("#statusBadge")).containsText("Completed");
    assertThat(page.locator("#progressText")).hasText("100%");
    assertThat(page.locator("#jobCountsCard")).isVisible();
    assertThat(page.locator("#countChunks")).hasText("7"); // JobCounts(docs=1, chunks=7, ...)
  }
}
