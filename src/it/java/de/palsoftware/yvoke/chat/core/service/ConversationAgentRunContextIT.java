package de.palsoftware.yvoke.chat.core.service;

import de.palsoftware.yvoke.chat.core.model.Conversation;
import de.palsoftware.yvoke.chat.core.model.Message;
import de.palsoftware.yvoke.chat.core.repository.ConversationRepository;
import de.palsoftware.yvoke.chat.core.repository.MessageRepository;
import de.palsoftware.yvoke.chat.orchestration.OrchestrationService;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProfile;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProfileRepository;
import de.palsoftware.yvoke.chat.web.PlaybookValidationController;
import de.palsoftware.yvoke.llm.core.model.LlmRequest;
import de.palsoftware.yvoke.llm.core.model.LlmResponse;
import de.palsoftware.yvoke.llm.core.model.LlmResponseChunk;
import de.palsoftware.yvoke.llm.core.model.LlmUsage;
import de.palsoftware.yvoke.llm.core.service.LlmClient;
import de.palsoftware.yvoke.rag.prompt.PlaybookService;
import de.palsoftware.yvoke.rag.retrieval.EmbeddingService;
import de.palsoftware.yvoke.shared.user.model.User;
import de.palsoftware.yvoke.shared.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.time.Duration;
import org.awaitility.Awaitility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import de.palsoftware.yvoke.llm.core.context.LlmCallContextHolder;
import de.palsoftware.yvoke.llm.core.event.LlmCallLoggedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.test.context.support.WithMockUser;

@SpringBootTest(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=filesystem:docker/db/migration"
})
@WithMockUser(username = "conv-context-user")
public class ConversationAgentRunContextIT {

    @Autowired
    private ChatMessageService chatMessageService;

    @Autowired
    private OrchestrationService orchestrationService;

    @Autowired
    private PlaybookValidationController playbookValidationController;

    @Autowired
    private CostCalculationService costCalculationService;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlaybookService playbookService;

    @Autowired
    private OrchestratorProfileRepository orchestratorProfileRepository;

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private JdbcClient jdbcClient;

    @MockitoBean(name = "llmProviderClient")
    private LlmClient llmClient;

    private User testUser;

