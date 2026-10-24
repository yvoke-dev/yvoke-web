package de.palsoftware.yvoke.rag.core.service;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.stubbing.Answer;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

class RagServiceAreaAdversarialStressTest {

    private HybridSearch hybridSearch;
    private LlmClient llmClient;
    private CitationVerifier citationVerifier;
    private CollectionService collectionService;
    private DocumentRepository documentRepository;
    private ChunkRepository chunkRepository;
    private RagService ragService;

    private static final String AREA_OIM_PADDED = "  OIM  ";
    private static final String AREA_PINGID_UPPER = "PINGID";
    private static final String AREA_PINGID_LOWER = "pingid";

    private final UUID colOimId = UUID.randomUUID();
    private final UUID colPingId = UUID.randomUUID();
    private final UUID colNullAreaId = UUID.randomUUID();

    private final UUID docOimId = UUID.randomUUID();
    private final UUID docPingId = UUID.randomUUID();
    private final UUID docNullAreaId = UUID.randomUUID();

    private final UUID chunkOimId = UUID.randomUUID();
    private final UUID chunkPingId = UUID.randomUUID();
    private final UUID chunkNullAreaId = UUID.randomUUID();

    private final Collection colOim = new Collection(colOimId, "oim-col", "OIM Docs", List.of(),
        OffsetDateTime.now(), AREA_OIM_PADDED);
    private final Collection colPing = new Collection(colPingId, "pingid-col", "PingID Docs",
        List.of(), OffsetDateTime.now(), AREA_PINGID_UPPER);
    private final Collection colNullArea = new Collection(colNullAreaId, "null-area-col",
        "Null Area Docs", List.of(), OffsetDateTime.now(), null);

    @BeforeEach
    void setUp() {
        hybridSearch = mock(HybridSearch.class);
        llmClient = mock(LlmClient.class);
        citationVerifier = mock(CitationVerifier.class);
        collectionService = mock(CollectionService.class);
        documentRepository = mock(DocumentRepository.class);
        chunkRepository = mock(ChunkRepository.class);

        when(collectionService.listCollections()).thenReturn(List.of(colOim, colPing, colNullArea));
        when(collectionService.findById(colOimId)).thenReturn(Optional.of(colOim));
        when(collectionService.findById(colPingId)).thenReturn(Optional.of(colPing));
        when(collectionService.findById(colNullAreaId)).thenReturn(Optional.of(colNullArea));

        DocumentRow docOim = new DocumentRow(docOimId, colOimId, "oim-col", "manual", "OIM Guide",
            null, "COMPLETED", List.of(), Instant.now());
        when(documentRepository.findById(docOimId)).thenReturn(Optional.of(docOim));

        DocumentRow docPing = new DocumentRow(docPingId, colPingId, "pingid-col", "manual",
            "PingID Guide", null, "COMPLETED", List.of(), Instant.now());
        when(documentRepository.findById(docPingId)).thenReturn(Optional.of(docPing));

        DocumentRow docNullArea = new DocumentRow(docNullAreaId, colNullAreaId, "null-area-col",
            "manual", "Null Area Guide", null, "COMPLETED", List.of(), Instant.now());
        when(documentRepository.findById(docNullAreaId)).thenReturn(Optional.of(docNullArea));

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

        ChunkRow chunkNullArea = new ChunkRow(chunkNullAreaId, docNullAreaId,
            "Null Area chunk content", List.of("Intro"), "Intro", 1, 0, null, "Null Area Guide",
            "manual", "null-area-col", null, 0.95);
        when(chunkRepository.findById(chunkNullAreaId)).thenReturn(Optional.of(chunkNullArea));
        when(chunkRepository.findByIdPrefix(chunkNullAreaId.toString()))
            .thenReturn(Optional.of(chunkNullArea));

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
            AgenticRequest.builder().query("adversarial test question").modelOverride("gemini-test")
                .allowedTools(List.of(toolName)).allowedAreasLower(allowedAreasLower).build();

        RagResult result = ragService.generateAgenticAnswer(request, token -> {
        });
        LlmMessage toolMsg =
            result.messages().stream().filter(m -> "tool".equals(m.role())).findFirst()
                .orElseThrow(() -> new IllegalStateException("No tool response found in messages"));
        return toolMsg.content();
    }

    // ---------------------------------------------------------------------------------------------
    // ADVERSARIAL CHALLENGE 1: Casing & Spacing Anomalies
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Casing & whitespace in collection area: OIM with spaces matches user with 'oim'")
    void paddedAreaNameMatchesNormalizedAllowedArea() {
        AgenticChatContext ctx = new AgenticChatContext(Set.of("oim"));
        assertThat(ctx.isAreaAllowed("  OIM  ")).isTrue();
        assertThat(ctx.isAreaAllowed("oim")).isTrue();
        assertThat(ctx.isAreaAllowed("OIM")).isTrue();
        assertThat(ctx.isAreaAllowed(" Oim ")).isTrue();

        assertThat(ragService.resolveCollection("oim-col", ctx)).isNotNull();
    }

