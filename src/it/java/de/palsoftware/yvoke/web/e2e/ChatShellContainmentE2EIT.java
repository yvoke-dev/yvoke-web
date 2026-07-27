package de.palsoftware.yvoke.web.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.Page;
import de.palsoftware.yvoke.rag.prompt.PlaybookService;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The chat shell must contain a wide answer instead of being widened by it.
 *
 * <p>This is the layout failure with real consequences: {@code .chat-main, .chat-main-thread}
 * ({@code index.css:772-780}) is a flex item with {@code flex-grow: 1}, and a flex item's default
 * {@code min-width: auto} refuses to shrink below its content's intrinsic minimum. An unbreakable
 * string in an answer can therefore push the whole column wider than its share of the layout and take
 * the composer — including the send button — off screen, at which point the user cannot send anything
 * and cannot see why.
 *
 * <p>Assertions are relations and containment checks, never absolute pixel counts, so they hold
 * whichever typeface the platform resolved.
 */
class ChatShellContainmentE2EIT extends AbstractE2E {

  private static final String PLAYBOOK = "e2e-shell";
  /** No spaces anywhere: the one case no wrapping strategy can rescue. */
  private static final String UNBREAKABLE = "XObjectKeyRefId".repeat(30);

  private static final String ESCAPING_ELEMENTS_JS =
      """
      () => {
        const limit = document.documentElement.clientWidth + 1;
        const inScroller = (el) => {
          for (let p = el.parentElement; p; p = p.parentElement) {
            const ox = getComputedStyle(p).overflowX;
            if (ox === 'auto' || ox === 'scroll') return true;
          }
          return false;
        };
        return Array.from(document.body.querySelectorAll('*'))
          .filter(el => {
            const r = el.getBoundingClientRect();
            return r.width > 0 && r.right > limit && !inScroller(el);
          })
          .slice(0, 5)
          .map(el => el.tagName.toLowerCase()
            + (el.id ? '#' + el.id : '')
            + ' right=' + Math.round(el.getBoundingClientRect().right)
            + ' limit=' + limit);
      }
      """;

  @Autowired private PlaybookService playbookService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void removePlaybook() {
    jdbcTemplate.update("DELETE FROM playbooks WHERE name = ?", PLAYBOOK);
  }

  /** Sends one question and renders an answer containing an unbreakable single-line code block. */
  private void receiveWideAnswer() {
    playbookService.savePlaybook(PLAYBOOK, "E2E Shell", "d", "Answer.", List.of(), false);
    stubAssistantReply("Here is the key:\n\n```\n" + UNBREAKABLE + "\n```\n");

    loginAs("user");
    page.setViewportSize(1280, 800);
    newConversation();
    selectPlaybookChip(PLAYBOOK);
    page.fill("#chat-input", "What is the key?");
    page.click("#send-stop-button");
    assertThat(page.locator("#chat-messages")).containsText("Here is the key:");

    page.addStyleTag(
        new Page.AddStyleTagOptions()
            .setContent("*,*::before,*::after{animation:none!important;transition:none!important}"));
  }

  private double right(String selector) {
    // Number, not double: a whole-pixel rect comes back from the driver as an Integer.
    return ((Number) page.evaluate(
        "(sel) => document.querySelector(sel).getBoundingClientRect().right", selector))
            .doubleValue();
  }

  private double clientWidth() {
    return ((Number) page.evaluate("() => document.documentElement.clientWidth")).doubleValue();
  }

  /**
   * The one that matters: if the send button leaves the viewport the conversation is over as far as the
   * user is concerned.
   */
  @Test
  void theSendButtonStaysInsideTheViewportAfterAWideAnswer() {
    receiveWideAnswer();

    Assertions.assertThat(right("#send-stop-button"))
        .as("send button pushed out of the viewport by wide answer content")
        .isLessThanOrEqualTo(clientWidth() + 1);
  }

  @Test
  void aWideAnswerDoesNotDragTheWholePageSideways() {
    receiveWideAnswer();

    @SuppressWarnings("unchecked")
    List<String> escaping = (List<String>) page.evaluate(ESCAPING_ELEMENTS_JS);
    Assertions.assertThat(escaping)
        .as("element(s) escaped the viewport outside any scroll container").isEmpty();
    Assertions
        .assertThat(
            (boolean) page.evaluate(
                "() => document.documentElement.scrollWidth"
                    + " > document.documentElement.clientWidth + 1"))
        .as("the page itself scrolls horizontally").isFalse();
  }

  /** The thread column must not outgrow the space left by the sidebar. */
  @Test
  void theThreadColumnStaysWithinTheLayout() {
    receiveWideAnswer();

    double threadRight = right(".chat-main-thread");
    Assertions.assertThat(threadRight).as("thread column extends past the viewport")
        .isLessThanOrEqualTo(clientWidth() + 1);
  }

  /**
   * Wide content is <em>supposed</em> to scroll inside the message area. Asserting this stops a future
   * "fix" from solving overflow by clipping the content instead of making it reachable.
   */
  @Test
  void wideContentRemainsReachableInsideTheMessageArea() {
    receiveWideAnswer();

    Assertions
        .assertThat(
            (boolean) page.evaluate(
                "() => { const el = document.querySelector('.message-bubble pre');"
                    + " return !!el && getComputedStyle(el).overflowX !== 'hidden'; }"))
        .as("the code block must be scrollable or wrappable, not clipped").isTrue();
  }

  /**
   * One declaration is all that keeps UUIDs, JWTs and file paths from widening every bubble. Cheap to
   * delete by accident, so pin it.
   */
  @Test
  void messageBubblesKeepBreakingLongWords() {
    receiveWideAnswer();

    assertThat(page.locator(".message-bubble").first()).hasCSS("word-break", "break-word");
  }
}
