package de.palsoftware.yvoke.chat.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import de.palsoftware.yvoke.chat.core.model.Conversation;
import de.palsoftware.yvoke.chat.core.service.ChatConversationService;
import de.palsoftware.yvoke.shared.user.repository.UserRepository;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "app.security.mock=true")
public class ChatControllerIT {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ChatConversationService chatConversationService;

    private MockMvc mockMvc;

    @BeforeEach
    public void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    public void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @SuppressWarnings("unchecked")
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
    @SuppressWarnings("unchecked")
    public void testConversationGroupingLogic() throws Exception {
        // 1. Setup user A
        String userAOid = "user-a-controller-test-oid";
        userRepository.upsert(userAOid, "user-a-test@local", "User A");
        setSecurityContext(userAOid, "user-a-test@local", "User A");

        // Create User A's private untagged conversation
        Conversation privateUntagged = chatConversationService.createConversation();
        UUID privateUntaggedId = privateUntagged.id();

        // Create User A's private tagged conversation
        Conversation privateTagged = chatConversationService.createConversation();
        UUID privateTaggedId = privateTagged.id();
        chatConversationService.addTag(privateTaggedId, "private-tag");

        // Create User A's public conversation with other tags
        Conversation publicTagged = chatConversationService.createConversation();
        UUID publicTaggedId = publicTagged.id();
        chatConversationService.addTag(publicTaggedId, "public");
        chatConversationService.addTag(publicTaggedId, "tutorial");

        // Create User A's public untagged conversation (only has 'public' tag)
        Conversation publicUntagged = chatConversationService.createConversation();
        UUID publicUntaggedId = publicUntagged.id();
        chatConversationService.addTag(publicUntaggedId, "public");

        // 2. Setup user B to create a public conversation owned by someone else
        String userBOid = "user-b-controller-test-oid";
        userRepository.upsert(userBOid, "user-b-test@local", "User B");
        setSecurityContext(userBOid, "user-b-test@local", "User B");

        Conversation otherPublic = chatConversationService.createConversation();
        UUID otherPublicId = otherPublic.id();
        chatConversationService.addTag(otherPublicId, "public");
        chatConversationService.addTag(otherPublicId, "shared-tag");

        // 3. Request the /chat index as User A and verify grouping
        setSecurityContext(userAOid, "user-a-test@local", "User A");
        var loginA = testUserLogin(userAOid, "user-a-test@local", "User A");

        MvcResult result = mockMvc.perform(get("/chat").with(loginA))
            .andExpect(status().isOk())
            .andExpect(model().attributeExists("folders"))
            .andExpect(model().attributeExists("untagged"))
            .andExpect(model().attributeExists("publicFolders"))
            .andExpect(model().attributeExists("publicUntagged"))
            .andReturn();

        Map<String, List<Conversation>> folders = (Map<String, List<Conversation>>) result.getModelAndView().getModel().get("folders");
        List<Conversation> untagged = (List<Conversation>) result.getModelAndView().getModel().get("untagged");
        Map<String, List<Conversation>> publicFolders = (Map<String, List<Conversation>>) result.getModelAndView().getModel().get("publicFolders");
        List<Conversation> publicUntaggedList = (List<Conversation>) result.getModelAndView().getModel().get("publicUntagged");

        // Assertions on private/owned section:
        assertThat(folders).containsKey("private-tag");
        assertThat(folders.get("private-tag")).extracting(Conversation::id).containsExactly(privateTaggedId);
        assertThat(folders).doesNotContainKey("public");
        assertThat(folders).doesNotContainKey("tutorial");
        assertThat(folders).doesNotContainKey("shared-tag");

        assertThat(untagged).extracting(Conversation::id).containsExactly(privateUntaggedId);

        // Assertions on public section:
        assertThat(publicFolders).containsKey("tutorial");
        assertThat(publicFolders.get("tutorial")).extracting(Conversation::id).containsExactly(publicTaggedId);
        assertThat(publicFolders).containsKey("shared-tag");
        assertThat(publicFolders.get("shared-tag")).extracting(Conversation::id).containsExactly(otherPublicId);

        assertThat(publicUntaggedList).extracting(Conversation::id).contains(publicUntaggedId);

        // Cleanup User A's conversations
        setSecurityContext(userAOid, "user-a-test@local", "User A");
        chatConversationService.deleteConversation(privateUntaggedId);
        chatConversationService.deleteConversation(privateTaggedId);
        chatConversationService.deleteConversation(publicTaggedId);
        chatConversationService.deleteConversation(publicUntaggedId);

        // Cleanup User B's conversation
        setSecurityContext(userBOid, "user-b-test@local", "User B");
        chatConversationService.deleteConversation(otherPublicId);
    }

