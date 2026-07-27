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
