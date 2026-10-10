package de.palsoftware.yvoke.chat.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.chat.core.model.Conversation;
import de.palsoftware.yvoke.chat.core.model.Message;
import de.palsoftware.yvoke.chat.core.model.ToolCallRecord;
import de.palsoftware.yvoke.chat.core.repository.MessageRepository;
import de.palsoftware.yvoke.chat.core.repository.MessageToolCallRepository;
import de.palsoftware.yvoke.chat.core.service.ChatConversationService;
import de.palsoftware.yvoke.chat.orchestration.AgentRun;
import de.palsoftware.yvoke.chat.orchestration.AgentRunRepository;
import de.palsoftware.yvoke.chat.orchestration.AgentStep;
import de.palsoftware.yvoke.chat.orchestration.AgentStepRepository;
import de.palsoftware.yvoke.llm.core.model.LlmRequest;
import de.palsoftware.yvoke.llm.core.model.LlmResponseChunk;
import de.palsoftware.yvoke.llm.core.model.LlmUsage;
import de.palsoftware.yvoke.llm.core.service.LlmClient;
import de.palsoftware.yvoke.rag.prompt.PlaybookService;
import de.palsoftware.yvoke.shared.user.repository.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
public class ChatAsyncControllerTraceIT {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ChatConversationService chatConversationService;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private MessageToolCallRepository messageToolCallRepository;

    @Autowired
    private AgentRunRepository agentRunRepository;

    @Autowired
    private AgentStepRepository agentStepRepository;

