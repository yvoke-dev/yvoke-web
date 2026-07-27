package de.palsoftware.yvoke.web.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.SelectOption;
import java.util.List;
import java.util.regex.Pattern;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The first admin <em>write</em> path driven in a real browser, and the first browser test that reads
 * its result back out of the database.
 *
 * <p>Playbooks gate every chat send — the client refuses to send without one — yet no test at any tier
 * posted to the playbook admin endpoints. A MockMvc POST submits parameters directly, so it proves the
 * controller while proving nothing about the form that feeds it: rename a field in the template and a
 * MockMvc test stays green while the real page silently stops sending that value. This test was
 * checked against exactly that failure — renaming the tools {@code <select>} makes it fail.
 *
 * <p>It also drives the template body through the <a
 * href="https://github.com/Ionaru/easy-markdown-editor">EasyMDE</a> editor, which hides the real
 * {@code <textarea>}. Note that deleting the explicit sync-on-submit in {@code admin/playbooks.html}
 * does <em>not</em> break this test: CodeMirror installs its own submit hook that copies the editor
 * content back, so that block is belt-and-braces for the copy and load-bearing only for its
 * "Template Content is required" guard.
 *
 * <p>Extends {@link AbstractE2E} with no extra {@code properties} and no extra mock beans, so it
 * reuses the shared e2e Spring context instead of minting a new one — see the context-cache pitfall in
 * CLAUDE.md.
 */
class AdminPlaybookCrudE2EIT extends AbstractE2E {

  private static final String NAME = "e2e-playbook-crud";
  private static final String TITLE = "E2E Playbook CRUD";
  private static final String TEMPLATE_BODY = "# Answer policy\n\nAlways cite the source document.";

  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void removePlaybook() {
    jdbcTemplate.update("DELETE FROM playbooks WHERE name = ?", NAME);
  }

  /**
   * Types into the EasyMDE editor rather than the underlying textarea, which is what exercises the
   * sync-on-submit. Fails loudly if EasyMDE never initialised: the editor is loaded from a CDN, and a
   * test that quietly fell back to the plain textarea would be reporting a pass for a form the user
   * cannot actually operate.
   */
  private void typeTemplateBody(String markdown) {
    Locator editor = page.locator(".CodeMirror");
    Assertions
        .assertThat(editor.count())
        .as("EasyMDE did not initialise on #templateText — the CDN asset "
            + "(cdn.jsdelivr.net/npm/easymde) is unreachable, so this form is not usable and the "
            + "test cannot meaningfully exercise the template-body sync")
        .isPositive();
    editor.click();
    page.keyboard().type(markdown);
  }

  @Test
  void createsAPlaybookThroughTheFormAndPersistsEveryField() {
    loginAs("admin");
    page.navigate(url("/admin/playbooks"));
    page.waitForLoadState(LoadState.NETWORKIDLE);

    page.fill("#name", NAME);
    page.fill("#title", TITLE);
    page.fill("#description", "Created by AdminPlaybookCrudE2EIT.");
    page.selectOption("#targetAgent", new SelectOption().setValue("specialist"));
    page.check("#codeExecution");

    // The tools multi-select is populated from the live tool registry; pick the first real option so
    // the List<String> binding is exercised rather than skipped.
    List<String> toolValues = page.locator("#tools option").allTextContents();
    Assertions.assertThat(toolValues).as("no tools offered by the registry").isNotEmpty();
    String firstTool = page.locator("#tools option").first().getAttribute("value");
    page.selectOption("#tools", new SelectOption().setValue(firstTool));

    typeTemplateBody(TEMPLATE_BODY);

    page.click("#submit-btn");
    page.waitForURL(Pattern.compile(".*/admin/playbooks$"));

    // Reload-independent proof: the row is in the database with the body the editor held.
    Assertions
        .assertThat(jdbcTemplate.queryForObject(
            "SELECT template_text FROM playbooks WHERE name = ?", String.class, NAME))
        .contains("Always cite the source document.");
    Assertions
        .assertThat(jdbcTemplate.queryForObject("SELECT code_execution FROM playbooks WHERE name = ?",
            Boolean.class, NAME))
        .isTrue();
    Assertions
        .assertThat(jdbcTemplate.queryForList(
            "SELECT unnest(tools) FROM playbooks WHERE name = ?", String.class, NAME))
        .containsExactly(firstTool);
    Assertions
        .assertThat(jdbcTemplate.queryForObject("SELECT title FROM playbooks WHERE name = ?",
            String.class, NAME))
        .isEqualTo(TITLE);

    // And it renders in the list after a fresh load, so the round trip is complete.
    page.navigate(url("/admin/playbooks"));
    assertThat(page.locator("body")).containsText(TITLE);
  }

  @Test
  void deletesAPlaybookFromTheList() {
    jdbcTemplate.update(
        "INSERT INTO playbooks (name, title, description, template_text, tools, code_execution, "
            + "target_agent) VALUES (?, ?, 'seeded', 'Body.', '{}', false, 'specialist') "
            + "ON CONFLICT (name) DO NOTHING",
        NAME, TITLE);

    loginAs("admin");
    page.navigate(url("/admin/playbooks"));
    assertThat(page.locator("body")).containsText(TITLE);

    // The delete form fires a native confirm(); Playwright auto-dismisses dialogs by default.
    page.onceDialog(dialog -> dialog.accept());
    page.click("form[action$='/admin/playbooks/delete']:has(input[value='" + NAME + "']) "
        + "button[type='submit']");
    page.waitForURL(Pattern.compile(".*/admin/playbooks$"));

    Assertions
        .assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM playbooks WHERE name = ?",
            Integer.class, NAME))
        .isZero();
  }
}
