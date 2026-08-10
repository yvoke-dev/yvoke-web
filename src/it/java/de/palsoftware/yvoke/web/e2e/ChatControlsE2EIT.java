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
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProfile;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProfileService;
import de.palsoftware.yvoke.llm.core.model.LlmResponseChunk;
import de.palsoftware.yvoke.llm.core.model.LlmUsage;
import java.util.function.Consumer;

/**
 * J3 — chat controls: the streaming (SSE) transport and stop-generation. Reuses the base
 * {@link AbstractE2E} context (no extra properties/mock beans) to avoid minting a new Spring context.
 */
class ChatControlsE2EIT extends AbstractE2E {

  @Autowired private PlaybookService playbookService;
  @Autowired private OrchestratorProfileService orchestratorProfileService;

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

  /**
   * A reload in the middle of a generation must re-attach the poll. {@code prepareAndSubmitAsync}
   * persists the assistant row with {@code status = "generating"} <em>before</em> handing the turn to
   * the executor ({@code ChatMessageService:152-157}), {@code chat/fragments/message.html} renders
   * that row with {@code data-status="generating"}, and the only thing that turns it back into an
   * answer is the resume scan in {@code thread.js:1233} — {@code document.querySelector(
   * '.message-assistant[data-status="generating"]')} followed by {@code pollMessageStatus}.
   *
   * <p>Break that scan or drop the attribute and the failure is silent and total: the answer still
   * completes, is still persisted, and is still on the page's next render — but the tab the user is
   * looking at shows a rotating loader with no poll behind it, forever. That reads as a hung system
   * rather than a bug, and a user who reloads a long orchestrated run (or whose browser restores the
   * tab) hits it every time.
   *
   * <p>Nothing else covers it. {@code ChatAsyncControllerIT#testThreadViewShowsGeneratingMessage}
   * asserts the sync-loading CSS class on a server render and never runs the client; the only two
   * {@code page.reload()} calls in this tier ({@code ChatSettingsPersistenceE2EIT},
   * {@code ChatClarifyingQuestionE2EIT}) both reload with nothing in flight. Here the provider is
   * blocked on a latch so the reload is guaranteed to land mid-run, the server-rendered attribute is
   * asserted first (so a failure distinguishes "the server stopped emitting it" from "the client
   * stopped looking"), and the answer is then required to appear with no further navigation — which
   * only a re-attached poll can produce.
   */
  @Test
  void aGenerationInFlightIsPickedBackUpAfterAPageReload() throws Exception {
    CountDownLatch callStarted = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    doAnswer(
            inv -> {
              Consumer<LlmResponseChunk> onChunk = inv.getArgument(1);
              callStarted.countDown();
              if (!release.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the test never released the generation");
              }
              onChunk.accept(
                  new LlmResponseChunk("Answer produced while the tab was reloading.", null, null,
                      new LlmUsage(10, 10, 20, 0, 0)));
              return null;
            })
        .when(llmClient)
        .generateStream(any(LlmRequest.class), any());

    newChatWithPlaybook("e2e-reload-resume");
    page.fill("#chat-input", "What survives a reload?");
    page.click("#send-stop-button"); // default (async + poll) transport

    if (!callStarted.await(10, TimeUnit.SECONDS)) {
      throw new AssertionError("generation never started");
    }

    // Throws away the in-flight fetch and every bit of client state with it.
    page.reload();

    // Server side of the contract: the placeholder row is rendered, and carries the marker the
    // resume scan keys off.
    assertThat(page.locator(".message-assistant[data-status='generating']")).hasCount(1);

    release.countDown();

    // Client side: no navigation happens after this point, so the text can only arrive via a poll
    // that the reloaded page attached to the existing message on its own.
    assertThat(page.locator("#chat-messages"))
        .containsText("Answer produced while the tab was reloading.");
    assertThat(page.locator(".message-assistant[data-status='generating']")).hasCount(0);
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
   * Orchestrator mode is a different transport and a different prompt contract, and the client is
   * the only thing that enforces either. {@code thread.js} derives {@code orchestratorMode} from
   * the selector's value (:417-418) and then branches on it; the two that matter are the send path
   * ({@code if (!streamingEnabled || orchestratorMode)}, :1450, which forces the async + poll
   * transport) and the playbook UI, which is hidden, cleared and exempted from the
   * mandatory-playbook preflight (:420-436, :1390, :1446).
   *
   * <p>Lose the transport branch and a user who had ever switched on streaming — a per-user
   * {@code localStorage} preference, so it survives across conversations and is invisible on the
   * server — sends a multi-agent question down {@code POST /chat/{id}/send}. That endpoint runs the
   * single-agent SSE path with no orchestration at all: no {@code agent_runs}/{@code agent_steps}
   * trace, no MAS cost attribution, and an answer produced by a prompt the user did not choose,
   * arriving perfectly normally. Lose the playbook branch and the same send is blocked by the
   * "select a playbook" shake for a mode in which no playbook is selectable, or it posts a
   * {@code promptName} that prepends a specialist template in front of the orchestrator's own
   * question.
   *
   * <p>The order here is the test: the mode buttons are hidden the instant a profile is selected,
   * so this is the only window in which the preference can be set, and the assertion is that the
   * still-active preference is overridden rather than honoured. {@code "/send-async"} does not end
   * with {@code "/send"}, so the transport predicate cannot match the SSE endpoint by accident.
   *
   * <p>Nothing pins any of it: {@code thread-text.test.js} covers {@code pollTerminalDecision}
   * only, no JS test references {@code orchestratorMode} (the branching lives in the inline,
   * non-module part of {@code thread.js} and cannot be imported), and {@code ChatThreadRenderingIT}
   * never renders a conversation with an orchestrator profile available.
   */
  @Test
  void selectingAnOrchestratorProfileDisablesStreamingAndPlaybookSelection() {
    stubAssistantReply("Irrelevant — this test asserts the transport, not the answer.");
    playbookService.savePlaybook(
        "e2e-mas-orchestrator", "MAS Orchestrator", "desc", "Delegate.", List.of(), false);
    playbookService.savePlaybook(
        "e2e-mas-reviewer", "MAS Reviewer", "desc", "Review.", List.of(), false);
    playbookService.savePlaybook(
        "e2e-mas-specialist", "MAS Specialist", "desc", "Answer.", List.of(), false);
    // The selector renders only when the DB holds at least one profile (thread.html:177), so it is
    // seeded here and removed afterwards — every e2e class shares one Spring context and one DB.
    orchestratorProfileService.saveProfile(
        new OrchestratorProfile(
            "e2e-mas",
            1,
            2,
            "e2e-mas-orchestrator",
            "e2e-mas-reviewer",
            List.of("e2e-mas-specialist"),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null));

    try {
      loginAs("user");
      newConversation();

      // The user's transport preference, set while it is still settable.
      page.click("#mode-streaming-btn");
      assertThat(page.locator("#mode-streaming-btn.active")).isVisible();

      // Selecting the profile POSTs it; wait for that round trip so the server-side conversation
      // setting is persisted before the send below.
      page.waitForResponse(
          r -> r.url().contains("/orchestrator-profile") && "POST".equals(r.request().method()),
          () -> page.selectOption("#orchestrator-profile-selector", "e2e-mas"));

      // Playbook selection is gone, and so are the transport/model controls.
      assertThat(page.locator("#prompt-chips")).isHidden();
      assertThat(page.locator("#mode-streaming-btn")).isHidden();
      assertThat(page.locator("#model-selector")).isHidden();
      // ...but the streaming preference itself is untouched — it is overridden, not cleared.
      assertThat(page.locator("#mode-streaming-btn.active")).hasCount(1);

      page.fill("#chat-input", "Who owns the install kit?");
      page.waitForResponse(
          r -> r.url().endsWith("/send-async") && "POST".equals(r.request().method()),
          () -> page.click("#send-stop-button"));
    } finally {
      orchestratorProfileService.deleteProfile("e2e-mas");
    }
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
