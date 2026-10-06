package de.palsoftware.yvoke.web.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.SelectOption;
import java.util.regex.Pattern;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Areas (P1-12) through the real admin pages: create an area, put a playbook in it from the
 * playbook form, then make that playbook the area's default from the area form. Each step reads
 * the result back from the database, so a form field the controller does not bind fails here
 * rather than being dropped silently.
 *
 * <p>Same annotations as every other e2e test, so it reuses the shared e2e Spring context.
 */
class AdminAreasE2EIT extends AbstractE2E {

  private static final String AREA = "E2E_AREA";
  private static final String PLAYBOOK = "e2e-area-playbook";

  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void cleanUp() {
    jdbcTemplate.update("DELETE FROM playbooks WHERE name = ?", PLAYBOOK);
    jdbcTemplate.update("DELETE FROM areas WHERE name = ?", AREA);
  }

  @Test
  void anAreaIsCreatedGetsAPlaybookAndTakesItAsItsDefault() {
    loginAs("admin");

    // 1. Create the area.
    page.navigate(url("/admin/areas"));
    page.waitForLoadState(LoadState.NETWORKIDLE);
    assertThat(page.locator("body")).containsText("OIM");
    page.fill("#name", AREA);
    page.fill("#title", "E2E Area");
    page.click("#submit-btn");
    page.waitForURL(Pattern.compile(".*/admin/areas$"));
    Assertions.assertThat(jdbcTemplate.queryForObject(
        "SELECT title FROM areas WHERE name = ?", String.class, AREA)).isEqualTo("E2E Area");

    // 2. Create a playbook in it from the playbook form.
    page.navigate(url("/admin/playbooks"));
    page.waitForLoadState(LoadState.NETWORKIDLE);
    page.fill("#name", PLAYBOOK);
    page.fill("#title", "E2E Area Playbook");
    page.selectOption("#area", new SelectOption().setValue(AREA));
    page.locator(".CodeMirror").click();
    page.keyboard().type("Body.");
    page.click("#submit-btn");
    page.waitForURL(Pattern.compile(".*/admin/playbooks$"));
    Assertions.assertThat(jdbcTemplate.queryForObject(
        "SELECT area FROM playbooks WHERE name = ?", String.class, PLAYBOOK)).isEqualTo(AREA);

    // 3. Make it the area's default; the select offers only the area's own playbooks.
    page.navigate(url("/admin/areas"));
    page.waitForLoadState(LoadState.NETWORKIDLE);
    page.click("button[data-name='" + AREA + "']");
    Assertions.assertThat(page.locator("#defaultPlaybook option").allTextContents())
        .containsExactly("None", PLAYBOOK);
    page.selectOption("#defaultPlaybook", new SelectOption().setValue(PLAYBOOK));
    page.click("#submit-btn");
    page.waitForURL(Pattern.compile(".*/admin/areas$"));
    Assertions.assertThat(jdbcTemplate.queryForObject(
        "SELECT default_playbook FROM areas WHERE name = ?", String.class, AREA))
        .isEqualTo(PLAYBOOK);
  }

  @Test
  void anAreaWithMembersCannotBeDeleted() {
    jdbcTemplate.update("INSERT INTO areas (name, title) VALUES (?, 'E2E Area')", AREA);
    jdbcTemplate.update("INSERT INTO playbooks (name, title, template_text, target_agent, area) "
        + "VALUES (?, 'P', 'Body.', 'specialist', ?)", PLAYBOOK, AREA);

    loginAs("admin");
    page.navigate(url("/admin/areas"));
    page.onceDialog(dialog -> dialog.accept());
    page.click("form[action$='/admin/areas/delete']:has(input[value='" + AREA + "']) "
        + "button[type='submit']");
    page.waitForURL(Pattern.compile(".*/admin/areas$"));

    assertThat(page.locator("body")).containsText("still has");
    Assertions.assertThat(jdbcTemplate.queryForObject(
        "SELECT count(*) FROM areas WHERE name = ?", Integer.class, AREA)).isEqualTo(1);
  }
}
