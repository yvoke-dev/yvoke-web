package de.palsoftware.yvoke.document.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.document.core.repository.ChunkSurfacingMessageLookup;
import de.palsoftware.yvoke.document.core.model.ChunkRow;
import de.palsoftware.yvoke.document.core.model.DocumentAdminViews.DocumentDetailPage;
import de.palsoftware.yvoke.document.core.model.DocumentRow;
import de.palsoftware.yvoke.document.core.repository.ChunkRepository;
import de.palsoftware.yvoke.document.core.repository.DocumentRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Which documents get a section-summary panel on the admin detail page.
 *
 * <p>
 * This used to be decided by {@code document.kind()}: hierarchical and confluence yes, everything
 * else an unconditional empty list. That was a correct proxy only while {@code SectionSummarizer}
 * ran in the hierarchical ingest alone. Standard documents can now carry summaries — either
 * ingested with the opt-in setting, or copied from their hierarchical twin — and the kind test hid
 * every one of them, so a document with 485 summaries in the table rendered no panel at all.
 *
 * <p>
 * The gate is now the data itself, which cannot go stale the same way: a document with no rows
 * renders nothing because there is nothing to render, and the template already keys off an empty
 * list ({@code th:if="${not #lists.isEmpty(sectionSummaries)}"}).
 */
public class DocumentAdminViewServiceTest {

    private static final UUID DOC_ID = UUID.randomUUID();

    private DocumentRepository documentRepository;
    private ChunkRepository chunkRepository;
    private DocumentAdminViewService service;

    @BeforeEach
    public void setUp() {
        documentRepository = mock(DocumentRepository.class);
        chunkRepository = mock(ChunkRepository.class);
        service = new DocumentAdminViewService(documentRepository, chunkRepository,
            mock(ChunkSurfacingMessageLookup.class), new ObjectMapper());
    }

    private void seed(String kind, List<Map<String, Object>> summaries) {
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(document(kind)));
        when(chunkRepository.findChunksByDocumentId(any(), isNull())).thenReturn(List.of(chunk()));
        when(documentRepository.findSectionSummaries(DOC_ID)).thenReturn(summaries);
    }

    private DocumentRow document(String kind) {
        return new DocumentRow(DOC_ID, UUID.randomUUID(), "TEST", kind,
            "Connecting to Microsoft Entra", Map.of("source_file", "manual.md"), "completed",
            List.of("9.3.1"), Instant.now());
    }

    private ChunkRow chunk() {
        return new ChunkRow(UUID.randomUUID(), DOC_ID, "body", List.of("Installing"),
            "Prerequisites", 2, 0, null, null, null, "TEST", null, 0.0);
    }

    private static Map<String, Object> summary(String... path) {
        return Map.of("path", List.of(path), "summary", "A summary of that section.");
    }

    @Test
    public void aStandardDocumentShowsTheSectionSummariesItActuallyHas() {
        seed("standard", List.of(summary("Installing", "Prerequisites")));

        DocumentDetailPage page = service.documentDetailPage(DOC_ID).orElseThrow();

        assertThat(page.sectionSummaries())
            .as("standard documents carry summaries now — gating the panel on kind hides every one")
            .hasSize(1);
    }

    @Test
    public void aHierarchicalDocumentStillShowsItsSummaries() {
        seed("hierarchical", List.of(summary("Installing", "Prerequisites")));

        assertThat(service.documentDetailPage(DOC_ID).orElseThrow().sectionSummaries()).hasSize(1);
    }

    @Test
    public void aDocumentWithNoSummariesShowsAnEmptyPanel() {
        seed("standard", List.of());

        assertThat(service.documentDetailPage(DOC_ID).orElseThrow().sectionSummaries()).isEmpty();
    }

    /**
     * The lookup is by document id, so a kind that never has summaries costs one cheap query that
     * returns nothing. That is the point: the emptiness is discovered, not assumed.
     *
     * <p>
     * The seeded kind must be one the deleted gate REJECTED. Seeding {@code confluence} — which the
     * old {@code HIERARCHICAL || CONFLUENCE} test already allowed — makes this pass identically
     * before and after the change, so it would pin nothing while being named for the thing it
     * failed to pin. {@code custom} is rejected by the old gate, so restoring the gate fails here.
     */
    @Test
    public void theSummaryLookupIsNotSkippedOnAccountOfTheDocumentKind() {
        seed("custom", List.of(summary("Page")));

        service.documentDetailPage(DOC_ID);

        verify(documentRepository).findSectionSummaries(DOC_ID);
    }
}
