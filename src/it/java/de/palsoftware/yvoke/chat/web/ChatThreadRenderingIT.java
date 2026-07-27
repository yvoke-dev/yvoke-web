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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "app.security.mock=true")
public class ChatThreadRenderingIT {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ChatConversationService chatConversationService;

    @Autowired
    private MessageRepository messageRepository;

    private MockMvc mockMvc;

    @BeforeEach
    public void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    public void tearDown() {
        SecurityContextHolder.clearContext();
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

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor testUserLogin(String oid, String email, String name) {
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
}
