package de.palsoftware.yvoke.web.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.Route;
import de.palsoftware.yvoke.rag.prompt.PlaybookService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * How the chat surfaces a failed send. Every failure mode here otherwise presents to the user as an
 * eternal spinner over a dead composer, so the two properties that matter are that an error is
 * <em>shown</em> and that the composer is <em>usable again</em> afterwards.
 *
 * <p>Errors are injected with {@code page.route} rather than by provoking the server, which makes each
 * test synchronous and independent of any real failure being reachable.
 *
 * <p>The two transports had drifted apart: the streaming branch ({@code thread.js:1820-1827}) maps 403
 * and 404 to readable sentences while the async branch ({@code :1765}) — which is the
 * <em>default</em> transport — had no status mapping at all and showed "Request failed (403)." These
 * tests pin both to the same wording.
 */
class ChatErrorRenderingE2EIT extends AbstractE2E {

  private static final String PLAYBOOK = "e2e-error-render";
  private static final String FORBIDDEN_TEXT = "You do not have access to this conversation.";
  private static final String GONE_TEXT = "This conversation no longer exists.";

  @Autowired private PlaybookService playbookService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void removePlaybook() {
    jdbcTemplate.update("DELETE FROM playbooks WHERE name = ?", PLAYBOOK);
  }

  private void openConversation() {
    playbookService.savePlaybook(PLAYBOOK, "E2E Error Render", "d", "Answer.", List.of(), false);
    loginAs("user");
    newConversation();
    selectPlaybookChip(PLAYBOOK);
  }

  /** Fails the given send endpoint with a status and body, then sends one message. */
  private void sendWithFailure(String urlGlob, int status, String body) {
    page.route(
        urlGlob,
        route ->
            route.fulfill(
                new Route.FulfillOptions().setStatus(status).setContentType("text/plain")
                    .setBody(body)));
    page.fill("#chat-input", "What is OIM?");
    page.click("#send-stop-button");
  }

  @Test
  void theDefaultTransportRendersAReadableMessageWhenAccessIsDenied() {
    openConversation();

    sendWithFailure("**/send-async", 403, "Forbidden");

    assertThat(page.locator(".error-text")).containsText(FORBIDDEN_TEXT);
  }

  @Test
  void theStreamingTransportRendersTheSameMessageWhenAccessIsDenied() {
    openConversation();
    page.click("#mode-streaming-btn");
    assertThat(page.locator("#mode-streaming-btn.active")).isVisible();

    sendWithFailure("**/chat/*/send", 403, "Forbidden");

    assertThat(page.locator(".error-text")).containsText(FORBIDDEN_TEXT);
  }

  @Test
  void aDeletedConversationIsReportedAsGoneRatherThanAsAStatusCode() {
    openConversation();

    sendWithFailure("**/send-async", 404, "Not Found");

    assertThat(page.locator(".error-text")).containsText(GONE_TEXT);
  }

  /**
   * The safety property: whatever went wrong, the user must be able to type again. Without this an
   * error leaves the composer disabled and the conversation is over.
   */
  @Test
  void theComposerIsUsableAgainAfterAFailedSend() {
    openConversation();

    sendWithFailure("**/send-async", 403, "Forbidden");
    assertThat(page.locator(".error-text")).isVisible();

    assertThat(page.locator("#chat-input")).isEnabled();
    // The send button stays disabled only because the input was cleared on send; typing must revive
    // it. Asserting it enabled straight after the error would be asserting the wrong behaviour.
    page.fill("#chat-input", "try again");
    assertThat(page.locator("#send-stop-button")).isEnabled();
  }

  /** An unmapped status still has to say something concrete. */
  @Test
  void anUnmappedStatusFallsBackToTheStatusCode() {
    openConversation();

    sendWithFailure("**/send-async", 503, "upstream down");

    assertThat(page.locator(".error-text")).containsText("Request failed (503)");
  }

  /** A JSON error body is surfaced verbatim in preference to the generic wording. */
  @Test
  void aJsonErrorBodyIsShownToTheUser() {
    openConversation();
    page.route(
        "**/send-async",
        route ->
            route.fulfill(
                new Route.FulfillOptions().setStatus(400).setContentType("application/json")
                    .setBody("{\"error\":\"Playbook 'nope' is not registered.\"}")));

    page.fill("#chat-input", "What is OIM?");
    page.click("#send-stop-button");

    assertThat(page.locator(".error-text")).containsText("Playbook 'nope' is not registered.");
  }
}
