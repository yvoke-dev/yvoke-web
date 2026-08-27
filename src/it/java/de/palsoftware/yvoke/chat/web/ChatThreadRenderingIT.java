package de.palsoftware.yvoke.chat.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.palsoftware.yvoke.chat.core.model.Conversation;
import de.palsoftware.yvoke.chat.core.model.Message;
import de.palsoftware.yvoke.chat.core.repository.MessageRepository;
import de.palsoftware.yvoke.chat.core.service.ChatConversationService;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProfile;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProfileRepository;
import de.palsoftware.yvoke.shared.user.repository.UserRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import java.util.Map;
import org.springframework.web.servlet.ModelAndView;
import java.util.Comparator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "app.security.mock=true")
public class ChatThreadRenderingIT {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ChatConversationService chatConversationService;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private OrchestratorProfileRepository orchestratorProfileRepository;

    private MockMvc mockMvc;

    @BeforeEach
    public void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    public void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /**
     * The startup sweep is an UNFILTERED global {@code UPDATE messages SET status='error',
     * content='⚠️ *[Generation interrupted (system restart)]*'} whose only scope is
     * {@code WHERE status = 'generating'}. It fires automatically on every boot
     * ({@code @EventListener(ApplicationReadyEvent)}), has no user or conversation filter, swallows
     * its own exceptions, and {@code messages.status} carries no CHECK constraint to backstop it.
     * Widen or drop that predicate — or generalise it to {@code status <> 'done'} when a new
     * in-flight status is added — and the next {@code ./redeploy.sh} silently replaces the CONTENT
     * of every message of every user with the interrupt marker, reported as an INFO log line.
     * The terminal-row assertions are the load-bearing half.
     */
    @Test
    public void theStartupSweepTouchesOnlyGeneratingRowsAndLeavesFinishedAnswersIntact() {
        setSecurityContext("sweep-oid", "sweep@local", "Sweep User");
        UUID convId = chatConversationService.createConversation().id();

        UUID generating = UUID.randomUUID();
        UUID done = UUID.randomUUID();
        UUID errored = UUID.randomUUID();
        UUID cancelled = UUID.randomUUID();
        messageRepository.save(new Message(generating, convId, "assistant", "", null, List.of(),
            List.of(), Instant.now(), 0, 0, 0, 0, 0, "generating", "m"));
        messageRepository.save(new Message(done, convId, "assistant", "kept answer", null,
            List.of(), List.of(), Instant.now(), 1, 1, 2, 0, 0, "done", "m"));
        messageRepository.save(new Message(errored, convId, "assistant", "prior failure", null,
            List.of(), List.of(), Instant.now(), 0, 0, 0, 0, 0, "error", "m"));
        messageRepository.save(new Message(cancelled, convId, "assistant", "stopped by the user",
            null, List.of(), List.of(), Instant.now(), 0, 0, 0, 0, 0, "cancelled", "m"));

        messageRepository.resetGeneratingMessages();

        assertThat(messageRepository.findById(generating).orElseThrow().status())
            .isEqualTo("error");
        assertThat(messageRepository.findById(generating).orElseThrow().content())
            .contains("Generation interrupted");

        // Nothing terminal may be rewritten — not its status and not its content.
        assertThat(messageRepository.findById(done).orElseThrow())
            .satisfies(m -> assertThat(m.status()).isEqualTo("done"))
            .satisfies(m -> assertThat(m.content()).isEqualTo("kept answer"));
        assertThat(messageRepository.findById(errored).orElseThrow().content())
            .isEqualTo("prior failure");
        assertThat(messageRepository.findById(cancelled).orElseThrow())
            .satisfies(m -> assertThat(m.status()).isEqualTo("cancelled"))
            .satisfies(m -> assertThat(m.content()).isEqualTo("stopped by the user"));
    }

