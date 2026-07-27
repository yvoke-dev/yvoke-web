package de.palsoftware.yvoke.web.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import de.palsoftware.yvoke.rag.prompt.PlaybookService;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

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
    org.assertj.core.api.Assertions.assertThat(sendAttempts.get())
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
    org.assertj.core.api.Assertions
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
    assertThat(page.locator("#send-stop-button")).hasClass(java.util.regex.Pattern
        .compile(".*state-send-disabled.*"));
    assertThat(page.locator("#send-stop-button .custom-tooltip"))
        .hasText("Send message (disabled)");

    page.fill("#chat-input", "x");
    assertThat(page.locator("#send-stop-button")).isEnabled();
    assertThat(page.locator("#send-stop-button")).hasClass(java.util.regex.Pattern
        .compile(".*state-send-active.*"));
    assertThat(page.locator("#send-stop-button .custom-tooltip")).hasText("Send message");

    // Whitespace only — proves the .trim() and not merely a length check.
    page.fill("#chat-input", "    ");
    assertThat(page.locator("#send-stop-button")).isDisabled();
    assertThat(page.locator("#send-stop-button .custom-tooltip"))
        .hasText("Send message (disabled)");
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
