package de.palsoftware.yvoke.rag.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.service.CollectionService;
import de.palsoftware.yvoke.document.core.model.ChunkRow;
import de.palsoftware.yvoke.document.core.model.DocumentDetails;
import de.palsoftware.yvoke.document.core.model.DocumentRow;
import de.palsoftware.yvoke.document.core.repository.ChunkRepository;
import de.palsoftware.yvoke.document.core.repository.DocumentRepository;
import de.palsoftware.yvoke.llm.core.model.LlmMessage;
import de.palsoftware.yvoke.llm.core.model.LlmResponseChunk;
import de.palsoftware.yvoke.llm.core.model.LlmToolCallDelta;
import de.palsoftware.yvoke.llm.core.service.LlmClient;
import de.palsoftware.yvoke.mcp.tools.ListDocumentsTool;
import de.palsoftware.yvoke.mcp.tools.ListDocumentsToolCallback;
import de.palsoftware.yvoke.mcp.tools.SearchCorpusTool;
import de.palsoftware.yvoke.mcp.tools.SearchCorpusToolCallback;
import de.palsoftware.yvoke.mcp.tools.VerifyCitationsTool;
import de.palsoftware.yvoke.mcp.tools.VerifyCitationsToolCallback;
import de.palsoftware.yvoke.rag.core.model.AgenticChatContext;
import de.palsoftware.yvoke.rag.core.model.AgenticRequest;
import de.palsoftware.yvoke.rag.core.model.RagResult;
import de.palsoftware.yvoke.rag.core.service.CitationVerifier.CitationCheckResult;
import de.palsoftware.yvoke.rag.core.service.CitationVerifier.CitationStatus;
import de.palsoftware.yvoke.rag.retrieval.HybridSearch;
import de.palsoftware.yvoke.rag.retrieval.HybridSearchResult;
import de.palsoftware.yvoke.rag.retrieval.SearchOptions;
import de.palsoftware.yvoke.rag.retrieval.SearchWithId;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

class RagServiceAreaIsolationTest {

    private HybridSearch hybridSearch;
    private LlmClient llmClient;
    private CitationVerifier citationVerifier;
    private CollectionService collectionService;
    private DocumentRepository documentRepository;
    private ChunkRepository chunkRepository;
    private RagService ragService;

    private static final String AREA_OIM = "OIM";
    private static final String AREA_PINGID = "PingID";

    private final UUID colOimId = UUID.randomUUID();
    private final UUID colPingId = UUID.randomUUID();
    private final UUID docOimId = UUID.randomUUID();
    private final UUID docPingId = UUID.randomUUID();
    private final UUID chunkOimId = UUID.randomUUID();
    private final UUID chunkPingId = UUID.randomUUID();

    private final Collection colOim =
        new Collection(colOimId, "oim-col", "OIM Docs", List.of(), OffsetDateTime.now(), AREA_OIM);
    private final Collection colPing = new Collection(colPingId, "pingid-col", "PingID Docs",
        List.of(), OffsetDateTime.now(), AREA_PINGID);

