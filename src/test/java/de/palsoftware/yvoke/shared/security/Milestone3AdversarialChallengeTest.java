package de.palsoftware.yvoke.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.area.core.Area;
import de.palsoftware.yvoke.area.core.AreaMembers;
import de.palsoftware.yvoke.area.core.AreaRepository;
import de.palsoftware.yvoke.area.core.AreaService;
import de.palsoftware.yvoke.chat.core.ChatProperties;
import de.palsoftware.yvoke.chat.core.model.Conversation;
import de.palsoftware.yvoke.chat.core.model.ConversationLockState;
import de.palsoftware.yvoke.chat.core.model.ConversationSidebar;
import de.palsoftware.yvoke.chat.core.repository.ConversationRepository;
import de.palsoftware.yvoke.chat.core.service.ChatCancellationService;
import de.palsoftware.yvoke.chat.core.service.ChatConversationService;
import de.palsoftware.yvoke.chat.core.service.ChatFeedbackService;
import de.palsoftware.yvoke.chat.core.service.ChatMessageService;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProfileService;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProperties;
import de.palsoftware.yvoke.chat.web.ChatController;
import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.service.CollectionService;
import de.palsoftware.yvoke.document.core.model.ChunkRow;
import de.palsoftware.yvoke.document.core.model.DocumentRow;
import de.palsoftware.yvoke.document.core.model.SectionResponse;
import de.palsoftware.yvoke.document.core.repository.ChunkRepository;
import de.palsoftware.yvoke.document.core.repository.DocumentRepository;
import de.palsoftware.yvoke.document.core.service.SectionService;
import de.palsoftware.yvoke.document.web.CitationController;
import de.palsoftware.yvoke.mcp.tools.AreaTools;
import de.palsoftware.yvoke.mcp.tools.ListDocumentsTool;
import de.palsoftware.yvoke.mcp.tools.PlaybookTools;
import de.palsoftware.yvoke.mcp.tools.SearchCorpusTool;
import de.palsoftware.yvoke.rag.prompt.Playbook;
import de.palsoftware.yvoke.rag.prompt.PlaybookRepository;
import de.palsoftware.yvoke.rag.prompt.PlaybookService;
import de.palsoftware.yvoke.rag.retrieval.HybridSearch;
import de.palsoftware.yvoke.shared.user.model.User;
import de.palsoftware.yvoke.shared.user.service.UserService;
import de.palsoftware.yvoke.tag.core.repository.TagRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.web.server.ResponseStatusException;

/**
 * Empirical adversarial stress tests challenging Milestone 3 (Wave 3) security boundaries: 1.
 * Cross-Area Conversation Access & Public Conversations area filtering 2. Cross-Area Citations
 * (chunk and document 404 opaque refusal) 3. MCP Tools Opaque Refusal across unauthorized areas
 */
