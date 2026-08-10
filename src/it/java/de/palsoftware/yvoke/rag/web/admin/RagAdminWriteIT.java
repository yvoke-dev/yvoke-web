package de.palsoftware.yvoke.rag.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.hamcrest.Matchers;

/**
 * The playbook and system-prompt write endpoints, which had no coverage at any tier: only
 * {@code GET /admin/playbooks} and {@code GET /admin/prompts} were ever requested (by the render and
 * console smoke sweeps), and the service-level tests run against a mocked repository.
 *
 * <p>These matter out of proportion to their size. A playbook is a hard dependency of the only user
 * workflow — the chat client refuses to send without one — and the active CHAT prompt is prepended to
 * every assistant turn application-wide, so a silent no-op in {@code POST /admin/prompts/active}
 * degrades every answer for every user with the whole suite still green.
 *
 * <p>What is asserted here is the <em>HTTP contract</em> the service tests cannot see: request-param
 * binding (including the {@code tools} multi-select to {@code List<String>}, the {@code codeExecution}
 * checkbox default, and {@code SystemPromptType} coercion), the multipart import path and its
 * empty-file guard, the export response headers, the redirect targets, and admin authorization on
 * every one of them.
 *
 * <p>Annotated identically to the other admin ITs so all of them share one cached Spring context —
 * see the context-cache pitfall in CLAUDE.md.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "app.security.mock=true")
public class RagAdminWriteIT {

    private static final String PLAYBOOK = "it-write-playbook";
    private static final String PROMPT = "it-write-prompt";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    public void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        cleanup();
    }

    @AfterEach
    public void tearDown() {
        cleanup();
    }

    private void cleanup() {
        jdbcTemplate.update("DELETE FROM playbooks WHERE name LIKE ?", PLAYBOOK + "%");
        jdbcTemplate.update("DELETE FROM system_prompts WHERE name LIKE ?", PROMPT + "%");
    }

    private static OidcLoginRequestPostProcessor admin() {
        return oidcLogin().idToken(token -> token.claim("oid", "it-rag-admin")).authorities(
            new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("ROLE_USER"));
    }

    private static OidcLoginRequestPostProcessor plainUser() {
        return oidcLogin().idToken(token -> token.claim("oid", "it-rag-user"))
            .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    // ---------- playbooks ----------

    @Test
    public void savingAPlaybookBindsToolsAndCodeExecutionAndRedirects() throws Exception {
        mockMvc
            .perform(post("/admin/playbooks").with(csrf()).with(admin()).param("name", PLAYBOOK)
                .param("title", "IT Write Playbook").param("description", "d")
                .param("templateText", "Answer carefully.").param("tools", "search_corpus")
                .param("tools", "get_section").param("codeExecution", "true")
                .param("targetAgent", "specialist"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/admin/playbooks"));

        assertThat(jdbcTemplate.queryForList("SELECT unnest(tools) FROM playbooks WHERE name = ?",
            String.class, PLAYBOOK)).containsExactlyInAnyOrder("search_corpus", "get_section");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT code_execution FROM playbooks WHERE name = ?", Boolean.class, PLAYBOOK))
                .isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT target_agent FROM playbooks WHERE name = ?",
            String.class, PLAYBOOK)).isEqualTo("specialist");
    }

    /**
     * A user-facing playbook with no tools cannot search, and the list MUST say so.
     *
     * <p>
     * Selecting nothing stores an empty array (see the test below), and an empty allow-list grants
     * nothing — so the playbook answers from the model alone, with no sources, which is the one
     * failure mode this product exists to avoid. It is invisible at answer time: the reply looks
     * normal, just ungrounded. The list previously rendered "Allowed Tools: All" for exactly this
     * row, which was wrong in every case and contradicted the form's own help text two panels away.
     */
    @Test
    public void aSpecialistPlaybookWithNoToolsIsFlaggedInTheList() throws Exception {
        mockMvc.perform(post("/admin/playbooks").with(csrf()).with(admin()).param("name", PLAYBOOK)
            .param("title", "IT Write Playbook").param("templateText", "Answer carefully.")
            .param("targetAgent", "specialist")).andExpect(status().is3xxRedirection());

        String html = mockMvc.perform(get("/admin/playbooks").with(admin())).andReturn()
            .getResponse().getContentAsString();

        assertThat(html).contains("No tools — answers without searching");
        assertThat(html).as("the old label claimed the opposite of what an empty list means")
            .doesNotContain("Allowed Tools: <span style=\"font-weight: 400; "
                + "color: var(--text-secondary);\">All</span>");
    }

    /**
     * The mirror-image mistake: tools chosen on a reviewer or orchestrator playbook are DISCARDED.
     * Those roles are handed a fixed list at the call site ({@code OrchestrationService} passes
     * {@code List.of("verify_citations", "get_section")} to the reviewer and
     * {@code List.of("ask_clarifying_question")} to the orchestrator), so the column is never read
     * for them. Without this the field looks functional and an admin can configure it carefully for
     * no effect.
     */
    @Test
    public void toolsChosenOnAReviewerPlaybookAreFlaggedAsIgnored() throws Exception {
        mockMvc
            .perform(post("/admin/playbooks").with(csrf()).with(admin()).param("name", PLAYBOOK)
                .param("title", "IT Write Playbook").param("templateText", "Review carefully.")
                .param("tools", "search_corpus").param("targetAgent", "reviewer"))
            .andExpect(status().is3xxRedirection());

        String html = mockMvc.perform(get("/admin/playbooks").with(admin())).andReturn()
            .getResponse().getContentAsString();

        assertThat(html).contains("Not used for this role — these tools are ignored");
    }

    /**
     * The checkbox is absent from the request when unticked and {@code tools} is absent when nothing
     * is selected — the two most likely binding regressions, since both silently produce a working
     * playbook with the wrong capabilities.
     */
    @Test
    public void omittedCheckboxAndToolsDefaultToDisabledAndEmpty() throws Exception {
        mockMvc.perform(post("/admin/playbooks").with(csrf()).with(admin()).param("name", PLAYBOOK)
            .param("title", "IT Write Playbook").param("templateText", "Answer carefully."))
            .andExpect(status().is3xxRedirection());

        assertThat(jdbcTemplate.queryForObject(
            "SELECT code_execution FROM playbooks WHERE name = ?", Boolean.class, PLAYBOOK))
                .isFalse();
        assertThat(jdbcTemplate.queryForList("SELECT unnest(tools) FROM playbooks WHERE name = ?",
            String.class, PLAYBOOK)).isEmpty();
        // Defaulted rather than rejected, per the controller's @RequestParam defaultValue.
        assertThat(jdbcTemplate.queryForObject("SELECT target_agent FROM playbooks WHERE name = ?",
            String.class, PLAYBOOK)).isEqualTo("specialist");
    }

    @Test
    public void exportingAPlaybookServesADownloadableMarkdownFile() throws Exception {
        savePlaybookRow();

        mockMvc.perform(get("/admin/playbooks/export").param("name", PLAYBOOK).with(admin()))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + PLAYBOOK + ".md\""))
            .andExpect(content().contentTypeCompatibleWith("text/markdown"))
            .andExpect(content().string(Matchers.containsString("Answer carefully.")));
    }

    @Test
    public void importingAPlaybookRoundTripsAnExportedFile() throws Exception {
        savePlaybookRow();
        String exported = mockMvc
            .perform(get("/admin/playbooks/export").param("name", PLAYBOOK).with(admin()))
            .andReturn().getResponse().getContentAsString();
        jdbcTemplate.update("DELETE FROM playbooks WHERE name = ?", PLAYBOOK);

        mockMvc
            .perform(multipart("/admin/playbooks/import")
                .file(new MockMultipartFile("file", PLAYBOOK + ".md", "text/markdown",
                    exported.getBytes(StandardCharsets.UTF_8)))
                .with(csrf()).with(admin()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/admin/playbooks"));

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM playbooks WHERE name = ?",
            Integer.class, PLAYBOOK)).isEqualTo(1);
    }

    /**
     * The empty-file guard throws {@code IllegalArgumentException}, which {@code MvcExceptionHandler}
     * converts to a redirect-with-error-flash — but only for a classic browser form post, which is
     * why the {@code Accept: text/html} header is load-bearing here. Without it the advice re-throws
     * and the caller gets a server error, so this is the contract a real browser sees, not a
     * convenient one.
     */
    @Test
    public void importingAnEmptyFileRedirectsBackWithAnError() throws Exception {
        mockMvc
            .perform(multipart("/admin/playbooks/import")
                .file(new MockMultipartFile("file", "empty.md", "text/markdown", new byte[0]))
                .header(HttpHeaders.ACCEPT, "text/html").with(csrf()).with(admin()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attributeExists("error"));
    }

    @Test
    public void deletingAPlaybookRemovesTheRow() throws Exception {
        savePlaybookRow();

        mockMvc
            .perform(post("/admin/playbooks/delete").with(csrf()).with(admin()).param("name",
                PLAYBOOK))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/admin/playbooks"));

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM playbooks WHERE name = ?",
            Integer.class, PLAYBOOK)).isZero();
    }

    @Test
    public void everyPlaybookWriteRequiresAdmin() throws Exception {
        mockMvc.perform(post("/admin/playbooks").with(csrf()).with(plainUser()).param("name",
            PLAYBOOK).param("title", "t").param("templateText", "x"))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/playbooks/delete").with(csrf()).with(plainUser()).param("name",
            PLAYBOOK)).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/playbooks/export").param("name", PLAYBOOK).with(plainUser()))
            .andExpect(status().isForbidden());
        mockMvc
            .perform(multipart("/admin/playbooks/import")
                .file(new MockMultipartFile("file", "p.md", "text/markdown", "x".getBytes()))
                .with(csrf()).with(plainUser()))
            .andExpect(status().isForbidden());

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM playbooks WHERE name = ?",
            Integer.class, PLAYBOOK)).isZero();
    }

    private void savePlaybookRow() {
        jdbcTemplate.update(
            "INSERT INTO playbooks (name, title, description, template_text, tools, "
                + "code_execution, target_agent) VALUES (?, ?, 'd', 'Answer carefully.', '{}', "
                + "false, 'specialist') ON CONFLICT (name) DO NOTHING",
            PLAYBOOK, "IT Write Playbook");
    }

    // ---------- system prompts ----------

    @Test
    public void savingASystemPromptCoercesTheTypeAndPersists() throws Exception {
        mockMvc
            .perform(post("/admin/prompts").with(csrf()).with(admin()).param("name", PROMPT)
                .param("type", "CHAT").param("description", "d")
                .param("systemPrompt", "You are terse."))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/prompts"));

        // SystemPromptType.dbValue() lowercases, so the request's "CHAT" lands as "chat".
        assertThat(jdbcTemplate.queryForObject("SELECT type FROM system_prompts WHERE name = ?",
            String.class, PROMPT)).isEqualTo("chat");
        assertThat(
            jdbcTemplate.queryForObject("SELECT system_prompt FROM system_prompts WHERE name = ?",
                String.class, PROMPT)).isEqualTo("You are terse.");
    }

    @Test
    public void anUnknownPromptTypeIsRejectedRatherThanStored() throws Exception {
        mockMvc.perform(post("/admin/prompts").with(csrf()).with(admin()).param("name", PROMPT)
            .param("type", "NOT_A_TYPE").param("systemPrompt", "x"))
            .andExpect(status().is4xxClientError());

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM system_prompts WHERE name = ?",
            Integer.class, PROMPT)).isZero();
    }

    /**
     * The application-wide default CHAT prompt. Nothing exercised this before, at any tier — and
     * {@code SystemPromptService.setDefaultChatPromptName} had no test caller at all.
     */
    @Test
    public void settingTheActiveChatPromptPersistsAndIsReadBackByThePage() throws Exception {
        mockMvc.perform(post("/admin/prompts").with(csrf()).with(admin()).param("name", PROMPT)
            .param("type", "CHAT").param("systemPrompt", "You are terse."))
            .andExpect(status().is3xxRedirection());

        mockMvc
            .perform(post("/admin/prompts/active").with(csrf()).with(admin())
                .param("activeChatPrompt", PROMPT))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/prompts"));

        mockMvc.perform(get("/admin/prompts").with(admin())).andExpect(status().isOk())
            .andExpect(content().string(Matchers.containsString(PROMPT)));
    }

    @Test
    public void deletingASystemPromptRemovesTheRow() throws Exception {
        mockMvc.perform(post("/admin/prompts").with(csrf()).with(admin()).param("name", PROMPT)
            .param("type", "KG").param("systemPrompt", "Extract entities."))
            .andExpect(status().is3xxRedirection());

        mockMvc
            .perform(post("/admin/prompts/delete").with(csrf()).with(admin()).param("name", PROMPT))
            .andExpect(status().is3xxRedirection());

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM system_prompts WHERE name = ?",
            Integer.class, PROMPT)).isZero();
    }

    /** The {@code <option>} list the Extract-KG modal fetches; never requested by any test before. */
    @Test
    public void kgPromptOptionsListsOnlyKgPrompts() throws Exception {
        mockMvc.perform(post("/admin/prompts").with(csrf()).with(admin()).param("name", PROMPT)
            .param("type", "KG").param("systemPrompt", "Extract entities."))
            .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/admin/prompts").with(csrf()).with(admin())
            .param("name", PROMPT + "-chat").param("type", "CHAT").param("systemPrompt", "Chat."))
            .andExpect(status().is3xxRedirection());

        String body = mockMvc.perform(get("/admin/prompts/kg-options").with(admin()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body).contains(PROMPT).doesNotContain(PROMPT + "-chat");
    }

    @Test
    public void everyPromptWriteRequiresAdmin() throws Exception {
        mockMvc.perform(post("/admin/prompts").with(csrf()).with(plainUser()).param("name", PROMPT)
            .param("type", "CHAT").param("systemPrompt", "x")).andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/prompts/active").with(csrf()).with(plainUser())
            .param("activeChatPrompt", PROMPT)).andExpect(status().isForbidden());
        mockMvc.perform(
            post("/admin/prompts/delete").with(csrf()).with(plainUser()).param("name", PROMPT))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/prompts/kg-options").with(plainUser()))
            .andExpect(status().isForbidden());
    }
}
