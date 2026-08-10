package de.palsoftware.yvoke.chat.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

import de.palsoftware.yvoke.chat.core.model.Conversation;
import de.palsoftware.yvoke.chat.core.service.ChatConversationService;
import de.palsoftware.yvoke.llm.core.model.LlmRequest;
import de.palsoftware.yvoke.llm.core.model.LlmResponse;
import de.palsoftware.yvoke.llm.core.model.LlmUsage;
import de.palsoftware.yvoke.llm.core.service.LlmClient;
import de.palsoftware.yvoke.rag.prompt.PlaybookService;
import de.palsoftware.yvoke.shared.user.repository.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.mockito.ArgumentCaptor;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "app.security.mock=true")
public class PlaybookValidationControllerIT {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ChatConversationService chatConversationService;

    @Autowired
    private PlaybookService playbookService;

    @MockitoBean(name = "llmProviderClient")
    private LlmClient llmClient;

    private MockMvc mockMvc;

    private List<UUID> conversationsToDelete = new ArrayList<>();

    @BeforeEach
    public void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        conversationsToDelete = new ArrayList<>();
    }

    @AfterEach
    public void tearDown() {
        SecurityContextHolder.clearContext();
        for (UUID conversationId : conversationsToDelete) {
            try {
                chatConversationService.deleteConversation(conversationId);
            } catch (Exception e) {
                // Ignore
            }
        }
        try {
            playbookService.deletePlaybook("correct-playbook");
        } catch (Exception e) {}
        try {
            playbookService.deletePlaybook("wrong-playbook");
        } catch (Exception e) {}
        try {
            playbookService.deletePlaybook("suggested-playbook");
        } catch (Exception e) {}
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

    private static OidcLoginRequestPostProcessor testUserLogin(String oid, String email, String name) {
        return oidcLogin()
            .idToken(token -> token.claim("oid", oid).claim("name", name).claim("email", email))
            .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Test
    public void testValidatePlaybookMatches() throws Exception {
        String userAOid = "user-a-validate-test-oid";
        userRepository.upsert(userAOid, "user-a-test@local", "User A");
        setSecurityContext(userAOid, "user-a-test@local", "User A");

        Conversation conv = chatConversationService.createConversation();
        conversationsToDelete.add(conv.id());

        playbookService.savePlaybook("correct-playbook", "Correct Playbook Title", "Correct Playbook Desc", "template", List.of(), false);

        when(llmClient.generate(any(LlmRequest.class)))
            .thenReturn(new LlmResponse("{\"plausible\": true, \"reason\": \"\", \"suggestedPlaybookName\": null}", new LlmUsage(0, 0, 0, 0, 0)));

        var login = testUserLogin(userAOid, "user-a-test@local", "User A");

        mockMvc.perform(post("/chat/" + conv.id() + "/validate-playbook")
                .with(login)
                .with(csrf())
                .param("content", "How do I query database?")
                .param("promptName", "correct-playbook"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.plausible").value(true))
            .andExpect(jsonPath("$.reason").value(""))
            .andExpect(jsonPath("$.suggestedPlaybookName").isEmpty());
    }

    @Test
    public void testValidatePlaybookDoesNotMatch() throws Exception {
        String userAOid = "user-a-validate-test-oid";
        userRepository.upsert(userAOid, "user-a-test@local", "User A");
        setSecurityContext(userAOid, "user-a-test@local", "User A");

        Conversation conv = chatConversationService.createConversation();
        conversationsToDelete.add(conv.id());

        playbookService.savePlaybook("wrong-playbook", "Wrong Playbook", "Wrong Playbook Desc", "template", List.of(), false);
        playbookService.savePlaybook("suggested-playbook", "Suggested Playbook", "Suggested Playbook Desc", "template", List.of(), false);

        when(llmClient.generate(any(LlmRequest.class)))
            .thenReturn(new LlmResponse("{\"plausible\": false, \"reason\": \"This question is about user setup.\", \"suggestedPlaybookName\": \"suggested-playbook\"}", new LlmUsage(0, 0, 0, 0, 0)));

        var login = testUserLogin(userAOid, "user-a-test@local", "User A");

        mockMvc.perform(post("/chat/" + conv.id() + "/validate-playbook")
                .with(login)
                .with(csrf())
                .param("content", "Setup a new user")
                .param("promptName", "wrong-playbook"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.plausible").value(false))
            .andExpect(jsonPath("$.reason").value("This question is about user setup."))
            .andExpect(jsonPath("$.suggestedPlaybookName").value("suggested-playbook"));
    }

    /**
     * The blank-content rejection sits BEFORE the try, and that position is the entire rule. The
     * body of this handler is deliberately fail-open: every exception is logged and answered with
     * {@code plausible=true, reason=""}, so that a validator outage can never stop a user sending a
     * message. That makes the try block a one-way converter from "error" to "your selection is
     * fine" — so moving the guard inside it (or wrapping the whole method in one big try, which is
     * the obvious tidy-up) does not soften the rejection, it DELETES it: a caller that posted an
     * empty question is told with a 200 that validation passed, and loses the only signal that it
     * sent nothing at all. The existing fallback test cannot tell the two apart, because the body
     * it asserts is byte-for-byte the body the broken version would return here; only the status
     * and the error message distinguish them. The provider assertion pins the second half: a
     * request that is invalid on its face must not reach a paid model, and after the move it would
     * only avoid doing so by accident of where in the try the guard happened to land.
     */
    @Test
    public void blankContentIsRejectedWithFourHundredRatherThanFailingOpen() throws Exception {
        String userAOid = "user-a-validate-test-oid";
        userRepository.upsert(userAOid, "user-a-test@local", "User A");
        setSecurityContext(userAOid, "user-a-test@local", "User A");

        Conversation conv = chatConversationService.createConversation();
        conversationsToDelete.add(conv.id());

        var login = testUserLogin(userAOid, "user-a-test@local", "User A");

        mockMvc.perform(post("/chat/" + conv.id() + "/validate-playbook")
                .with(login)
                .with(csrf())
                .param("content", " ")
                .param("promptName", "correct-playbook"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Content cannot be blank"));

        verify(llmClient, never()).generate(any(LlmRequest.class));
    }

    @Test
    public void testValidatePlaybookAccessDenied() throws Exception {
        String userAOid = "user-a-validate-test-oid";
        userRepository.upsert(userAOid, "user-a-test@local", "User A");
        setSecurityContext(userAOid, "user-a-test@local", "User A");

        Conversation conv = chatConversationService.createConversation();
        conversationsToDelete.add(conv.id());

        String userBOid = "user-b-validate-test-oid";
        userRepository.upsert(userBOid, "user-b-test@local", "User B");
        var loginB = testUserLogin(userBOid, "user-b-test@local", "User B");

        mockMvc.perform(post("/chat/" + conv.id() + "/validate-playbook")
                .with(loginB)
                .with(csrf())
                .param("content", "Hello")
                .param("promptName", "some-playbook"))
            .andExpect(status().isForbidden());
    }

    /**
     * This is a preflight that runs before EVERY message, not a chat turn, and each parameter on it
     * is load-bearing in a way that fails silently when it is wrong. The reply is deserialized
     * straight into {@code ValidationResponse} with no tolerance for prose, so without
     * {@code responseMimeType=application/json} the model is free to answer conversationally or in
     * a fenced block; {@code readValue} then throws inside the fail-open catch, the endpoint
     * answers 200 {@code plausible=true}, and playbook validation is off for every user with no
     * error anywhere and no change to any status code. Temperature 0.0 keeps the same question
     * getting the same verdict instead of a coin flip on borderline routing; the 500-token cap and
     * {@code low} thinking keep a check that precedes every message from costing more than the
     * answer it precedes. The empty tool list matters twice over: a validator has no business
     * searching the corpus, and {@code responseMimeType} is documented on {@code LlmRequest} as
     * "only honored when no tools are supplied" — so adding a single tool would switch JSON mode
     * off without touching the line that sets it. No existing test looks at the request at all;
     * they all stub the client with {@code any(LlmRequest.class)}.
     */
    @Test
    public void thePreflightCallPinsJsonModeAndOffersNoTools() throws Exception {
        String userAOid = "user-a-validate-test-oid";
        userRepository.upsert(userAOid, "user-a-test@local", "User A");
        setSecurityContext(userAOid, "user-a-test@local", "User A");

        Conversation conv = chatConversationService.createConversation();
        conversationsToDelete.add(conv.id());

        playbookService.savePlaybook("correct-playbook", "Correct Playbook Title", "Correct Playbook Desc", "template", List.of(), false);

        when(llmClient.generate(any(LlmRequest.class)))
            .thenReturn(new LlmResponse("{\"plausible\": true, \"reason\": \"\", \"suggestedPlaybookName\": null}", new LlmUsage(0, 0, 0, 0, 0)));

        var login = testUserLogin(userAOid, "user-a-test@local", "User A");

        mockMvc.perform(post("/chat/" + conv.id() + "/validate-playbook")
                .with(login)
                .with(csrf())
                .param("content", "How do I query database?")
                .param("promptName", "correct-playbook"))
            .andExpect(status().isOk());

        ArgumentCaptor<LlmRequest> sent = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmClient).generate(sent.capture());
        LlmRequest request = sent.getValue();

        assertThat(request.responseMimeType())
            .as("the reply is parsed directly, so prose or a fenced block would fail open to 'ok'")
            .isEqualTo("application/json");
        assertThat(request.temperature())
            .as("the same question must not get a different verdict on a re-ask").isEqualTo(0.0);
        assertThat(request.maxTokens())
            .as("a check that runs before every message must stay cheap").isEqualTo(500);
        assertThat(request.thinkingLevel()).isEqualTo("low");
        assertThat(request.tools())
            .as("a validator must not search, and any tool would disable JSON mode").isEmpty();
    }

    @Test
    public void testValidatePlaybookFallbackOnException() throws Exception {
        String userAOid = "user-a-validate-test-oid";
        userRepository.upsert(userAOid, "user-a-test@local", "User A");
        setSecurityContext(userAOid, "user-a-test@local", "User A");

        Conversation conv = chatConversationService.createConversation();
        conversationsToDelete.add(conv.id());

        when(llmClient.generate(any(LlmRequest.class)))
            .thenThrow(new RuntimeException("LLM service offline"));

        var login = testUserLogin(userAOid, "user-a-test@local", "User A");

        mockMvc.perform(post("/chat/" + conv.id() + "/validate-playbook")
                .with(login)
                .with(csrf())
                .param("content", "Testing fallback")
                .param("promptName", "some-playbook"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.plausible").value(true))
            .andExpect(jsonPath("$.reason").value(""))
            .andExpect(jsonPath("$.suggestedPlaybookName").isEmpty());
    }

    @Test
    public void testValidatePlaybookSuggestedDoesNotExistFallback() throws Exception {
        String userAOid = "user-a-validate-test-oid";
        userRepository.upsert(userAOid, "user-a-test@local", "User A");
        setSecurityContext(userAOid, "user-a-test@local", "User A");

        Conversation conv = chatConversationService.createConversation();
        conversationsToDelete.add(conv.id());

        playbookService.savePlaybook("wrong-playbook", "Wrong Playbook", "Wrong Playbook Desc", "template", List.of(), false);

        when(llmClient.generate(any(LlmRequest.class)))
            .thenReturn(new LlmResponse("{\"plausible\": false, \"reason\": \"Bad selection.\", \"suggestedPlaybookName\": \"non-existent-playbook\"}", new LlmUsage(0, 0, 0, 0, 0)));

        var login = testUserLogin(userAOid, "user-a-test@local", "User A");

        mockMvc.perform(post("/chat/" + conv.id() + "/validate-playbook")
                .with(login)
                .with(csrf())
                .param("content", "Hello")
                .param("promptName", "wrong-playbook"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.plausible").value(false))
            .andExpect(jsonPath("$.reason").value("Bad selection."))
            .andExpect(jsonPath("$.suggestedPlaybookName").isEmpty());
    }
}
