package de.palsoftware.yvoke.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.area.core.AreaService;
import de.palsoftware.yvoke.chat.core.tool.AskClarifyingQuestionToolCallback;
import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.service.CollectionService;
import de.palsoftware.yvoke.document.core.repository.DocumentRepository;
import de.palsoftware.yvoke.kg.core.repository.KgGraphReadRepository;
import de.palsoftware.yvoke.mcp.tools.GetGraphNeighborsTool;
import de.palsoftware.yvoke.mcp.tools.GetSectionTool;
import de.palsoftware.yvoke.mcp.tools.GetSectionToolCallback;
import de.palsoftware.yvoke.mcp.tools.ListDocumentsTool;
import de.palsoftware.yvoke.mcp.tools.ListDocumentsToolCallback;
import de.palsoftware.yvoke.mcp.tools.PlaybookTools;
import de.palsoftware.yvoke.mcp.tools.SearchCorpusTool;
import de.palsoftware.yvoke.mcp.tools.SearchCorpusToolCallback;
import de.palsoftware.yvoke.mcp.tools.SearchGraphEntitiesTool;
import de.palsoftware.yvoke.mcp.tools.SubmitFeedbackTool;
import de.palsoftware.yvoke.mcp.tools.VerifyCitationsTool;
import de.palsoftware.yvoke.mcp.tools.VerifyCitationsToolCallback;
import de.palsoftware.yvoke.rag.core.McpToolHints;
import de.palsoftware.yvoke.rag.prompt.Playbook;
import de.palsoftware.yvoke.rag.prompt.PlaybookRepository;
import de.palsoftware.yvoke.rag.prompt.PlaybookService;
import de.palsoftware.yvoke.rag.retrieval.HybridSearch;
import de.palsoftware.yvoke.shared.security.AreaAccessContext;
import de.palsoftware.yvoke.shared.user.service.UserService;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class McpErrorContractAndCollectionAccessTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticate(String... roles) {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                "user",
                                "n/a",
                                AreaAccessContext.mapRoleClaimsToAuthorities(
                                        List.of(roles), "Admin", "User")));
    }

    @Test
    void collectionLookupFailureIsCaughtByToolErrorNotLeakedOrThrown() {
        authenticate("User");
        CollectionService failingCols = mock(CollectionService.class);
        when(failingCols.listCollections())
                .thenThrow(new RuntimeException("PSQLException: db-host:5432 refused"));

        SearchCorpusTool searchTool =
                new SearchCorpusTool(mock(HybridSearch.class), failingCols, 10, 20);
        ListDocumentsTool listDocsTool =
                new ListDocumentsTool(mock(DocumentRepository.class), failingCols);
        SearchGraphEntitiesTool searchGraphTool =
                new SearchGraphEntitiesTool(mock(KgGraphReadRepository.class), failingCols);
        GetGraphNeighborsTool neighborsTool =
                new GetGraphNeighborsTool(mock(KgGraphReadRepository.class), failingCols);

        String searchRes = searchTool.searchCorpus("q", "OIM", null, null, null);
        assertThat(searchRes)
                .isEqualTo("ERROR: the 'search_corpus' tool failed to complete the request.")
                .doesNotContain("db-host");

        String listRes = listDocsTool.listDocuments("OIM", null, null, null, null, null, null);
        assertThat(listRes)
                .isEqualTo("ERROR: the 'list_documents' tool failed to complete the request.")
                .doesNotContain("db-host");

        String graphRes = searchGraphTool.searchGraphEntities("q", "OIM", null, null, null);
        assertThat(graphRes)
                .isEqualTo(
                        "ERROR: the 'search_graph_entities' tool failed to complete the request.")
                .doesNotContain("db-host");

        String neighborsRes =
                neighborsTool.getGraphNeighbors("Person", "OIM", null, null, null, null);
        assertThat(neighborsRes)
                .isEqualTo("ERROR: the 'get_graph_neighbors' tool failed to complete the request.")
                .doesNotContain("db-host");
    }

    @Test
    void callbacksDoNotLabelExecutionFailuresAsArgumentParseErrors() {
        RuntimeException dbBoom = new RuntimeException("PSQLException: db-secret-host");

        ListDocumentsTool listTool = mock(ListDocumentsTool.class);
        when(listTool.listDocuments(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(dbBoom);
        String listOut =
                new ListDocumentsToolCallback(listTool, MAPPER).call("{\"collection\":\"OIM\"}");
        assertThat(listOut)
                .doesNotContain("Error parsing arguments")
                .doesNotContain("db-secret-host")
                .isEqualTo("ERROR: the 'list_documents' tool failed to complete the request.");

        GetSectionTool sectionTool = mock(GetSectionTool.class);
        when(sectionTool.getSection(any(), any(), any(), any())).thenThrow(dbBoom);
        String sectionOut =
                new GetSectionToolCallback(sectionTool, MAPPER).call("{\"document_id\":\"123\"}");
        assertThat(sectionOut)
                .doesNotContain("Error parsing arguments")
                .doesNotContain("db-secret-host")
                .isEqualTo("ERROR: the 'get_section' tool failed to complete the request.");

        VerifyCitationsTool verifyTool = mock(VerifyCitationsTool.class);
        when(verifyTool.verifyCitations(any(), any())).thenThrow(dbBoom);
        String verifyOut =
                new VerifyCitationsToolCallback(verifyTool, MAPPER)
                        .call("{\"citations\":[\"[1]\"]}");
        assertThat(verifyOut)
                .doesNotContain("Error parsing arguments")
                .doesNotContain("db-secret-host")
                .isEqualTo("ERROR: the 'verify_citations' tool failed to complete the request.");
    }

    @Test
    void nullAreaCollectionsAndPlaybooksFailClosedForRestrictedUsers() {
        authenticate("OIM.User");
        Collection nullAreaCol =
                new Collection(
                        UUID.randomUUID(),
                        "Orphan-Col",
                        "No area",
                        List.of(),
                        OffsetDateTime.now(),
                        null);
        CollectionService cols = mock(CollectionService.class);
        when(cols.listCollections()).thenReturn(List.of(nullAreaCol));

        SearchCorpusTool searchTool = new SearchCorpusTool(mock(HybridSearch.class), cols, 10, 20);
        assertThat(searchTool.searchCorpus("q", "Orphan-Col", null, null, null))
                .isEqualTo("Error: Collection 'Orphan-Col' does not exist.");

        ListDocumentsTool listTool = new ListDocumentsTool(mock(DocumentRepository.class), cols);
        assertThat(listTool.listDocuments("Orphan-Col", null, null, null, null, null, null))
                .isEqualTo("Error: Collection 'Orphan-Col' does not exist.");

        PlaybookRepository pbRepo = mock(PlaybookRepository.class);
        when(pbRepo.findByName("orphan-pb"))
                .thenReturn(
                        Optional.of(
                                new Playbook(
                                        "orphan-pb",
                                        "Orphan",
                                        "Desc",
                                        "Body",
                                        List.of(),
                                        false,
                                        "specialist",
                                        false,
                                        null,
                                        null,
                                        false,
                                        null,
                                        1)));
        PlaybookTools pbTools =
                new PlaybookTools(
                        mock(PlaybookService.class), pbRepo, mock(AreaService.class), MAPPER);
        assertThat(pbTools.getPlaybook("orphan-pb"))
                .isEqualTo("ERROR: playbook 'orphan-pb' not found.");
    }

    @Test
    void searchGraphEntitiesForwardsStoredCollectionCasingToRepository() {
        authenticate("OIM.User");
        Collection oimGraph =
                new Collection(
                        UUID.randomUUID(),
                        "OIM - Graph",
                        "Graph",
                        List.of(),
                        OffsetDateTime.now(),
                        "OIM");
        CollectionService cols = mock(CollectionService.class);
        when(cols.listCollections()).thenReturn(List.of(oimGraph));
        KgGraphReadRepository kgRepo = mock(KgGraphReadRepository.class);
        when(kgRepo.fuzzySearchEntities(any(), anyInt(), any(), any(), any()))
                .thenReturn(List.of());

        SearchGraphEntitiesTool tool = new SearchGraphEntitiesTool(kgRepo, cols);
        tool.searchGraphEntities("Person", "oim - graph", null, null, null);

        verify(kgRepo)
                .fuzzySearchEntities(eq("Person"), eq(20), eq(null), eq("OIM - Graph"), eq(null));
    }

    @Test
    void mcpSyncToolAdapterSetsIsErrorForErrorStringsQuotedErrorsAndExceptions() {
        ToolCallback rawError = stubCallback("t1", "Error: Collection 'x' does not exist.");
        ToolCallback quotedError = stubCallback("t2", "\"ERROR: playbook 'p' not found.\"");
        ToolCallback okCallback = stubCallback("t3", "# Section: Intro\nHello");
        ToolCallback throwingDb = mock(ToolCallback.class);
        when(throwingDb.getToolDefinition())
                .thenReturn(
                        ToolDefinition.builder()
                                .name("t4")
                                .description("d")
                                .inputSchema("{\"type\":\"object\"}")
                                .build());
        when(throwingDb.call(any(), any()))
                .thenThrow(new RuntimeException("PSQLException: internal-host:5432"));

        McpSchema.CallToolRequest req = new McpSchema.CallToolRequest("t", Map.of());
        McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);

        McpServerFeatures.SyncToolSpecification spec1 = McpSyncToolAdapter.adapt(rawError);
        McpSchema.CallToolResult res1 = spec1.callHandler().apply(exchange, req);
        assertThat(res1.isError()).isTrue();
        assertThat(((McpSchema.TextContent) res1.content().get(0)).text())
                .isEqualTo("Error: Collection 'x' does not exist.");

        McpServerFeatures.SyncToolSpecification spec2 = McpSyncToolAdapter.adapt(quotedError);
        McpSchema.CallToolResult res2 = spec2.callHandler().apply(exchange, req);
        assertThat(res2.isError()).isTrue();

        McpServerFeatures.SyncToolSpecification spec3 = McpSyncToolAdapter.adapt(okCallback);
        McpSchema.CallToolResult res3 = spec3.callHandler().apply(exchange, req);
        assertThat(res3.isError()).isFalse();

        McpServerFeatures.SyncToolSpecification spec4 = McpSyncToolAdapter.adapt(throwingDb);
        McpSchema.CallToolResult res4 = spec4.callHandler().apply(exchange, req);
        assertThat(res4.isError()).isTrue();
        assertThat(((McpSchema.TextContent) res4.content().get(0)).text())
                .isEqualTo("ERROR: the 't4' tool failed to complete the request.")
                .doesNotContain("internal-host");
    }

    @Test
    void mcpSyncToolAdapterResolvesDeclaredHintsAndNeverDefaultsUnannotatedToReadOnly() {
        SearchCorpusToolCallback searchCb =
                new SearchCorpusToolCallback(
                        mock(SearchCorpusTool.class), MAPPER, 10, 20, mock(UserService.class));
        McpSchema.Tool readOnlyTool = McpSyncToolAdapter.adapt(searchCb).tool();
        assertThat(readOnlyTool.annotations()).isNotNull();
        assertThat(readOnlyTool.annotations().readOnlyHint()).isTrue();
        assertThat(readOnlyTool.annotations().destructiveHint()).isFalse();
        assertThat(readOnlyTool.annotations().idempotentHint()).isTrue();
        assertThat(readOnlyTool.annotations().openWorldHint()).isFalse();

        McpToolHints feedbackHints = SubmitFeedbackTool.class.getAnnotation(McpToolHints.class);
        assertThat(feedbackHints).isNotNull();
        ToolCallback wrappedFeedback =
                McpSyncToolAdapter.wrapWithHints(
                        stubCallback("submit_feedback", "ok"),
                        McpSyncToolAdapter.fromHints(feedbackHints));
        McpSchema.Tool writeTool = McpSyncToolAdapter.adapt(wrappedFeedback).tool();
        assertThat(writeTool.annotations()).isNotNull();
        assertThat(writeTool.annotations().readOnlyHint()).isFalse();
        assertThat(writeTool.annotations().destructiveHint()).isTrue();
        assertThat(writeTool.annotations().idempotentHint()).isTrue();
        assertThat(writeTool.annotations().openWorldHint()).isFalse();

        // An unannotated tool callback (even with an arbitrary name) must NOT silently default to
        // readOnlyHint=true — conservative MCP defaults apply unless explicitly annotated.
        McpSchema.Tool unannotatedTool =
                McpSyncToolAdapter.adapt(stubCallback("future_write_tool", "ok")).tool();
        assertThat(unannotatedTool.annotations()).isNotNull();
        assertThat(unannotatedTool.annotations().readOnlyHint()).isFalse();
        assertThat(unannotatedTool.annotations().destructiveHint()).isTrue();
        assertThat(unannotatedTool.annotations().idempotentHint()).isFalse();
        assertThat(unannotatedTool.annotations().openWorldHint()).isTrue();
    }

    @Test
    void argumentParseFailuresNameBadFieldWithoutLeakingRawExceptionDetails() {
        String expectedGeneric =
                "Error parsing arguments: invalid JSON or argument types; check the tool schema.";

        String searchOut =
                new SearchCorpusToolCallback(
                                mock(SearchCorpusTool.class),
                                MAPPER,
                                10,
                                20,
                                mock(UserService.class))
                        .call("{not-json");
        String listOut =
                new ListDocumentsToolCallback(mock(ListDocumentsTool.class), MAPPER)
                        .call("{not-json");
        String sectionOut =
                new GetSectionToolCallback(mock(GetSectionTool.class), MAPPER).call("{not-json");
        String verifyOut =
                new VerifyCitationsToolCallback(mock(VerifyCitationsTool.class), MAPPER)
                        .call("{not-json");
        String askJsonOut = new AskClarifyingQuestionToolCallback(MAPPER).call("{not-json");
        String askCastOut =
                new AskClarifyingQuestionToolCallback(MAPPER).call("{\"question\":{\"nested\":1}}");

        ToolCallback throwingParse = mock(ToolCallback.class);
        when(throwingParse.getToolDefinition())
                .thenReturn(
                        ToolDefinition.builder()
                                .name("get_toc")
                                .description("d")
                                .inputSchema("{\"type\":\"object\"}")
                                .build());
        when(throwingParse.call(any(), any()))
                .thenThrow(
                        new RuntimeException(
                                new JsonParseException(
                                        null, "Unexpected character ('x' in secret)")));
        McpSchema.CallToolResult adaptedRes =
                McpSyncToolAdapter.adapt(throwingParse)
                        .callHandler()
                        .apply(
                                mock(McpSyncServerExchange.class),
                                new McpSchema.CallToolRequest("get_toc", Map.of()));
        String adapterOut = ((McpSchema.TextContent) adaptedRes.content().get(0)).text();

        JsonMappingException fieldError =
                JsonMappingException.from(
                        (JsonParser) null,
                        "Cannot deserialize value of type java.lang.Integer from String \"bad\"");
        fieldError.prependPath(new Object(), "rating");
        ToolCallback throwingFieldMapping = mock(ToolCallback.class);
        when(throwingFieldMapping.getToolDefinition())
                .thenReturn(
                        ToolDefinition.builder()
                                .name("submit_feedback")
                                .description("d")
                                .inputSchema("{\"type\":\"object\"}")
                                .build());
        when(throwingFieldMapping.call(any(), any()))
                .thenThrow(new IllegalStateException("wrapper", fieldError));
        McpSchema.CallToolResult fieldRes =
                McpSyncToolAdapter.adapt(throwingFieldMapping)
                        .callHandler()
                        .apply(
                                mock(McpSyncServerExchange.class),
                                new McpSchema.CallToolRequest("submit_feedback", Map.of()));
        String adapterFieldOut = ((McpSchema.TextContent) fieldRes.content().get(0)).text();

        assertThat(adaptedRes.isError()).isTrue();
        for (String out :
                List.of(searchOut, listOut, sectionOut, verifyOut, askJsonOut, adapterOut)) {
            assertThat(out)
                    .isEqualTo(expectedGeneric)
                    .doesNotContain("Unexpected character")
                    .doesNotContain("LinkedHashMap")
                    .doesNotContain("cannot be cast");
        }
        assertThat(askCastOut)
                .isEqualTo(
                        "Error parsing arguments: invalid value for field 'question'; check the tool schema.")
                .doesNotContain("LinkedHashMap")
                .doesNotContain("cannot be cast");
        assertThat(fieldRes.isError()).isTrue();
        assertThat(adapterFieldOut)
                .isEqualTo(
                        "Error parsing arguments: invalid value for field 'rating'; check the tool schema.")
                .doesNotContain("java.lang.Integer")
                .doesNotContain("Cannot deserialize");
    }

    private static ToolCallback stubCallback(String name, String response) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(name)
                        .description("desc")
                        .inputSchema("{\"type\":\"object\"}")
                        .build();
            }

            @Override
            public String call(String toolInput) {
                return response;
            }
        };
    }
}
