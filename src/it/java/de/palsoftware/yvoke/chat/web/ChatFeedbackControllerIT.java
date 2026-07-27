package de.palsoftware.yvoke.chat.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.palsoftware.yvoke.chat.core.model.Message;
import de.palsoftware.yvoke.chat.core.repository.ConversationRepository;
import de.palsoftware.yvoke.chat.core.repository.MessageRepository;
import de.palsoftware.yvoke.shared.user.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * Ownership regression tests for {@code POST /chat/message/{messageId}/feedback}. The endpoint takes a
 * message id straight from the path under a chain that only requires {@code hasRole("USER")}
 * (SecurityConfig), so the only thing standing between a user and another user's messages is the
 * service-level {@code verifyOwnership} call — which had no test at any tier, and which the
 * rating-only request path used to skip entirely by never reaching the service.
 *
 * <p>Feedback is a <em>write</em>, so the codebase's "admins may read but not write another user's
 * conversation" invariant applies: an admin is denied here exactly as on {@code /chat/{id}/send} and
 * {@code /chat/{id}/model} (see {@code ConversationsAdminIT}).
 *
 * <p>Deliberately annotated identically to {@code ConversationsAdminIT} so it reuses that cached
 * Spring context rather than minting a new one — see the context-cache pitfall in CLAUDE.md.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "app.security.mock=true")
public class ChatFeedbackControllerIT {

    private static final String OWNER_OID = "feedback-owner-oid";
    private static final String OTHER_OID = "feedback-other-oid";
    private static final String ADMIN_OID = "feedback-admin-oid";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    private UUID messageId;

    @BeforeEach
    public void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        UUID ownerId = createUser(OWNER_OID);
        createUser(OTHER_OID);
        createUser(ADMIN_OID);

        UUID conversationId = UUID.randomUUID();
        conversationRepository.create(conversationId, ownerId, "Feedback owner conversation",
            Map.of(), "web");
        messageId = UUID.randomUUID();
        messageRepository.save(new Message(messageId, conversationId, "assistant", "An answer.",
            List.of(), List.of(), Instant.now()));
    }

    private UUID createUser(String entraOid) {
        userRepository.upsert(entraOid, entraOid + "@local", entraOid);
        return userRepository.findByEntraOid(entraOid).orElseThrow().id();
    }

    private static OidcLoginRequestPostProcessor user(String oid) {
        return oidcLogin().idToken(token -> token.claim("oid", oid))
            .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    private static OidcLoginRequestPostProcessor admin(String oid) {
        return oidcLogin().idToken(token -> token.claim("oid", oid)).authorities(
            new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("ROLE_USER"));
    }

    private int persistedFeedbackCount() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM message_feedback WHERE message_id = ?", Integer.class, messageId);
        return count == null ? 0 : count;
    }

    @Test
    public void ownerCanSubmitACommentAndItIsPersisted() throws Exception {
        mockMvc
            .perform(post("/chat/message/" + messageId + "/feedback").with(csrf())
                .param("rating", "-1").param("comment", "Answer was wrong.").with(user(OWNER_OID)))
            .andExpect(status().isOk());

        assertThat(persistedFeedbackCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT comment FROM message_feedback WHERE message_id = ?", String.class, messageId))
                .isEqualTo("Answer was wrong.");
    }

    @Test
    public void anotherUserCannotSubmitACommentOnSomeoneElsesMessage() throws Exception {
        mockMvc
            .perform(post("/chat/message/" + messageId + "/feedback").with(csrf())
                .param("rating", "-1").param("comment", "Not mine.").with(user(OTHER_OID)))
            .andExpect(status().isForbidden());

        assertThat(persistedFeedbackCount()).isZero();
    }

    /**
     * The bare-rating request the thumbs buttons send. It persists nothing by design, but it used to
     * bypass the service altogether — so it neither authorized the caller nor checked the message
     * existed, and happily reflected a fragment for any id.
     */
    @Test
    public void anotherUserCannotEvenSubmitARatingOnSomeoneElsesMessage() throws Exception {
        mockMvc.perform(post("/chat/message/" + messageId + "/feedback").with(csrf())
            .param("rating", "1").with(user(OTHER_OID))).andExpect(status().isForbidden());
    }

    @Test
    public void adminCannotSubmitFeedbackOnAnotherUsersMessage() throws Exception {
        mockMvc
            .perform(post("/chat/message/" + messageId + "/feedback").with(csrf())
                .param("rating", "-1").param("comment", "Admins may read, not write.")
                .with(admin(ADMIN_OID)))
            .andExpect(status().isForbidden());

        mockMvc.perform(post("/chat/message/" + messageId + "/feedback").with(csrf())
            .param("rating", "1").with(admin(ADMIN_OID))).andExpect(status().isForbidden());

        assertThat(persistedFeedbackCount()).isZero();
    }

    @Test
    public void anUnknownMessageIdIsRejectedRatherThanReflectedBack() throws Exception {
        mockMvc.perform(post("/chat/message/" + UUID.randomUUID() + "/feedback").with(csrf())
            .param("rating", "1").with(user(OWNER_OID))).andExpect(status().isNotFound());
    }

    /**
     * The thumbs buttons no longer send the comment back (it used to ride in the request URL), so
     * flipping a rating after commenting relies on the server keeping the stored text.
     */
    @Test
    public void flippingTheRatingKeepsTheStoredCommentWithoutResendingIt() throws Exception {
        mockMvc
            .perform(post("/chat/message/" + messageId + "/feedback").with(csrf())
                .param("rating", "-1").param("comment", "Answer was wrong.").with(user(OWNER_OID)))
            .andExpect(status().isOk());

        mockMvc.perform(post("/chat/message/" + messageId + "/feedback").with(csrf())
            .param("rating", "1").with(user(OWNER_OID))).andExpect(status().isOk());

        assertThat(persistedFeedbackCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT rating FROM message_feedback WHERE message_id = ?", Integer.class, messageId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT comment FROM message_feedback WHERE message_id = ?", String.class, messageId))
                .isEqualTo("Answer was wrong.");
    }

    /** A first bare rating is intentionally not stored — the comment is what creates the row. */
    @Test
    public void aFirstRatingWithNoCommentIsNotPersisted() throws Exception {
        mockMvc.perform(post("/chat/message/" + messageId + "/feedback").with(csrf())
            .param("rating", "1").with(user(OWNER_OID))).andExpect(status().isOk());

        assertThat(persistedFeedbackCount()).isZero();
    }

    @Test
    public void aRatingThatIsNotAVoteIsRejected() throws Exception {
        mockMvc
            .perform(post("/chat/message/" + messageId + "/feedback").with(csrf())
                .param("rating", "5").param("comment", "Five stars.").with(user(OWNER_OID)))
            .andExpect(status().isBadRequest());

        assertThat(persistedFeedbackCount()).isZero();
    }
}
