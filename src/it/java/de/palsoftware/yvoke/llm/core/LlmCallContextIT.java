package de.palsoftware.yvoke.llm.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.chat.core.model.Conversation;
import de.palsoftware.yvoke.chat.core.model.Message;
import de.palsoftware.yvoke.chat.core.repository.ConversationRepository;
import de.palsoftware.yvoke.chat.core.repository.MessageRepository;
import de.palsoftware.yvoke.chat.orchestration.AgentRunRepository;
import de.palsoftware.yvoke.llm.core.context.LlmCallContextHolder;
import de.palsoftware.yvoke.llm.core.event.LlmCallLoggedEvent;
import de.palsoftware.yvoke.mcp.tools.SearchCorpusTool;
import de.palsoftware.yvoke.mcp.tools.SearchCorpusToolCallback;
import de.palsoftware.yvoke.shared.user.model.User;
import de.palsoftware.yvoke.shared.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=filesystem:docker/db/migration"
})
public class LlmCallContextIT {

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private AgentRunRepository agentRunRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private User testUser;
    private Conversation testConversation;
    private Message testMessage;
    private UUID testAgentRunId;

    @BeforeEach
    void setUp() {
        String oid = "context-oid-" + UUID.randomUUID();
        userRepository.upsert(oid, oid + "@example.com", "Context User");
        testUser = userRepository.findByEntraOid(oid).orElseThrow();

        UUID convId = UUID.randomUUID();
        conversationRepository.create(convId, testUser.id(), "Context Test Conv", Map.of("profile", "oim"));
        testConversation = conversationRepository.findById(convId).orElseThrow();

        UUID msgId = UUID.randomUUID();
        messageRepository.save(new Message(msgId, convId, "assistant", "test output", null, Collections.emptyList(), Collections.emptyList(), Instant.now(), 10, 20, 30, 0, 0, "done", "gemini-2.5-flash"));
        testMessage = messageRepository.findById(msgId).orElseThrow();

        testAgentRunId = UUID.randomUUID();
        agentRunRepository.create(testAgentRunId, convId, "oim", Map.of());
    }

    @Test
    void testLlmCallLoggingService_persistsContextFromEvent() {
        String testModel = "context-it-model-" + UUID.randomUUID();
        LlmCallLoggedEvent event = new LlmCallLoggedEvent(
            testConversation.id(),
            testMessage.id(),
            testAgentRunId,
            testUser.id(),
            "embedding",
            "embedding",
            testModel,
            128,
            0,
            0,
            0
        );

        eventPublisher.publishEvent(event);

        List<Map<String, Object>> rows = jdbcClient.sql("""
            SELECT conversation_id, message_id, agent_run_id, user_id, source, model
            FROM llm_call_logs
            WHERE conversation_id = :cid AND model = :m
        """)
        .param("cid", testConversation.id())
        .param("m", testModel)
        .query()
        .listOfRows();

        assertThat(rows).hasSize(1);
        Map<String, Object> matched = rows.get(0);

        assertThat(matched.get("conversation_id")).isEqualTo(testConversation.id());
        assertThat(matched.get("message_id")).isEqualTo(testMessage.id());
        assertThat(matched.get("agent_run_id")).isEqualTo(testAgentRunId);
        assertThat(matched.get("user_id")).isEqualTo(testUser.id());
        assertThat(matched.get("source")).isEqualTo("embedding");
    }

