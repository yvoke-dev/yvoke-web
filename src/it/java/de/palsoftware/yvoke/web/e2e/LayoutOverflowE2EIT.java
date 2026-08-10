package de.palsoftware.yvoke.web.e2e;

import com.microsoft.playwright.options.LoadState;
import de.palsoftware.yvoke.document.core.repository.DocumentRepository;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import com.microsoft.playwright.Page;

/**
 * Horizontal-overflow invariants for the admin console: wide content must scroll inside its own
 * container, never drag the whole page sideways.
 *
 * <p>No screenshots. The app ships no {@code @font-face}, so macOS resolves San Francisco and CI
 * resolves DejaVu; text wraps differently and image dimensions differ, which makes pixel baselines
 * unfixable rather than merely noisy. Every assertion here is a <em>relation</em> between two measured
 * values, which is stable on both platforms.
 *
 * <p>Two things make these tests non-vacuous, and both are load-bearing:
 *
 * <ul>
 *   <li>A fresh Testcontainers database renders every admin page empty, and an empty table cannot
 *       overflow. Deliberately wide content is seeded first — unbroken strings, which is the case CSS
 *       cannot wrap its way out of.
 *   <li>{@code body} declares {@code overflow-x: hidden} ({@code index.css:53}), which propagates to
 *       the viewport and could mask overflow from a naive probe. {@link
 *       #theOverflowProbeDetectsRealOverflow()} injects a deliberately oversized element and asserts
 *       the probe fires, so the sweeps below cannot pass by being blind.
 * </ul>
 */
class LayoutOverflowE2EIT extends AbstractE2E {

  private static final String COLLECTION = "E2E-LAYOUT-OVERFLOW";
  /** Unbroken, so no wrapping strategy can save the layout — the honest worst case. */
  private static final String WIDE_TITLE = "OIM".repeat(140);

  private static final List<String> ADMIN_PAGES =
      List.of("/admin", "/admin/jobs", "/admin/audit", "/admin/collections", "/admin/documents",
          "/admin/kg", "/admin/json-objects", "/admin/ingest", "/admin/connectors", "/admin/search",
          "/admin/logs", "/admin/playbooks", "/admin/prompts", "/admin/conversations",
          "/admin/feedback", "/admin/agent-runs", "/admin/costs", "/admin/pricing",
          "/admin/orchestrators");

  /**
   * True when the page itself scrolls sideways. Uses {@code documentElement} rather than
   * {@code window.innerWidth} so the measurement excludes the scrollbar, which is 0px wide on macOS
   * overlay scrollbars and ~15px on CI.
   */
  private static final String PAGE_OVERFLOWS_JS =
      "() => document.documentElement.scrollWidth > document.documentElement.clientWidth + 1";

  /**
   * Any element sticking out past the viewport that is not inside a legitimate scroll container. This
   * is the probe that survives {@code overflow-x: hidden} on an ancestor, because it reads geometry
   * per element instead of relying on the document reporting a scrollable width.
   */
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
            + (el.className && typeof el.className === 'string'
                ? '.' + el.className.trim().split(/\\s+/).join('.') : '')
            + ' right=' + Math.round(el.getBoundingClientRect().right));
      }
      """;

  @Autowired private DocumentRepository documentRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID collectionId;

  @BeforeEach
  void seedWideContent() {
    cleanup();
    collectionId = UUID.randomUUID();
    jdbcTemplate.update("INSERT INTO collections (id, name, tags) VALUES (?, ?, ARRAY['9.3'])",
        collectionId, COLLECTION);
    documentRepository.upsertManualDocument(COLLECTION, "9.3",
        "e2e_layout_" + "very_long_source_file_name_".repeat(8) + ".md", "manual", WIDE_TITLE);
    jdbcTemplate.update(
        "INSERT INTO ingestion_jobs (id, kind, source_ref, tags, collection_id, status, progress) "
            + "VALUES (?, 'kg-extract', ?, ARRAY['9.3'], ?, 'succeeded', 100)",
        UUID.randomUUID(), "confluence/" + "SPACEKEY".repeat(40), collectionId);
  }

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM collections WHERE name = ?", COLLECTION);
  }

  /** Kills animated transforms so a mid-flight geometry read cannot be off by the animation offset. */
  private void freezeAnimations() {
    page.addStyleTag(
        new Page.AddStyleTagOptions()
            .setContent("*,*::before,*::after{animation:none!important;transition:none!important}"));
  }

  private boolean pageOverflows() {
    return (boolean) page.evaluate(PAGE_OVERFLOWS_JS);
  }

  /**
   * The probe's own self-test. If this ever fails, every other test in this class is worthless and
   * silently passing — {@code overflow-x: hidden} on {@code body} is exactly the kind of rule that can
   * make a document stop reporting its own overflow.
   */
  @Test
  void theOverflowProbeDetectsRealOverflow() {
    loginAs("admin");
    page.setViewportSize(1280, 800);
    page.navigate(url("/admin"));
    page.waitForLoadState(LoadState.NETWORKIDLE);

    Assertions.assertThat(pageOverflows()).as("clean page should not overflow").isFalse();

    page.evaluate(
        "() => { const d = document.createElement('div'); d.id = 'overflow-control';"
            + " d.style.width = '3000px'; d.style.height = '1px';"
            + " document.body.appendChild(d); }");
    Assertions
        .assertThat(pageOverflows())
        .as("a 3000px element must be detected, otherwise this probe cannot see overflow at all")
        .isTrue();

    page.evaluate("() => document.getElementById('overflow-control').remove()");
    Assertions.assertThat(pageOverflows()).as("probe should recover after removal").isFalse();
  }

  @Test
  void noAdminPageDragsTheWholeWindowSidewaysAt1280() {
    loginAs("admin");
    page.setViewportSize(1280, 800);
    sweep();
  }

  /** A second width, because the stylesheet's only breakpoint keys off the viewport at 1200px. */
  @Test
  void noAdminPageDragsTheWholeWindowSidewaysAt1440() {
    loginAs("admin");
    page.setViewportSize(1440, 900);
    sweep();
  }

  private void sweep() {
    for (String path : ADMIN_PAGES) {
      page.navigate(url(path + (path.equals("/admin/documents") || path.equals("/admin/jobs")
          ? "?collection=" + COLLECTION : "")));
      page.waitForLoadState(LoadState.NETWORKIDLE);
      freezeAnimations();
      Assertions.assertThat(pageOverflows())
          .as("%s scrolls horizontally — wide content must scroll inside its container", path)
          .isFalse();
    }
  }

  /**
   * The structural rule behind the sweep above: wide content is allowed, but only inside something
   * that scrolls. This is what stops the next admin page from omitting the {@code .table-container}
   * wrapper and quietly breaking the whole layout.
   */
  @Test
  void nothingEscapesTheViewportExceptInsideAScrollContainer() {
    loginAs("admin");
    page.setViewportSize(1280, 800);

    for (String path : List.of("/admin/documents?collection=" + COLLECTION, "/admin/jobs",
        "/admin/audit", "/admin/conversations", "/admin/feedback", "/admin/agent-runs")) {
      page.navigate(url(path));
      page.waitForLoadState(LoadState.NETWORKIDLE);
      freezeAnimations();

      @SuppressWarnings("unchecked")
      List<String> escaping = (List<String>) page.evaluate(ESCAPING_ELEMENTS_JS);
      Assertions.assertThat(escaping)
          .as("%s has element(s) past the viewport edge outside any scroll container", path)
          .isEmpty();
    }
  }
}
