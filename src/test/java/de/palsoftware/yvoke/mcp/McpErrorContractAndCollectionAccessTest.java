package de.palsoftware.yvoke.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.area.core.AreaService;
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
import de.palsoftware.yvoke.mcp.tools.SearchGraphEntitiesTool;
import de.palsoftware.yvoke.mcp.tools.VerifyCitationsTool;
import de.palsoftware.yvoke.mcp.tools.VerifyCitationsToolCallback;
import de.palsoftware.yvoke.rag.prompt.Playbook;
import de.palsoftware.yvoke.rag.prompt.PlaybookRepository;
import de.palsoftware.yvoke.rag.prompt.PlaybookService;
import de.palsoftware.yvoke.rag.retrieval.HybridSearch;
import de.palsoftware.yvoke.shared.security.AreaAccessContext;
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
