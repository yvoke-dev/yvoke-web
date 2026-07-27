package de.palsoftware.yvoke.chat.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
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
import de.palsoftware.yvoke.llm.core.service.LlmClient;
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
    properties = { "app.security.mock=true", "app.chat.playbook-validation-enabled=false" })
public class PlaybookValidationDisabledIT {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ChatConversationService chatConversationService;

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
    public void testValidatePlaybookWhenDisabled() throws Exception {
        String userAOid = "user-a-validate-disabled-oid";
        userRepository.upsert(userAOid, "user-a-disabled@local", "User A");
        setSecurityContext(userAOid, "user-a-disabled@local", "User A");

        Conversation conv = chatConversationService.createConversation();
        conversationsToDelete.add(conv.id());

        var login = testUserLogin(userAOid, "user-a-disabled@local", "User A");

        mockMvc.perform(post("/chat/" + conv.id() + "/validate-playbook")
                .with(login)
                .with(csrf())
                .param("content", "How do I query database?")
                .param("promptName", "correct-playbook"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.plausible").value(true))
            .andExpect(jsonPath("$.reason").value(""))
            .andExpect(jsonPath("$.suggestedPlaybookName").isEmpty());

        verifyNoInteractions(llmClient);
    }
}