    @BeforeEach
    void setUp() {
        hybridSearch = mock(HybridSearch.class);
        llmClient = mock(LlmClient.class);
        citationVerifier = mock(CitationVerifier.class);
        collectionService = mock(CollectionService.class);
        documentRepository = mock(DocumentRepository.class);
        chunkRepository = mock(ChunkRepository.class);

        when(collectionService.listCollections()).thenReturn(List.of(colOim, colPing));
        when(collectionService.findById(colOimId)).thenReturn(Optional.of(colOim));
        when(collectionService.findById(colPingId)).thenReturn(Optional.of(colPing));

        DocumentRow docOim = new DocumentRow(docOimId, colOimId, "oim-col", "manual", "OIM Guide",
            null, "COMPLETED", List.of(), Instant.now());
        when(documentRepository.findById(docOimId)).thenReturn(Optional.of(docOim));

        DocumentRow docPing = new DocumentRow(docPingId, colPingId, "pingid-col", "manual",
            "PingID Guide", null, "COMPLETED", List.of(), Instant.now());
        when(documentRepository.findById(docPingId)).thenReturn(Optional.of(docPing));

        ChunkRow chunkOim = new ChunkRow(chunkOimId, docOimId, "OIM chunk content",
            List.of("Intro"), "Intro", 1, 0, null, "OIM Guide", "manual", "oim-col", null, 0.95);
        when(chunkRepository.findById(chunkOimId)).thenReturn(Optional.of(chunkOim));
        when(chunkRepository.findByIdPrefix(chunkOimId.toString()))
            .thenReturn(Optional.of(chunkOim));

        ChunkRow chunkPing =
            new ChunkRow(chunkPingId, docPingId, "PingID chunk content", List.of("Intro"), "Intro",
                1, 0, null, "PingID Guide", "manual", "pingid-col", null, 0.95);
        when(chunkRepository.findById(chunkPingId)).thenReturn(Optional.of(chunkPing));
        when(chunkRepository.findByIdPrefix(chunkPingId.toString()))
            .thenReturn(Optional.of(chunkPing));

        ObjectMapper mapper = new ObjectMapper();
        ragService = new RagService(hybridSearch, llmClient, citationVerifier, mapper,
            collectionService, documentRepository, chunkRepository);
        ragService.getToolRegistry().put("search_corpus", new SearchCorpusToolCallback(
            new SearchCorpusTool(hybridSearch, collectionService, 10, 50), mapper, 50));
        ragService.getToolRegistry().put("list_documents", new ListDocumentsToolCallback(
            new ListDocumentsTool(documentRepository, collectionService), mapper));
        ragService.getToolRegistry().put("verify_citations",
            new VerifyCitationsToolCallback(new VerifyCitationsTool(citationVerifier), mapper));
    }

    private ToolCallback createMockTool(String name) {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition def = mock(ToolDefinition.class);
        when(def.name()).thenReturn(name);
        when(def.inputSchema()).thenReturn("{\"type\":\"object\"}");
        when(callback.getToolDefinition()).thenReturn(def);
        when(callback.call(anyString())).thenReturn("mock tool result for " + name);
        ragService.getToolRegistry().put(name, callback);
        return callback;
    }

    private String runTurnWithToolCall(String toolName, String jsonArgs,
        @Nullable Set<String> allowedAreasLower) {
        doAnswer((Answer<Void>) inv -> {
            Consumer<LlmResponseChunk> cb = inv.getArgument(1);
            LlmToolCallDelta delta = new LlmToolCallDelta(0, "call_1", toolName, jsonArgs);
            cb.accept(new LlmResponseChunk(null, null, List.of(delta), null));
            return null;
        }).doAnswer((Answer<Void>) inv -> {
            Consumer<LlmResponseChunk> cb = inv.getArgument(1);
            cb.accept(new LlmResponseChunk("Turn 2 finish", null, null, null));
            return null;
        }).when(llmClient).generateStream(any(), any());

        AgenticRequest request =
            AgenticRequest.builder().query("test question").modelOverride("gemini-test")
                .allowedTools(List.of(toolName)).allowedAreasLower(allowedAreasLower).build();

        RagResult result = ragService.generateAgenticAnswer(request, token -> {
        });
        LlmMessage toolMsg =
            result.messages().stream().filter(m -> "tool".equals(m.role())).findFirst()
                .orElseThrow(() -> new IllegalStateException("No tool response found in messages"));
        return toolMsg.content();
    }

    @Test
    @DisplayName("search_corpus: targeting PingID collection when restricted to OIM returns not found")
    void searchCorpusCrossAreaRejected() {
        ToolCallback mockTool = createMockTool("search_corpus");
        String response = runTurnWithToolCall("search_corpus",
            "{\"collection\": \"pingid-col\", \"query\": \"auth\"}", Set.of("oim"));

        assertThat(response).isEqualTo("Error: Collection 'pingid-col' does not exist.");
        verify(mockTool, never()).call(anyString());
        verify(hybridSearch, never()).searchWithId(anyString(), any());
    }