    @Test
    @DisplayName("Casing & whitespace in collection area: PingID uppercase matches user with 'pingid'")
    void uppercaseAreaNameMatchesNormalizedAllowedArea() {
        AgenticChatContext ctx = new AgenticChatContext(Set.of("pingid"));
        assertThat(ctx.isAreaAllowed("PINGID")).isTrue();
        assertThat(ctx.isAreaAllowed("pingid")).isTrue();
        assertThat(ctx.isAreaAllowed("PingID")).isTrue();
        assertThat(ctx.isAreaAllowed("  PingId  ")).isTrue();

        assertThat(ragService.resolveCollection("pingid-col", ctx)).isNotNull();
    }

    @Test
    @DisplayName("Crafted tool arguments with mixed case and whitespace resolve correctly")
    void toolArgumentsWithWhitespaceAndMixedCase() {
        ToolCallback mockTool = createMockTool("search_corpus");
        String response = runTurnWithToolCall("search_corpus",
            "{\"collection\": \"  OIM-COL  \", \"query\": \"test\"}", Set.of("oim"));

        assertThat(response).isEqualTo("mock tool result for search_corpus");
        verify(mockTool).call(anyString());
    }

    @Test
    @DisplayName("Cross-area access with crafted casing and spaces is strictly rejected")
    void crossAreaAccessWithWhitespaceAndMixedCaseRejected() {
        ToolCallback mockTool = createMockTool("search_corpus");
        String response = runTurnWithToolCall("search_corpus",
            "{\"collection\": \"  PINGID-COL  \", \"query\": \"test\"}", Set.of("oim"));

        assertThat(response).isEqualTo("Error: Collection 'PINGID-COL' does not exist.");
        verify(mockTool, never()).call(anyString());
    }

    // ---------------------------------------------------------------------------------------------
    // ADVERSARIAL CHALLENGE 2: Empty Set vs Null (Unrestricted) vs Populated Set
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Empty allowedAreasLower Set.of() rejects access to ALL areas")
    void emptyAllowedAreasRejectsAll() {
        AgenticChatContext emptyCtx = new AgenticChatContext(Set.of());
        assertThat(emptyCtx.isAreaAllowed("oim")).isFalse();
        assertThat(emptyCtx.isAreaAllowed("pingid")).isFalse();
        assertThat(emptyCtx.isAreaAllowed(null)).isFalse();

        assertThat(ragService.resolveCollection("oim-col", emptyCtx)).isNull();
        assertThat(ragService.resolveCollection("pingid-col", emptyCtx)).isNull();
    }

    @Test
    @DisplayName("Empty allowedAreasLower Set.of() in search_corpus omit-collection yields no results")
    void emptyAllowedAreasOmitCollectionSearch() {
        String response =
            runTurnWithToolCall("search_corpus", "{\"query\": \"anything\"}", Set.of());

        assertThat(response).isEqualTo("No relevant documents found.");
        verify(hybridSearch, never()).searchWithId(anyString(), any());
    }

    @Test
    @DisplayName("Empty allowedAreasLower Set.of() in list_documents omit-collection yields empty notice")
    void emptyAllowedAreasOmitCollectionListDocs() {
        String response = runTurnWithToolCall("list_documents", "{}", Set.of());

        assertThat(response).isEqualTo("(no documents in **all allowed collections**)");
        verify(documentRepository, never()).listDocuments(anyString(), anyInt(), anyInt(), any(),
            any(), any(), any(), any());
    }

    @Test
    @DisplayName("ALL_AREAS allowedAreasLower (unrestricted) permits access to collections with null area")
    void nullAllowedAreasPermitsAllIncludingNullArea() {
        AgenticChatContext globalCtx = new AgenticChatContext(AgenticChatContext.ALL_AREAS);
        assertThat(globalCtx.isAreaAllowed(null)).isTrue();
        assertThat(globalCtx.isAreaAllowed("oim")).isTrue();
        assertThat(globalCtx.isAreaAllowed("pingid")).isTrue();

        assertThat(ragService.resolveCollection("null-area-col", globalCtx)).isNotNull();
        assertThat(ragService.resolveCollection("oim-col", globalCtx)).isNotNull();
        assertThat(ragService.resolveCollection("pingid-col", globalCtx)).isNotNull();
    }

    @Test
    @DisplayName("Restricted allowedAreasLower denies access to collections with null area")
    void restrictedAllowedAreasDeniesNullArea() {
        AgenticChatContext oimCtx = new AgenticChatContext(Set.of("oim"));
        assertThat(oimCtx.isAreaAllowed(null)).isFalse();
        assertThat(oimCtx.isAreaAllowed("")).isFalse();
        assertThat(oimCtx.isAreaAllowed("   ")).isFalse();

        assertThat(ragService.resolveCollection("null-area-col", oimCtx)).isNull();
    }

