package de.palsoftware.yvoke.web.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import de.palsoftware.yvoke.rag.prompt.PlaybookService;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import com.microsoft.playwright.Route;
import org.assertj.core.api.Assertions;
import java.util.regex.Pattern;

/**
 * The composer's client-side guards: a playbook is mandatory before anything can be sent, Enter sends
 * while Shift+Enter does not, and the send button reflects whether there is anything to send.
 *
 * <p>Five of these six tests need no LLM emission at all, which makes this the cheapest real coverage
 * on the client side — and the playbook guard is load-bearing, since it is the only thing standing
 * between a user and a request the server would reject.
 *
 * <p>Deliberately <em>not</em> asserted: the transient {@code .shake-input} class. It lives for 300ms
 * ({@code thread.js:1676-1682}), which is a race against a test process that has to schedule a click
 * and then query. The stable consequences are asserted instead — the warning appears, the text is
 * retained, no message row is added, and no request is issued.
 */
class ChatSendGuardE2EIT extends AbstractE2E {

  private static final String PLAYBOOK = "e2e-send-guard";

  @Autowired private PlaybookService playbookService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void removePlaybook() {
    jdbcTemplate.update("DELETE FROM playbooks WHERE name = ?", PLAYBOOK);
  }

  private void openConversation() {
    playbookService.savePlaybook(PLAYBOOK, "E2E Send Guard", "d", "Answer.", List.of(), false);
    loginAs("user");
    newConversation();
  }

  @Test
  void sendingWithoutAPlaybookIsSuppressedAndWarns() {
    openConversation();
    // Count real send attempts. Registered on the page so it observes what the browser actually
    // issued, rather than inferring from the absence of a rendered answer.
    AtomicInteger sendAttempts = new AtomicInteger();
    page.route(
        "**/send**",
        route -> {
          sendAttempts.incrementAndGet();
          route.resume();
        });

    page.fill("#chat-input", "What is OIM?");
    page.click("#send-stop-button");

    // Auto-retrying, so this also establishes that the submit handler has run before the counter is
    // read — a bare counter check straight after the click would prove nothing.
    assertThat(page.locator("#playbook-validation-warning")).isVisible();
    assertThat(page.locator("#chat-input")).hasValue("What is OIM?");
    assertThat(page.locator("#chat-messages .message-row:not(#prompt-chips)")).hasCount(0);
    Assertions.assertThat(sendAttempts.get())
        .as("no request may be issued when no playbook is selected").isZero();
    // The shake class is cleaned up by a timer; assert it is gone rather than racing its 300ms life.
    assertThat(page.locator(".chat-input-box.shake-input")).hasCount(0);
  }

  @Test
  void selectingAPlaybookReleasesTheGuard() {
    openConversation();
    stubAssistantReply("OIM is One Identity Manager.");

    selectPlaybookChip(PLAYBOOK);
    page.fill("#chat-input", "What is OIM?");
    page.click("#send-stop-button");

    assertThat(page.locator("#chat-messages")).containsText("OIM is One Identity Manager.");
  }

  @Test
  void enterSendsAndShiftEnterInsertsANewline() {
    openConversation();
    stubAssistantReply("Two lines received.");
    selectPlaybookChip(PLAYBOOK);

    page.fill("#chat-input", "line one");
    page.press("#chat-input", "Shift+Enter");
    page.type("#chat-input", "line two");

    // Shift+Enter must not have sent anything.
    assertThat(page.locator(".message-user")).hasCount(0);
    Assertions
        .assertThat((String) page.evaluate("() => document.getElementById('chat-input').value"))
        .contains("\n");

    page.press("#chat-input", "Enter");
    assertThat(page.locator("#chat-messages")).containsText("Two lines received.");
    assertThat(page.locator(".message-user")).hasCount(1);
  }

  /**
   * Discriminating version of "Enter on an empty box does nothing": a fresh conversation would satisfy
   * a zero-count assertion for three unrelated reasons, so one real message is sent first and the
   * count must then <em>stay</em> at one.
   */
  @Test
  void enterOnAnEmptyInputSendsNothingFurther() {
    openConversation();
    stubAssistantReply("First answer.");
    selectPlaybookChip(PLAYBOOK);
    page.fill("#chat-input", "first question");
    page.press("#chat-input", "Enter");
    assertThat(page.locator("#chat-messages")).containsText("First answer.");
    assertThat(page.locator(".message-user")).hasCount(1);

    page.press("#chat-input", "Enter");
    page.press("#chat-input", "Enter");

    assertThat(page.locator(".message-user")).hasCount(1);
  }

