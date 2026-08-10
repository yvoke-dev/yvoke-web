package de.palsoftware.yvoke.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import de.palsoftware.yvoke.shared.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Did I break a page?" smoke sweep: renders every parameterless page through the real MVC +
 * Security + Thymeleaf stack (against the Testcontainers DB) and asserts none returns a 4xx/5xx. A
 * broken template, a missing model attribute or a null-unsafe expression 500s here — cheaply
 * covering the admin controllers/views that unit tests can't reach. Detail pages ({id}) are out of
 * scope (they need seeded entities); the chat round-trip is covered by
 * {@code de.palsoftware.yvoke.web.e2e.ChatSmokeE2EIT}.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "app.security.mock=true")
class AllPagesRenderSmokeIT {

  private static final String OID = "smoke-admin";

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;

  private MockMvc mockMvc;

  private static OidcLoginRequestPostProcessor adminUser() {
    return oidcLogin()
        .idToken(
            token ->
                token
                    .claim("oid", OID)
                    .claim("email", "smoke@test.local")
                    .claim("name", "Smoke User"))
        .authorities(
            new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("ROLE_USER"));
  }

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    // getCurrentUser() looks up by the "oid" claim (no upsert-on-read), so pages that resolve @User
    // (e.g. /chat) need the row to exist or they 401.
    userRepository.upsert(OID, "smoke@test.local", "Smoke User");
  }

  /**
   * Every admin page must ship the two CSRF meta tags AND the two scripts that read them, because
   * CSRF is deliberately still ENABLED on the browser/session chain while most admin interactions
   * are htmx or fetch calls that Spring's automatic hidden-form-field injection never touches.
   *
   * <p>The connectors page is the sharp case: its per-row "Test Connection" button is an {@code
   * hx-post}, the whole-form test is an {@code hx-post}, and the collection picker is an {@code
   * hx-get} — none of them is a {@code <form th:action>}, so the ONLY thing that puts a token on
   * the wire is the {@code htmx:configRequest} listener in {@code admin/layout.html}'s header
   * fragment (plus the {@code window.fetch} patch beside it for non-htmx calls). Drop either, or
   * rename a meta tag, or let {@code ${_csrf}} evaluate to null, and every state-changing admin
   * interaction starts coming back 403 with an empty body — htmx swaps nothing, so the button
   * simply does not respond and the page looks frozen rather than broken. There is no console
   * error to find and no server log that names the page.
   *
   * <p>An empty token is checked separately from a missing tag on purpose: {@code
   * th:content="${_csrf?.token}"} is null-safe, so disabling CSRF on the browser chain, or making
   * it stateless, renders {@code content=""} — the markup still looks right in a diff and in a
   * view-source, and the header goes out empty. Nothing in {@code src/test} or {@code src/it}
   * mentions {@code _csrf}, {@code _csrf_header} or {@code htmx:configRequest} today, so all of
   * this is unobserved; the sweep above only proves these pages return a status below 400.
   */
  @ParameterizedTest(name = "GET {0} wires CSRF for htmx and fetch")
  @ValueSource(
      strings = {
        "/admin/connectors",
        "/admin/ingest",
        "/admin/collections",
        "/admin/documents",
        "/admin/jobs"
      })
  void adminPageEmitsTheCsrfMetaTagsAndTheHtmxConfigRequestHook(String path) throws Exception {
    var response = mockMvc.perform(get(path).with(adminUser())).andReturn().getResponse();
    assertThat(response.getStatus()).as("GET %s must render the page itself", path).isEqualTo(200);
    String html = response.getContentAsString();

    String token = metaContent(html, "name=\"_csrf\" content=\"");
    assertThat(token).as("%s must carry the <meta name=\"_csrf\"> tag", path).isNotNull();
    assertThat(token)
        .as("an EMPTY token renders identically and silently 403s every htmx POST on %s", path)
        .isNotBlank();

    String headerName = metaContent(html, "name=\"_csrf_header\" content=\"");
    assertThat(headerName)
        .as("%s must carry the <meta name=\"_csrf_header\"> tag", path)
        .isNotNull();
    assertThat(headerName)
        .as("the header name the scripts set must be the one CsrfFilter validates")
        .isEqualTo("X-CSRF-TOKEN");

    assertThat(html)
        .as("the htmx hook that stamps the token onto every hx-post/hx-get on %s", path)
        .contains("htmx:configRequest");
    assertThat(html)
        .as("the fetch patch that stamps the token onto every non-GET fetch on %s", path)
        .contains("window.fetch");
  }

  /**
   * The value of the {@code content="…"} attribute Thymeleaf appends after {@code marker}, or null
   * when the tag is absent. Deliberately not a regex: the two markers differ only by the closing
   * quote after the tag name, which a literal indexOf gets right and a loose pattern would not.
   */
  private static String metaContent(String html, String marker) {
    int at = html.indexOf(marker);
    if (at < 0) {
      return null;
    }
    String rest = html.substring(at + marker.length());
    return rest.substring(0, rest.indexOf('"'));
  }

  @ParameterizedTest(name = "GET {0} renders without a 4xx/5xx")
  @ValueSource(
      strings = {
        // public
        "/login",
        "/logged-out",
        // user
        "/chat",
        // admin infra
        "/admin",
        "/admin/jobs",
        "/admin/audit",
        // collections / documents / kg / json objects
        "/admin/collections",
        "/admin/documents",
        "/admin/kg",
        // "/admin/kg/view" omitted: requires a mandatory ?collection param (detail view, not a page).
        "/admin/json-objects",
        // ingest / connectors
        "/admin/ingest",
        "/admin/connectors",
        // rag
        "/admin/search",
        "/admin/logs",
        "/admin/playbooks",
        "/admin/prompts",
        // chat ops
        "/admin/conversations",
        "/admin/feedback",
        "/admin/agent-runs",
        "/admin/costs",
        "/admin/pricing",
        "/admin/orchestrators"
      })
  void pageRendersWithoutServerError(String path) throws Exception {
    int status =
        mockMvc.perform(get(path).with(adminUser())).andReturn().getResponse().getStatus();
    assertThat(status)
        .as("GET %s should render (2xx) or redirect (3xx), not a client/server error", path)
        .isLessThan(400);
  }
}