    /**
     * The sibling test asserts the context is null BEFORE and AFTER the call, so it only ever
     * exercises the outermost-frame branch of {@code callWithContext}'s {@code finally} — the
     * {@code clear()} side. In production the callback is never in that situation: an orchestrated
     * run always invokes it with an outer {@link LlmCallContextHolder.Context} already set, and
     * both of the branches that matter there are untested.
     *
     * <p>
     * <b>Restore, not clear.</b> If the {@code finally} cleared instead of restoring, every LLM
     * call made after the first {@code search_corpus} in a turn would be logged with
     * {@code source="unknown"} and a null conversation/message/run/user — dropping out of every
     * per-conversation, per-run and per-profile cost view at once. That is precisely the failure
     * {@code OrchestrationService} was fixed for, and it is invisible: the answer is still correct,
     * only the ledger is wrong.
     *
     * <p>
     * <b>Inheritance of source/role.</b> The tool's own sub-calls (embedding, rerank) are logged
     * from inside {@code searchCorpus}, so their attribution is whatever the callback set on the
     * way in. There is no {@code source} argument in the tool schema — the ONLY place it can come
     * from is the outer frame — so replacing {@code outerCtx.source()} with {@code null} reads like
     * a harmless simplification and quietly attributes every retrieval sub-call to nothing. Nothing
     * downstream fails; the rows are simply written with a null source. Capturing the context
     * INSIDE the stubbed tool is the only way to observe it, because the {@code finally} has
     * already overwritten it by the time the caller regains control.
     */
    @Test
    void aNestedSearchCorpusCallRestoresTheOuterContextAndInheritsItsSource() {
        SearchCorpusTool mockTool = mock(SearchCorpusTool.class);
        AtomicReference<LlmCallContextHolder.Context> seenInsideTool = new AtomicReference<>();
        when(mockTool.searchCorpus("nested query", "OIM", null, null, null))
            .thenAnswer(invocation -> {
                seenInsideTool.set(LlmCallContextHolder.get());
                return "Searched OK";
            });

        SearchCorpusToolCallback callback =
            new SearchCorpusToolCallback(mockTool, objectMapper, 20);

        // The frame an orchestrated run is already in when the model asks for search_corpus.
        LlmCallContextHolder.set(testConversation.id(), testMessage.id(), testAgentRunId,
            testUser.id(), "orchestrator", "specialist");
        LlmCallContextHolder.Context outer = LlmCallContextHolder.get();

        try {
            // Deliberately NO *_id arguments, and the schema has no source/role at all: everything
            // the sub-call is billed under has to be inherited from the outer frame.
            String result = callback.callWithContext("""
                {"query": "nested query", "collection": "OIM"}
                """, null);

            assertThat(result).isEqualTo("Searched OK");

            LlmCallContextHolder.Context inner = seenInsideTool.get();
            assertThat(inner)
                .as("the tool must run with a context, or its embedding/rerank sub-calls are "
                    + "logged as unattributed")
                .isNotNull();
            assertThat(inner.source())
                .as("source is not an argument — the outer frame is its only possible origin")
                .isEqualTo("orchestrator");
            assertThat(inner.role()).isEqualTo("specialist");
            assertThat(inner.conversationId()).isEqualTo(testConversation.id());
            assertThat(inner.messageId()).isEqualTo(testMessage.id());
            assertThat(inner.agentRunId()).isEqualTo(testAgentRunId);
            assertThat(inner.userId()).isEqualTo(testUser.id());

            assertThat(LlmCallContextHolder.get())
                .as("the outer frame must be restored field-for-field, not cleared — otherwise "
                    + "every later LLM call in the turn is logged as source=unknown")
                .isEqualTo(outer);
        } finally {
            LlmCallContextHolder.clear();
        }
    }

    @Test
    void testSearchCorpusToolCallback_parsesAndBindsContextArguments() {
        SearchCorpusTool mockTool = mock(SearchCorpusTool.class);
        when(mockTool.searchCorpus("test query", "OIM", null, null, null)).thenReturn("Searched OK");

        SearchCorpusToolCallback callback = new SearchCorpusToolCallback(mockTool, objectMapper, 20);

        String jsonArgs = String.format("""
            {
                "query": "test query",
                "collection": "OIM",
                "conversation_id": "%s",
                "message_id": "%s",
                "agent_run_id": "%s",
                "user_id": "%s"
            }
            """, testConversation.id(), testMessage.id(), testAgentRunId, testUser.id());

        assertThat(LlmCallContextHolder.get()).isNull();

        String result = callback.callWithContext(jsonArgs, null);

        assertThat(result).isEqualTo("Searched OK");
        assertThat(LlmCallContextHolder.get()).isNull(); // Context cleaned up after tool call
    }
}