    @Test
    @DisplayName("search_corpus: targeting OIM collection when restricted to OIM succeeds")
    void searchCorpusSameAreaAllowed() {
        ToolCallback mockTool = createMockTool("search_corpus");
        String response = runTurnWithToolCall("search_corpus",
            "{\"collection\": \"oim-col\", \"query\": \"auth\"}", Set.of("oim"));

        assertThat(response).isEqualTo("mock tool result for search_corpus");
        verify(mockTool).call(anyString());
    }

    @Test
    @DisplayName("search_corpus: omit-collection search scopes strictly to OIM collections")
    void searchCorpusOmitCollectionScopedToArea() {
        HybridSearchResult chunkResult =
            new HybridSearchResult(chunkOimId, docOimId, "OIM chunk content", List.of("Chapter 1"),
                "Intro", 1, 0, null, "OIM Guide", "manual", "oim-col", null, 0.95, null);
        SearchWithId swi = new SearchWithId(List.of(chunkResult), UUID.randomUUID());
        when(hybridSearch.searchWithId(eq("find me"), any(SearchOptions.class))).thenReturn(swi);

        String response =
            runTurnWithToolCall("search_corpus", "{\"query\": \"find me\"}", Set.of("oim"));

        assertThat(response).contains("OIM chunk content");
        verify(hybridSearch).searchWithId(eq("find me"),
            argThat(
                opts -> opts.collections() != null && opts.collections().equals(List.of("oim-col"))
                    && !opts.collections().contains("pingid-col")));
    }

    @Test
    @DisplayName("list_documents: targeting PingID collection when restricted to OIM returns not found")
    void listDocumentsCrossAreaRejected() {
        ToolCallback mockTool = createMockTool("list_documents");
        String response = runTurnWithToolCall("list_documents", "{\"collection\": \"pingid-col\"}",
            Set.of("oim"));

        assertThat(response).isEqualTo("Error: Collection 'pingid-col' does not exist.");
        verify(mockTool, never()).call(anyString());
    }

