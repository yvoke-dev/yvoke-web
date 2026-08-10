package de.palsoftware.yvoke.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import de.palsoftware.yvoke.llm.core.context.LlmCallContextHolder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import de.palsoftware.yvoke.rag.core.model.AgenticChatContext;
import jakarta.annotation.Nullable;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Contracts of the search_corpus tool CALLBACK: the limit ceiling it advertises to the model, and
 * the cost-attribution context it borrows and must give back.
 *
 * <p>
 * The ceiling the tool schema ADVERTISES must be the ceiling the server actually enforces.
 *
 * <p>
 * This description is model input, and it is the only place the model learns what to do about a
 * capped result. It used to hardcode "max 200" — a figure taken from a constant that no longer
 * exists — while {@code HybridSearch} clamps every request to {@code app.retrieval.max-limit},
 * which is 20. The consequence is a loop the model cannot escape: a specialist asks for 100,
 * receives 20, is told the result is capped and to raise the limit, raises it, and receives 20
 * again. Nothing errors and nothing in the answer reveals why the enumeration is short.
 *
 * <p>
 * Pinning the number alone would just move the duplication into this file, so the assertions check
 * the schema against the value the callback was CONSTRUCTED with — the same one
 * {@code McpToolsConfig} reads from configuration and hands to {@code HybridSearch}.
 */
public class SearchCorpusToolCallbackTest {

    private static String schemaFor(int maxLimit) {
        return new SearchCorpusToolCallback(mock(SearchCorpusTool.class), new ObjectMapper(),
            maxLimit).getToolDefinition().inputSchema();
    }

    @Test
    public void theAdvertisedLimitCeilingIsTheConfiguredOne() {
        assertThat(schemaFor(20)).contains("(max 20)");
        assertThat(schemaFor(75)).as("the ceiling must track configuration, not a literal")
            .contains("(max 75)").doesNotContain("(max 20)");
    }

    /**
     * The old literal must not come back, in either direction: a hardcoded 200 would resume telling
     * the model to raise a limit that is silently clamped to a twentieth of it.
     */
    @Test
    public void noHardcodedCeilingSurvivesInTheSchema() {
        assertThat(schemaFor(20)).doesNotContain("max 200");
    }

    /** The placeholder must actually be substituted — an unformatted text block would ship "%d". */
    @Test
    public void theSchemaIsFormattedRatherThanShippingItsPlaceholder() {
        assertThat(schemaFor(20)).doesNotContain("%d");
    }