    /**
     * {@code GET /chat/{id}} carries two contracts that no test reaches today, and one request
     * proves both.
     *
     * <p>
     * <b>404 vs 403 is asymmetric.</b> An unknown conversation id must be a 404 for everyone, while
     * a foreign private one is a 403 — and only the 403 half is pinned (in
     * {@code ChatConversationServiceTest}). Nothing anywhere requests {@code /chat/<unknown uuid>},
     * so replacing {@code getConversation(id).orElseThrow(...)} with a bare {@code else} that
     * treats an unknown id as a fresh thread would silently render an empty new chat: the user
     * follows a stale link, sees a working page, and never learns the conversation is gone.
     *
     * <p>
     * <b>The model attribute set is the template's whole contract.</b> {@code chat/thread.html}
     * reads {@code conversation}, {@code messages}, {@code allowedModels}, {@code settings},
     * {@code feedbacks}, {@code prompts}, {@code isReadOnly}, {@code playbookValidationEnabled} and
     * {@code orchestratorProfiles}. The three existing rendering ITs in this class assert only on
     * HTML substrings of the message content, which survive an attribute going missing — Thymeleaf
     * iterates a null collection as empty and {@code th:if} on a null reads as false. So dropping
     * one turns a legitimate conversation into a silently degraded page (an empty model picker, no
     * playbook list, a read-write thread rendered read-only) or, for the attributes the template
     * dereferences, a 500 — with the failure visible only in production. Asserting on the model
     * rather than on rendered HTML is deliberate: the render is exactly what fails to notice.
     */
    @Test
    public void anUnknownConversationIdIs404AndAVisibleOneCarriesTheFullThreadModel()
        throws Exception {
        String userOid = "thread-model-user-oid";
        userRepository.upsert(userOid, "thread-model@local", "Thread Model User");
        setSecurityContext(userOid, "thread-model@local", "Thread Model User");
        var login = testUserLogin(userOid, "thread-model@local", "Thread Model User");

        // Not "someone else's" — an id that belongs to no conversation at all, which is a 404 for
        // every caller including an admin.
        mockMvc.perform(get("/chat/" + UUID.randomUUID()).with(login).with(csrf()))
            .andExpect(status().isNotFound());

        // Re-seed: MockMvc's security filter clears SecurityContextHolder after every request, and
        // createConversation() resolves its owner from the holder. Without this the conversation is
        // created with no owner and the GET below is a 403 rather than the 200 under test.
        setSecurityContext(userOid, "thread-model@local", "Thread Model User");
        Conversation conv = chatConversationService.createConversation();
        UUID convId = conv.id();
        messageRepository.save(new Message(UUID.randomUUID(), convId, "assistant", "an answer",
            "oim-ask", List.of(), List.of(), Instant.now(), 1, 1, 2, 0, 0, "done", "m"));

        MvcResult result = mockMvc.perform(get("/chat/" + convId).with(login).with(csrf()))
            .andExpect(status().isOk()).andReturn();

        ModelAndView mav = result.getModelAndView();
        assertThat(mav).isNotNull();
        Map<String, Object> viewModel = mav.getModel();

        assertThat(viewModel)
            .as("every attribute chat/thread.html reads must be present — a missing one degrades "
                + "the page silently rather than failing the render")
            .containsKeys("conversation", "messages", "allowedModels", "settings", "feedbacks",
                "prompts", "isReadOnly", "playbookValidationEnabled", "orchestratorProfiles");

        assertThat(((Conversation) viewModel.get("conversation")).id()).isEqualTo(convId);
        assertThat((List<?>) viewModel.get("messages")).hasSize(1);
        // The model picker is populated from the same whitelist createConversation() seeds the
        // conversation's model from, so an empty list here means the picker offers nothing at all.
        assertThat((List<?>) viewModel.get("allowedModels")).isNotEmpty();
        Object settings = viewModel.get("settings");
        assertThat(settings).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) settings).get("model")).isNotNull();
        assertThat(viewModel.get("isReadOnly")).isEqualTo(false);
        assertThat(viewModel.get("playbookValidationEnabled")).isInstanceOf(Boolean.class);

        // Cleanup (the security filter clears the holder after each MockMvc request).
        setSecurityContext(userOid, "thread-model@local", "Thread Model User");
        chatConversationService.deleteConversation(convId);
    }

    /**
     * {@code messages.model} is what the thread's model badge and the per-message half of the cost
     * views read, and it is written ONCE — by {@code save}, when the assistant placeholder row is
     * created. Every later write goes through {@code updateContentAndStatus}, and three of its six
     * production call sites pass {@code model = null} on purpose: the orchestrated path has no
     * single model to name, since the orchestrator, the specialists and the reviewer each run their
     * own. {@code COALESCE(:model, model)} is what makes "I have nothing to say about the model"
     * mean "leave it alone" rather than "blank it".
     *
     * <p>
     * Turning that into a plain {@code model = :model} reads like removing a redundant wrapper —
     * every other column in the same SET list is assigned directly — and it fails silently: the row
     * is still updated, the status is still correct, the answer still renders, and only the model
     * attribution disappears. No unit test can see it either, because the repository is mocked
     * everywhere upstream ({@code ChatMessageServiceTest} verifies the ARGUMENTS passed to this
     * method, never the SQL's effect), so an IT against a real Postgres is the only place the
     * COALESCE exists at all.
     */
    @Test
    public void anUpdateWithNoModelKeepsTheModelAlreadyOnTheRow() {
        setSecurityContext("coalesce-oid", "coalesce@local", "Coalesce User");
        UUID convId = chatConversationService.createConversation().id();

        UUID messageId = UUID.randomUUID();
        messageRepository.save(new Message(messageId, convId, "assistant", "", null, List.of(),
            List.of(), Instant.now(), null, null, null, null, null, "generating",
            "gemini-3.6-flash"));

        // The orchestrated finish: content, tokens and status are known, the model is not.
        messageRepository.updateContentAndStatus(messageId, "the final answer", List.of(),
            List.of(), 10, 20, 30, 0, 5, "done", null);

        Message stored = messageRepository.findById(messageId).orElseThrow();
        assertThat(stored.model())
            .as("a null model means 'unchanged', never 'blank it' — this row's model is the only "
                + "record of which model produced it")
            .isEqualTo("gemini-3.6-flash");
        // ...and the update genuinely happened, so the assertion above is not passing because the
        // whole statement was a no-op.
        assertThat(stored.status()).isEqualTo("done");
        assertThat(stored.content()).isEqualTo("the final answer");

        // A non-null model still wins, so COALESCE is not simply ignoring the parameter.
        messageRepository.updateContentAndStatus(messageId, "the final answer", List.of(),
            List.of(), 10, 20, 30, 0, 5, "done", "gemini-3.6-pro");
        assertThat(messageRepository.findById(messageId).orElseThrow().model())
            .isEqualTo("gemini-3.6-pro");
    }

    /**
     * {@code ConversationRepository.listAll} is {@code ORDER BY created_at DESC, id ASC} and it is
     * the ONLY read behind the sidebar — the conversation list, every folder, the tag vocabulary
     * derived from it, and the {@code SIDEBAR_LIMIT = 100} page it is cut to. Neither half is
     * pinned anywhere: {@code ConversationRepositoryTest} mocks {@code JdbcClient} and asserts on
     * SQL substrings, and {@code ChatConversationServiceTest} stubs {@code listAll} with a
     * ready-made list, so both stay green whatever the ORDER BY says.
     *
     * <p>
     * The {@code id ASC} tie-break is the same defect family as the desktop-sync incident recorded
     * in CLAUDE.md, and {@code create} makes it reachable: it stamps {@code created_at} with
     * {@code CURRENT_TIMESTAMP}, which Postgres freezes for the whole TRANSACTION, so any batch
     * that creates more than one conversation in one transaction ties. Drop the tie-break and the
     * order among tied rows is whatever the plan happens to return — and because the list is paged
     * with LIMIT/OFFSET, an unstable order does not merely look untidy, it repeats and skips
     * conversations across pages, which is how a chat silently disappears from a user's sidebar.
     *
     * <p>
     * The collision is forced with an explicit UPDATE — nothing else makes the tie-break
     * observable — and the rows are stamped far in the FUTURE so they occupy the head of the list
     * regardless of what other ITs have left in the table. The UPDATEs are applied in descending id
     * order so the physical row order left behind is the exact reverse of the answer required,
     * which is what a plan returning rows in heap order would produce. Postgres orders {@code uuid}
     * by its 16 bytes, i.e. lexicographically over the canonical lower-case hex — NOT
     * {@code UUID.compareTo}, which compares the halves as SIGNED longs and disagrees whenever the
     * top bit is set.
     */
    @Test
    public void theSidebarListIsOrderedByCreatedAtDescendingThenIdAscending() {
        String userOid = "sidebar-order-user-oid";
        userRepository.upsert(userOid, "sidebar-order@local", "Sidebar Order User");
        setSecurityContext(userOid, "sidebar-order@local", "Sidebar Order User");

        List<UUID> newer = new ArrayList<>();
        List<UUID> older = new ArrayList<>();
        List<UUID> created = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            UUID id = chatConversationService.createConversation().id();
            created.add(id);
            (i < 3 ? older : newer).add(id);
        }

        List<UUID> stampOrder = new ArrayList<>(created);
        stampOrder.sort(Comparator.comparing(UUID::toString).reversed());
        for (UUID id : stampOrder) {
            jdbcClient.sql("UPDATE conversations SET created_at = :ts::timestamptz WHERE id = :id")
                .param("ts",
                    newer.contains(id) ? "2999-01-02 00:00:00+00" : "2999-01-01 00:00:00+00")
                .param("id", id).update();
        }

        List<UUID> expected = new ArrayList<>();
        newer.stream().sorted(Comparator.comparing(UUID::toString)).forEach(expected::add);
        older.stream().sorted(Comparator.comparing(UUID::toString)).forEach(expected::add);

        List<UUID> actual = chatConversationService.listAllConversations(100, 0).stream()
            .map(Conversation::id).limit(6).toList();

        assertThat(actual)
            .as("newest first, and a created_at tie resolved by id ASC — anything else makes the "
                + "paged sidebar repeat and skip conversations")
            .isEqualTo(expected);

        for (UUID id : created) {
            chatConversationService.deleteConversation(id);
        }
    }

    private void setSecurityContext(String oid, String email, String name) {
        OidcUser oidcUser = mock(OidcUser.class);
        when(oidcUser.getClaimAsString("oid")).thenReturn(oid);
        when(oidcUser.getClaimAsString("name")).thenReturn(name);
        when(oidcUser.getClaimAsString("email")).thenReturn(email);

        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getPrincipal()).thenReturn(oidcUser);

        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        when(auth.getAuthorities()).thenAnswer(inv -> authorities);

        SecurityContext secContext = SecurityContextHolder.createEmptyContext();
        secContext.setAuthentication(auth);
        SecurityContextHolder.setContext(secContext);
    }

    private static SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor testUserLogin(String oid, String email, String name) {
        return oidcLogin()
            .idToken(token -> token.claim("oid", oid).claim("name", name).claim("email", email))
            .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Test
    @DisplayName("Guards thread.html against raw unescaped regex brackets inside th:inline='javascript' blocks")
    public void testThymeleafInlineScriptBracketSafety() throws IOException {
        Path templatePath = Paths.get("src/main/resources/templates/chat/thread.html");
        String content = Files.readString(templatePath, StandardCharsets.UTF_8);

        Pattern scriptInlinePattern = Pattern.compile("<script[^>]*th:inline=\"javascript\"[^>]*>([\\s\\S]*?)</script>");
        Matcher matcher = scriptInlinePattern.matcher(content);

        List<String> violations = new ArrayList<>();
        while (matcher.find()) {
            String scriptBody = matcher.group(1);

            // Check for raw literal regex bracket patterns like .replace(/\[.../g)
            Pattern unsafeBracketRegexPattern = Pattern.compile("\\.replace\\s*\\(\\s*/\\\\x5B");
            Matcher unsafeMatcher = unsafeBracketRegexPattern.matcher(scriptBody);
            while (unsafeMatcher.find()) {
                violations.add(unsafeMatcher.group());
            }
        }

        assertThat(violations)
            .as("Found unsafe raw regex brackets in th:inline='javascript' script blocks that crash Thymeleaf parsing! Use new RegExp('\\x5B...\\x5D', 'g') instead.")
            .isEmpty();
    }

    @Test
    @DisplayName("Guards the chat thread script against Temporal Dead Zone (TDZ) ReferenceErrors on page initialization")
    public void testInlineScriptVariableDeclarationOrderBeforeInitialization() throws IOException {
        // The chat-thread JS was extracted to static/js/chat/thread.js (MNT-02); the TDZ hazard is a
        // JS-runtime concern that follows the code, so the guard now scans the extracted script.
        Path scriptPath = Paths.get("src/main/resources/static/js/chat/thread.js");
        String scriptBody = Files.readString(scriptPath, StandardCharsets.UTF_8);

        List<String> violations = new ArrayList<>();
        int applyOrchestratorIdx = scriptBody.indexOf("applyOrchestratorModeUI();");
        int autocompletePopupDeclIdx = scriptBody.indexOf("const autocompletePopup =");
        int filteredPromptsDeclIdx = scriptBody.indexOf("let filteredAutocompletePrompts =");

        if (applyOrchestratorIdx != -1) {
            if (autocompletePopupDeclIdx != -1 && autocompletePopupDeclIdx > applyOrchestratorIdx) {
                violations.add("autocompletePopup declared after top-level applyOrchestratorModeUI() call");
            }
            if (filteredPromptsDeclIdx != -1 && filteredPromptsDeclIdx > applyOrchestratorIdx) {
                violations.add("filteredAutocompletePrompts declared after top-level applyOrchestratorModeUI() call");
            }
        }

        assertThat(violations)
            .as("Found top-level script statements that execute before const/let variables (TDZ violation) causing Uncaught ReferenceError on page load!")
            .isEmpty();
    }

    /**
     * {@code ChatMessageService.getMessages} is a single {@code findByConversationId(id, 100, 0)}
     * and it feeds TWO consumers: the rendered thread and the history handed to the model. No test
     * anywhere seeds more than a handful of messages, so neither half of that one line is
     * exercised.
     *
     * <p>
     * <b>The cap.</b> Raising or losing it silently changes prompt size and therefore cost on every
     * long thread — nothing errors, the page simply renders more and the next turn bills more.
     *
     * <p>
     * <b>The total order.</b> {@code ORDER BY created_at ASC, id ASC} is the fix for the desktop-
     * sync incident: a batched write inside one transaction used to stamp every row with the same
     * {@code CURRENT_TIMESTAMP}, so the ORDER BY fell through to a random v4 {@code id} and whole
     * turns rendered answer-above-question — stably, because the covering index reproduces the same
     * wrong order on every read, and that scrambled log then becomes LLM history on rehydration.
     * {@code save} now uses {@code clock_timestamp()}, so this test has to force the collision
     * itself with an explicit UPDATE; that is the only way to make the tie-break observable at all.
     * Note Postgres orders {@code uuid} by its 16 bytes, i.e. lexicographically over the canonical
     * lower-case hex — NOT {@code UUID.compareTo}, which compares the halves as SIGNED longs and
     * disagrees whenever the top bit is set.
     */
    @Test
    public void theThreadRendersAtMostOneHundredMessagesAndInATotalOrder() throws Exception {
        String userOid = "thread-cap-user-oid";
        userRepository.upsert(userOid, "thread-cap@local", "Thread Cap User");
        setSecurityContext(userOid, "thread-cap@local", "Thread Cap User");

        Conversation conv = chatConversationService.createConversation();
        UUID convId = conv.id();

        // Two batches, each stamped with ONE created_at — exactly what a transactional batch write
        // produces, which leaves `id ASC` as the only thing deciding the order within a batch.
        List<UUID> older = new ArrayList<>();
        List<UUID> newer = new ArrayList<>();
        for (int i = 0; i < 105; i++) {
            UUID id = UUID.randomUUID();
            boolean isOlder = i < 60;
            (isOlder ? older : newer).add(id);
            messageRepository.save(new Message(id, convId, (i % 2 == 0) ? "user" : "assistant",
                "message " + i, null, List.of(), List.of(), Instant.now(), 0, 0, 0, 0, 0, "done",
                "m"));
            jdbcClient.sql("UPDATE messages SET created_at = :ts::timestamptz WHERE id = :id")
                .param("ts", isOlder ? "2020-01-01 00:00:00+00" : "2020-01-02 00:00:00+00")
                .param("id", id).update();
        }

        List<UUID> expected = new ArrayList<>();
        older.stream().sorted(Comparator.comparing(UUID::toString)).forEach(expected::add);
        newer.stream().sorted(Comparator.comparing(UUID::toString)).forEach(expected::add);

        var login = testUserLogin(userOid, "thread-cap@local", "Thread Cap User");
        MvcResult result = mockMvc.perform(get("/chat/" + convId).with(login).with(csrf()))
            .andExpect(status().isOk()).andReturn();

        ModelAndView mav = result.getModelAndView();
        assertThat(mav).isNotNull();
        List<?> rendered = (List<?>) mav.getModel().get("messages");

        assertThat(rendered)
            .as("the thread is capped at 100 messages, and the same call bounds the history handed "
                + "to the model — changing the cap changes prompt size and cost with no signal")
            .hasSize(100);
        assertThat(rendered.stream().map(m -> ((Message) m).id()).toList())
            .as("created_at ASC then id ASC: a tie resolved by anything else scrambles the turn, "
                + "and the same list is what rehydrates LLM history")
            .isEqualTo(expected.subList(0, 100));

        // Cleanup (the security filter clears the holder after each MockMvc request).
        setSecurityContext(userOid, "thread-cap@local", "Thread Cap User");
        chatConversationService.deleteConversation(convId);
    }

    @Test
    @DisplayName("Renders AD sync permissions answer via MockMvc HTTP GET /chat/{id} with 200 OK")
    public void testThreadRenderingWithPermissionsAndRawUuidCitations() throws Exception {
        String userOid = "rendering-test-user-oid";
        userRepository.upsert(userOid, "rendering-test@local", "Rendering Test User");
        setSecurityContext(userOid, "rendering-test@local", "Rendering Test User");

        Conversation conv = chatConversationService.createConversation();
        UUID convId = conv.id();

        String rawContent = "While it is theoretically possible to perform imports with only **Read** permissions and full synchronization with **Write** permissions [4b7b0f51-6293-4cd6-8f4b-5a66adf42742], the default configuration in One Identity Manager requires the synchronization user account to be a member of the **Domain Admins** group [2a01286b-6827-453f-b635-0311b6b59e41].\n\nIf you choose not to use a Domain Admin account, you will encounter several limitations:\n\n1. **Permission List Modifications:** Changes to permission lists.\n2. **Specially Protected Objects:** Domain administrator permissions.\n\n## References\n- [2a01286b-6827-453f-b635-0311b6b59e41]\n- [4b7b0f51-6293-4cd6-8f4b-5a66adf42742]";

        Message assistantMessage = new Message(
            UUID.randomUUID(), convId, "assistant", rawContent,
            "oim-ask", List.of(), List.of(), Instant.now(),
            100, 50, 150, 0, 10, "done", "deepseek-v4-pro"
        );
        messageRepository.save(assistantMessage);

        var login = testUserLogin(userOid, "rendering-test@local", "Rendering Test User");

        MvcResult result = mockMvc.perform(get("/chat/" + convId).with(login).with(csrf()))
            .andExpect(status().isOk())
            .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("data-raw-content");
        assertThat(html).contains("4b7b0f51-6293-4cd6-8f4b-5a66adf42742");
        assertThat(html).contains("## References");

        // Cleanup
        setSecurityContext(userOid, "rendering-test@local", "Rendering Test User");
        chatConversationService.deleteConversation(convId);
    }

    @Test
    @DisplayName("Renders RAG installation prerequisites answer with chunk_id, document_id, and file citations via MockMvc")
    public void testThreadRenderingWithRagCitationsAndToolLogs() throws Exception {
        String userOid = "rendering-test-user-oid";
        userRepository.upsert(userOid, "rendering-test@local", "Rendering Test User");
        setSecurityContext(userOid, "rendering-test@local", "Rendering Test User");

        Conversation conv = chatConversationService.createConversation();
        UUID convId = conv.id();

        String ragContent = "### Installation Prerequisites > System Requirements > Database\n\nFor One Identity Manager version 9.3.1, the database server requires:\n\n* **Microsoft SQL Server 2019** [chunk_id=8f7c1a2b-3c4d-5e6f-7a8b-9c0d1e2f3a4b]\n* **RAM:** Minimum 16 GB [document_id=9a8b7c6d-1a2b-3c4d-5e6f-7a8b9c0d1e2f]\n* **Storage:** Fast SSD storage [file=oim_install_guide.pdf]\n\n🔧 Calling tool: search_corpus";

        Message assistantMessage = new Message(
            UUID.randomUUID(), convId, "assistant", ragContent,
            "oim-ask", List.of(), List.of(), Instant.now(),
            120, 60, 180, 0, 20, "done", "deepseek-v4-pro"
        );
        messageRepository.save(assistantMessage);

        var login = testUserLogin(userOid, "rendering-test@local", "Rendering Test User");

        MvcResult result = mockMvc.perform(get("/chat/" + convId).with(login).with(csrf()))
            .andExpect(status().isOk())
            .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("chunk_id=8f7c1a2b-3c4d-5e6f-7a8b-9c0d1e2f3a4b");
        assertThat(html).contains("document_id=9a8b7c6d-1a2b-3c4d-5e6f-7a8b9c0d1e2f");
        assertThat(html).contains("file=oim_install_guide.pdf");
        assertThat(html).contains("🔧 Calling tool:");

        // Cleanup
        setSecurityContext(userOid, "rendering-test@local", "Rendering Test User");
        chatConversationService.deleteConversation(convId);
    }

    @Test
    @DisplayName("Renders complex answers containing <think>, <clarifying-question>, Markdown tables via MockMvc")
    public void testThreadRenderingWithThinkingAndInteractiveCards() throws Exception {
        String userOid = "rendering-test-user-oid";
        userRepository.upsert(userOid, "rendering-test@local", "Rendering Test User");
        setSecurityContext(userOid, "rendering-test@local", "Rendering Test User");

        Conversation conv = chatConversationService.createConversation();
        UUID convId = conv.id();

        String complexContent = "<think>Analyzing graph relationships.</think>Here are the entity relations:\n\n| Counterpart | Relation |\n|---|---|\n| `PersonHasQERResource` | `ASSIGNED_TO` |\n\n<clarifying-question><question>Target environment?</question><option>Prod</option><option>Stage</option></clarifying-question>";

        Message assistantMessage = new Message(
            UUID.randomUUID(), convId, "assistant", complexContent,
            "oim-ask", List.of(), List.of(), Instant.now(),
            150, 80, 230, 0, 30, "done", "deepseek-v4-pro"
        );
        messageRepository.save(assistantMessage);

        var login = testUserLogin(userOid, "rendering-test@local", "Rendering Test User");

        MvcResult result = mockMvc.perform(get("/chat/" + convId).with(login).with(csrf()))
            .andExpect(status().isOk())
            .andReturn();

        String html = result.getResponse().getContentAsString();
        // The raw <think>/<clarifying-question> tags are parsed client-side (now in
        // static/js/chat/thread.js after the MNT-02 extraction), so they no longer appear literally
        // in the server HTML — the tag literals used to match the inline script. Assert instead on
        // the message *content* the server delivers to the page (survives HTML-escaping), which is
        // what this test really cares about: the complex answer round-trips into the render.
        assertThat(html).contains("Analyzing graph relationships");
        assertThat(html).contains("Target environment?");
        assertThat(html).contains("PersonHasQERResource");

        // Cleanup
        setSecurityContext(userOid, "rendering-test@local", "Rendering Test User");
        chatConversationService.deleteConversation(convId);
    }

    /**
     * The server-rendered profile dropdown hides a prototype profile — unless this conversation is
     * the one using it.
     *
     * <p>
     * The exemption is the whole point and it is the half a unit test cannot reach: the decision is
     * a Thymeleaf expression, so a wrong one produces a page that renders perfectly and simply lists
     * the wrong options. Without it a conversation set to a prototype profile paints a dropdown
     * whose selected option is hidden, which browsers render as the FIRST visible option — "Single
     * playbook" — over a conversation that is still in multi-agent mode. The user would read that as
     * their profile having been unset, and the only way to make the control tell the truth again is
     * to pick some other profile.
     *
     * <p>
     * Asserting on the rendered HTML rather than the model is deliberate: {@code threadView} sends
     * every profile flagged (pinned in {@code ChatControllerTest}), so the model is identical in
     * both cases and only the markup differs.
     */
    @Test
    @DisplayName("A prototype profile renders hidden, except when this conversation has selected it")
    public void thePrototypeProfileOptionIsHiddenUnlessThisConversationSelectedIt() throws Exception {
        String userOid = "profile-visibility-oid";
        userRepository.upsert(userOid, "profile-visibility@local", "Profile Visibility User");
        orchestratorProfileRepository.upsert(new OrchestratorProfile("IT_Vis_Ordinary", 2, 8,
            "orch", "rev", List.of("spec"), null, null, null, null, null, null, false, null, null));
        orchestratorProfileRepository.upsert(new OrchestratorProfile("IT_Vis_Prototype", 2, 8,
            "orch", "rev", List.of("spec"), null, null, null, null, null, null, true, null, null));

        setSecurityContext(userOid, "profile-visibility@local", "Profile Visibility User");
        UUID convId = chatConversationService.createConversation().id();
        var login = testUserLogin(userOid, "profile-visibility@local", "Profile Visibility User");

        String html = mockMvc.perform(get("/chat/" + convId).with(login).with(csrf()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(optionTag(html, "IT_Vis_Prototype"))
            .as("an unselected prototype profile must not be offered while the toggle is off")
            .contains("hidden");
        assertThat(optionTag(html, "IT_Vis_Ordinary")).doesNotContain("hidden");

        setSecurityContext(userOid, "profile-visibility@local", "Profile Visibility User");
        chatConversationService.updateSettings(convId,
            Map.of("orchestrator-profile", "IT_Vis_Prototype"));

        String selected = mockMvc.perform(get("/chat/" + convId).with(login).with(csrf()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(optionTag(selected, "IT_Vis_Prototype"))
            .as("the conversation's own profile stays listed, or the dropdown lies about the mode")
            .doesNotContain("hidden");
        assertThat(optionTag(selected, "IT_Vis_Prototype")).contains("selected");

        setSecurityContext(userOid, "profile-visibility@local", "Profile Visibility User");
        chatConversationService.deleteConversation(convId);
        orchestratorProfileRepository.delete("IT_Vis_Ordinary");
        orchestratorProfileRepository.delete("IT_Vis_Prototype");
    }

    /** The single {@code <option>} tag whose value is {@code profileName}, attributes included. */
    private static String optionTag(String html, String profileName) {
        Matcher m = Pattern.compile("<option[^>]*value=\"" + Pattern.quote(profileName) + "\"[^>]*>")
            .matcher(html);
        assertThat(m.find()).as("no <option> for profile " + profileName).isTrue();
        return m.group();
    }
}
