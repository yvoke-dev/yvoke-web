package de.palsoftware.yvoke.web.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.Response;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * What an authenticated-but-unauthorised person actually SEES when they hit an admin URL.
 *
 * <p>There is no {@code exceptionHandling}/{@code AccessDeniedHandler} anywhere in {@code
 * src/main/java}, so this outcome is assembled entirely out of framework defaults plus two pieces of
 * project state that look unrelated to each other: Spring Security's {@code AccessDeniedHandlerImpl}
 * {@code sendError(403)}s, the container ERROR-dispatches to {@code /error} (which is why {@code
 * /error} sits in {@code SecurityConfig}'s permitAll list), and {@code BasicErrorController} returns
 * the view name {@code "error"} — which resolves to the project's own {@code templates/error.html}
 * only because there is no {@code @ErrorController} and no {@code templates/error/} directory for
 * {@code DefaultErrorViewResolver} to find first. Add either of those, rename the template, or set
 * {@code server.error.whitelabel.enabled}, and a denied admin URL silently degrades to Boot's
 * whitelabel page — a bare stack-trace-shaped page with no navigation back into the app, which reads
 * to the user as "the site is broken" rather than "you may not go here".
 *
 * <p>The absent Details row is the security half. {@code application.yml} carries no {@code
 * server.error.*} keys, so {@code include-message} stays at its {@code never} default and the
 * template's {@code th:if} suppresses the row. Turning it on — the obvious thing to do while
 * debugging — publishes raw exception text on a page that any signed-in user can reach by guessing a
 * URL, and exception messages in this codebase routinely carry provider detail, SQL fragments and
 * ids (SEC-17).
 *
 * <p>Nothing existing can notice any of this. {@code SecurityGatingIT.testUserAccessGating} and
 * {@code SecurityMockGatingIT.testUserAccessGating} assert {@code status().isForbidden()} through
 * MockMvc, which performs no ERROR dispatch at all — the body they see is empty whatever the error
 * page does — and {@code AllPagesRenderSmokeIT} only ever loads pages as a user who is allowed on
 * them. This is the only place the rendered 403 is executed.
 */
class AdminAccessDeniedE2EIT extends AbstractE2E {

  @Test
  void aForbiddenAdminPageRendersTheBrandedErrorCardNotTheWhitelabelPage() {
    // Mock auth grants "user" ROLE_USER only; /admin/** requires ROLE_ADMIN.
    loginAs("user");

    Response response = page.navigate(url("/admin/documents"));

    Assertions.assertThat(response.status())
        .as("an authenticated non-admin must be refused with a real 403, not bounced to /login")
        .isEqualTo(403);

    assertThat(page.locator(".error-card")).isVisible();
    assertThat(page.locator(".error-code")).hasText("403");
    assertThat(page.locator(".error-title")).hasText("Access Denied");
    assertThat(page.locator(".error-description")).containsText("administrators");

    // Exactly one icon branch renders, and it is the padlock: the 404 branch draws a <circle> and
    // the 5xx branch a warning triangle, so the <rect> is what identifies the 403 glyph.
    assertThat(page.locator(".error-icon-wrapper svg")).hasCount(1);
    assertThat(page.locator(".error-icon-wrapper svg rect")).hasCount(1);

    // Error + Path and nothing else. A third row means server.error.include-message was turned on
    // and raw exception text is now on a page every signed-in user can reach.
    assertThat(page.locator(".error-details-row")).hasCount(2);
    assertThat(page.locator(".error-details-box")).containsText("Forbidden");
    assertThat(page.locator(".error-details-box")).containsText("/admin/documents");

    // The card is a way out, not a dead end.
    assertThat(page.locator(".error-actions a")).hasCount(2);

    Assertions.assertThat(page.content())
        .as("Boot's whitelabel page must never be what a denied user sees")
        .doesNotContain("Whitelabel Error Page");
  }
}
