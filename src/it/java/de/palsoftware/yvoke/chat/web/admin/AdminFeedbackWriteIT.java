package de.palsoftware.yvoke.chat.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.palsoftware.yvoke.chat.core.model.Message;
import de.palsoftware.yvoke.chat.core.repository.ConversationRepository;
import de.palsoftware.yvoke.chat.core.repository.MessageRepository;
import de.palsoftware.yvoke.shared.user.repository.UserRepository;
import java.time.Instant;
import java.util.List;
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
import org.hamcrest.Matchers;

/**
 * The feedback dashboard's two htmx write cells and its filter/sort controls.
 *
 * <p>{@code AdminFeedbackController} and {@code ChatAdminQueryRepository} had no tests whatsoever, so
 * the {@code sort} and {@code timeRange} SQL branches were dead to the suite and the two write
 * endpoints were never called. The writes return <em>fragments</em> ({@code :: reviewed-cell},
 * {@code :: notes-cell}), which is exactly what a whole-page GET can never exercise: a fragment renders
 * a sub-tree against a model containing only two attributes, so a stale expression in it fails here
 * and nowhere else.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "app.security.mock=true")
public class AdminFeedbackWriteIT {

    private static final String OID = "it-feedback-owner";

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

    private UUID feedbackId;
    private UUID conversationId;

    @BeforeEach
    public void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        userRepository.upsert(OID, OID + "@local", "IT Feedback Owner");
        UUID userId = userRepository.findByEntraOid(OID).orElseThrow().id();
        conversationId = UUID.randomUUID();
        conversationRepository.create(conversationId, userId, "IT feedback conversation", Map.of(),
            "web");
        UUID messageId = UUID.randomUUID();
        messageRepository.save(new Message(messageId, conversationId, "assistant", "An answer.",
            List.of(), List.of(), Instant.now()));
        feedbackId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO message_feedback (id, message_id, rating, comment) "
                + "VALUES (?, ?, -1, 'Needs work.')",
            feedbackId, messageId);
    }

    @AfterEach
    public void tearDown() {
        jdbcTemplate.update("DELETE FROM conversations WHERE id = ?", conversationId);
    }

    private static OidcLoginRequestPostProcessor admin() {
        return oidcLogin().idToken(token -> token.claim("oid", "it-feedback-admin")).authorities(
            new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("ROLE_USER"));
    }

    private static OidcLoginRequestPostProcessor plainUser() {
        return oidcLogin().idToken(token -> token.claim("oid", "it-feedback-user"))
            .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    private Boolean reviewedFlag() {
        return jdbcTemplate.queryForObject("SELECT reviewed FROM message_feedback WHERE id = ?",
            Boolean.class, feedbackId);
    }

    @Test
    public void togglingReviewedPersistsAndReturnsTheCellFragment() throws Exception {
        assertThat(reviewedFlag()).isFalse();

        mockMvc
            .perform(post("/admin/feedback/" + feedbackId + "/toggle-reviewed").with(csrf())
                .with(admin()).param("reviewed", "true").header("HX-Request", "true"))
            .andExpect(status().isOk());

        assertThat(reviewedFlag()).isTrue();
    }

    /** The checkbox posts {@code on}, not {@code true}, when a browser submits it. */
    @Test
    public void theCheckboxOnValueIsAlsoAcceptedAsReviewed() throws Exception {
        mockMvc
            .perform(post("/admin/feedback/" + feedbackId + "/toggle-reviewed").with(csrf())
                .with(admin()).param("reviewed", "on").header("HX-Request", "true"))
            .andExpect(status().isOk());

        assertThat(reviewedFlag()).isTrue();
    }

    @Test
    public void anAbsentReviewedParamClearsTheFlag() throws Exception {
        jdbcTemplate.update("UPDATE message_feedback SET reviewed = true WHERE id = ?", feedbackId);

        mockMvc.perform(post("/admin/feedback/" + feedbackId + "/toggle-reviewed").with(csrf())
            .with(admin()).header("HX-Request", "true")).andExpect(status().isOk());

        assertThat(reviewedFlag()).isFalse();
    }

    @Test
    public void savingNotesPersistsAndReturnsTheCellFragment() throws Exception {
        mockMvc
            .perform(post("/admin/feedback/" + feedbackId + "/notes").with(csrf()).with(admin())
                .param("notes", "Chased with the author.").header("HX-Request", "true"))
            .andExpect(status().isOk())
            .andExpect(content().string(Matchers.containsString("Chased with")));

        assertThat(jdbcTemplate.queryForObject("SELECT notes FROM message_feedback WHERE id = ?",
            String.class, feedbackId)).isEqualTo("Chased with the author.");
    }

    @Test
    public void bothFeedbackWritesRequireAdmin() throws Exception {
        mockMvc.perform(post("/admin/feedback/" + feedbackId + "/toggle-reviewed").with(csrf())
            .with(plainUser()).param("reviewed", "true")).andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/feedback/" + feedbackId + "/notes").with(csrf())
            .with(plainUser()).param("notes", "nope")).andExpect(status().isForbidden());

        assertThat(reviewedFlag()).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT notes FROM message_feedback WHERE id = ?",
            String.class, feedbackId)).isNull();
    }

    /**
     * Every non-default value of the dashboard's filter and sort controls, which no test had ever
     * supplied — so the {@code oldest} ordering and all three {@code timeRange} cutoffs were unreached
     * SQL. A dropped bind or a typo'd column name only surfaces when the branch actually runs.
     */
    @Test
    public void everyFilterAndSortCombinationRenders() throws Exception {
        for (String sort : new String[] {"newest", "oldest"}) {
            for (String timeRange : new String[] {"all", "day", "week", "month"}) {
                mockMvc
                    .perform(get("/admin/feedback").param("sort", sort)
                        .param("timeRange", timeRange).with(admin()))
                    .andExpect(status().isOk());
            }
        }
        for (String rating : new String[] {"1", "-1"}) {
            mockMvc.perform(get("/admin/feedback").param("rating", rating).with(admin()))
                .andExpect(status().isOk());
        }
        for (String reviewed : new String[] {"true", "false"}) {
            mockMvc.perform(get("/admin/feedback").param("reviewed", reviewed).with(admin()))
                .andExpect(status().isOk());
        }
    }

    /**
     * Pagination past page 0 — the offset arithmetic and the {@code totalPages > 1} block were never
     * rendered by any test, on any admin list.
     */
    @Test
    public void theSecondPageOfTheDashboardRenders() throws Exception {
        mockMvc.perform(get("/admin/feedback").param("page", "1").param("size", "1").with(admin()))
            .andExpect(status().isOk());
    }
}
