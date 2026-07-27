package de.palsoftware.yvoke.web.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.options.LoadState;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Loads every admin page in a real browser as an admin and asserts none raises an uncaught
 * JavaScript error. This catches client-side breakage that server-side render tests
 * ({@link de.palsoftware.yvoke.web.AllPagesRenderSmokeIT}) cannot — notably the documented
 * Temporal-Dead-Zone init-order pitfall in the inline module scripts, which throws a
 * {@code ReferenceError} at page init.
 *
 * <p>The page-error listener itself lives in {@link AbstractE2E}, so every e2e test now fails on an
 * uncaught error; what this class adds is breadth (all 19 nav pages) and a per-page assertion so a
 * failure names the page. Uncaught errors only (high signal) — benign {@code console.error} network
 * noise from the CDN assets is deliberately not failed on.
 */
class AdminPagesConsoleE2EIT extends AbstractE2E {

  private static final List<String> ADMIN_PAGES =
      List.of(
          "/admin",
          "/admin/jobs",
          "/admin/audit",
          "/admin/collections",
          "/admin/documents",
          "/admin/kg",
          "/admin/json-objects",
          "/admin/ingest",
          "/admin/connectors",
          "/admin/search",
          "/admin/logs",
          "/admin/playbooks",
          "/admin/prompts",
          "/admin/conversations",
          "/admin/feedback",
          "/admin/agent-runs",
          "/admin/costs",
          "/admin/pricing",
          "/admin/orchestrators");

  @Test
  void adminPagesLoadWithoutUncaughtJsErrors() {
    loginAs("admin");

    for (String path : ADMIN_PAGES) {
      page.navigate(url(path));
      // Wait for the network to settle (CDN + inline module scripts have run) instead of a blind
      // sleep, so a slow init-time ReferenceError is still observed before we assert.
      page.waitForLoadState(LoadState.NETWORKIDLE);
      // The listener lives in AbstractE2E (every e2e test now asserts on it); assert per page here so
      // the failure names the page that broke rather than just the class.
      assertNoPageErrors("after loading " + path);
    }
  }

  /**
   * Proves the shared listener actually fires. Without this, a wiring mistake (listener registered
   * after the first navigation, wrong Playwright event, errors swallowed) would make every
   * {@code assertNoPageErrors} call above — and the automatic one in {@code AbstractE2E.closePage} —
   * a silent no-op that passes forever. Lives here because this class is the mechanism's main user.
   */
  @Test
  void harnessDetectsAnUncaughtJsError() {
    page.navigate(url("/login"));
    // Thrown from a timeout callback so it surfaces as an uncaught page error rather than as the
    // return value of evaluate().
    page.evaluate("setTimeout(() => { throw new Error('harness self-test'); }, 0)");

    assertThat(awaitAndDrainPageErrors()).anyMatch(e -> e.contains("harness self-test"));
  }
}