public class Milestone3AdversarialChallengeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // Conversation mocks
    private ConversationRepository conversationRepository;
    private UserService userService;
    private TagRepository tagRepository;
    private ChatConversationService chatConversationService;
    private ChatMessageService chatMessageService;
    private ChatFeedbackService chatFeedbackService;
    private ChatCancellationService chatCancellationService;
    private PlaybookService playbookService;
    private OrchestratorProfileService orchestratorProfileService;
    private ChatController chatController;

    // Citation mocks
    private SectionService sectionService;
    private ChunkRepository chunkRepository;
    private DocumentRepository documentRepository;
    private CollectionService collectionService;
    private CitationController citationController;

    // MCP Tools mocks
    private AreaRepository areaRepository;
    private AreaService areaService;
    private AreaTools areaTools;
    private PlaybookRepository playbookRepository;
    private PlaybookTools playbookTools;
    private HybridSearch hybridSearch;
    private SearchCorpusTool searchCorpusTool;
    private ListDocumentsTool listDocumentsTool;

    @BeforeEach
    public void setUp() {
        // Conversation setup
        conversationRepository = mock(ConversationRepository.class);
        userService = mock(UserService.class);
        tagRepository = mock(TagRepository.class);
        ChatProperties chatProperties = new ChatProperties(true, List.of("model-a"), true, false);
        chatConversationService = new ChatConversationService(conversationRepository, userService,
            chatProperties, tagRepository);
        chatMessageService = mock(ChatMessageService.class);
        chatFeedbackService = mock(ChatFeedbackService.class);
        chatCancellationService = mock(ChatCancellationService.class);
        playbookService = mock(PlaybookService.class);
        OrchestratorProperties orchestratorProperties =
            new OrchestratorProperties(3, 8, null, List.of());
        orchestratorProfileService = mock(OrchestratorProfileService.class);
        chatController = new ChatController(chatConversationService, chatMessageService,
            chatFeedbackService, chatCancellationService, playbookService, orchestratorProperties,
            orchestratorProfileService);
        when(chatMessageService.resolveLockState(any(), any()))
            .thenReturn(ConversationLockState.from(Optional.empty(), false, false));

        // Citation setup
        sectionService = mock(SectionService.class);
        chunkRepository = mock(ChunkRepository.class);
        documentRepository = mock(DocumentRepository.class);
        collectionService = mock(CollectionService.class);
        citationController = new CitationController(sectionService, chunkRepository,
            documentRepository, collectionService);

        // MCP Tools setup
        areaRepository = mock(AreaRepository.class);
        areaService = new AreaService(areaRepository);
        areaTools = new AreaTools(areaService, objectMapper);

        playbookRepository = mock(PlaybookRepository.class);
        playbookTools =
            new PlaybookTools(playbookService, playbookRepository, areaService, objectMapper);

        hybridSearch = mock(HybridSearch.class);
        searchCorpusTool = new SearchCorpusTool(hybridSearch, collectionService, 10, 50);
        listDocumentsTool = new ListDocumentsTool(documentRepository, collectionService);
    }

    @AfterEach
    public void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // =========================================================================
    // Challenge 1: Cross-Area Conversation Access & Public Conversations
    // =========================================================================

    @Test
    public void authenticatedUserInAreaPingId_accessingConversationInOim_receives404OpaqueRefusal() {
        UUID convId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        User currentUser = new User(ownerId, "entra-user", "user@test.local", "U", null);
        when(userService.getCurrentUser()).thenReturn(Optional.of(currentUser));

        Conversation oimConv = new Conversation(convId, ownerId, "OIM Secret",
            Collections.emptyMap(), Instant.now(), Instant.now(), List.of(), "web", "OIM");
        when(conversationRepository.findById(convId)).thenReturn(Optional.of(oimConv));

        // Authenticated user with PingID area only
        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken("user", "pass", "ROLE_USER", "AREA_USER_PINGID"));

        // 1. getConversation throws 404
        assertThatThrownBy(() -> chatConversationService.getConversation(convId))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND));

        // 2. verifyOwnership(id, false) throws 404
        assertThatThrownBy(() -> chatConversationService.verifyOwnership(convId, false))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND));

        // 3. chatController.threadView throws 404
        Model model = new ConcurrentModel();
        assertThatThrownBy(() -> chatController.threadView(convId, model))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    public void publicConversationInOim_userWithOnlyPingIdAuthority_cannotAccessOrListIt() {
        UUID convId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID viewerId = UUID.randomUUID();
        User viewer = new User(viewerId, "viewer", "viewer@test.local", "V", null);
        when(userService.getCurrentUser()).thenReturn(Optional.of(viewer));

        Conversation oimPublicConv = new Conversation(convId, ownerId, "OIM Public Chat",
            Collections.emptyMap(), Instant.now(), Instant.now(), List.of("public"), "web", "OIM");
        when(conversationRepository.findById(convId)).thenReturn(Optional.of(oimPublicConv));

        // Caller has only AREA_USER_PINGID
        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken("viewer", "pass", "ROLE_USER", "AREA_USER_PINGID"));

        // 1. Direct access via getConversation yields 404
        assertThatThrownBy(() -> chatConversationService.getConversation(convId))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND));

        // 2. Direct read-only verifyOwnership yields 404
        assertThatThrownBy(() -> chatConversationService.verifyOwnership(convId, true))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND));

        // 3. listAllConversations passes allowedAreasLower = ['pingid'] to repository
        chatConversationService.listAllConversations(50, 0);
        verify(conversationRepository).listAll(viewerId, 50, 0, Set.of("pingid"));

        // 4. buildSidebar passes allowedAreasLower = ['pingid'] and excludes OIM from sidebar
        when(conversationRepository.listAll(eq(viewerId), any(Integer.class), eq(0),
            eq(Set.of("pingid")))).thenReturn(List.of()); // Simulated SQL response which filtered
                                                          // out OIM
        ConversationSidebar sidebar = chatConversationService.buildSidebar();
        assertThat(sidebar.conversations()).isEmpty();
        assertThat(sidebar.publicFolders()).isEmpty();
        assertThat(sidebar.publicUntagged()).isEmpty();
        assertThat(sidebar.publicCount()).isEqualTo(0);
    }

    @Test
    public void publicConversationInOim_userWithOimAuthority_canAccessAndListIt() {
        UUID convId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID viewerId = UUID.randomUUID();
        User viewer = new User(viewerId, "viewer", "viewer@test.local", "V", null);
        when(userService.getCurrentUser()).thenReturn(Optional.of(viewer));

        Conversation oimPublicConv = new Conversation(convId, ownerId, "OIM Public Chat",
            Collections.emptyMap(), Instant.now(), Instant.now(), List.of("public"), "web", "OIM");
        when(conversationRepository.findById(convId)).thenReturn(Optional.of(oimPublicConv));

        // Caller has AREA_USER_OIM
        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken("viewer", "pass", "ROLE_USER", "AREA_USER_OIM"));

        // Direct access succeeds
        Optional<Conversation> conv = chatConversationService.getConversation(convId);
        assertThat(conv).isPresent();
        assertThat(conv.get().id()).isEqualTo(convId);

        // verifyOwnership(convId, true) succeeds
        Conversation verified = chatConversationService.verifyOwnership(convId, true);
        assertThat(verified).isNotNull();

        // listAllConversations requests allowedAreasLower = ['oim']
        chatConversationService.listAllConversations(50, 0);
        verify(conversationRepository).listAll(viewerId, 50, 0, Set.of("oim"));
    }

    @Test
    public void conversationWithNullArea_allowsOwnerAndPublicAccessAcrossUsers() {
        UUID convId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID viewerId = UUID.randomUUID();
        User viewer = new User(viewerId, "viewer", "viewer@test.local", "V", null);
        when(userService.getCurrentUser()).thenReturn(Optional.of(viewer));

        Conversation globalPublicConv = new Conversation(convId, ownerId, "Global Public Chat",
            Collections.emptyMap(), Instant.now(), Instant.now(), List.of("public"), "web", null);
        when(conversationRepository.findById(convId)).thenReturn(Optional.of(globalPublicConv));

        // Caller has PingID authority only
        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken("viewer", "pass", "ROLE_USER", "AREA_USER_PINGID"));

        // Unscoped public conversation accessible
        Optional<Conversation> conv = chatConversationService.getConversation(convId);
        assertThat(conv).isPresent();
    }

    // =========================================================================
    // Challenge 2: Cross-Area Citations Isolation
    // =========================================================================

    @Test
    public void getChunkCitation_unauthorizedArea_throws404OpaqueRefusal() {
        String chunkId = "chunk-oim-123";
        ChunkRow chunk = new ChunkRow(UUID.randomUUID(), UUID.randomUUID(), "text", List.of(),
            "heading", 1, 1, null, "docTitle", "md", "OIM-Col", null, 1.0);
        when(chunkRepository.findByIdPrefix(chunkId)).thenReturn(Optional.of(chunk));

        Collection col = new Collection(UUID.randomUUID(), "OIM-Col", "desc", List.of(),
            OffsetDateTime.now(), "OIM");
        when(collectionService.getCollection("OIM-Col")).thenReturn(Optional.of(col));

        // User only has AREA_USER_PINGID
        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken("user", "pass", "ROLE_USER", "AREA_USER_PINGID"));

        // 1. API endpoint returns 404
        assertThatThrownBy(() -> citationController.getChunkCitation(chunkId))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND));

        // 2. HTML partial endpoint returns NOT_FOUND_ERROR without leaking source
        Model model = new ConcurrentModel();
        String view = citationController.getCitation(chunkId, null, model);
        assertThat(view).isEqualTo("document/fragments/citation-expander");
        assertThat(model.getAttribute("section")).isNull();
        assertThat(model.getAttribute("error")).isEqualTo("This source is no longer available.");
    }

    @Test
    public void getDocumentCitation_unauthorizedArea_throws404OpaqueRefusal() {
        UUID docId = UUID.randomUUID();
        UUID colId = UUID.randomUUID();
        DocumentRow doc = new DocumentRow(docId, colId, "OIM-Col", "md", "Secret OIM Doc", null,
            "SUCCESS", List.of(), Instant.now());
        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));

        Collection col =
            new Collection(colId, "OIM-Col", "desc", List.of(), OffsetDateTime.now(), "OIM");
        when(collectionService.findById(colId)).thenReturn(Optional.of(col));

        // User only has AREA_USER_PINGID
        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken("user", "pass", "ROLE_USER", "AREA_USER_PINGID"));

        // 1. API endpoint returns 404
        assertThatThrownBy(() -> citationController.getDocumentCitation(docId.toString()))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND));

        // 2. HTML partial endpoint returns NOT_FOUND_ERROR
        Model model = new ConcurrentModel();
        String view = citationController.getCitation(null, docId.toString(), model);
        assertThat(view).isEqualTo("document/fragments/citation-expander");
        assertThat(model.getAttribute("section")).isNull();
        assertThat(model.getAttribute("error")).isEqualTo("This source is no longer available.");
    }

    @Test
    public void getCitation_authorizedArea_returnsSectionContent() {
        String chunkId = "chunk-ping-123";
        ChunkRow chunk = new ChunkRow(UUID.randomUUID(), UUID.randomUUID(), "text", List.of(),
            "heading", 1, 1, null, "docTitle", "md", "Ping-Col", null, 1.0);
        when(chunkRepository.findByIdPrefix(chunkId)).thenReturn(Optional.of(chunk));

        Collection col = new Collection(UUID.randomUUID(), "Ping-Col", "desc", List.of(),
            OffsetDateTime.now(), "PingID");
        when(collectionService.getCollection("Ping-Col")).thenReturn(Optional.of(col));

        SectionResponse section =
            new SectionResponse(List.of("Intro"), "file.md", "1.0", 1, "heading", "ping text");
        when(sectionService.getChunkContent(chunkId)).thenReturn(section);

        // User has AREA_USER_PINGID
        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken("user", "pass", "ROLE_USER", "AREA_USER_PINGID"));

        SectionResponse response = citationController.getChunkCitation(chunkId);
        assertThat(response).isEqualTo(section);

        Model model = new ConcurrentModel();
        String view = citationController.getCitation(chunkId, null, model);
        assertThat(view).isEqualTo("document/fragments/citation-expander");
        assertThat(model.getAttribute("section")).isEqualTo(section);
        assertThat(model.getAttribute("error")).isNull();
    }

    // =========================================================================
    // Challenge 3: MCP Tools Opaque Refusal across Areas
    // =========================================================================

    @Test
    public void mcpAreaTools_unauthorizedArea_returnsIdenticalErrorToNonexistent()
        throws Exception {
        when(areaRepository.findAll()).thenReturn(
            List.of(new Area("OIM", "Oracle Identity", "desc", false, null, null, null, null, null),
                new Area("PingID", "Ping Identity", "desc", false, null, null, null, null, null)));
        when(areaRepository.findAllMembers()).thenReturn(
            Map.of("OIM", new AreaMembers(List.of(), List.of("OIM-Docs"), List.of(), List.of()),
                "PingID", new AreaMembers(List.of(), List.of("Ping-Docs"), List.of(), List.of())));

        // User with PingID area authority only
        SecurityContextHolder.getContext()
            .setAuthentication(new UsernamePasswordAuthenticationToken("user", "pass",
                List.of(new SimpleGrantedAuthority("AREA_USER_PINGID"))));

        // list_areas filters out OIM
        String listJson = areaTools.listAreas();
        JsonNode array = objectMapper.readTree(listJson);
        assertThat(array).hasSize(1);
        assertThat(array.get(0).get("name").asText()).isEqualTo("PingID");

        // get_area_overview on unauthorized area OIM
        String unauthorizedError = areaTools.getAreaOverview("OIM");
        // get_area_overview on nonexistent area UnknownArea
        String nonexistentError = areaTools.getAreaOverview("UnknownArea");

        assertThat(unauthorizedError).isEqualTo("Error: Area 'OIM' not found.");
        assertThat(nonexistentError).isEqualTo("Error: Area 'UnknownArea' not found.");

        // Authorized lookup succeeds
        String pingOverview = areaTools.getAreaOverview("PingID");
        JsonNode pingNode = objectMapper.readTree(pingOverview);
        assertThat(pingNode.get("name").asText()).isEqualTo("PingID");
    }

    @Test
    public void mcpPlaybookTools_unauthorizedArea_returnsIdenticalErrorToNonexistent()
        throws Exception {
        Playbook oimPlaybook = new Playbook("oim-full", "OIM Full", "desc", "instructions",
            List.of(), false, "specialist", false, null, null, false, "OIM", null);
        Playbook pingPlaybook = new Playbook("ping-full", "Ping Full", "desc", "instructions",
            List.of(), false, "specialist", false, null, null, false, "PingID", null);

        when(playbookRepository.findByName("oim-full")).thenReturn(Optional.of(oimPlaybook));
        when(playbookRepository.findByName("ping-full")).thenReturn(Optional.of(pingPlaybook));
        when(playbookRepository.findByName("unknown")).thenReturn(Optional.empty());

        when(playbookService.listSpecializedPlaybooks())
            .thenReturn(List.of(oimPlaybook, pingPlaybook));

        // User with PingID authority only
        SecurityContextHolder.getContext()
            .setAuthentication(new UsernamePasswordAuthenticationToken("user", "pass",
                List.of(new SimpleGrantedAuthority("AREA_USER_PINGID"))));

        // list_playbooks filters out OIM
        String listJson = playbookTools.listPlaybooks(null);
        JsonNode array = objectMapper.readTree(listJson);
        assertThat(array).hasSize(1);
        assertThat(array.get(0).get("name").asText()).isEqualTo("ping-full");

        // get_playbook on unauthorized area
        String unauthorizedError = playbookTools.getPlaybook("oim-full");
        // get_playbook on nonexistent playbook
        String nonexistentError = playbookTools.getPlaybook("unknown");

        assertThat(unauthorizedError).isEqualTo("ERROR: playbook 'oim-full' not found.");
        assertThat(nonexistentError).isEqualTo("ERROR: playbook 'unknown' not found.");

        // get_playbook on authorized area succeeds
        String pingJson = playbookTools.getPlaybook("ping-full");
        JsonNode pbNode = objectMapper.readTree(pingJson);
        assertThat(pbNode.get("name").asText()).isEqualTo("ping-full");
    }

    @Test
    public void mcpSearchCorpusAndListDocuments_unauthorizedArea_returnsIdenticalErrorToNonexistent() {
        Collection oimCol = new Collection(UUID.randomUUID(), "OIM-Col", "OIM docs", List.of(),
            OffsetDateTime.now(), "OIM");
        Collection pingCol = new Collection(UUID.randomUUID(), "Ping-Col", "Ping docs", List.of(),
            OffsetDateTime.now(), "PingID");

        when(collectionService.listCollections()).thenReturn(List.of(oimCol, pingCol));

        // User with PingID authority only
        SecurityContextHolder.getContext()
            .setAuthentication(new UsernamePasswordAuthenticationToken("user", "pass",
                List.of(new SimpleGrantedAuthority("AREA_USER_PINGID"))));

        // SearchCorpus: unauthorized vs nonexistent
        String searchUnauthorized =
            searchCorpusTool.searchCorpus("query", "OIM-Col", null, null, null);
        String searchNonexistent =
            searchCorpusTool.searchCorpus("query", "Unknown-Col", null, null, null);

        assertThat(searchUnauthorized).isEqualTo("Error: Collection 'OIM-Col' does not exist.");
        assertThat(searchNonexistent).isEqualTo("Error: Collection 'Unknown-Col' does not exist.");

        // ListDocuments: unauthorized vs nonexistent
        String listUnauthorized =
            listDocumentsTool.listDocuments("OIM-Col", null, null, null, null, null);
        String listNonexistent =
            listDocumentsTool.listDocuments("Unknown-Col", null, null, null, null, null);

        assertThat(listUnauthorized).isEqualTo("Error: Collection 'OIM-Col' does not exist.");
        assertThat(listNonexistent).isEqualTo("Error: Collection 'Unknown-Col' does not exist.");
    }
}
