package de.palsoftware.yvoke.shared.web.admin;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.palsoftware.yvoke.chat.core.repository.ConversationRepository;
import de.palsoftware.yvoke.shared.user.repository.UserRepository;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "app.security.mock=true")
public class ConversationsAdminIT {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    public void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        cleanup();
    }

    @AfterEach
    public void tearDown() {
        cleanup();
    }

    private void cleanup() {
        jdbcTemplate.update("DELETE FROM message_feedback WHERE message_id IN (SELECT id FROM messages WHERE conversation_id IN (SELECT id FROM conversations WHERE title LIKE 'CAIT-%'))");
        jdbcTemplate.update("DELETE FROM messages WHERE conversation_id IN (SELECT id FROM conversations WHERE title LIKE 'CAIT-%')");
        jdbcTemplate.update("DELETE FROM conversations WHERE title LIKE 'CAIT-%'");
        jdbcTemplate.update("DELETE FROM users WHERE entra_oid LIKE 'cait-%'");
    }

    private UUID createUser(String entraOid, String email, String displayName) {
        userRepository.upsert(entraOid, email, displayName);
        return userRepository.findByEntraOid(entraOid).orElseThrow().id();
    }

    private static OidcLoginRequestPostProcessor adminUser() {
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
        UUID userAId = createUser("cait-user-a-oid", "user-a@local", "User A");
        UUID convId = UUID.randomUUID();
        conversationRepository.create(convId, userAId, "CAIT-Conversation A", Map.of(), "web");

        // Ensure admin user is synced/exists in DB
        createUser("mock-admin-oid", "admin@local", "Admin User");

        // Admin should be able to view `/chat/{id}`
        mockMvc.perform(get("/chat/" + convId).with(adminUser())).andExpect(status().isOk());
    }

    @Test
    public void testAdminCannotSendMessageOrDeleteAnotherUserConversation() throws Exception {
        UUID userAId = createUser("cait-user-b-oid", "user-b@local", "User B");
        UUID convId = UUID.randomUUID();
        conversationRepository.create(convId, userAId, "CAIT-Conversation B", Map.of(), "web");

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
        UUID userAId = createUser("cait-user-c-oid", "user-c@local", "User C");
        UUID convId = UUID.randomUUID();
        conversationRepository.create(convId, userAId, "CAIT-Conversation C", Map.of(), "web");

        // Ensure admin user is synced/exists in DB
        createUser("mock-admin-oid", "admin@local", "Admin User");

        // Admin tries to update model of another user's conversation -> 403 Forbidden
        mockMvc.perform(post("/chat/" + convId + "/model").with(csrf()).param("model", "gpt-4o")
            .with(adminUser())).andExpect(status().isForbidden());
    }

    @Test
    public void testConversationsAdminHtmlElements_FiltersAndColspan() throws Exception {
        createUser("mock-admin-oid", "admin@local", "Admin User");

        mockMvc.perform(get("/admin/conversations")
                .param("userIds", UUID.randomUUID().toString())
                .with(adminUser()))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("filters-panel")))
            .andExpect(content().string(containsString("name=\"userIds\"")))
            .andExpect(content().string(containsString("value=\"anonymous\"")))
            .andExpect(content().string(containsString("name=\"timeRange\"")))
            .andExpect(content().string(containsString("name=\"fromDate\"")))
            .andExpect(content().string(containsString("name=\"toDate\"")))
            .andExpect(content().string(containsString("name=\"feedback\"")))
            .andExpect(content().string(containsString("Total Conversations")))
            .andExpect(content().string(containsString("Per-User Statistics")))
            .andExpect(content().string(containsString("Helpful (👍)")))
            .andExpect(content().string(containsString("Unhelpful (👎)")))
            .andExpect(content().string(containsString("<th>Feedback</th>")))
            .andExpect(content().string(containsString("colspan=\"7\"")));
    }

    @Test
    public void testConversationsAdminPaginationPreservesFilterParams() throws Exception {
        createUser("mock-admin-oid", "admin@local", "Admin User");
        UUID userAId = createUser("cait-user-e1-oid", "user-e1@local", "User E1");
        UUID userBId = createUser("cait-user-e2-oid", "user-e2@local", "User E2");

        for (int i = 0; i < 15; i++) {
            conversationRepository.create(UUID.randomUUID(), userAId, "CAIT-Pagination-" + i, Map.of(), "web");
        }
        for (int i = 15; i < 25; i++) {
            conversationRepository.create(UUID.randomUUID(), userBId, "CAIT-Pagination-" + i, Map.of(), "web");
        }

        mockMvc.perform(get("/admin/conversations")
                .param("userIds", userAId.toString(), userBId.toString())
                .param("timeRange", "custom")
                .param("fromDate", "2026-01-01")
                .param("toDate", "2026-12-31")
                .param("feedback", "all")
                .param("size", "20")
                .param("page", "0")
                .with(adminUser()))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("page=1")))
            .andExpect(content().string(containsString("size=20")))
            .andExpect(content().string(containsString("userIds=" + userAId)))
            .andExpect(content().string(containsString("userIds=" + userBId)))
            .andExpect(content().string(containsString("timeRange=custom")))
            .andExpect(content().string(containsString("fromDate=2026-01-01")))
            .andExpect(content().string(containsString("toDate=2026-12-31")))
            .andExpect(content().string(containsString("feedback=all")));
    }

    @Test
    public void testMalformedFilterParametersRejectedWith400() throws Exception {
        createUser("mock-admin-oid", "admin@local", "Admin User");

        mockMvc.perform(get("/admin/conversations").param("fromDate", "not-a-valid-date").with(adminUser()))
            .andExpect(status().isBadRequest());

        mockMvc.perform(get("/admin/conversations").param("userIds", "not-a-valid-uuid").with(adminUser()))
            .andExpect(status().isBadRequest());

        mockMvc.perform(get("/admin/conversations").param("feedback", "unknown-rating").with(adminUser()))
            .andExpect(status().isBadRequest());

        mockMvc.perform(get("/admin/conversations").param("timeRange", "unknown-range").with(adminUser()))
            .andExpect(status().isBadRequest());
    }

    @Test
    public void testConversationsAdminPerUserStatsTruncationHint() throws Exception {
        createUser("mock-admin-oid", "admin@local", "Admin User");
        OffsetDateTime statsTime = OffsetDateTime.of(2037, 2, 20, 12, 0, 0, 0, ZoneOffset.UTC);
        for (int i = 1; i <= 50; i++) {
            UUID uid = createUser("cait-stats50-" + i + "-oid", "cait-stats50-" + i + "@local", "CAIT User " + i);
            UUID convId = UUID.randomUUID();
            conversationRepository.create(convId, uid, "CAIT-Stats50-" + i, Map.of(), "web");
            jdbcTemplate.update(
                "UPDATE conversations SET created_at = ?, updated_at = ? WHERE id = ?",
                Timestamp.from(statsTime.toInstant()),
                Timestamp.from(statsTime.toInstant()),
                convId);
        }
        // 50 registered users + 1 anonymous conversation = 51 groups total
        UUID anonConvId = UUID.randomUUID();
        conversationRepository.create(anonConvId, null, "CAIT-Stats50-Anon", Map.of(), "web");
        jdbcTemplate.update(
            "UPDATE conversations SET created_at = ?, updated_at = ? WHERE id = ?",
            Timestamp.from(statsTime.toInstant()),
            Timestamp.from(statsTime.toInstant()),
            anonConvId);

        mockMvc.perform(get("/admin/conversations")
                .param("timeRange", "custom")
                .param("fromDate", "2037-02-20")
                .param("toDate", "2037-02-20")
                .with(adminUser()))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("(Top 50 of 51 users incl. anonymous)")));
    }

    @Test
    public void testConversationsAdminPerUserStatsTruncationHint_noAnonymousUsers() throws Exception {
        createUser("mock-admin-oid", "admin@local", "Admin User");
        OffsetDateTime statsTime = OffsetDateTime.of(2037, 2, 21, 12, 0, 0, 0, ZoneOffset.UTC);
        for (int i = 1; i <= 51; i++) {
            UUID uid = createUser("cait-stats51-" + i + "-oid", "cait-stats51-" + i + "@local", "CAIT User 51-" + i);
            UUID convId = UUID.randomUUID();
            conversationRepository.create(convId, uid, "CAIT-Stats51-" + i, Map.of(), "web");
            jdbcTemplate.update(
                "UPDATE conversations SET created_at = ?, updated_at = ? WHERE id = ?",
                Timestamp.from(statsTime.toInstant()),
                Timestamp.from(statsTime.toInstant()),
                convId);
        }

        mockMvc.perform(get("/admin/conversations")
                .param("timeRange", "custom")
                .param("fromDate", "2037-02-21")
                .param("toDate", "2037-02-21")
                .with(adminUser()))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("(Top 50 of 51 users)")))
            .andExpect(content().string(not(containsString("incl. anonymous"))));
    }
}