    @BeforeEach
    void setUp() {
        String oid = "conv-context-user";
        userRepository.upsert(oid, oid + "@example.com", "Conv Context User");
        testUser = userRepository.findByEntraOid(oid).orElseThrow();

        // Stub llmClient.generateStream to stream chunks and emit an embedding log from context
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Consumer<LlmResponseChunk> cb = inv.getArgument(1);
            cb.accept(new LlmResponseChunk("Hello from mocked LLM", null, null, new LlmUsage(50, 25, 75, 0, 0)));
            LlmCallContextHolder.Context ctx = LlmCallContextHolder.get();
            if (ctx != null && eventPublisher != null) {
                eventPublisher.publishEvent(new LlmCallLoggedEvent(
                    ctx.conversationId(), ctx.messageId(), ctx.agentRunId(), ctx.userId(),
                    "embedding", "embedding", "voyage-4-large", 15, 0, 0, 0
                ));
            }
            return null;
        }).when(llmClient).generateStream(any(LlmRequest.class), any());

        // Stub llmClient.generate for playbook validation
        when(llmClient.generate(any(LlmRequest.class))).thenReturn(
            new LlmResponse("{\"plausible\": true, \"reason\": \"\", \"suggestedPlaybookName\": null}",
                new LlmUsage(100, 20, 120, 0, 0))
        );

        playbookService.savePlaybook("oim-orch", "OIM Orchestrator", "", "Orchestrator prompt", List.of(), false);
        playbookService.savePlaybook("oim-rev", "OIM Reviewer", "", "Reviewer prompt", List.of(), false);
        playbookService.savePlaybook("oim-spec", "OIM Specialist", "", "Specialist prompt", List.of(), false);

        orchestratorProfileRepository.upsert(new OrchestratorProfile(
            "oim", 2, 5, "oim-orch", "oim-rev", List.of("oim-spec"),
            "gemini-2.5-pro", "high", "gemini-2.5-pro", "high", "gemini-2.5-flash", "medium", false, null, null
        ));
    }

    @Test
    void testChatMessageService_generateSync_logsCallWithConversationMessageAndUserId() {
        UUID convId = UUID.randomUUID();
        conversationRepository.create(convId, testUser.id(), "Sync Chat Conv",
            Map.of("model", "gemini-2.5-flash"));

        // Must go through prepare(): it persists the user message, which is the only message row
        // that exists while the LLM call is in flight and therefore the one accounting points at.
        ChatMessageService.PreparedChat prepared =
            chatMessageService.prepare(convId, "What is the system status?", "oim-spec");

        UUID assistantMessageId = UUID.randomUUID();
        chatMessageService.generateSync(prepared, assistantMessageId);

        List<Map<String, Object>> logs = jdbcClient.sql("""
            SELECT conversation_id, message_id, user_id, source, role, model, total_tokens
            FROM llm_call_logs
            WHERE conversation_id = :cid
        """)
        .param("cid", convId)
        .query()
        .listOfRows();

        assertThat(logs).isNotEmpty();

        Map<String, Object> logRow = logs.stream()
            .filter(r -> "chat".equals(r.get("source")))
            .findFirst()
            .orElseThrow();

        assertThat(logRow.get("conversation_id")).isEqualTo(convId);
        assertThat(logRow.get("message_id")).isEqualTo(prepared.userMessageId());
        assertThat(logRow.get("user_id")).isEqualTo(testUser.id());
        assertThat(logRow.get("source")).isEqualTo("chat");
        assertThat(logRow.get("role")).isEqualTo("assistant");
    }

    @Test
    void testChatMessageService_stream_logsCallAndEmbeddingWithFullContext() {
        UUID convId = UUID.randomUUID();
        conversationRepository.create(convId, testUser.id(), "Stream Chat Conv", Map.of("model", "gemini-2.5-flash"));

        ChatMessageService.PreparedChat prepared = chatMessageService.prepare(
            convId, "What is the system status?", "oim-orch"
        );

        UUID assistantMessageId = UUID.randomUUID();
        chatMessageService.stream(prepared, assistantMessageId, token -> {});

        List<Map<String, Object>> logs = jdbcClient.sql("""
            SELECT conversation_id, message_id, user_id, source, role, model
            FROM llm_call_logs
            WHERE conversation_id = :cid
        """)
        .param("cid", convId)
        .query()
        .listOfRows();

        assertThat(logs).hasSizeGreaterThanOrEqualTo(2);

        for (Map<String, Object> logRow : logs) {
            assertThat(logRow.get("conversation_id")).isEqualTo(convId);
            assertThat(logRow.get("message_id")).isNotNull();
            assertThat(logRow.get("user_id")).isEqualTo(testUser.id());
        }

        assertThat(logs.stream().anyMatch(r -> "chat".equals(r.get("source")))).isTrue();
        assertThat(logs.stream().anyMatch(r -> "embedding".equals(r.get("source")))).isTrue();
    }

    /**
     * {@code llm_call_logs.message_id} is the key the cost dashboard groups a turn by, and all four
     * chat entry points have to mean the same thing by it: the persisted USER message. The SSE path
     * ({@code stream}) and {@code generateSync} have no choice — the assistant row does not exist
     * while the call is in flight and {@code fk_llm_call_logs_messages} would reject it — but this
     * one DOES write a "generating" assistant placeholder before submitting, so naming
     * {@code assistantMessageId} here is perfectly legal and nothing at all would fail.
     *
     * <p>
     * That is exactly what makes it the dangerous one. {@code /chat/&#123;id&#125;/send-async} is
     * the browser's normal send, so most production rows would silently carry a different meaning
     * from the rest of the table, and any {@code GROUP BY message_id} would split one turn in two —
     * part attributed to the question, part to the answer — while every total still adds up and
     * every foreign key still holds. Nothing pinned it: the two ChatMessageService cases above
     * drive {@code prepare} + {@code stream} and {@code generateSync}, both of which are FK-forced
     * into the right answer, so the single entry point where the rule is a CHOICE rather than a
     * constraint was the one with no coverage.
     */
    @Test
    void asyncTurnAttributesCallsToTheUserMessageNotTheAssistantPlaceholder() {
        UUID convId = UUID.randomUUID();
        conversationRepository.create(convId, testUser.id(), "Async Chat Conv",
            Map.of("model", "gemini-2.5-flash"));

        UUID assistantMessageId = chatMessageService.prepareAndSubmitAsync(convId,
            "What is the system status?", "oim-spec");

        // The placeholder is persisted up front, so it WOULD satisfy the FK: on this path the
        // attribution rule is a deliberate choice, not something the database enforces.
        assertThat(messageRepository.findById(assistantMessageId)).isPresent();

        Awaitility.await().atMost(Duration.ofSeconds(30))
            .untilAsserted(() -> assertThat(
                messageRepository.findById(assistantMessageId).orElseThrow().status())
                    .as("the background generation never completed").isEqualTo("done"));

        UUID userMessageId = jdbcClient
            .sql("SELECT id FROM messages WHERE conversation_id = :cid AND role = 'user'")
            .param("cid", convId).query(UUID.class).single();
        assertThat(userMessageId).isNotEqualTo(assistantMessageId);

        List<Map<String, Object>> logs = jdbcClient.sql("""
            SELECT message_id, user_id, source, role
            FROM llm_call_logs
            WHERE conversation_id = :cid AND source = 'chat'
        """)
        .param("cid", convId)
        .query()
        .listOfRows();

        assertThat(logs).as("the async turn logged no LLM spend at all").isNotEmpty();
        for (Map<String, Object> logRow : logs) {
            assertThat(logRow.get("message_id"))
                .as("async spend attributes to the question, like every other entry point")
                .isEqualTo(userMessageId);
            assertThat(logRow.get("message_id")).isNotEqualTo(assistantMessageId);
            assertThat(logRow.get("user_id")).isEqualTo(testUser.id());
        }
    }

    @Test
    void testOrchestrationService_runOrchestration_logsCallWithConversationAndAgentRunId() {
        UUID convId = UUID.randomUUID();
        conversationRepository.create(convId, testUser.id(), "Orchestration Conv", Map.of());

        UUID agentRunId = UUID.randomUUID();
        UUID userMessageId = UUID.randomUUID();
        messageRepository.save(new Message(userMessageId, convId, "user", "Analyze system logs", null, Collections.emptyList(), Collections.emptyList(), Instant.now()));

        orchestrationService.runOrchestration(convId, userMessageId, "Analyze system logs", Collections.emptyList(), agentRunId, "oim");

        List<Map<String, Object>> logs = jdbcClient.sql("""
            SELECT conversation_id, message_id, agent_run_id, user_id, source, role, model
            FROM llm_call_logs
            WHERE conversation_id = :cid AND agent_run_id = :runId AND source = 'orchestrator'
        """)
        .param("cid", convId)
        .param("runId", agentRunId)
        .query()
        .listOfRows();

        assertThat(logs).isNotEmpty();

        for (Map<String, Object> logRow : logs) {
            assertThat(logRow.get("conversation_id")).isEqualTo(convId);
            assertThat(logRow.get("message_id")).isEqualTo(userMessageId);
            assertThat(logRow.get("agent_run_id")).isEqualTo(agentRunId);
            assertThat(logRow.get("user_id")).isEqualTo(testUser.id());
            assertThat(logRow.get("source")).isEqualTo("orchestrator");
        }
    }

    @Test
    void testPlaybookValidationController_validatePlaybook_logsCallWithConversationAndUserId() {
        UUID convId = UUID.randomUUID();
        conversationRepository.create(convId, testUser.id(), "Validation Conv", Map.of());

        playbookValidationController.validatePlaybook(convId, "Can I request access?", "oim-access-governance");

        List<Map<String, Object>> logs = jdbcClient.sql("""
            SELECT conversation_id, user_id, source, role
            FROM llm_call_logs
            WHERE conversation_id = :cid AND source = 'playbook_validator'
        """)
        .param("cid", convId)
        .query()
        .listOfRows();

        assertThat(logs).hasSize(1);
        Map<String, Object> logRow = logs.get(0);
        assertThat(logRow.get("conversation_id")).isEqualTo(convId);
        assertThat(logRow.get("user_id")).isEqualTo(testUser.id());
        assertThat(logRow.get("source")).isEqualTo("playbook_validator");
        assertThat(logRow.get("role")).isEqualTo("validator");
    }

    @Test
    void testCostCalculationService_getFilteredExplorerReport_resolvesPlaybookAndUser() {
        UUID convId = UUID.randomUUID();
        conversationRepository.create(convId, testUser.id(), "Explorer Conv",
            Map.of("model", "gemini-2.5-flash", "chat-prompt", "oim-access-governance"));

        playbookService.savePlaybook("oim-access-governance", "OIM Access Governance", "",
            "Access governance prompt", List.of(), false);

        UUID assistantMessageId = UUID.randomUUID();
        ChatMessageService.PreparedChat prepared = chatMessageService.prepare(convId,
            "What is attestation?", "oim-access-governance");
        chatMessageService.stream(prepared, assistantMessageId, token -> {});

        CostCalculationService.FilteredCostExplorerReport report = costCalculationService.getFilteredExplorerReport(
            "MESSAGE", null, null, null, null, null, null
        );

        assertThat(report.messages()).isNotEmpty();
        CostCalculationService.FilteredMessageRow row = report.messages().stream()
            .filter(r -> convId.equals(r.conversationId()))
            .findFirst()
            .orElseThrow();

        assertThat(row.userName()).isEqualTo(testUser.displayName());
        assertThat(row.playbook()).isEqualTo("oim-access-governance");
    }
}