    @Autowired
    private ObjectMapper objectMapper;

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
            playbookService.deletePlaybook("trace-playbook");
        } catch (Exception e) {
            // Ignore
        }
    }

    private void setSecurityContext(String oid, String email, String name, String role) {
        OidcUser oidcUser = mock(OidcUser.class);
        when(oidcUser.getClaimAsString("oid")).thenReturn(oid);
        when(oidcUser.getClaimAsString("name")).thenReturn(name);
        when(oidcUser.getClaimAsString("email")).thenReturn(email);

        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getPrincipal()).thenReturn(oidcUser);

        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(role));
        when(auth.getAuthorities()).thenAnswer(inv -> authorities);

        SecurityContext secContext = SecurityContextHolder.createEmptyContext();
        secContext.setAuthentication(auth);
        SecurityContextHolder.setContext(secContext);
    }

    private static OidcLoginRequestPostProcessor testUserLogin(String oid, String email, String name,
        String... roles) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (String r : roles) {
            authorities.add(new SimpleGrantedAuthority(r));
        }
        return oidcLogin()
            .idToken(token -> token.claim("oid", oid).claim("name", name).claim("email", email))
            .authorities(authorities);
    }

    @Test
    public void testGetMessageTraceSingleAgentHappyPath() throws Exception {
        String userOid = "user-trace-a-oid";
        userRepository.upsert(userOid, "user-trace-a@local", "User Trace A");
        setSecurityContext(userOid, "user-trace-a@local", "User Trace A", "ROLE_USER");

        Conversation conv = chatConversationService.createConversation();
        conversationsToDelete.add(conv.id());

        UUID messageId = UUID.randomUUID();
        Message assistantMessage = new Message(messageId, conv.id(), "assistant",
            "This is the generated answer.", null, Collections.emptyList(), Collections.emptyList(),
            Instant.now(), 50, 100, 150, 20, 30, "done", "gemini-3.1-flash-lite");
        messageRepository.save(assistantMessage);

        ToolCallRecord call0 = new ToolCallRecord(UUID.randomUUID(), messageId, 0, "call_123",
            "search_corpus", "{\"query\":\"database\"}", "Found 3 results", false, Instant.now());
        ToolCallRecord call1 = new ToolCallRecord(UUID.randomUUID(), messageId, 1, "call_456",
            "lookup_user", "{\"id\":42}", "Error: user not found", true, Instant.now());
        messageToolCallRepository.insertAll(messageId, List.of(call0, call1));

        mockMvc.perform(get("/chat/" + conv.id() + "/messages/" + messageId + "/trace")
                .with(testUserLogin(userOid, "user-trace-a@local", "User Trace A", "ROLE_USER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.mode").value("single"))
            .andExpect(jsonPath("$.messageId").value(messageId.toString()))
            .andExpect(jsonPath("$.conversationId").value(conv.id().toString()))
            .andExpect(jsonPath("$.status").value("done"))
            .andExpect(jsonPath("$.model").value("gemini-3.1-flash-lite"))
            .andExpect(jsonPath("$.tokens.prompt").value(50))
            .andExpect(jsonPath("$.tokens.completion").value(100))
            .andExpect(jsonPath("$.tokens.total").value(150))
            .andExpect(jsonPath("$.tokens.cached").value(20))
            .andExpect(jsonPath("$.tokens.thought").value(30))
            .andExpect(jsonPath("$.toolCalls.length()").value(2))
            .andExpect(jsonPath("$.toolCalls[0].seq").value(0))
            .andExpect(jsonPath("$.toolCalls[0].id").value("call_123"))
            .andExpect(jsonPath("$.toolCalls[0].name").value("search_corpus"))
            .andExpect(jsonPath("$.toolCalls[0].arguments").value("{\"query\":\"database\"}"))
            .andExpect(jsonPath("$.toolCalls[0].result").value("Found 3 results"))
            .andExpect(jsonPath("$.toolCalls[0].isError").value(false))
            .andExpect(jsonPath("$.toolCalls[1].seq").value(1))
            .andExpect(jsonPath("$.toolCalls[1].isError").value(true));
    }

    @Test
    public void testGetMessageTraceCrossConversationIdorReturns404() throws Exception {
        String userOid = "user-idor-oid";
        userRepository.upsert(userOid, "user-idor@local", "User Idor");
        setSecurityContext(userOid, "user-idor@local", "User Idor", "ROLE_USER");

        Conversation convA = chatConversationService.createConversation();
        conversationsToDelete.add(convA.id());

        Conversation convB = chatConversationService.createConversation();
        conversationsToDelete.add(convB.id());

        UUID messageBId = UUID.randomUUID();
        Message messageB = new Message(messageBId, convB.id(), "assistant", "Answer in B", null,
            Collections.emptyList(), Collections.emptyList(), Instant.now(), 10, 20, 30, 0, 0,
            "done", "model-b");
        messageRepository.save(messageB);

        // Requesting messageB under conversation A should return 404 (IDOR guard)
        mockMvc.perform(get("/chat/" + convA.id() + "/messages/" + messageBId + "/trace")
                .with(testUserLogin(userOid, "user-idor@local", "User Idor", "ROLE_USER")))
            .andExpect(status().isNotFound());
    }

    @Test
    public void testGetMessageTraceUnauthorizedUserReturns403() throws Exception {
        String userAOid = "user-owner-oid";
        userRepository.upsert(userAOid, "user-owner@local", "User Owner");
        setSecurityContext(userAOid, "user-owner@local", "User Owner", "ROLE_USER");

        Conversation convA = chatConversationService.createConversation();
        conversationsToDelete.add(convA.id());

        UUID msgId = UUID.randomUUID();
        Message messageA = new Message(msgId, convA.id(), "assistant", "Answer", null,
            Collections.emptyList(), Collections.emptyList(), Instant.now(), 10, 20, 30, 0, 0,
            "done", "model-a");
        messageRepository.save(messageA);

        String userBOid = "user-intruder-oid";
        userRepository.upsert(userBOid, "user-intruder@local", "User Intruder");

        // Intruder accessing user A's private conversation gets 403 Forbidden
        mockMvc.perform(get("/chat/" + convA.id() + "/messages/" + msgId + "/trace")
                .with(testUserLogin(userBOid, "user-intruder@local", "User Intruder", "ROLE_USER")))
            .andExpect(status().isForbidden());
    }

    @Test
    public void testGetMessageTraceAdminCanAccessOtherUsersConversation() throws Exception {
        String userAOid = "user-owner-2-oid";
        userRepository.upsert(userAOid, "user-owner-2@local", "User Owner 2");
        setSecurityContext(userAOid, "user-owner-2@local", "User Owner 2", "ROLE_USER");

        Conversation convA = chatConversationService.createConversation();
        conversationsToDelete.add(convA.id());

        UUID msgId = UUID.randomUUID();
        Message messageA = new Message(msgId, convA.id(), "assistant", "Answer for Admin", null,
            Collections.emptyList(), Collections.emptyList(), Instant.now(), 10, 20, 30, 0, 0,
            "done", "model-a");
        messageRepository.save(messageA);

        String adminOid = "admin-user-oid";
        userRepository.upsert(adminOid, "admin@local", "Admin User");

        // Admin has ROLE_ADMIN and ROLE_USER, permitted via verifyOwnership(id, true)
        mockMvc.perform(get("/chat/" + convA.id() + "/messages/" + msgId + "/trace")
                .with(testUserLogin(adminOid, "admin@local", "Admin User", "ROLE_ADMIN", "ROLE_USER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.mode").value("single"))
            .andExpect(jsonPath("$.messageId").value(msgId.toString()));
    }

    @Test
    public void testGetMessageTraceMasMode() throws Exception {
        String userOid = "user-mas-oid";
        userRepository.upsert(userOid, "user-mas@local", "User Mas");
        setSecurityContext(userOid, "user-mas@local", "User Mas", "ROLE_USER");

        Conversation conv = chatConversationService.createConversation();
        conversationsToDelete.add(conv.id());

        UUID messageId = UUID.randomUUID();
        Message assistantMessage = new Message(messageId, conv.id(), "assistant", "MAS synthesis",
            null, Collections.emptyList(), Collections.emptyList(), Instant.now(), null, null, null,
            null, null, "done", null);
        messageRepository.save(assistantMessage);

        UUID runId = UUID.randomUUID();
        agentRunRepository.create(runId, conv.id(), "deep_research", Collections.emptyMap());
        agentRunRepository.finish(runId, messageId, "done", 2, "approved", 100, 200, 300, 40, 50,
            null);

        agentStepRepository.insert(UUID.randomUUID(), runId, 0, "orchestrator", 1, "lead",
            "gemini-3.1-flash-lite", "high", "plan question", "plan output",
            Collections.emptyList(), "ok", 50, 100, 150, 20, 25);

        mockMvc.perform(get("/chat/" + conv.id() + "/messages/" + messageId + "/trace")
                .with(testUserLogin(userOid, "user-mas@local", "User Mas", "ROLE_USER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.mode").value("mas"))
            .andExpect(jsonPath("$.messageId").value(messageId.toString()))
            .andExpect(jsonPath("$.tokens.prompt").value(100))
            .andExpect(jsonPath("$.tokens.completion").value(200))
            .andExpect(jsonPath("$.tokens.total").value(300))
            .andExpect(jsonPath("$.agentRun.id").value(runId.toString()))
            .andExpect(jsonPath("$.agentRun.profileName").value("deep_research"))
            .andExpect(jsonPath("$.steps.length()").value(1))
            .andExpect(jsonPath("$.steps[0].role").value("orchestrator"));
    }

    @Test
    public void testSendAsyncQuestionWaitDoneAndGetTrace() throws Exception {
        String userOid = "user-async-trace-oid";
        userRepository.upsert(userOid, "user-async-trace@local", "User Async Trace");
        setSecurityContext(userOid, "user-async-trace@local", "User Async Trace", "ROLE_USER");

        Conversation conv = chatConversationService.createConversation();
        conversationsToDelete.add(conv.id());

        Conversation convOther = chatConversationService.createConversation();
        conversationsToDelete.add(convOther.id());

        playbookService.savePlaybook("trace-playbook", "Trace Playbook", "Desc", "Template",
            List.of(), false, "specialist", false, "OIM");

        doAnswer(inv -> {
            Consumer<LlmResponseChunk> cb = inv.getArgument(1);
            cb.accept(new LlmResponseChunk("Hello async answer with trace!", null, null,
                new LlmUsage(15, 25, 40, 5, 6)));
            return null;
        }).when(llmClient).generateStream(any(LlmRequest.class), any());

        var login = testUserLogin(userOid, "user-async-trace@local", "User Async Trace", "ROLE_USER");

        MvcResult postResult = mockMvc.perform(post("/chat/" + conv.id() + "/send-async")
                .with(login)
                .with(csrf())
                .param("content", "Hello async query")
                .param("promptName", "trace-playbook"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.assistantMessageId").exists())
            .andReturn();

        String responseBody = postResult.getResponse().getContentAsString();
        Map<String, String> responseMap = objectMapper.readValue(responseBody, Map.class);
        UUID assistantMessageId = UUID.fromString(responseMap.get("assistantMessageId"));

        // Wait for generation to finish
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
                break;
            }
            Thread.sleep(100);
        }
        assertThat(done).isTrue();

        // Trace call on correct conversation
        mockMvc.perform(get("/chat/" + conv.id() + "/messages/" + assistantMessageId + "/trace")
                .with(login))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.mode").value("single"))
            .andExpect(jsonPath("$.messageId").value(assistantMessageId.toString()))
            .andExpect(jsonPath("$.conversationId").value(conv.id().toString()))
            .andExpect(jsonPath("$.status").value("done"))
            .andExpect(jsonPath("$.tokens.prompt").value(15))
            .andExpect(jsonPath("$.tokens.completion").value(25))
            .andExpect(jsonPath("$.tokens.total").value(40))
            .andExpect(jsonPath("$.tokens.cached").value(5))
            .andExpect(jsonPath("$.tokens.thought").value(6))
            .andExpect(jsonPath("$.toolCalls").isArray());

        // IDOR cross-conversation trace request returns 404
        mockMvc.perform(get("/chat/" + convOther.id() + "/messages/" + assistantMessageId + "/trace")
                .with(login))
            .andExpect(status().isNotFound());
    }
}
