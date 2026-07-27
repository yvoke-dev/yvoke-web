package de.palsoftware.yvoke.web.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import de.palsoftware.yvoke.rag.prompt.PlaybookService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The clarifying-question flow, whose failure mode is the worst in the chat UI: the card deliberately
 * <em>disables</em> the composer ({@code thread.js:1066-1081} sets {@code disabled} on
 * {@code #chat-input}, {@code #voice-input-btn} and {@code #send-stop-button}), and the only thing
 * that re-enables it is {@code updateMainInputState} ({@code :1167}) finding no
 * {@code .clarifying-question-card:not(.answered)}. If the card fails to render, or the answered-state
 * detection breaks, the user is locked out of their own conversation with no way to reply.
 *
 * <p>None of this was reachable by any previous test. {@code ChatThreadRenderingIT} asserts only that
 * the raw {@code <clarifying-question>} text survived into the HTML — the client-side parser that turns
 * it into a usable card never ran anywhere.
 *
 * <p>The answered state is the subtle part and is worth understanding before editing these tests: the
 * card is only marked {@code .answered} when the renderer walks {@code nextElementSibling} and finds a
 * following user message row ({@code thread.js:1013-1028}), which cannot be true in the same turn that
 * produced the card. It therefore only appears after a reload — which is exactly why the reload test
 * here is the one that matters.
 */
class ChatClarifyingQuestionE2EIT extends AbstractE2E {

  private static final String PLAYBOOK = "e2e-clarify";
  private static final String CARD = ".clarifying-question-card";
  private static final String QUESTION =
      "<clarifying-question><question>Which OIM version?</question>"
          + "<option>9.3</option><option>10.0</option></clarifying-question>";

  @Autowired private PlaybookService playbookService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void removePlaybook() {
    // All e2e classes share one Testcontainers database, so a leaked playbook would change the chip
    // and autocomplete counts in a different class.
    jdbcTemplate.update("DELETE FROM playbooks WHERE name = ?", PLAYBOOK);
  }

  /** Sends one question and has the assistant reply with a clarifying question. */
  private String askAndReceiveClarifyingQuestion() {
    playbookService.savePlaybook(PLAYBOOK, "E2E Clarify", "d", "Answer.", List.of(), false);
    stubAssistantReply(QUESTION);

    loginAs("user");
    String conversationId = newConversation();
    selectPlaybookChip(PLAYBOOK);
    page.fill("#chat-input", "Is ADS supported?");
    page.click("#send-stop-button");

    assertThat(page.locator(CARD)).isVisible();
    return conversationId;
  }

  @Test
  void anUnansweredCardLocksTheComposer() {
    askAndReceiveClarifyingQuestion();

    assertThat(page.locator(CARD + " .question-title")).containsText("Clarification Required");
    assertThat(page.locator(CARD + " .question-text")).containsText("Which OIM version?");
    assertThat(page.locator(".option-chip")).hasCount(2);
    assertThat(page.locator(".question-answer-input")).isVisible();

    // The lockout itself. Without a working card these would all be enabled, so this is the
    // assertion that would catch a rendering regression turning into a dead conversation.
    assertThat(page.locator("#chat-input")).isDisabled();
    assertThat(page.locator("#chat-input"))
        .hasAttribute("placeholder", "Please respond to the clarifying question above...");
    assertThat(page.locator("#send-stop-button")).isDisabled();
  }

  @Test
  void answeringViaAnOptionChipUnlocksTheComposerAndSendsTheAnswer() {
    askAndReceiveClarifyingQuestion();
    stubAssistantReply("Yes, ADS is supported in 9.3.");

    page.click(".option-chip[data-answer='9.3']");

    // The chip writes its answer into the main input and submits the form, so the answer must appear
    // as a user message and the follow-up answer must render.
    assertThat(page.locator("#chat-messages")).containsText("9.3");
    assertThat(page.locator("#chat-messages")).containsText("Yes, ADS is supported in 9.3.");
    assertThat(page.locator("#chat-input")).isEnabled();
    assertThat(page.locator("#send-stop-button")).isEnabled();
  }

  /**
   * The one that matters. On reload the card is rebuilt from the stored raw text, and whether the
   * composer is usable depends entirely on the renderer detecting the following user message and
   * adding {@code .answered}. Break that walk and this goes red in both visible ways at once: the card
   * renders as still-required, and the composer stays disabled.
   */
  @Test
  void anAnsweredCardRendersAsClarifiedAndLeavesTheComposerUsableAfterReload() {
    askAndReceiveClarifyingQuestion();
    stubAssistantReply("Yes, ADS is supported in 9.3.");
    page.click(".option-chip[data-answer='9.3']");
    assertThat(page.locator("#chat-messages")).containsText("Yes, ADS is supported in 9.3.");

    page.reload();

    assertThat(page.locator(CARD + ".answered")).isVisible();
    assertThat(page.locator(".clarified-badge")).containsText("9.3");
    assertThat(page.locator(CARD + " .question-title")).containsText("Clarification Provided");
    assertThat(page.locator("#chat-input")).isEnabled();
    // The send button is correctly disabled over an empty composer on a fresh load, so usability is
    // proven by typing rather than by asserting it enabled straight after the reload.
    page.fill("#chat-input", "and what about 10.0?");
    assertThat(page.locator("#send-stop-button")).isEnabled();
    // No live card is left behind, which is what updateMainInputState keys off.
    assertThat(page.locator(CARD + ":not(.answered)")).hasCount(0);
  }

  /** Enter in the card's own textarea submits it — a separate handler from the main composer's. */
  @Test
  void aCustomTypedAnswerSubmitsOnEnter() {
    askAndReceiveClarifyingQuestion();
    stubAssistantReply("Only 9.2 and later.");

    page.fill(".question-answer-input", "9.2");
    page.press(".question-answer-input", "Enter");

    assertThat(page.locator("#chat-messages")).containsText("Only 9.2 and later.");
    assertThat(page.locator("#chat-input")).isEnabled();
  }

  /** An empty custom answer must not submit — otherwise Enter sends a blank turn to the model. */
  @Test
  void anEmptyCustomAnswerDoesNotSubmit() {
    askAndReceiveClarifyingQuestion();

    page.click(".btn-submit-answer");

    // Still locked, still unanswered, and no user message was appended.
    assertThat(page.locator(CARD + ":not(.answered)")).isVisible();
    assertThat(page.locator("#chat-input")).isDisabled();
    assertThat(page.locator(".message-user")).hasCount(1);
  }
}