    @Test
    @DisplayName("list_documents: omit-collection scopes listing to OIM collections")
    void listDocumentsOmitCollectionScopedToArea() {
        DocumentDetails dd = new DocumentDetails(docOimId, colOimId, "oim-col", "manual",
            "OIM Overview", null, "COMPLETED", 4L, true, Instant.now());

        when(documentRepository.listDocuments(eq("oim-col"), anyInt(), anyInt(), any(), any(),
            any(), any(), any())).thenReturn(List.of(dd));

        String response = runTurnWithToolCall("list_documents", "{}", Set.of("oim"));

        assertThat(response).contains("OIM Overview");
        verify(documentRepository).listDocuments(eq("oim-col"), anyInt(), anyInt(), any(), any(),
            any(), any(), any());
        verify(documentRepository, never()).listDocuments(eq("pingid-col"), anyInt(), anyInt(),
            any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("get_toc: document in PingID collection returns 404 when restricted to OIM")
    void getTocCrossAreaRejected() {
        ToolCallback mockTool = createMockTool("get_toc");
        String response = runTurnWithToolCall("get_toc", "{\"document_id\": \"" + docPingId + "\"}",
            Set.of("oim"));

        assertThat(response).isEqualTo("Error: Document with ID '" + docPingId + "' not found.");
        verify(mockTool, never()).call(anyString());
    }

    @Test
    @DisplayName("get_section: document and chunk in PingID collection return 404 when restricted to OIM")
    void getSectionCrossAreaRejected() {
        ToolCallback mockTool = createMockTool("get_section");

        String docResponse = runTurnWithToolCall("get_section",
            "{\"document_id\": \"" + docPingId + "\"}", Set.of("oim"));
        assertThat(docResponse).isEqualTo("Error: Document not found: " + docPingId);

        String chunkResponse = runTurnWithToolCall("get_section",
            "{\"chunk_id\": \"" + chunkPingId + "\"}", Set.of("oim"));
        assertThat(chunkResponse).isEqualTo("Error: Chunk not found: " + chunkPingId);

        verify(mockTool, never()).call(anyString());
    }

    @Test
    @DisplayName("search_graph_entities & get_graph_neighbors: PingID collection rejected")
    void kgToolsCrossAreaRejected() {
        ToolCallback mockSearchGraph = createMockTool("search_graph_entities");
        String resp1 = runTurnWithToolCall("search_graph_entities",
            "{\"collection\": \"pingid-col\", \"query\": \"user\"}", Set.of("oim"));
        assertThat(resp1).isEqualTo("Error: Collection 'pingid-col' does not exist.");
        verify(mockSearchGraph, never()).call(anyString());

        ToolCallback mockNeighbors = createMockTool("get_graph_neighbors");
        String resp2 = runTurnWithToolCall("get_graph_neighbors",
            "{\"collection\": \"pingid-col\", \"entity_name\": \"PingNode\"}", Set.of("oim"));
        assertThat(resp2).isEqualTo("Error: Collection 'pingid-col' does not exist.");
        verify(mockNeighbors, never()).call(anyString());
    }

    @Test
    @DisplayName("get_json_schema & query_json_objects: PingID collection rejected")
    void jsonToolsCrossAreaRejected() {
        ToolCallback mockSchema = createMockTool("get_json_schema");
        String resp1 = runTurnWithToolCall("get_json_schema", "{\"collection\": \"pingid-col\"}",
            Set.of("oim"));
        assertThat(resp1).isEqualTo("Error: Collection 'pingid-col' does not exist.");
        verify(mockSchema, never()).call(anyString());

        ToolCallback mockQuery = createMockTool("query_json_objects");
        String resp2 = runTurnWithToolCall("query_json_objects",
            "{\"collection\": \"pingid-col\", \"jsonPath\": \"$.status\"}", Set.of("oim"));
        assertThat(resp2).isEqualTo("Error: Collection 'pingid-col' does not exist.");
        verify(mockQuery, never()).call(anyString());
    }

    @Test
    @DisplayName("verify_citations: citation pointing to PingID chunk reported as FABRICATED when restricted to OIM")
    void verifyCitationsCrossAreaReportedAsFabricated() {
        String citToken = "[chunk_id=" + chunkPingId + "]";
        when(citationVerifier.checkCitations(List.of(citToken), Set.of("oim"))).thenReturn(List.of(
            new CitationCheckResult(citToken, "chunk", CitationStatus.FABRICATED, "0 chunk(s)")));

        String response = runTurnWithToolCall("verify_citations",
            "{\"citations\": [\"" + citToken + "\"]}", Set.of("oim"));

        assertThat(response).contains("FABRICATED");
        assertThat(response).contains("Remove or correct every FABRICATED citation");
        verify(citationVerifier).checkCitations(List.of(citToken), Set.of("oim"));
    }

    @Test
    @DisplayName("Global User (allowedAreasLower = ALL_AREAS) can access both OIM and PingID collections")
    void globalUserUnrestrictedAccess() {
        ToolCallback mockTool = createMockTool("search_corpus");
        when(mockTool.call(anyString())).thenReturn("Global access granted");

        String pingResponse = runTurnWithToolCall("search_corpus",
            "{\"collection\": \"pingid-col\", \"query\": \"test\"}", AgenticChatContext.ALL_AREAS);
        assertThat(pingResponse).isEqualTo("Global access granted");

        String oimResponse = runTurnWithToolCall("search_corpus",
            "{\"collection\": \"oim-col\", \"query\": \"test\"}", AgenticChatContext.ALL_AREAS);
        assertThat(oimResponse).isEqualTo("Global access granted");
    }

    @Test
    @DisplayName("resolveCollection: enforces area isolation and returns null for unauthorized or non-existent collections")
    void resolveCollectionWithAreaRestriction() {
        AgenticChatContext oimCtx = new AgenticChatContext(Set.of("oim"));
        assertThat(ragService.resolveCollection("oim-col", oimCtx)).isEqualTo(colOim);
        assertThat(ragService.resolveCollection("pingid-col", oimCtx)).isNull();
        assertThat(ragService.resolveCollection("non-existent", oimCtx)).isNull();

        AgenticChatContext globalCtx = new AgenticChatContext(AgenticChatContext.ALL_AREAS);
        assertThat(ragService.resolveCollection("oim-col", globalCtx)).isEqualTo(colOim);
        assertThat(ragService.resolveCollection("pingid-col", globalCtx)).isEqualTo(colPing);
        assertThat(ragService.resolveCollection("non-existent", globalCtx)).isNull();

        assertThatThrownBy(() -> new AgenticChatContext(null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> AgenticRequest.builder().allowedAreasLower(null))
            .isInstanceOf(NullPointerException.class);
        assertThat(
            AgenticRequest.builder().query("q").modelOverride("m").build().allowedAreasLower())
            .isEmpty();
    }

    @Test
    @DisplayName("Denied tool (not in allowedTools) is rejected before guardToolExecution can synthesise a response")
    void deniedToolCannotBypassAllowListViaAreaGuard() {
        createMockTool("verify_citations");
        createMockTool("search_corpus");

        doAnswer((Answer<Void>) inv -> {
            Consumer<LlmResponseChunk> cb = inv.getArgument(1);
            LlmToolCallDelta delta =
                new LlmToolCallDelta(0, "call_1", "search_corpus", "{\"query\": \"secret\"}");
            cb.accept(new LlmResponseChunk(null, null, List.of(delta), null));
            return null;
        }).doAnswer((Answer<Void>) inv -> {
            Consumer<LlmResponseChunk> cb = inv.getArgument(1);
            cb.accept(new LlmResponseChunk("Done", null, null, null));
            return null;
        }).when(llmClient).generateStream(any(), any());

        AgenticRequest request =
            AgenticRequest.builder().query("review this").modelOverride("gemini-test")
                .allowedTools(List.of("verify_citations")).allowedAreasLower(Set.of("oim")).build();

        RagResult result = ragService.generateAgenticAnswer(request, token -> {
        });
        LlmMessage toolMsg = result.messages().stream().filter(m -> "tool".equals(m.role()))
            .findFirst().orElseThrow();

        assertThat(toolMsg.content()).isEqualTo("Error: Tool search_corpus not found.");
        verify(hybridSearch, never()).searchWithId(anyString(), any());
    }

    @Test
    @DisplayName("get_section with invalid short chunk_id prefix is caught by the tool error boundary instead of aborting the turn")
    void guardExceptionIsContainedWithinToolErrorBoundary() {
        ToolCallback mockTool = createMockTool("get_section");
        when(chunkRepository.findByIdPrefix("123")).thenThrow(
            new IllegalArgumentException("Chunk ID prefix must be at least 8 hex chars"));
        when(chunkRepository.findByIdPrefix(eq("123"), any())).thenThrow(
            new IllegalArgumentException("Chunk ID prefix must be at least 8 hex chars"));

        String response =
            runTurnWithToolCall("get_section", "{\"chunk_id\": \"123\"}", Set.of("oim"));

        assertThat(response).isNotNull();
        verify(mockTool).call(anyString());
    }

    @Test
    @DisplayName("verify_citations callback preserves VerifyCitationsTool.SCOPE_NOTE and formatting")
    void verifyCitationsGuardPreservesScopeNote() {
        String citToken = "[chunk_id=" + chunkOimId + "]";
        when(citationVerifier.checkCitations(List.of(citToken), Set.of("oim"))).thenReturn(
            List.of(new CitationCheckResult(citToken, "chunk", CitationStatus.REAL, "1 chunk(s)")));

        String response = runTurnWithToolCall("verify_citations",
            "{\"citations\": [\"" + citToken + "\"]}", Set.of("oim"));

        assertThat(response).contains("Every cited id exists in the corpus.");
        assertThat(response).contains("does not read the cited text");
    }

    @Test
    @DisplayName("search_corpus omit-collection on a single tag-scoped collection enforces requireTag")
    void searchCorpusOmitCollectionOnTagScopedCollectionRequiresTag() {
        Collection taggedOim = new Collection(colOimId, "oim-col", "OIM Docs",
            List.of("9.3.1", "10.0"), OffsetDateTime.now(), AREA_OIM);
        when(collectionService.listCollections()).thenReturn(List.of(taggedOim, colPing));

        String response =
            runTurnWithToolCall("search_corpus", "{\"query\": \"find me\"}", Set.of("oim"));

        assertThat(response).contains("is version/tag-scoped").contains("9.3.1").contains("10.0");
        verify(hybridSearch, never()).searchWithId(anyString(), any());
    }

    @Test
    @DisplayName("search_corpus and list_documents omit-collection across multiple area collections enforce tag when any collection is tagged")
    void omitCollectionAcrossMultipleAreaCollectionsEnforcesTagWhenAnyCollectionHasTags() {
        Collection taggedOim1 = new Collection(colOimId, "oim-manuals", "OIM Manuals",
            List.of("9.3.1", "10.0"), OffsetDateTime.now(), AREA_OIM);
        Collection untaggedOim2 = new Collection(UUID.randomUUID(), "oim-kb", "OIM KB", List.of(),
            OffsetDateTime.now(), AREA_OIM);
        Collection taggedOim3 = new Collection(UUID.randomUUID(), "oim-schema", "OIM Schema",
            List.of("10.0"), OffsetDateTime.now(), AREA_OIM);
        when(collectionService.listCollections())
            .thenReturn(List.of(taggedOim1, untaggedOim2, taggedOim3, colPing));

        String searchNoTag =
            runTurnWithToolCall("search_corpus", "{\"query\": \"find me\"}", Set.of("oim"));
        assertThat(searchNoTag).startsWith("Error:").contains("version/tag-scoped")
            .contains("oim-manuals");
        verify(hybridSearch, never()).searchWithId(anyString(), any());

        String listNoTag = runTurnWithToolCall("list_documents", "{}", Set.of("oim"));
        assertThat(listNoTag).startsWith("Error:").contains("version/tag-scoped")
            .contains("oim-manuals");

        String searchMismatchedTag = runTurnWithToolCall("search_corpus",
            "{\"query\": \"find me\", \"tag\": \"9.3.1\"}", Set.of("oim"));
        assertThat(searchMismatchedTag).startsWith("Error:").contains("9.3.1")
            .contains("oim-schema");
        verify(hybridSearch, never()).searchWithId(anyString(), any());

        HybridSearchResult chunkResult =
            new HybridSearchResult(chunkOimId, docOimId, "OIM 10.0 content", List.of("Chapter 1"),
                "Intro", 1, 0, null, "OIM Guide", "manual", "oim-manuals", null, 0.95, null);
        when(hybridSearch.searchWithId(eq("find me"), any(SearchOptions.class)))
            .thenReturn(new SearchWithId(List.of(chunkResult), UUID.randomUUID()));

        String searchSharedTag = runTurnWithToolCall("search_corpus",
            "{\"query\": \"find me\", \"tag\": \"10.0\"}", Set.of("oim"));
        assertThat(searchSharedTag).contains("OIM 10.0 content");
    }

    @Test
    @DisplayName("list_documents omit-collection adopts stored kind casing")
    void listDocumentsOmitCollectionAdoptsStoredKindCasing() {
        DocumentDetails dd = new DocumentDetails(docOimId, colOimId, "oim-col", "table",
            "DialogTable", null, "COMPLETED", 2L, true, Instant.now());
        when(documentRepository.findDistinctKindsInCollection("oim-col"))
            .thenReturn(List.of("table", "manual"));
        when(documentRepository.listDocuments(eq("oim-col"), anyInt(), anyInt(), eq("table"), any(),
            any(), any(), any())).thenReturn(List.of(dd));

        String response =
            runTurnWithToolCall("list_documents", "{\"kind\": \"TABLE\"}", Set.of("oim"));

        assertThat(response).contains("DialogTable");
        verify(documentRepository).listDocuments(eq("oim-col"), anyInt(), anyInt(), eq("table"),
            any(), any(), any(), any());
    }
}