    /**
     * S5, tool catalogue. {@code search_corpus} is one of the two tools that are HAND-registered
     * with a hand-written {@code inputSchema} literal, instead of being derived from the Java
     * method the way the other eight are. Nothing ties that literal to
     * {@code SearchCorpusTool.searchCorpus}, so the schema and the signature are a two-place
     * contract kept in sync only by whoever remembers.
     *
     * <p>
     * Both directions fail silently. Add a parameter to the method and forget the schema: the model
     * is never told the parameter exists, so it is never sent, the callback reads {@code null} for
     * it, and the tool quietly runs with a default — the same drift
     * {@code McpToolCatalogueParityTest} now catches for the annotation-driven eight, which cannot
     * see this tool at all because it carries no {@code @McpTool}/{@code @Tool}. In the other
     * direction, weakening {@code required} lets the model omit {@code collection}; the tool then
     * returns "Error: 'collection' parameter is required" as an ordinary tool result, which the
     * model reads as a malformed QUERY and repairs by rewriting the query — a loop it cannot
     * escape.
     *
     * <p>
     * The four attribution keys are the one legitimate asymmetry: the CALLBACK consumes them into
     * {@code LlmCallContextHolder} and never passes them on, so they are properties with no
     * parameter by design. They are named here so that a NEW schema-only key — the shape a stray
     * copy-paste takes — still fails.
     *
     * <p>
     * This is pure reflection plus a JSON parse: no Spring context, so it cannot affect the
     * TestContext cache.
     */
    @Test
    public void theHandWrittenSchemaMatchesTheJavaSignatureOfSearchCorpus() throws Exception {
        // Consumed by the CALLBACK for cost attribution; deliberately not parameters of the tool.
        Set<String> attributionOnly =
            Set.of("conversation_id", "message_id", "agent_run_id", "user_id");

        Method method = SearchCorpusTool.class.getMethod("searchCorpus", String.class, String.class,
            String.class, Integer.class, AgenticChatContext.class);

        List<String> modelFacing = new ArrayList<>();
        List<String> mandatory = new ArrayList<>();
        for (Parameter p : method.getParameters()) {
            if (AgenticChatContext.class.equals(p.getType())) {
                continue; // server-injected run context, never model-authored
            }
            modelFacing.add(p.getName());
            if (!p.isAnnotationPresent(Nullable.class)) {
                mandatory.add(p.getName());
            }
        }
        assertThat(modelFacing)
            .as("reflection must see real parameter names — otherwise this test proves nothing")
            .isNotEmpty().allSatisfy(n -> assertThat(n).doesNotMatch("arg\\d+"));

        @SuppressWarnings("unchecked")
        Map<String, Object> schema = new ObjectMapper().readValue(schemaFor(20), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");

        assertThat(properties.keySet())
            .as("a parameter with no schema property can never be sent by the model")
            .containsAll(modelFacing);
        assertThat(required)
            .as("every non-@Nullable parameter must be advertised as required, and nothing else")
            .containsExactlyInAnyOrderElementsOf(mandatory);
        assertThat(properties.keySet())
            .as("a schema property that is neither a parameter nor cost attribution is a key the "
                + "model will populate and nothing will read")
            .containsExactlyInAnyOrderElementsOf(
                Stream.concat(modelFacing.stream(), attributionOnly.stream()).toList());
        assertThat(properties.keySet()).as("a required key must be a declared property")
            .containsAll(required);
    }

    /**
     * The tool must RESTORE the caller's cost-attribution context, not leave its own behind.
     *
     * <p>
     * {@code LlmCallContextHolder} is a ThreadLocal that decides which conversation, message and
     * agent run every subsequent LLM call is billed to. This callback overrides it so the tool's
     * own embedding and rerank calls are attributed to the conversation named in the tool arguments
     * — and must put the outer value back in a {@code finally}, because the calling thread goes on
     * to make more calls after the tool returns.
     *
     * <p>
     * Without the restore, every later call on that thread is billed to whatever the last tool
     * invocation happened to name. Nothing fails: the answer is correct, the totals are correct,
     * and only the attribution is wrong — so the cost explorer confidently shows spend against the
     * wrong conversation and there is no symptom to notice.
     */
    @Test
    public void theOuterCostAttributionContextIsRestoredAfterTheToolRuns() {
        UUID outerConv = UUID.randomUUID();
        UUID innerConv = UUID.randomUUID();
        SearchCorpusTool tool = mock(SearchCorpusTool.class);
        List<UUID> seenByTool = new ArrayList<>();
        when(tool.searchCorpus(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            seenByTool.add(LlmCallContextHolder.get().conversationId());
            return "ok";
        });
        SearchCorpusToolCallback callback =
            new SearchCorpusToolCallback(tool, new ObjectMapper(), 20);

        try {
            LlmCallContextHolder.set(outerConv, null, null, null, "chat", "assistant");

            callback.callWithContext("{\"query\":\"q\",\"collection\":\"OIM\","
                + "\"conversation_id\":\"" + innerConv + "\"}", null);

            assertThat(seenByTool).as("the tool's own calls bill to the id it was given")
                .containsExactly(innerConv);
            assertThat(LlmCallContextHolder.get()).isNotNull();
            assertThat(LlmCallContextHolder.get().conversationId())
                .as("the caller's context must survive the tool call").isEqualTo(outerConv);
        } finally {
            LlmCallContextHolder.clear();
        }
    }

    /**
     * The four attribution ids are read from MODEL-authored arguments, in BOTH spellings, and a
     * value that is not a UUID must degrade to the outer context rather than abort the search.
     *
     * <p>
     * The tool schema advertises {@code conversation_id} / {@code message_id} /
     * {@code agent_run_id} / {@code user_id}, but the model routinely emits the camelCase forms —
     * it is generating a JSON argument object, where camelCase is the overwhelming prior, and
     * nothing in the protocol rejects an unrecognised key. Dropping the camelCase lookups therefore
     * breaks nothing visible: every one of those calls still runs, still returns the right chunks,
     * and is simply billed to whatever the outer context happened to be. The cost explorer then
     * attributes a specialist's embedding and rerank spend to the parent conversation — or, on a
     * bearer-token MCP thread with no outer context, to nothing at all — and the only symptom is a
     * number on a dashboard that nothing cross-checks.
     *
     * <p>
     * The garbage half is the same contract in the opposite direction. {@code parseUuid} swallows
     * the failure deliberately, because these ids are advisory metadata and a model that
     * hallucinates {@code "conv-123"} must not lose its search over it. Were that to become a
     * throw, it would surface as {@code "Error parsing arguments: …"} — which the model reads as
     * "my query was malformed", so its repair attempt is to rewrite the query, which cannot
     * possibly help.
     */
    @Test
    public void camelCaseAttributionIdsAreAcceptedAndGarbageFallsBackToTheOuterContext() {
        UUID outerConv = UUID.randomUUID();
        UUID camelConv = UUID.randomUUID();
        UUID camelMsg = UUID.randomUUID();
        UUID camelRun = UUID.randomUUID();
        UUID camelUser = UUID.randomUUID();
        SearchCorpusTool tool = mock(SearchCorpusTool.class);
        List<LlmCallContextHolder.Context> seenByTool = new ArrayList<>();
        when(tool.searchCorpus(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            seenByTool.add(LlmCallContextHolder.get());
            return "ok";
        });
        SearchCorpusToolCallback callback =
            new SearchCorpusToolCallback(tool, new ObjectMapper(), 20);

        try {
            LlmCallContextHolder.set(outerConv, null, null, null, "chat", "assistant");

            callback.callWithContext(
                "{\"query\":\"q\",\"collection\":\"OIM\"," + "\"conversationId\":\"" + camelConv
                    + "\"," + "\"messageId\":\"" + camelMsg + "\"," + "\"agentRunId\":\"" + camelRun
                    + "\"," + "\"userId\":\"" + camelUser + "\"}",
                null);

            assertThat(seenByTool).hasSize(1);
            LlmCallContextHolder.Context camel = seenByTool.get(0);
            assertThat(camel.conversationId()).as("camelCase conversationId").isEqualTo(camelConv);
            assertThat(camel.messageId()).as("camelCase messageId").isEqualTo(camelMsg);
            assertThat(camel.agentRunId()).as("camelCase agentRunId").isEqualTo(camelRun);
            assertThat(camel.userId()).as("camelCase userId").isEqualTo(camelUser);

            String out = callback.callWithContext(
                "{\"query\":\"q\",\"collection\":\"OIM\",\"conversation_id\":\"not-a-uuid\"}",
                null);

            assertThat(out).as("an unparseable advisory id must not abort the search")
                .isEqualTo("ok");
            assertThat(seenByTool).hasSize(2);
            assertThat(seenByTool.get(1).conversationId())
                .as("garbage falls back to the outer context rather than blanking attribution")
                .isEqualTo(outerConv);
        } finally {
            LlmCallContextHolder.clear();
        }
    }

    /** With no outer context there is nothing to restore, so the holder must be left CLEAR. */
    @Test
    public void aToolCallOnACleanThreadLeavesNoContextBehind() {
        SearchCorpusTool tool = mock(SearchCorpusTool.class);
        when(tool.searchCorpus(any(), any(), any(), any(), any())).thenReturn("ok");
        SearchCorpusToolCallback callback =
            new SearchCorpusToolCallback(tool, new ObjectMapper(), 20);

        LlmCallContextHolder.clear();
        callback.callWithContext("{\"query\":\"q\",\"collection\":\"OIM\",\"conversation_id\":\""
            + UUID.randomUUID() + "\"}", null);

        assertThat(LlmCallContextHolder.get())
            .as("a tool must not leak its attribution onto a thread that had none").isNull();
    }
}
