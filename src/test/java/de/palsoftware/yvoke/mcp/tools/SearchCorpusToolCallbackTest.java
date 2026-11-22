package de.palsoftware.yvoke.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.llm.core.context.LlmCallContextHolder;
import de.palsoftware.yvoke.rag.core.model.AgenticChatContext;
import de.palsoftware.yvoke.shared.user.model.User;
import de.palsoftware.yvoke.shared.user.service.UserService;
import jakarta.annotation.Nullable;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Contracts of the search_corpus tool CALLBACK: the limit ceiling it advertises to the model, and
 * the cost-attribution context it borrows and must give back.
 *
 * <p>The ceiling the tool schema ADVERTISES must be the ceiling the server actually enforces.
 *
 * <p>This description is model input, and it is the only place the model learns what to do about a
 * capped result. It used to hardcode "max 200" — a figure taken from a constant that no longer
 * exists — while {@code HybridSearch} clamps every request to {@code app.retrieval.max-limit},
 * which is 20. The consequence is a loop the model cannot escape: a specialist asks for 100,
 * receives 20, is told the result is capped and to raise the limit, raises it, and receives 20
 * again. Nothing errors and nothing in the answer reveals why the enumeration is short.
 *
 * <p>Pinning the number alone would just move the duplication into this file, so the assertions
 * check the schema against the value the callback was CONSTRUCTED with — the same one {@code
 * McpToolsConfig} reads from configuration and hands to {@code HybridSearch}.
 */
public class SearchCorpusToolCallbackTest {

    private static String schemaFor(int maxLimit) {
        return schemaFor(10, maxLimit);
    }

    private static String schemaFor(int defaultLimit, int maxLimit) {
        return new SearchCorpusToolCallback(
                        mock(SearchCorpusTool.class),
                        new ObjectMapper(),
                        defaultLimit,
                        maxLimit,
                        mock(UserService.class))
                .getToolDefinition()
                .inputSchema();
    }

