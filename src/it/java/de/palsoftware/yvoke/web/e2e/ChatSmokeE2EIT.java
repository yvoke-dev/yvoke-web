package de.palsoftware.yvoke.web.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import de.palsoftware.yvoke.rag.prompt.PlaybookService;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Smoke tests proving the e2e loop end to end: Playwright -> real HTTP server -> Spring MVC +
 * Security + Thymeleaf -> Testcontainers Postgres, with a mocked {@link
 * de.palsoftware.yvoke.llm.core.service.LlmClient} driving the rendered answer.
 */
class ChatSmokeE2EIT extends AbstractE2E {

  @Autowired private PlaybookService playbookService;

  @Test
  void userCanLogInAndReachChat() {
    loginAs("user");

    // The /chat landing shows the empty state + the sidebar's "New Chat" control. The chat input
    // textarea (#chat-input) lives on the /chat/{id} thread view, not here.
    assertThat(page).hasURL(Pattern.compile(".*/chat$"));
    assertThat(page.locator(".chat-empty-state")).isVisible();
    assertThat(page.locator("button.new-chat-btn")).isVisible();
  }

  @Test
  void mockedAssistantAnswerRendersInChat() {
    playbookService.savePlaybook(
        "e2e-playbook", "E2E Playbook", "Smoke-test playbook", "Answer the question.", List.of(),
        false);
    stubAssistantReply("Mocked answer from the fake LlmClient.");

    loginAs("user");

    // Start a fresh conversation -> redirected to /chat/{id} with the empty-state playbook chips.
    page.click("button.new-chat-btn");
    page.waitForURL(Pattern.compile(".*/chat/[0-9a-fA-F-]+$"));

    // A playbook must be selected before the client will send (the UI blocks otherwise).
    page.click("#prompt-chips button[data-prompt-name='e2e-playbook']");

    page.fill("#chat-input", "What is OIM?");
    page.click("#send-stop-button");

    // Default transport is async polling (~3s); the mocked answer must appear in the thread.
    assertThat(page.locator("#chat-messages"))
        .containsText("Mocked answer from the fake LlmClient.");
    assertThat(page.locator("#chat-messages")).containsText("What is OIM?");
  }
}
