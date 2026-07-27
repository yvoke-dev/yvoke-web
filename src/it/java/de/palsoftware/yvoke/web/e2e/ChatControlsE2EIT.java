package de.palsoftware.yvoke.web.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.microsoft.playwright.Page;
import de.palsoftware.yvoke.llm.core.model.LlmRequest;
import de.palsoftware.yvoke.rag.prompt.PlaybookService;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * J3 — chat controls: the streaming (SSE) transport and stop-generation. Reuses the base
 * {@link AbstractE2E} context (no extra properties/mock beans) to avoid minting a new Spring context.
 */
class ChatControlsE2EIT extends AbstractE2E {

  @Autowired private PlaybookService playbookService;

  /** New conversation with a selected playbook — the client blocks sending until one is chosen. */
  private void newChatWithPlaybook(String playbook) {
    playbookService.savePlaybook(playbook, "E2E Playbook", "desc", "Answer.", List.of(), false);
    loginAs("user");
    newConversation();
    selectPlaybookChip(playbook);
  }

  @Test
  void streamingTransportUsesSseAndRendersAnswer() {
    stubAssistantReply("Streamed answer via SSE.");
    newChatWithPlaybook("e2e-stream");

    // Flip to the SSE transport; streamingEnabled is set synchronously on this click.
    page.click("#mode-streaming-btn");
    assertThat(page.locator("#mode-streaming-btn.active")).isVisible();
    assertThat(page.locator("#mode-standard-btn.active")).hasCount(0);

    page.fill("#chat-input", "What is OIM?");

    // Transport proof: the SSE endpoint POST /chat/{id}/send is used (NOT /send-async;
    // "/send-async".endsWith("/send") is false, so the predicate is unambiguous).
    page.waitForResponse(
        r -> r.url().endsWith("/send") && "POST".equals(r.request().method()),
        () -> page.click("#send-stop-button"));

    assertThat(page.locator("#chat-messages")).containsText("Streamed answer via SSE.");
    assertThat(page.locator("#chat-messages")).containsText("What is OIM?");
  }

  @Test
  void stopButtonCancelsInFlightGeneration() throws Exception {
    // A blocking generateStream so there is a window to stop (stubAssistantReply returns instantly).
    CountDownLatch callStarted = new CountDownLatch(1);
    CountDownLatch cancellationHandled = new CountDownLatch(1);
    doAnswer(
            inv -> {
              callStarted.countDown();
              try {
                Thread.sleep(10_000);
              } catch (InterruptedException e) {
                cancellationHandled.countDown();
                Thread.currentThread().interrupt();
                throw new CancellationException("Chat generation cancelled");
              }
              return null;
            })
        .when(llmClient)
        .generateStream(any(LlmRequest.class), any());

    newChatWithPlaybook("e2e-stop");
    page.click("#mode-streaming-btn"); // SSE transport => the abort is immediate

    page.fill("#chat-input", "Take your time...");
    page.click("#send-stop-button"); // starts generation; the same button becomes Stop

    if (!callStarted.await(5, TimeUnit.SECONDS)) {
      throw new AssertionError("generation never started");
    }
    assertThat(page.locator("#send-stop-button.state-stop")).isVisible();

    // Same button, now Stop: aborts the fetch + POST /chat/{id}/stop (interrupts the worker thread).
    page.waitForResponse(
        r -> r.url().endsWith("/stop") && "POST".equals(r.request().method()),
        () -> page.click("#send-stop-button"));

    if (!cancellationHandled.await(5, TimeUnit.SECONDS)) {
      throw new AssertionError("generateStream was never interrupted");
    }
    // Client renders "[Generation stopped by user]"; server persists "*[...]*" — assert the substring.
    assertThat(page.locator("#chat-messages")).containsText("Generation stopped by user");
    assertThat(page.locator("#send-stop-button.state-stop")).hasCount(0);
  }

  /**
   * The reported bug, end to end. A generation that FAILED was captioned "[Generation stopped by
   * user]" underneath the system error, because the async poll fed the server-reported
   * {@code status === 'error'} into finalizeStream's cancellation flag.
   *
   * <p>This runs on the DEFAULT (async + poll) transport deliberately: {@link
   * #stopButtonCancelsInFlightGeneration()} flips to SSE, so that path was the only one covered and
   * the defect lived in the one that was not.
   */
  @Test
  void providerFailureIsNotReportedAsAUserCancellation() {
    doAnswer(
            inv -> {
              throw new IllegalStateException("simulated provider fault");
            })
        .when(llmClient)
        .generateStream(any(LlmRequest.class), any());

    newChatWithPlaybook("e2e-failure");
    page.fill("#chat-input", "This will fail.");
    page.click("#send-stop-button");

    assertThat(page.locator("#chat-messages")).containsText("System Error");
    // The whole point: the user did not stop this, so the bubble must not say they did.
    assertThat(page.locator("#chat-messages")).not().containsText("Generation stopped by user");
    // And the real cause never reaches the browser (SEC-17).
    assertThat(page.locator("#chat-messages")).not().containsText("simulated provider fault");
  }

  /**
   * The inverse guard: a genuine stop on the async transport must still say so, exactly once. The
   * server persists the marker AND the client renders a notice, so a careless fix in either place
   * produces a duplicated line.
   */
  @Test
  void stopOnTheAsyncTransportRendersTheCancellationNoticeExactlyOnce() throws Exception {
    CountDownLatch callStarted = new CountDownLatch(1);
    doAnswer(
            inv -> {
              callStarted.countDown();
              try {
                Thread.sleep(10_000);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancellationException("Chat generation cancelled");
              }
              return null;
            })
        .when(llmClient)
        .generateStream(any(LlmRequest.class), any());

    newChatWithPlaybook("e2e-stop-async");
    page.fill("#chat-input", "Take your time...");
    page.click("#send-stop-button");

    if (!callStarted.await(5, TimeUnit.SECONDS)) {
      throw new AssertionError("generation never started");
    }
    page.waitForResponse(
        r -> r.url().endsWith("/stop") && "POST".equals(r.request().method()),
        () -> page.click("#send-stop-button"));

    assertThat(page.locator("#chat-messages")).containsText("Generation stopped by user");
    assertThat(page.getByText("[Generation stopped by user]", new Page.GetByTextOptions()))
        .hasCount(1);
  }
}
