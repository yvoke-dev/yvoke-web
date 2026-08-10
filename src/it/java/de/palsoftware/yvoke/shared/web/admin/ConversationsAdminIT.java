package de.palsoftware.yvoke.shared.web.admin;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import de.palsoftware.yvoke.chat.core.repository.ConversationRepository;
import de.palsoftware.yvoke.shared.user.repository.UserRepository;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "app.security.mock=true")
public class ConversationsAdminIT {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    private MockMvc mockMvc;

    @BeforeEach
    public void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private UUID createUser(String entraOid, String email, String displayName) {
        userRepository.upsert(entraOid, email, displayName);
        return userRepository.findByEntraOid(entraOid).orElseThrow().id();
    }

    private static SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor adminUser() {
        return oidcLogin().idToken(token -> token.claim("oid", "mock-admin-oid")).authorities(
            new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Test
    public void testAccessToConversationsRestrictedToAdmins() throws Exception {
        mockMvc
            .perform(get("/admin/conversations")
                .with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
            .andExpect(status().isForbidden());

        mockMvc
            .perform(get("/admin/conversations")
                .with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
            .andExpect(status().isOk());
    }

    @Test
    public void testAdminCanReadAnotherUserConversation() throws Exception {
        UUID userAId = createUser("user-a-oid", "user-a@local", "User A");
        UUID convId = UUID.randomUUID();
        conversationRepository.create(convId, userAId, "Conversation A", Map.of(), "web");

        // Ensure admin user is synced/exists in DB
        createUser("mock-admin-oid", "admin@local", "Admin User");

        // Admin should be able to view `/chat/{id}`
        mockMvc.perform(get("/chat/" + convId).with(adminUser())).andExpect(status().isOk());
    }

    @Test
    public void testAdminCannotSendMessageOrDeleteAnotherUserConversation() throws Exception {
        UUID userAId = createUser("user-b-oid", "user-b@local", "User B");
        UUID convId = UUID.randomUUID();
        conversationRepository.create(convId, userAId, "Conversation B", Map.of(), "web");

        // Ensure admin user is synced/exists in DB
        createUser("mock-admin-oid", "admin@local", "Admin User");

        // Admin tries to send a message to another user's conversation -> 403 Forbidden
        mockMvc.perform(post("/chat/" + convId + "/send").with(csrf()).param("content", "Hello")
            .with(adminUser())).andExpect(status().isForbidden());

        // Admin tries to delete another user's conversation -> 403 Forbidden
        mockMvc.perform(post("/chat/" + convId + "/delete").with(csrf()).with(adminUser()))
            .andExpect(status().isForbidden());
    }

    @Test
    public void testAdminCannotUpdateModelOrSettingsOfAnotherUserConversation() throws Exception {
        UUID userAId = createUser("user-c-oid", "user-c@local", "User C");
        UUID convId = UUID.randomUUID();
        conversationRepository.create(convId, userAId, "Conversation C", Map.of(), "web");

        // Ensure admin user is synced/exists in DB
        createUser("mock-admin-oid", "admin@local", "Admin User");

        // Admin tries to update model of another user's conversation -> 403 Forbidden
        mockMvc.perform(post("/chat/" + convId + "/model").with(csrf()).param("model", "gpt-4o")
            .with(adminUser())).andExpect(status().isForbidden());
    }
}
