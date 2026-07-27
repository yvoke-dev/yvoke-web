package de.palsoftware.yvoke.web.e2e;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import de.palsoftware.yvoke.llm.core.model.LlmRequest;
import de.palsoftware.yvoke.llm.core.model.LlmResponseChunk;
import de.palsoftware.yvoke.llm.core.model.LlmUsage;
import de.palsoftware.yvoke.llm.core.service.LlmClient;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Base class for browser end-to-end tests. Boots the full application on a random port
 * (Testcontainers Postgres is wired in automatically for every {@code src/it} test via
 * {@code META-INF/spring.factories}), runs under mock auth on the {@code test} profile, and replaces
 * the {@link LlmClient} bean with a Mockito mock so assistant answers are deterministic and no
 * network is touched. Playwright drives real headless Chromium against {@code http://localhost:{port}}.
 *
 * <p>Playbook preflight validation is disabled here so first-message sends don't require a second
 * (mocked) LLM round-trip; tests that specifically exercise it can re-enable it per class. The
 * background job worker is also disabled in <em>every</em> e2e context so that no worker claims jobs
 * enqueued by {@code AdminJobProgressE2EIT} against the shared Testcontainers DB (that test drives
 * the job row itself); this also keeps all e2e classes on a single shared Spring context.
 *
 * <p><b>Every</b> e2e test fails on an uncaught JavaScript error, not just the page-loading sweep in
 * {@code AdminPagesConsoleE2EIT}. Playwright does not fail on page errors by default, so without this
 * the 2000-line {@code static/js/chat/thread.js} would run unwatched through the chat tests and the
 * documented Temporal-Dead-Zone init-order pitfall could only ever be caught on a page some test
 * happens to load. Assertion happens in {@link #closePage()} so it covers the whole test, including
 * anything raised after the last explicit assertion.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "app.security.mock=true",
      "app.chat.playbook-validation-enabled=false",
      "app.worker.enabled=false"
    })
public abstract class AbstractE2E {

  private static final double DEFAULT_TIMEOUT_MS = 15_000;

  @LocalServerPort protected int port;

  @MockitoBean(name = "llmProviderClient") protected LlmClient llmClient;

  private static Playwright playwright;
  private static Browser browser;
  protected BrowserContext browserContext;
  protected Page page;

  /** Uncaught JS errors raised by {@link #page} during the current test, newest last. */
  private final List<String> pageErrors = new CopyOnWriteArrayList<>();

  @BeforeAll
  static void startBrowser() {
    playwright = Playwright.create();
    browser = playwright.chromium().launch();
    // The chat default transport polls every 3s, so UI assertions need headroom over Playwright's
    // 5s default.
    PlaywrightAssertions.setDefaultAssertionTimeout(DEFAULT_TIMEOUT_MS);
  }

  @AfterAll
  static void stopBrowser() {
    if (browser != null) {
      browser.close();
    }
    if (playwright != null) {
      playwright.close();
    }
  }

  @BeforeEach
  void openPage() {
    browserContext = browser.newContext();
    browserContext.setDefaultTimeout(DEFAULT_TIMEOUT_MS);
    // Every off-origin request is refused, so the suite cannot pass because a CDN happened to be
    // reachable from a developer's machine. htmx and EasyMDE are vendored precisely because code
    // depends on them unconditionally; mermaid, KaTeX and FontAwesome are still remote but are
    // feature-detected or cosmetic, so they degrade. If a new hard dependency on a CDN is ever
    // added, it fails here rather than intermittently in CI.
    browserContext.route(
        "**/*",
        route -> {
          if (route.request().url().startsWith("http://localhost:")) {
            route.resume();
          } else {
            route.abort();
          }
        });
    page = browserContext.newPage();
    pageErrors.clear();
    // Registered before the first navigation so init-time errors on the very first page are seen.
    page.onPageError(pageErrors::add);
  }

  @AfterEach
  void closePage() {
    try {
      if (browserContext != null) {
        browserContext.close();
      }
    } finally {
      assertNoPageErrors("during the test");
    }
  }

  /**
   * Fails if the page has raised an uncaught JS error so far. Called automatically after every test;
   * call it explicitly mid-test (as the admin sweep does per page) when the failure message should
   * name what was being exercised.
   *
   * <p>Only uncaught errors are asserted on — benign {@code console.error} network noise from the CDN
   * assets is deliberately ignored, since those hosts may be unreachable in CI.
   */
  protected void assertNoPageErrors(String context) {
    Assertions.assertThat(pageErrors).as("uncaught JavaScript error(s) %s", context).isEmpty();
  }

  /**
   * Waits for at least one uncaught JS error, then removes and returns everything collected so far.
   *
   * <p>Test seam for the harness self-test only: draining is what lets a test deliberately provoke an
   * error without {@link #closePage()} then failing it. Draining in a normal test would hide a real
   * error, so don't.
   */
  protected List<String> awaitAndDrainPageErrors() {
    page.waitForCondition(() -> !pageErrors.isEmpty());
    List<String> drained = List.copyOf(pageErrors);
    pageErrors.clear();
    return drained;
  }

  protected String url(String path) {
    return "http://localhost:" + port + path;
  }

  /** Logs in through the mock-auth form ({@code user} or {@code admin}); any password is accepted. */
  protected void loginAs(String username) {
    page.navigate(url("/login"));
    page.fill("#username", username);
    page.fill("#password", "password");
    page.click("button[type=submit]");
    page.waitForURL(u -> !u.contains("/login"));
  }

  /** Clicks the sidebar "New Chat" control and returns the new conversation id (last URL segment). */
  protected String newConversation() {
    page.click("button.new-chat-btn");
    page.waitForURL(Pattern.compile(".*/chat/[0-9a-fA-F-]+$"));
    String current = page.url();
    return current.substring(current.lastIndexOf('/') + 1);
  }

  /** Selects a seeded playbook chip on the empty-conversation view (required before the first send). */
  protected void selectPlaybookChip(String promptName) {
    page.click("#prompt-chips button[data-prompt-name='" + promptName + "']");
  }

  /** Scripts the single-chunk assistant answer streamed for the next {@code generateStream} call. */
  protected void stubAssistantReply(String answer) {
    doAnswer(
            inv -> {
              Consumer<LlmResponseChunk> onChunk = inv.getArgument(1);
              onChunk.accept(
                  new LlmResponseChunk(answer, null, null, new LlmUsage(10, 10, 20, 0, 0)));
              return null;
            })
        .when(llmClient)
        .generateStream(any(LlmRequest.class), any());
  }
}
