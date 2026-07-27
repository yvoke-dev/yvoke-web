package de.palsoftware.yvoke.web.e2e;

import com.microsoft.playwright.options.LoadState;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Computed-style and geometry contracts for the admin shell.
 *
 * <p>Computed styles are the most durable layout assertion available: the value is resolved by the
 * engine but never rasterised, so it does not depend on which typeface the platform supplied. These
 * pin the handful of declarations the admin layout silently depends on — the kind that get deleted
 * during a refactor because nothing appears to use them.
 */
class LayoutContractE2EIT extends AbstractE2E {

  private double number(String js, Object... args) {
    return ((Number) page.evaluate(js, args.length == 1 ? args[0] : args)).doubleValue();
  }

  private void openAdmin(String path) {
    loginAs("admin");
    page.setViewportSize(1280, 800);
    page.navigate(url(path));
    page.waitForLoadState(LoadState.NETWORKIDLE);
  }

  /**
   * The admin layout lines up only because {@code .main-content}'s left margin and
   * {@code .sidebar}'s width are both {@code var(--sidebar-width)} ({@code index.css:85}, {@code
   * :184}). Change one and content slides under a fixed sidebar or leaves a gap.
   */
  @Test
  void theContentMarginMatchesTheSidebarWidth() {
    openAdmin("/admin");

    String margin = (String) page.evaluate(
        "() => getComputedStyle(document.querySelector('.main-content')).marginLeft");
    String sidebarWidth = (String) page.evaluate(
        "() => getComputedStyle(document.querySelector('.sidebar')).width");

    Assertions.assertThat(margin).as("content margin must match the sidebar width")
        .isEqualTo(sidebarWidth);
  }

  /** The sidebar is {@code position: fixed}, so any overlap is content the user cannot reach. */
  @Test
  void noAdminContentHidesUnderTheFixedSidebar() {
    for (String path : List.of("/admin", "/admin/jobs", "/admin/collections", "/admin/search")) {
      openAdmin(path);

      @SuppressWarnings("unchecked")
      List<String> overlapping = (List<String>) page.evaluate(
          """
          () => {
            const sidebar = document.querySelector('.sidebar');
            const main = document.querySelector('.main-content');
            if (!sidebar || !main) return [];
            const edge = sidebar.getBoundingClientRect().right;
            return Array.from(main.querySelectorAll('*'))
              .filter(el => {
                const r = el.getBoundingClientRect();
                return r.width > 0 && r.height > 0 && r.left < edge - 1;
              })
              .slice(0, 5)
              .map(el => el.tagName.toLowerCase() + (el.id ? '#' + el.id : ''));
          }
          """);

      Assertions.assertThat(overlapping).as("%s has content under the fixed sidebar", path)
          .isEmpty();
    }
  }

  // Deliberately not tested here: the modal backdrops on admin/playbooks.html and admin/prompts.html
  // used `width: 100vw`, which includes the vertical scrollbar and so overflows the content area on
  // platforms with classic scrollbars. Both were changed to `width: 100%`, but headless Chromium uses
  // overlay scrollbars (window.innerWidth == documentElement.clientWidth), so the defect is not
  // observable in this harness in either direction. A test that cannot fail is worse than no test, so
  // there isn't one — the fix stands on the CSS being read, not on a green assertion.

  /**
   * A {@code <dialog>} defaults to {@code position: absolute}, so hand-centring it with
   * {@code top/left: 50%} plus a transform positions it against the document rather than the viewport
   * — on a scrolled page it lands off screen. Every other dialog in the app declares
   * {@code position: fixed}; this one relies on the default.
   */
  @Test
  void aHandCentredDialogIsPositionedAgainstTheViewport() {
    openAdmin("/admin/json-objects");

    String position = (String) page.evaluate(
        "() => { const d = document.getElementById('filterBuilderDialog');"
            + " return d ? getComputedStyle(d).position : 'missing'; }");

    Assertions.assertThat(position)
        .as("a dialog centred with top/left:50%% must be fixed, or it centres on the document")
        .isEqualTo("fixed");
  }
}
