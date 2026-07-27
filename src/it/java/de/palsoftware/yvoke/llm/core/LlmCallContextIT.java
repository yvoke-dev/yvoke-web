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

    @Test
    void testSearchCorpusToolCallback_parsesAndBindsContextArguments() {
        SearchCorpusTool mockTool = mock(SearchCorpusTool.class);
        when(mockTool.searchCorpus("test query", "OIM", null, null, null)).thenReturn("Searched OK");

        SearchCorpusToolCallback callback = new SearchCorpusToolCallback(mockTool, objectMapper);

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
