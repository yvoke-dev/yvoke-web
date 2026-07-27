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