  @Test
  void theSendButtonTracksWhetherThereIsAnythingToSend() {
    openConversation();

    assertThat(page.locator("#send-stop-button")).isDisabled();
    assertThat(page.locator("#send-stop-button")).hasClass(Pattern
        .compile(".*state-send-disabled.*"));
    assertThat(page.locator("#send-stop-button .custom-tooltip"))
        .hasText("Send message (disabled)");

    page.fill("#chat-input", "x");
    assertThat(page.locator("#send-stop-button")).isEnabled();
    assertThat(page.locator("#send-stop-button")).hasClass(Pattern
        .compile(".*state-send-active.*"));
    assertThat(page.locator("#send-stop-button .custom-tooltip")).hasText("Send message");

    // Whitespace only — proves the .trim() and not merely a length check.
    page.fill("#chat-input", "    ");
    assertThat(page.locator("#send-stop-button")).isDisabled();
    assertThat(page.locator("#send-stop-button .custom-tooltip"))
        .hasText("Send message (disabled)");
  }

  /**
   * The two halves of {@code if (isFirstMessage && validationEnabled && !bypassPreflight)}
   * ({@code thread.js:1408}) are the only things bounding what the preflight LLM check costs and
   * whether a user can ever escape it, and each fails differently.
   *
   * <p>Lose {@code isFirstMessage} and every turn of every conversation pays an extra synchronous
   * round trip before the real send, with the composer disabled while it runs — a latency tax on
   * each message and a doubled call volume in {@code llm_call_logs} attributed to the validator.
   * Lose {@code bypassPreflight} and "Send Anyway" cannot escape at all: the resubmit re-runs the
   * check, gets the same non-plausible verdict, and re-renders the card the user just dismissed, so
   * a question the validator dislikes can never be sent.
   *
   * <p>Neither is pinned anywhere. {@code PlaybookValidationControllerIT} exercises the endpoint,
   * not the client's decision to call it; the whole branch lives in the non-module part of
   * {@code thread.js} and cannot be imported by the JS tier. The client flag is flipped on the live
   * {@code CHAT_CONFIG} object rather than by setting {@code app.chat.playbook-validation-enabled}
   * — the server flag is off in the shared e2e context and changing it would mint a second Spring
   * context — and {@code thread.js} captures {@code window.CHAT_CONFIG} by reference
   * ({@code :50}) and reads {@code playbookValidationEnabled} at submit time, so this is exactly
   * the state the server-rendered bootstrap would have produced. The verdict is stubbed with
   * {@code page.route} so the counter observes what the browser actually issued.
   */
  @Test
  void preflightRunsOnceOnTheFirstMessageAndSendAnywayBypassesIt() {
    openConversation();
    stubAssistantReply("OIM is One Identity Manager.");
    selectPlaybookChip(PLAYBOOK);

    page.evaluate("() => { window.CHAT_CONFIG.playbookValidationEnabled = true; }");

    AtomicInteger validations = new AtomicInteger();
    page.route(
        "**/validate-playbook",
        route -> {
          validations.incrementAndGet();
          route.fulfill(
              new Route.FulfillOptions().setStatus(200).setContentType("application/json")
                  .setBody("{\"plausible\":false,\"reason\":\"A different playbook fits better.\","
                      + "\"suggestedPlaybookName\":null}"));
        });

    page.fill("#chat-input", "What is OIM?");
    page.click("#send-stop-button");

    // Non-plausible verdict: the card is shown and nothing was sent.
    assertThat(page.locator("#preflight-warning-card-el")).isVisible();
    assertThat(page.locator(".message-user")).hasCount(0);
    assertThat(page.locator("#chat-input")).hasValue("What is OIM?");

    page.click(".btn-preflight-anyway");

    // The escape hatch actually escapes: the answer renders and the check was not re-run.
    assertThat(page.locator("#chat-messages")).containsText("OIM is One Identity Manager.");
    assertThat(page.locator("#preflight-warning-card-el")).hasCount(0);
    Assertions.assertThat(validations.get())
        .as("\"Send Anyway\" must not re-run the very check it is escaping").isEqualTo(1);

    // Second turn: no longer the first message, so no further check may be issued. The user bubble
    // count is the barrier — a preflight would return before appending it.
    page.fill("#chat-input", "And what is ADS?");
    page.click("#send-stop-button");

    assertThat(page.locator(".message-user")).hasCount(2);
    assertThat(page.locator("#preflight-warning-card-el")).hasCount(0);
    Assertions.assertThat(validations.get())
        .as("preflight is a first-message check, not a per-turn one").isEqualTo(1);
  }

  @Test
  void clearingTheActivePlaybookRestoresTheChipsAndRearmsTheGuard() {
    openConversation();
    selectPlaybookChip(PLAYBOOK);

    assertThat(page.locator("#active-playbook")).isVisible();
    assertThat(page.locator("#prompt-chips")).isHidden();

    page.click(".active-playbook-remove");

    assertThat(page.locator("#active-playbook")).isHidden();
    assertThat(page.locator("#prompt-chips")).isVisible();

    // Guard is armed again: sending now warns instead of submitting.
    page.fill("#chat-input", "What is OIM?");
    page.click("#send-stop-button");
    assertThat(page.locator("#playbook-validation-warning")).isVisible();
    assertThat(page.locator(".message-user")).hasCount(0);
  }
}
