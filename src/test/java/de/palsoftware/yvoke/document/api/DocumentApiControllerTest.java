package de.palsoftware.yvoke.document.api;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.service.CollectionService;
import de.palsoftware.yvoke.document.core.model.DocumentDetails;
import de.palsoftware.yvoke.document.core.repository.DocumentRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Standalone-MockMvc slice test for {@link DocumentApiController}'s single GET endpoint
 * ({@code GET /api/document/v1}). Covers DTO mapping, param trimming, empty result, and the 400 on
 * a missing required param. Auth is bypassed by standalone setup (covered by the security-chain
 * ITs).
 */
@ExtendWith(MockitoExtension.class)
public class DocumentApiControllerTest {

    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private CollectionService collectionService;
    @InjectMocks
    private DocumentApiController controller;

    private MockMvc mockMvc;

    @BeforeEach
    public void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private static DocumentDetails details(UUID id, String title, Map<String, Object> metadata,
        String status) {
        return new DocumentDetails(id, UUID.randomUUID(), "OIM", "manual", title, metadata, status,
            3L, false, Instant.now());
    }

    @Test
    public void listDocumentsReturnsMappedDtosAndTrimsParams() throws Exception {
        when(collectionService.listCollections()).thenReturn(List.of(collection("OIM")));
        UUID id = UUID.randomUUID();
        when(documentRepository.listByCollectionAndTag("OIM", "9.3")).thenReturn(
            List.of(details(id, "Admin Guide", Map.of("source_file", "admin.pdf"), "completed")));

        mockMvc
            .perform(get("/api/document/v1").param("collection", "  OIM  ").param("tag", "  9.3  "))
            .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].id").value(id.toString()))
            .andExpect(jsonPath("$[0].sourceFile").value("admin.pdf"))
            .andExpect(jsonPath("$[0].title").value("Admin Guide"))
            .andExpect(jsonPath("$[0].ingestionStatus").value("completed"));

        verify(documentRepository).listByCollectionAndTag("OIM", "9.3"); // controller trims first
    }

    @Test
    public void listDocumentsHandlesNullMetadataWithoutCrashing() throws Exception {
        when(collectionService.listCollections()).thenReturn(List.of(collection("OIM")));
        when(documentRepository.listByCollectionAndTag("OIM", "9.3"))
            .thenReturn(List.of(details(UUID.randomUUID(), "No Meta", null, "pending")));

        mockMvc.perform(get("/api/document/v1").param("collection", "OIM").param("tag", "9.3"))
            .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].title").value("No Meta"));
    }

    @Test
    public void listDocumentsReturnsEmptyArrayWhenNoMatches() throws Exception {
        when(collectionService.listCollections()).thenReturn(List.of(collection("OIM")));
        when(documentRepository.listByCollectionAndTag("OIM", "9.9")).thenReturn(List.of());

        mockMvc.perform(get("/api/document/v1").param("collection", "OIM").param("tag", "9.9"))
            .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    public void missingRequiredParamYields400() throws Exception {
        mockMvc.perform(get("/api/document/v1").param("collection", "OIM")) // no "tag"
            .andExpect(status().isBadRequest());
    }

    private static Collection collection(String name) {
        return new Collection(UUID.randomUUID(), name, "desc", List.of("9.3"),
            OffsetDateTime.now());
    }

    /**
     * The silent-empty bug this project's own § 6 pitfall names: "Validate leniently, query
     * strictly". An area that does not exist must be refused, not answered with an empty list —
     * which an MCP agent or a script reads as "the corpus does not contain this".
     */
    @Test
    public void anUnknownCollectionIsRefusedRatherThanAnsweredWithAnEmptyList() throws Exception {
        when(collectionService.listCollections()).thenReturn(List.of(collection("OIM")));

        mockMvc.perform(get("/api/document/v1").param("collection", "nope").param("tag", "9.3"))
            .andExpect(status().isNotFound());

        verify(documentRepository, never()).listByCollectionAndTag(anyString(), anyString());
    }

    /**
     * The other half of the same pitfall: matching leniently and then querying with the CALLER's
     * spelling resolves to nothing. The stored name has to be adopted before the query runs.
     */
    @Test
    public void aWronglyCasedCollectionQueriesTheStoredNameNotTheCallersSpelling()
        throws Exception {
        when(collectionService.listCollections()).thenReturn(List.of(collection("OIM")));
        when(documentRepository.listByCollectionAndTag("OIM", "9.3")).thenReturn(List.of());

        mockMvc.perform(get("/api/document/v1").param("collection", "oim").param("tag", "9.3"))
            .andExpect(status().isOk());

        verify(documentRepository).listByCollectionAndTag("OIM", "9.3");
    }
}
