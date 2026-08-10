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

    /**
     * A first bare rating IS stored, with a null comment.
     *
     * <p>
     * It used to be discarded — the comment was what created the row — and the buttons rendered the
     * vote as transient UI state, so a user clicked the thumb, watched it light up, and lost it on
     * reload. The desktop API meanwhile stores exactly this case (it demands a comment only for a
     * negative rating), so the single satisfaction ratio on the feedback dashboard was computed over
     * two different collection rules and systematically undercounted web positives. Both surfaces
     * now record a bare thumbs-up.
     */
    @Test
    public void aFirstRatingWithNoCommentIsPersistedWithANullComment() throws Exception {
        mockMvc.perform(post("/chat/message/" + messageId + "/feedback").with(csrf())
            .param("rating", "1").with(user(OWNER_OID))).andExpect(status().isOk());

        assertThat(persistedFeedbackCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT rating FROM message_feedback WHERE message_id = ?", Integer.class, messageId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT comment FROM message_feedback WHERE message_id = ?", String.class, messageId))
                .as("no comment was given, so none must be invented").isNull();
    }

    /**
     * And it survives a reload — the point of persisting it. The vote must come back from the
     * server, not from whatever the browser happened to still be showing.
     */
    @Test
    public void aBareRatingIsStillThereOnTheNextRead() throws Exception {
        mockMvc.perform(post("/chat/message/" + messageId + "/feedback").with(csrf())
            .param("rating", "-1").with(user(OWNER_OID))).andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject(
            "SELECT rating FROM message_feedback WHERE message_id = ?", Integer.class, messageId))
                .isEqualTo(-1);
    }

    /**
     * The feedback widget is authored TWICE — {@code feedbackButtonsMarkup()} in
     * {@code static/js/chat/thread-markup.js} builds it in the browser for an answer that has just
     * streamed in, and this Thymeleaf fragment re-renders it as the htmx swap response — and the
     * two copies have to agree on three attributes, because the second one replaces the first in
     * the live DOM.
     *
     * <p>
     * The container id is the swap address. Both copies emit
     * {@code id="feedback-container-<messageId>"} and both target
     * {@code #feedback-container-<messageId>}. Rename it on either side and htmx finds no target:
     * the click still posts and the vote is still stored, but nothing on screen changes, so the
     * user clicks again and again on a widget that already recorded their opinion — and the only
     * evidence is a console line from htmx.
     *
     * <p>
     * {@code hx-swap} is the other half, and it is the one that looks harmless. The response IS the
     * container, so it must be swapped as {@code outerHTML}. With {@code innerHTML} the returned
     * container is nested INSIDE the existing one: the document gains a duplicate
     * {@code feedback-container-<messageId>} id (so the next {@code #}-selector match is
     * ambiguous), one more nesting level per vote, and the outer copy keeps the stale
     * {@code active} class — the buttons show the previous vote forever.
     *
     * <p>
     * Both of these are pure-markup regressions: everything compiles, the endpoint returns 200 and
     * the row is written correctly, so every other test in this file stays green — they all assert
     * on the database. Only an assertion on the rendered attributes can see it. The two rating
     * buttons are checked separately on purpose: they are copy-pasted siblings, so editing one and
     * not the other is the likely mistake, and a body-wide {@code contains} would be satisfied by
     * whichever copy survived.
     */
    @Test
    public void theServerFeedbackFragmentKeepsTheSameSwapContractAsTheClientCopy()
        throws Exception {
        String body = mockMvc
            .perform(post("/chat/message/" + messageId + "/feedback").with(csrf())
                .param("rating", "1").with(user(OWNER_OID)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        // The swap address — byte-identical to the id thread-markup.js emits for this message.
        assertThat(body).contains("id=\"feedback-container-" + messageId + "\"");

        for (String buttonClass : List.of("feedback-thumbs-up", "feedback-thumbs-down")) {
            int start = body.indexOf(buttonClass);
            assertThat(start).as("the %s button must be rendered", buttonClass).isNotNegative();
            // The open tag only: an attribute has to sit on the button itself, not merely somewhere
            // in the fragment.
            String openTag = body.substring(start, body.indexOf('>', start));
            assertThat(openTag).as("%s must target the container", buttonClass)
                .contains("hx-target=\"#feedback-container-" + messageId + "\"");
            assertThat(openTag).as("%s must replace the container, not fill it", buttonClass)
                .contains("hx-swap=\"outerHTML\"");
        }
    }

    /**
     * The comment is the user's free text about an answer, and it MUST NOT ride in the URL. It used
     * to: the widget carried it as a query parameter, which writes whatever a user says about an
     * answer — usually quoting the answer, on a corpus of customer material — into the web server's
     * access log, the browser's history, and any {@code Referer} header sent onward from the page.
     * None of those are covered by the retention or access rules the {@code message_feedback} table
     * is covered by, and none of them can be cleaned up afterwards. That is why the thumbs buttons
     * send only a rating and the server preserves the stored comment
     * ({@code flippingTheRatingKeepsTheStoredCommentWithoutResendingIt}); this pins the other half,
     * the comment form itself, which posts the textarea in the request body.
     *
     * <p>
     * The trap is that re-adding it is a one-attribute edit that looks like a bug fix. The
     * re-rendered form does have to show the text the user already submitted, and hanging
     * {@code comment=${feedback.comment}} on the {@code th:hx-post} is the obvious way to make it
     * "come back" — while the textarea's {@code th:text}, three lines below, is what actually does
     * it. Both versions render a widget that looks and behaves identically, and both keep every
     * other test in this file green, because they all assert on the database and never on the
     * request the browser would send.
     *
     * <p>
     * The closing quote is part of the expected {@code hx-post} value on purpose: it is what turns
     * a substring check into an assertion that NOTHING follows the rating in the query string.
     */
    @Test
    public void theFeedbackCommentFormPostsItsTextInTheBodyNotTheQueryString() throws Exception {
        String body = mockMvc
            .perform(post("/chat/message/" + messageId + "/feedback").with(csrf())
                .param("rating", "-1").param("comment", "Answer was wrong.").with(user(OWNER_OID)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        int formStart = body.indexOf("<form");
        assertThat(formStart).as("the comment form must be re-rendered").isNotNegative();
        String formTag = body.substring(formStart, body.indexOf('>', formStart));

        assertThat(formTag)
            .contains("hx-post=\"/chat/message/" + messageId + "/feedback?rating=-1\"");
        assertThat(formTag).as("the comment must never travel as a query parameter")
            .doesNotContain("comment=");

        // The stored text comes back in the textarea, which htmx posts in the request body.
        int textareaStart = body.indexOf("<textarea");
        assertThat(textareaStart).as("the comment textarea must be re-rendered").isNotNegative();
        String textarea = body.substring(textareaStart, body.indexOf("</textarea>", textareaStart));
        assertThat(textarea).contains("name=\"comment\"").contains("Answer was wrong.");
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