    /**
     * {@code MvcExceptionHandler} turns a failed request into a 302 + flash message, and whether it
     * does so is decided by the SHAPE of the request, not by the endpoint. That decision has to keep
     * working for {@code ChatController}, because {@code ChatController} is a plain {@code @Controller}
     * (so the JSON {@code ApiExceptionHandler}, which is selected by {@code @RestController}, does not
     * cover it) and several of its endpoints are {@code @ResponseBody void} handlers called from bare
     * {@code fetch()} — {@code thread.js} posts {@code /chat/{id}/streaming} and
     * {@code /chat/{id}/show-thinking} that way, with the default {@code Accept: *}{@code /*}.
     *
     * <p>
     * Widen {@code isNavigationalFormPost} — or just drop its call from {@code handleUnexpected},
     * which reads as "we always want the friendly error page" — and these fetches stop getting a
     * status at all: they get a 302 into an HTML page, which {@code fetch} follows silently and
     * reports as {@code response.ok === true}. The browser then believes it saved a setting it did
     * not save, and the flash message it was redirected to is attached to a page nobody is looking
     * at, so the failure is invisible on both sides. A 400 is the only answer that lets the client
     * know the value was rejected.
     *
     * <p>
     * The second stanza is what makes the first discriminating: inverting the predicate so it is
     * always false would leave the 400 intact while quietly deleting the flash-redirect behaviour
     * every admin form depends on. {@code MvcExceptionHandlerTest} covers the predicate against a
     * synthetic probe controller, and asserts only that the exception PROPAGATES
     * ({@code fetchPostWithoutHtmlAcceptIsNotHijackedIntoARedirect} does
     * {@code hasRootCauseInstanceOf}); it never asserts the status the framework then produces, and
     * it never runs against a real chat route, so the end-to-end "malformed flag reaches the browser
     * as 400" contract is untested without this.
     */
    @Test
    public void aNonBooleanStreamingFlagIsAFourHundredAndIsNeverSwallowedIntoAFlashRedirect()
        throws Exception {
        // No conversation is needed: @RequestParam binding fails before the handler body runs, so
        // nothing touches the database and no ownership check is reached.
        UUID convId = UUID.randomUUID();
        var login = testUserLogin("user-a-controller-test-oid", "user-a-test@local", "User A");

        // Spring's StringToBooleanConverter accepts true/on/yes/1 and false/off/no/0; "maybe" is
        // outside that set, so binding raises MethodArgumentTypeMismatchException.
        mockMvc
            .perform(post("/chat/" + convId + "/streaming").with(login).with(csrf())
                .header("Accept", "*/*").param("enabled", "maybe"))
            .andExpect(status().isBadRequest()).andExpect(flash().attributeCount(0));

        // Same URL, classic browser form shape: this one IS meant to be swallowed into a
        // redirect-back with a flash error.
        mockMvc
            .perform(post("/chat/" + convId + "/streaming").with(login).with(csrf())
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Referer", "http://localhost/chat/" + convId).param("enabled", "maybe"))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/chat/" + convId))
            .andExpect(flash().attributeExists("error"));
    }

    @Test
    public void testSendMessageSseThrowsIfNoPlaybook() throws Exception {
        String userAOid = "user-a-controller-test-oid";
        userRepository.upsert(userAOid, "user-a-test@local", "User A");
        setSecurityContext(userAOid, "user-a-test@local", "User A");

        Conversation conv = chatConversationService.createConversation();
        UUID convId = conv.id();
        var loginA = testUserLogin(userAOid, "user-a-test@local", "User A");

        mockMvc.perform(post("/chat/" + convId + "/send")
                .with(loginA)
                .with(csrf())
                .param("content", "Hello")
                .param("promptName", ""))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("A playbook must be selected before asking a question."));

        // Cleanup
        setSecurityContext(userAOid, "user-a-test@local", "User A");
        chatConversationService.deleteConversation(convId);
    }

    @Test
    public void testSendMessageAsyncThrowsIfNoPlaybook() throws Exception {
        String userAOid = "user-a-controller-test-oid";
        userRepository.upsert(userAOid, "user-a-test@local", "User A");
        setSecurityContext(userAOid, "user-a-test@local", "User A");

        Conversation conv = chatConversationService.createConversation();
        UUID convId = conv.id();
        var loginA = testUserLogin(userAOid, "user-a-test@local", "User A");

        mockMvc.perform(post("/chat/" + convId + "/send-async")
                .with(loginA)
                .with(csrf())
                .param("content", "Hello")
                .param("promptName", ""))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("A playbook must be selected before asking a question."));

        // Cleanup
        setSecurityContext(userAOid, "user-a-test@local", "User A");
        chatConversationService.deleteConversation(convId);
    }
}