    @Test
    public void theAdvertisedLimitCeilingIsTheConfiguredOne() {
        assertThat(schemaFor(10, 20)).contains("(max 20)").contains("server default (10)");
        assertThat(schemaFor(15, 75))
                .as("both default and ceiling must track configuration, not literals")
                .contains("(max 75)")
                .contains("server default (15)")
                .doesNotContain("(max 20)")
                .doesNotContain("around 10");
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
     * method the way the other eight are. Nothing ties that literal to {@code
     * SearchCorpusTool.searchCorpus}, so the schema and the signature are a two-place contract kept
     * in sync only by whoever remembers.
     *
     * <p>Both directions fail silently. Add a parameter to the method and forget the schema: the
     * model is never told the parameter exists, so it is never sent, the callback reads {@code
     * null} for it, and the tool quietly runs with a default — the same drift {@code
     * McpToolCatalogueParityTest} now catches for the annotation-driven eight, which cannot see
     * this tool at all because it carries no {@code @McpTool}/{@code @Tool}. In the other
     * direction, weakening {@code required} lets the model omit {@code collection}; the tool then
     * returns "Error: 'collection' parameter is required" as an ordinary tool result, which the
     * model reads as a malformed QUERY and repairs by rewriting the query — a loop it cannot
     * escape.
     *
     * <p>The four attribution keys are the one legitimate asymmetry: the CALLBACK consumes them
     * into {@code LlmCallContextHolder} and never passes them on, so they are properties with no
     * parameter by design. They are named here so that a NEW schema-only key — the shape a stray
     * copy-paste takes — still fails.
     *
     * <p>This is pure reflection plus a JSON parse: no Spring context, so it cannot affect the
     * TestContext cache.
     */
    @Test
    public void theHandWrittenSchemaMatchesTheJavaSignatureOfSearchCorpus() throws Exception {
        Method method =
                SearchCorpusTool.class.getMethod(
                        "searchCorpus",
                        String.class,
                        String.class,
                        String.class,
                        Integer.class,
                        AgenticChatContext.class);

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
                .isNotEmpty()
                .allSatisfy(n -> assertThat(n).doesNotMatch("arg\\d+"));

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
                .as(
                        "every non-@Nullable parameter must be advertised as required, and nothing else")
                .containsExactlyInAnyOrderElementsOf(mandatory);
        assertThat(properties.keySet())
                .as(
                        "no client-controlled cost-attribution ids or extra keys may be advertised in "
                                + "the schema — attribution must come strictly from server context / principal")
                .containsExactlyInAnyOrderElementsOf(modelFacing);
        assertThat(properties.keySet())
                .as("a required key must be a declared property")
                .containsAll(required);
    }

    @Test
    public void outerCostAttributionContextAlwaysWinsOverCallerSuppliedArguments() {
        UUID outerConv = UUID.randomUUID();
        UUID outerMsg = UUID.randomUUID();
        UUID outerRun = UUID.randomUUID();
        UUID outerUser = UUID.randomUUID();
        UUID forgedConv = UUID.randomUUID();
        UUID forgedMsg = UUID.randomUUID();
        UUID forgedRun = UUID.randomUUID();
        UUID forgedUser = UUID.randomUUID();
        SearchCorpusTool tool = mock(SearchCorpusTool.class);
        List<LlmCallContextHolder.Context> seenByTool = new ArrayList<>();
        when(tool.searchCorpus(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    seenByTool.add(LlmCallContextHolder.get());
                    return "ok";
                });
        UserService userService = mock(UserService.class);
        SearchCorpusToolCallback callback =
                new SearchCorpusToolCallback(tool, new ObjectMapper(), 10, 20, userService);

        try {
            LlmCallContextHolder.set(outerConv, outerMsg, outerRun, outerUser, "chat", "assistant");

            callback.callWithContext(
                    "{\"query\":\"q\",\"collection\":\"OIM\","
                            + "\"conversation_id\":\""
                            + forgedConv
                            + "\",\"message_id\":\""
                            + forgedMsg
                            + "\",\"agent_run_id\":\""
                            + forgedRun
                            + "\",\"user_id\":\""
                            + forgedUser
                            + "\",\"userId\":\""
                            + forgedUser
                            + "\"}",
                    null);

            assertThat(seenByTool).hasSize(1);
            LlmCallContextHolder.Context seen = seenByTool.get(0);
            assertThat(seen.conversationId()).isEqualTo(outerConv);
            assertThat(seen.messageId()).isEqualTo(outerMsg);
            assertThat(seen.agentRunId()).isEqualTo(outerRun);
            assertThat(seen.userId()).isEqualTo(outerUser);
            assertThat(LlmCallContextHolder.get()).isNotNull();
            assertThat(LlmCallContextHolder.get().conversationId()).isEqualTo(outerConv);
            assertThat(LlmCallContextHolder.get().userId()).isEqualTo(outerUser);
        } finally {
            LlmCallContextHolder.clear();
        }
    }

    /**
     * On an external MCP call (no outer context), userId comes strictly from the authenticated
     * principal via {@link UserService#getCurrentUser()}, forged argument IDs are ignored, and the
     * ThreadLocal is cleared afterwards.
     */
    @Test
    public void aToolCallOnACleanThreadDerivesUserIdFromPrincipalAndLeavesNoContextBehind() {
        UUID realUserId = UUID.randomUUID();
        User realUser =
                new User(realUserId, "entra-oid", "alice@example.com", "Alice", Instant.now());
        UserService userService = mock(UserService.class);
        when(userService.getCurrentUser()).thenReturn(Optional.of(realUser));

        SearchCorpusTool tool = mock(SearchCorpusTool.class);
        List<LlmCallContextHolder.Context> seenByTool = new ArrayList<>();
        when(tool.searchCorpus(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    seenByTool.add(LlmCallContextHolder.get());
                    return "ok";
                });
        SearchCorpusToolCallback callback =
                new SearchCorpusToolCallback(tool, new ObjectMapper(), 10, 20, userService);

        LlmCallContextHolder.clear();
        UUID forgedUser = UUID.randomUUID();
        UUID forgedConv = UUID.randomUUID();
        callback.callWithContext(
                "{\"query\":\"q\",\"collection\":\"OIM\",\"user_id\":\""
                        + forgedUser
                        + "\",\"conversation_id\":\""
                        + forgedConv
                        + "\"}",
                null);

        assertThat(seenByTool).hasSize(1);
        LlmCallContextHolder.Context seen = seenByTool.get(0);
        assertThat(seen).isNotNull();
        assertThat(seen.userId())
                .as("spend must be attributed to the authenticated principal, never the forged arg")
                .isEqualTo(realUserId);
        assertThat(seen.conversationId()).isNull();
        assertThat(seen.messageId()).isNull();
        assertThat(seen.agentRunId()).isNull();
        assertThat(LlmCallContextHolder.get())
                .as("a tool must not leak its attribution onto a thread that had none")
                .isNull();
    }

    @Test
    public void infrastructureExceptionDuringExecutionIsNotLabelledAsArgumentParseError() {
        UserService userService = mock(UserService.class);
        when(userService.getCurrentUser()).thenReturn(Optional.empty());
        SearchCorpusTool tool = mock(SearchCorpusTool.class);
        when(tool.searchCorpus(any(), any(), any(), any(), any()))
                .thenThrow(
                        new RuntimeException("PSQLException: connection to db-host:5432 refused"));
        SearchCorpusToolCallback callback =
                new SearchCorpusToolCallback(tool, new ObjectMapper(), 10, 20, userService);

        String result = callback.callWithContext("{\"query\":\"q\",\"collection\":\"OIM\"}", null);

        assertThat(result)
                .doesNotContain("Error parsing arguments")
                .doesNotContain("db-host")
                .doesNotContain("PSQLException")
                .isEqualTo("ERROR: the 'search_corpus' tool failed to complete the request.");
    }
}
