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
import java.util.concurrent.atomic.AtomicInteger;

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

  /**
   * Enabling streaming force-enables the thinking toggle and <em>locks</em> it:
   * {@code updateShowThinkingUI} ({@code thread.js:474-484}) adds {@code .active} and paints the
   * button at 0.6 opacity with a not-allowed cursor, and {@code toggleShowThinking} ({@code :498})
   * returns before touching anything. The button is styled disabled but is never {@code disabled} —
   * the early return is the entire lock.
   *
   * <p>No existing test clicks the button while streaming is on, so that branch is never entered.
   * Without it the click flips {@code showThinkingEnabled} to <em>true</em> and POSTs
   * {@code show-thinking=true}: the click the user believes turned thinking off has silently turned
   * it on in the database for every future non-streaming turn on that conversation. Nothing surfaces
   * it, because while streaming the SSE transport interleaves thinking and tool tokens regardless —
   * the divergence only shows up on the next standard-mode turn, with no error anywhere.
   *
   * <p>Two independent proofs, because the DB row alone would not distinguish "no POST" from "a POST
   * that has not landed yet": the request counter observes what the browser actually issued, and the
   * {@code .active} class after switching back to standard mode reflects the in-memory flag the
   * click would have flipped. Switching back is also the barrier — the show-thinking fetch, if any,
   * is issued synchronously in the earlier click handler, so it precedes the {@code /streaming}
   * round trip waited on here.
   */
  @Test
  void showThinkingCannotBeToggledOffWhileStreamingIsOn() {
    String conversationId = openConversation();

    AtomicInteger showThinkingWrites = new AtomicInteger();
    page.route(
        "**/show-thinking**",
        route -> {
          showThinkingWrites.incrementAndGet();
          route.resume();
        });

    page.waitForResponse(
        r -> r.url().contains("/streaming") && r.status() == 200,
        () -> page.click("#mode-streaming-btn"));

    // Forced on and painted as locked, but still clickable — which is why the early return matters.
    assertThat(page.locator("#show-thinking-btn.active")).isVisible();
    assertThat(page.locator("#show-thinking-btn")).isEnabled();

    page.click("#show-thinking-btn");

    page.waitForResponse(
        r -> r.url().contains("/streaming") && r.status() == 200,
        () -> page.click("#mode-standard-btn"));

    // Back in standard mode nothing forces the button on any more, so .active here would mean the
    // locked click had flipped the flag after all.
    assertThat(page.locator("#show-thinking-btn.active")).hasCount(0);
    Assertions.assertThat(showThinkingWrites.get())
        .as("a locked toggle must issue no request").isZero();
    Assertions.assertThat(storedSetting(conversationId, "show-thinking")).isEqualTo("false");
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
   * "Client mirrors, DB wins": {@code thread.js:249-258} and {@code :459-468} seed streaming and
   * show-thinking from {@code CHAT_CONFIG} and consult {@code localStorage} <em>only</em> when the
   * server value is null/undefined. A new conversation is seeded {@code streaming=false} and
   * {@code show-thinking=false} ({@code ChatConversationService:70-71}), so the server always has an
   * opinion and it must be the one that wins.
   *
   * <p>{@link #aReloadWithNoLocalStateStillShowsTheStoredSettings()} clears {@code localStorage}
   * before reloading, so an inverted precedence — local mirror preferred whenever present — passes
   * it unchanged. That is the gap: {@code userDefaultStreaming}/{@code userDefaultShowThinking} are
   * per-browser, not per-conversation, so if the precedence flips, one user who ever enabled
   * streaming sees <em>every</em> conversation they open rendered as streaming, including ones the
   * server has stored as async and ones they merely have read access to. The UI then disagrees with
   * what the next send will actually do, which is exactly the mismatch that sends a question down
   * the plain SSE path instead of the transport the conversation was configured for.
   *
   * <p>The local mirror is therefore set to the opposite of the stored value and the reload must
   * ignore it. Both toggles are asserted because they are two independent copies of the same
   * precedence rule.
   */
  @Test
  void storedSettingsWinOverAConflictingLocalStoragePreference() {
    String conversationId = openConversation();

    // The creation defaults: present and false, so "the server has no value" cannot explain a pass.
    Assertions.assertThat(storedSetting(conversationId, "streaming")).isEqualTo("false");
    Assertions.assertThat(storedSetting(conversationId, "show-thinking")).isEqualTo("false");

    page.evaluate(
        "() => { localStorage.setItem('userDefaultStreaming', 'true');"
            + " localStorage.setItem('userDefaultShowThinking', 'true'); }");
    page.reload();

    assertThat(page.locator("#mode-standard-btn.active")).isVisible();
    assertThat(page.locator("#mode-streaming-btn.active")).hasCount(0);
    assertThat(page.locator("#show-thinking-btn.active")).hasCount(0);
    // Reading the page must not have rewritten the conversation either.
    Assertions.assertThat(storedSetting(conversationId, "streaming")).isEqualTo("false");
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
