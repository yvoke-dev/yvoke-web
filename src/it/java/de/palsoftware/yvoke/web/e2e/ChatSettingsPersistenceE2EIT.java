package de.palsoftware.yvoke.web.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.options.SelectOption;
import de.palsoftware.yvoke.rag.prompt.PlaybookService;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The per-conversation settings the composer writes with bare {@code fetch} calls: model,
 * thinking-level, streaming and show-thinking. Each is a fire-and-forget POST whose failure is
 * reported only to {@code console.error}, so a broken one is invisible to the user until they notice
 * their choice silently reverting.
 *
 * <p>Assertions go to the database rather than to the reloaded UI. That is not belt-and-braces: the
 * show-thinking and streaming toggles <em>also</em> write {@code localStorage}
 * ({@code thread.js:508-517}, {@code :538-548}) and read it back whenever the conversation carries no
 * explicit value, so a reload in the same browser context shows the chosen state even if the POST
 * 403'd or the write no-op'd. Reading {@code conversations.settings} is the only assertion that
 * actually proves the round trip.
 */
class ChatSettingsPersistenceE2EIT extends AbstractE2E {

  private static final String PLAYBOOK = "e2e-settings";

  @Autowired private PlaybookService playbookService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void removePlaybook() {
    jdbcTemplate.update("DELETE FROM playbooks WHERE name = ?", PLAYBOOK);
  }

  private String openConversation() {
    playbookService.savePlaybook(PLAYBOOK, "E2E Settings", "d", "Answer.", List.of(), false);
    loginAs("user");
    return newConversation();
  }

  private String storedSetting(String conversationId, String key) {
    return jdbcTemplate.queryForObject(
        "SELECT settings->>? FROM conversations WHERE id = ?::uuid", String.class, key,
        conversationId);
  }

  @Test
  void choosingAModelPersistsItToTheConversation() {
    String conversationId = openConversation();
    // Whatever the deployment allows; the second entry differs from the default first selection.
    List<String> models = page.locator("#model-selector option").allInnerTexts();
    Assertions.assertThat(models).as("no models offered").isNotEmpty();
    String target = models.get(models.size() - 1).trim();

    page.waitForResponse(
        r -> r.url().endsWith("/model") && r.status() == 200,
        () -> page.selectOption("#model-selector", new SelectOption().setLabel(target)));

    Assertions.assertThat(storedSetting(conversationId, "model")).isEqualTo(target);
  }

  @Test
  void choosingAThinkingLevelPersistsItToTheConversation() {
    String conversationId = openConversation();

    page.waitForResponse(
        r -> r.url().endsWith("/thinking-level") && r.status() == 200,
        () -> page.selectOption("#thinking-level-selector", new SelectOption().setValue("high")));

    Assertions.assertThat(storedSetting(conversationId, "thinking-level")).isEqualTo("high");
  }

  @Test
  void switchingToStreamingPersistsTheTransportChoice() {
    String conversationId = openConversation();

    page.waitForResponse(
        r -> r.url().contains("/streaming") && r.status() == 200,
        () -> page.click("#mode-streaming-btn"));

    assertThat(page.locator("#mode-streaming-btn.active")).isVisible();
    Assertions.assertThat(storedSetting(conversationId, "streaming")).isEqualTo("true");
  }

  @Test
  void togglingShowThinkingPersistsToTheConversationNotJustLocalStorage() {
    String conversationId = openConversation();

    // contains, not endsWith: the flag rides in the query string (…/show-thinking?enabled=true).
    page.waitForResponse(
        r -> r.url().contains("/show-thinking") && r.status() == 200,
        () -> page.click("#show-thinking-btn"));

    Assertions.assertThat(storedSetting(conversationId, "show-thinking")).isEqualTo("true");
  }

  /**
   * A reload must reflect the stored value and not a stale local one. localStorage is cleared first so
   * that the state on screen can only have come from the database.
   */
  @Test
  void aReloadWithNoLocalStateStillShowsTheStoredSettings() {
    String conversationId = openConversation();
    page.waitForResponse(
        r -> r.url().endsWith("/thinking-level") && r.status() == 200,
        () -> page.selectOption("#thinking-level-selector", new SelectOption().setValue("high")));
    page.waitForResponse(
        r -> r.url().contains("/streaming") && r.status() == 200,
        () -> page.click("#mode-streaming-btn"));

    page.evaluate("() => localStorage.clear()");
    page.reload();

    assertThat(page.locator("#thinking-level-selector")).hasValue("high");
    assertThat(page.locator("#mode-streaming-btn.active")).isVisible();
    Assertions.assertThat(storedSetting(conversationId, "thinking-level")).isEqualTo("high");
  }
}