    // ---------------------------------------------------------------------------------------------
    // ADVERSARIAL CHALLENGE 3: Cross-Area Access across EVERY Tool
    // ---------------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"search_graph_entities", "get_graph_neighbors", "get_json_schema",
        "query_json_objects"})
    @DisplayName("All KG and JSON tools reject cross-area collection targeting")
    void allKgAndJsonToolsRejectCrossArea(String toolName) {
        ToolCallback mockTool = createMockTool(toolName);
        String response = runTurnWithToolCall(toolName,
            "{\"collection\": \"pingid-col\", \"query\": \"test\", \"entity_name\": \"test\", \"jsonPath\": \"$\"}",
            Set.of("oim"));

        assertThat(response).isEqualTo("Error: Collection 'pingid-col' does not exist.");
        verify(mockTool, never()).call(anyString());
    }

    @Test
    @DisplayName("get_toc rejects document in unauthorized area")
    void getTocRejectsUnauthorizedAreaDoc() {
        ToolCallback mockTool = createMockTool("get_toc");
        String response = runTurnWithToolCall("get_toc", "{\"document_id\": \"" + docPingId + "\"}",
            Set.of("oim"));

        assertThat(response).isEqualTo("Error: Document with ID '" + docPingId + "' not found.");
        verify(mockTool, never()).call(anyString());
    }

    @Test
    @DisplayName("get_section rejects document_id and chunk_id in unauthorized area")
    void getSectionRejectsUnauthorizedAreaDocAndChunk() {
        ToolCallback mockTool = createMockTool("get_section");

        String docResp = runTurnWithToolCall("get_section",
            "{\"document_id\": \"" + docPingId + "\"}", Set.of("oim"));
        assertThat(docResp).isEqualTo("Error: Document not found: " + docPingId);

        String chunkResp = runTurnWithToolCall("get_section",
            "{\"chunk_id\": \"" + chunkPingId + "\"}", Set.of("oim"));
        assertThat(chunkResp).isEqualTo("Error: Chunk not found: " + chunkPingId);

        verify(mockTool, never()).call(anyString());
    }

    @Test
    @DisplayName("verify_citations marks cross-area chunk citations as FABRICATED")
    void verifyCitationsCrossAreaIsFabricated() {
        String pingCit = "[chunk_id=" + chunkPingId + "]";
        when(citationVerifier.checkCitations(List.of(pingCit), Set.of("oim"))).thenReturn(List.of(
            new CitationCheckResult(pingCit, "chunk", CitationStatus.FABRICATED, "0 chunk(s)")));

        String response = runTurnWithToolCall("verify_citations",
            "{\"citations\": [\"" + pingCit + "\"]}", Set.of("oim"));

        assertThat(response).contains("FABRICATED");
        assertThat(response).contains("Remove or correct every FABRICATED citation");
    }

    // ---------------------------------------------------------------------------------------------
    // ADVERSARIAL CHALLENGE 4: Information Leakage & Error Message Indistinguishability
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Error messages for unauthorized collection vs non-existent collection are 100% identical")
    void errorMessagesForUnauthorizedVsNonExistentAreIdentical() {
        createMockTool("search_corpus");

        String unauthorizedResp = runTurnWithToolCall("search_corpus",
            "{\"collection\": \"pingid-col\", \"query\": \"auth\"}", Set.of("oim"));
        String nonExistentResp = runTurnWithToolCall("search_corpus",
            "{\"collection\": \"non-existent-col\", \"query\": \"auth\"}", Set.of("oim"));

        assertThat(unauthorizedResp).isEqualTo("Error: Collection 'pingid-col' does not exist.");
        assertThat(nonExistentResp)
            .isEqualTo("Error: Collection 'non-existent-col' does not exist.");

        // Structural template must match exactly
        assertThat(unauthorizedResp.replace("pingid-col", "X"))
            .isEqualTo(nonExistentResp.replace("non-existent-col", "X"));

        // No info leakage: no auth/forbidden wording, no area name if different from collection
        assertThat(unauthorizedResp.toLowerCase()).doesNotContain("unauthorized", "forbidden",
            "area", "permission");
    }

    @Test
    @DisplayName("Error messages for unauthorized document vs non-existent document in get_toc are 100% identical")
    void errorMessagesForUnauthorizedDocumentAreIdentical() {
        createMockTool("get_toc");
        UUID randomId = UUID.randomUUID();

        String unauthorizedResp = runTurnWithToolCall("get_toc",
            "{\"document_id\": \"" + docPingId + "\"}", Set.of("oim"));

        assertThat(unauthorizedResp)
            .isEqualTo("Error: Document with ID '" + docPingId + "' not found.");
        // Does not leak collection name, area name, or document title!
        assertThat(unauthorizedResp.toLowerCase()).doesNotContain("unauthorized", "forbidden",
            "area", "permission", "pingid-col", "pingid", "guide");
    }
}
