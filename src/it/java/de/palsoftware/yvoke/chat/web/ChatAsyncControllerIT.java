package de.palsoftware.yvoke.chat.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.chat.core.model.Conversation;
import de.palsoftware.yvoke.chat.core.model.ConversationSetting;
import de.palsoftware.yvoke.chat.core.model.Message;
import de.palsoftware.yvoke.chat.core.service.ChatConversationService;
import de.palsoftware.yvoke.chat.core.repository.MessageRepository;
import de.palsoftware.yvoke.llm.core.model.LlmMessage;
import de.palsoftware.yvoke.llm.core.model.LlmRequest;
import de.palsoftware.yvoke.llm.core.model.LlmResponseChunk;
import de.palsoftware.yvoke.llm.core.model.LlmUsage;
import de.palsoftware.yvoke.llm.core.service.LlmClient;
import de.palsoftware.yvoke.rag.prompt.PlaybookService;
import de.palsoftware.yvoke.shared.user.repository.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
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
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "app.security.mock=true")
public class ChatAsyncControllerIT {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ChatConversationService chatConversationService;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private PlaybookService playbookService;

    @Autowired
    private ObjectMapper objectMapper;

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
    public void testSendAsyncHappyPath() throws Exception {
        String userAOid = "user-a-async-test-oid";
        userRepository.upsert(userAOid, "user-a-async@local", "User A");
        setSecurityContext(userAOid, "user-a-async@local", "User A");

        Conversation conv = chatConversationService.createConversation();
        conversationsToDelete.add(conv.id());

        // Configure standard settings if needed
        Map<String, Object> settings = new HashMap<>(conv.settings());
        settings.put(ConversationSetting.MODEL.getValue(), "gemini-3.1-flash-lite");
        chatConversationService.updateSettings(conv.id(), settings);

        playbookService.savePlaybook("correct-playbook", "Correct Playbook", "Desc", "Template", List.of(), false);

        CountDownLatch callStarted = new CountDownLatch(1);
        CountDownLatch releaseCall = new CountDownLatch(1);

        doAnswer(inv -> {
            Consumer<LlmResponseChunk> cb = inv.getArgument(1);
            callStarted.countDown();
            try {
                releaseCall.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            cb.accept(new LlmResponseChunk("Hello async response!", null, null, new LlmUsage(10, 10, 20, 0, 0)));
            return null;
        }).when(llmClient).generateStream(any(LlmRequest.class), any());

        var login = testUserLogin(userAOid, "user-a-async@local", "User A");

        MvcResult postResult = mockMvc.perform(post("/chat/" + conv.id() + "/send-async")
                .with(login)
                .with(csrf())
                .param("content", "Hello async query")
                .param("promptName", "correct-playbook"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.assistantMessageId").exists())
            .andReturn();

        String responseBody = postResult.getResponse().getContentAsString();
        Map<String, String> responseMap = objectMapper.readValue(responseBody, Map.class);
        UUID assistantMessageId = UUID.fromString(responseMap.get("assistantMessageId"));

        boolean started = callStarted.await(5, TimeUnit.SECONDS);
        assertThat(started).isTrue();

        mockMvc.perform(get("/chat/" + conv.id() + "/messages/" + assistantMessageId + "/status")
                .with(login))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("generating"));

        releaseCall.countDown();

        boolean done = false;
        for (int i = 0; i < 50; i++) {
            MvcResult statusResult = mockMvc.perform(get("/chat/" + conv.id() + "/messages/" + assistantMessageId + "/status")
                    .with(login))
                .andExpect(status().isOk())
                .andReturn();
            String statusBody = statusResult.getResponse().getContentAsString();
            Map<String, Object> statusMap = objectMapper.readValue(statusBody, Map.class);
            if ("done".equals(statusMap.get("status"))) {
                done = true;
                Map<String, Object> messageMap = (Map<String, Object>) statusMap.get("message");
                assertThat(messageMap.get("content")).isEqualTo("Hello async response!");
                break;
            }
            Thread.sleep(100);
        }
        assertThat(done).isTrue();
    }

    @Test
    public void testSendAsyncInvalidPlaybook() throws Exception {
        String userAOid = "user-a-async-test-oid";
        userRepository.upsert(userAOid, "user-a-async@local", "User A");
        setSecurityContext(userAOid, "user-a-async@local", "User A");

        Conversation conv = chatConversationService.createConversation();
        conversationsToDelete.add(conv.id());

        var login = testUserLogin(userAOid, "user-a-async@local", "User A");

        mockMvc.perform(post("/chat/" + conv.id() + "/send-async")
                .with(login)
                .with(csrf())
                .param("content", "Hello")
                .param("promptName", "non-existent-playbook"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("The selected playbook is invalid."));
    }

    @Test
    public void testSendAsyncAccessDenied() throws Exception {
        String userAOid = "user-a-async-test-oid";
        userRepository.upsert(userAOid, "user-a-async@local", "User A");
        setSecurityContext(userAOid, "user-a-async@local", "User A");

        Conversation conv = chatConversationService.createConversation();
        conversationsToDelete.add(conv.id());

        playbookService.savePlaybook("correct-playbook", "Correct Playbook", "Desc", "Template", List.of(), false);

        String userBOid = "user-b-async-test-oid";
        userRepository.upsert(userBOid, "user-b-async@local", "User B");
        var loginB = testUserLogin(userBOid, "user-b-async@local", "User B");

        // User B cannot send message to User A's conversation
        mockMvc.perform(post("/chat/" + conv.id() + "/send-async")
                .with(loginB)
                .with(csrf())
                .param("content", "Hello")
                .param("promptName", "correct-playbook"))
            .andExpect(status().isForbidden());

        // User B cannot poll message status on User A's conversation
        mockMvc.perform(get("/chat/" + conv.id() + "/messages/" + UUID.randomUUID() + "/status")
                .with(loginB))
            .andExpect(status().isForbidden());
    }

    @Test
    public void testStopCancelsAsyncGeneration() throws Exception {
        String userAOid = "user-a-async-test-oid";
        userRepository.upsert(userAOid, "user-a-async@local", "User A");
        setSecurityContext(userAOid, "user-a-async@local", "User A");

        Conversation conv = chatConversationService.createConversation();
        conversationsToDelete.add(conv.id());

        playbookService.savePlaybook("correct-playbook", "Correct Playbook", "Desc", "Template", List.of(), false);

        CountDownLatch callStarted = new CountDownLatch(1);
        CountDownLatch cancellationHandled = new CountDownLatch(1);

        doAnswer(inv -> {
            callStarted.countDown();
            try {
                // Sleep longer to ensure cancellation is requested from test thread
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                cancellationHandled.countDown();
                Thread.currentThread().interrupt();
                throw new CancellationException("Chat generation cancelled");
            }
            return null;
        }).when(llmClient).generateStream(any(LlmRequest.class), any());

        var login = testUserLogin(userAOid, "user-a-async@local", "User A");

        MvcResult postResult = mockMvc.perform(post("/chat/" + conv.id() + "/send-async")
                .with(login)
                .with(csrf())
                .param("content", "Hello stop query")
                .param("promptName", "correct-playbook"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.assistantMessageId").exists())
            .andReturn();

        String responseBody = postResult.getResponse().getContentAsString();
        Map<String, String> responseMap = objectMapper.readValue(responseBody, Map.class);
        UUID assistantMessageId = UUID.fromString(responseMap.get("assistantMessageId"));

        boolean started = callStarted.await(5, TimeUnit.SECONDS);
        assertThat(started).isTrue();

        // Call stop endpoint
        mockMvc.perform(post("/chat/" + conv.id() + "/stop")
                .with(login)
                .with(csrf()))
            .andExpect(status().isOk());

        boolean cancelled = cancellationHandled.await(5, TimeUnit.SECONDS);
        assertThat(cancelled).isTrue();

        // Poll until it transitions to 'cancelled' — NOT 'error'. A stop and a failure both used to
        // land on 'error', which left the browser unable to tell them apart and captioning genuine
        // failures as "[Generation stopped by user]".
        boolean cancelledStatus = false;
        for (int i = 0; i < 50; i++) {
            MvcResult statusResult = mockMvc.perform(get("/chat/" + conv.id() + "/messages/" + assistantMessageId + "/status")
                    .with(login))
                .andExpect(status().isOk())
                .andReturn();
            String statusBody = statusResult.getResponse().getContentAsString();
            Map<String, Object> statusMap = objectMapper.readValue(statusBody, Map.class);
            if ("cancelled".equals(statusMap.get("status"))) {
                cancelledStatus = true;
                Map<String, Object> messageMap = (Map<String, Object>) statusMap.get("message");
                assertThat((String) messageMap.get("content")).contains("[Generation stopped by user]");
                break;
            }
            Thread.sleep(100);
        }
        assertThat(cancelledStatus).isTrue();
    }

    /**
     * The mirror of the stop test: a provider failure must report 'error' and must NOT carry the
     * cancellation wording. Together the two pin that the two outcomes stay distinguishable.
     */
    @Test
    public void testProviderFailureReportsErrorAndNotCancelled() throws Exception {
        String userOid = "user-fail-async-test-oid";
        userRepository.upsert(userOid, "user-fail-async@local", "User Fail");
        setSecurityContext(userOid, "user-fail-async@local", "User Fail");

        Conversation conv = chatConversationService.createConversation();
        conversationsToDelete.add(conv.id());

        playbookService.savePlaybook("failure-playbook", "Failure Playbook", "Desc", "Template",
            List.of(), false);

        doAnswer(inv -> {
            throw new IllegalStateException("simulated provider fault");
        }).when(llmClient).generateStream(any(LlmRequest.class), any());

        var login = testUserLogin(userOid, "user-fail-async@local", "User Fail");

        MvcResult result = mockMvc.perform(post("/chat/" + conv.id() + "/send-async")
                .with(login)
                .with(csrf())
                .param("content", "trigger a failure")
                .param("promptName", "failure-playbook"))
            .andExpect(status().isAccepted())
            .andReturn();

        Map<String, String> responseMap =
            objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        UUID assistantMessageId = UUID.fromString(responseMap.get("assistantMessageId"));

        boolean errored = false;
        for (int i = 0; i < 50; i++) {
            MvcResult statusResult = mockMvc.perform(get("/chat/" + conv.id() + "/messages/" + assistantMessageId + "/status")
                    .with(login))
                .andExpect(status().isOk())
                .andReturn();
            Map<String, Object> statusMap =
                objectMapper.readValue(statusResult.getResponse().getContentAsString(), Map.class);
            if ("error".equals(statusMap.get("status"))) {
                errored = true;
                Map<String, Object> messageMap = (Map<String, Object>) statusMap.get("message");
                String content = (String) messageMap.get("content");
                assertThat(content).contains("could not complete this response");
                assertThat(content).doesNotContain("stopped by user");
                assertThat(content).doesNotContain("simulated provider fault");
                break;
            }
            Thread.sleep(100);
        }
        assertThat(errored).isTrue();
    }

    @Test
    public void testThreadViewShowsGeneratingMessage() throws Exception {
        String userAOid = "user-a-async-test-oid";
        userRepository.upsert(userAOid, "user-a-async@local", "User A");
        setSecurityContext(userAOid, "user-a-async@local", "User A");

        Conversation conv = chatConversationService.createConversation();
        conversationsToDelete.add(conv.id());

        // Insert assistant message with status generating
        UUID generatingMsgId = UUID.randomUUID();
        Message generatingMsg = new Message(
            generatingMsgId,
            conv.id(),
            "assistant",
            "",
            null,
            List.of(),
            List.of(),
            Instant.now(),
            null, null, null, null, null,
            "generating"
        );
        messageRepository.save(generatingMsg);

        var login = testUserLogin(userAOid, "user-a-async@local", "User A");

        MvcResult viewResult = mockMvc.perform(get("/chat/" + conv.id())
                .with(login))
            .andExpect(status().isOk())
            .andReturn();

        String htmlContent = viewResult.getResponse().getContentAsString();
        assertThat(htmlContent).contains("sync-loader-container");
        assertThat(htmlContent).contains("sync-loader-text");
        assertThat(htmlContent).contains("Thinking");
        assertThat(htmlContent).contains("message-assistant sync-loading");
    }

    @Test
    public void testGeneratingMessageExcludedFromHistory() throws Exception {
        String userAOid = "user-a-async-test-oid";
        userRepository.upsert(userAOid, "user-a-async@local", "User A");
        setSecurityContext(userAOid, "user-a-async@local", "User A");

        Conversation conv = chatConversationService.createConversation();
        conversationsToDelete.add(conv.id());

        playbookService.savePlaybook("correct-playbook", "Correct Playbook Title", "Correct Playbook Desc", "template", List.of(), false);

        // 1. Insert a user message
        UUID userMsgId = UUID.randomUUID();
        Message userMsg = new Message(
            userMsgId,
            conv.id(),
            "user",
            "First question",
            "correct-playbook",
            List.of(),
            List.of(),
            Instant.now(),
            null, null, null, null, null,
            "done"
        );
        messageRepository.save(userMsg);

        // 2. Insert a generating assistant message
        UUID generatingMsgId = UUID.randomUUID();
        Message generatingMsg = new Message(
            generatingMsgId,
            conv.id(),
            "assistant",
            "Thinking...",
            null,
            List.of(),
            List.of(),
            Instant.now(),
            null, null, null, null, null,
            "generating"
        );
        messageRepository.save(generatingMsg);

        // 3. Set up llmClient generateStream mock to capture requests
        CopyOnWriteArrayList<LlmRequest> capturedRequests = new CopyOnWriteArrayList<>();
        doAnswer(inv -> {
            LlmRequest req = inv.getArgument(0);
            capturedRequests.add(req);
            Consumer<LlmResponseChunk> cb = inv.getArgument(1);
            cb.accept(new LlmResponseChunk("Hello again!", null, null, new LlmUsage(10, 10, 20, 0, 0)));
            return null;
        }).when(llmClient).generateStream(any(LlmRequest.class), any());

        var login = testUserLogin(userAOid, "user-a-async@local", "User A");

        // 4. Send another message async
        mockMvc.perform(post("/chat/" + conv.id() + "/send-async")
                .with(login)
                .with(csrf())
                .param("content", "Second question")
                .param("promptName", "correct-playbook"))
            .andExpect(status().isAccepted());

        boolean captured = false;
        for (int i = 0; i < 50; i++) {
            if (!capturedRequests.isEmpty()) {
                captured = true;
                break;
            }
            Thread.sleep(100);
        }
        assertThat(captured).isTrue();

        LlmRequest request = capturedRequests.get(0);
        List<LlmMessage> messages = request.messages();

        // Check that generating message is excluded
        boolean containsGenerating = messages.stream()
            .anyMatch(m -> "Thinking...".equals(m.content()));
        assertThat(containsGenerating).isFalse();
    }

    @Test
    public void testStreamingModeUnaffected() throws Exception {
        String userAOid = "user-a-async-test-oid";
        userRepository.upsert(userAOid, "user-a-async@local", "User A");
        setSecurityContext(userAOid, "user-a-async@local", "User A");

        Conversation conv = chatConversationService.createConversation();
        conversationsToDelete.add(conv.id());

        // Configure standard settings if needed
        Map<String, Object> settings = new HashMap<>(conv.settings());
        settings.put(ConversationSetting.MODEL.getValue(), "gemini-3.1-flash-lite");
        chatConversationService.updateSettings(conv.id(), settings);

        playbookService.savePlaybook("correct-playbook", "Correct Playbook", "Desc", "Template", List.of(), false);

        doAnswer(inv -> {
            Consumer<LlmResponseChunk> cb = inv.getArgument(1);
            cb.accept(new LlmResponseChunk("SSE chunk response!", null, null, new LlmUsage(5, 5, 10, 0, 0)));
            return null;
        }).when(llmClient).generateStream(any(LlmRequest.class), any());

        var login = testUserLogin(userAOid, "user-a-async@local", "User A");

        MvcResult sseResult = mockMvc.perform(post("/chat/" + conv.id() + "/send")
                .with(login)
                .with(csrf())
                .param("content", "Hello SSE query")
                .param("promptName", "correct-playbook"))
            .andExpect(request().asyncStarted())
            .andReturn();

        mockMvc.perform(asyncDispatch(sseResult))
            .andExpect(status().isOk());

        String sseResponse = sseResult.getResponse().getContentAsString();
        String cleaned = sseResponse.replace("data:", "").replace("\n", "").replace("\r", "").trim();
        assertThat(cleaned).contains("SSE chunk response!");

        // Wait a bit for status to transition to done in db
        boolean saved = false;
        for (int i = 0; i < 50; i++) {
            List<Message> dbMsgs = messageRepository.findByConversationId(conv.id(), 100, 0);
            boolean hasAssistantDone = dbMsgs.stream()
                .anyMatch(m -> "assistant".equals(m.role()) && "done".equals(m.status()) && m.content().contains("SSE chunk response!"));
            if (hasAssistantDone) {
                saved = true;
                break;
            }
            Thread.sleep(100);
        }
        assertThat(saved).isTrue();
    }
}
