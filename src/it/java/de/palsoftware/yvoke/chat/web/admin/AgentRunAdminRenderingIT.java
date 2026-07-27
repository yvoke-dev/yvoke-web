package de.palsoftware.yvoke.chat.web.admin;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.palsoftware.yvoke.chat.core.repository.ConversationRepository;
import de.palsoftware.yvoke.chat.orchestration.AgentRunRepository;
import de.palsoftware.yvoke.chat.orchestration.AgentStepRepository;
import de.palsoftware.yvoke.shared.user.repository.UserRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Full Thymeleaf render coverage for the agent-run admin pages after the DTO-at-boundary refactor
 * (Wave 3.3). Seeds a user → conversation → run → step (agent_runs FKs conversations NOT NULL) and
 * renders the listing and the detail page; a missing accessor on the run/step DTOs would 500.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "app.security.mock=true")
public class AgentRunAdminRenderingIT {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private AgentRunRepository agentRunRepository;

    @Autowired
    private AgentStepRepository agentStepRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private UUID conversationId;
    private UUID runId;

    private static OidcLoginRequestPostProcessor admin() {
        return oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"),
            new SimpleGrantedAuthority("ROLE_USER"));
    }

    @BeforeEach
    public void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        userRepository.upsert("agentrun-view-oid", "agentrun@local", "AgentRun Tester");
        UUID userId = userRepository.findByEntraOid("agentrun-view-oid").orElseThrow().id();

        conversationId = UUID.randomUUID();
        conversationRepository.create(conversationId, userId, "Agent Run Conversation", Map.of(),
            "web");

        runId = UUID.randomUUID();
        agentRunRepository.create(runId, conversationId, "default-profile", Map.of());
        agentRunRepository.finish(runId, null, "done", 1, Map.of("verdict", "approved"), 10, 20, 30,
            0, 0, null);
        agentStepRepository.insert(UUID.randomUUID(), runId, 0, "orchestrator", 0, "playbook-x",
            "gemini", "high", "step input text", "step output text", Map.of(),
            Map.of("verdict", "approved"), 5, 10, 15, 0, 0);
    }

    @AfterEach
    public void tearDown() {
        // agent_runs/agent_steps cascade from the conversation delete.
        conversationRepository.delete(conversationId);
        jdbcTemplate.update("DELETE FROM users WHERE entra_oid = ?", "agentrun-view-oid");
    }

    @Test
    public void agentRunsListRendersRunSummaryDtos() throws Exception {
        mockMvc.perform(get("/admin/agent-runs").with(admin())).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("default-profile")));
    }

    @Test
    public void agentRunDetailRendersRunAndStepDtos() throws Exception {
        mockMvc.perform(get("/admin/agent-runs/" + runId).with(admin())).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("orchestrator")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("step output text")));
    }

    /**
     * The point of the whole change: a failed run must explain itself on this page, so nobody has to
     * go reading container logs. Seeds a run whose error is the multi-line diagnosis
     * {@code OrchestrationService} now composes, plus the failed step that killed it.
     */
    @Test
    public void agentRunDetailRendersTheFailureDiagnosisAndTheFailedStep() throws Exception {
        UUID failedRunId = UUID.randomUUID();
        agentRunRepository.create(failedRunId, conversationId, "default-profile", Map.of());
        agentRunRepository.finish(failedRunId, null, "error", 0, null, 0, 0, 0, 0, 0,
            "agent: role=specialist playbook=oim-install-kit model=gemini-3.6-flash round=0"
                + " afterSteps=4 specialistCalls=2\n"
                + "ClientException: HTTP 429 (rate limit / quota exhausted)\n"
                + "provider: status=\"\" message=\"\" raw=\"429 . \"\n"
                + "retries: Gemini.generateStream failed on attempt 3/3");
        agentStepRepository.insertFailed(UUID.randomUUID(), failedRunId, 1, "specialist", 0,
            "oim-install-kit", "gemini-3.6-flash", "high", "the sub-question",
            "partial streamed output", "ClientException: HTTP 429 (rate limit / quota exhausted)");

        mockMvc.perform(get("/admin/agent-runs/" + failedRunId).with(admin()))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Failure details")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("ClientException")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("rate limit")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("role=specialist")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("attempt 3/3")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("failed")))
            .andExpect(
                content().string(org.hamcrest.Matchers.containsString("partial streamed output")));
    }

    /**
     * The detail page now carries provider text and stack frames, so the admin gate is load-bearing.
     * Nothing pinned it before: there is no {@code @PreAuthorize} anywhere, so the single
     * {@code /admin/**} rule in {@code SecurityConfig} is the entire control.
     */
    @Test
    public void agentRunDetailIsDeniedToNonAdmins() throws Exception {
        mockMvc
            .perform(get("/admin/agent-runs/" + runId)
                .with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
            .andExpect(status().isForbidden());
    }
}
