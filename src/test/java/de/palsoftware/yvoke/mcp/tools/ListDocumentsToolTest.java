package de.palsoftware.yvoke.mcp.tools;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.service.CollectionService;
import de.palsoftware.yvoke.document.core.model.DocumentDetails;
import de.palsoftware.yvoke.document.core.repository.DocumentRepository;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ListDocumentsToolTest {

    private DocumentRepository documentRepository;
    private CollectionService collectionService;
    private ListDocumentsTool listDocumentsTool;

    @BeforeEach
    public void setUp() {
        documentRepository = mock(DocumentRepository.class);
        collectionService = mock(CollectionService.class);
        listDocumentsTool = new ListDocumentsTool(documentRepository, collectionService);

        Collection dbCol = new Collection(UUID.randomUUID(), "OIM - DB", "DB Schema",
            List.of("9.3", "10.0"), null);
        Collection manualsCol =
            new Collection(UUID.randomUUID(), "OIM - Manuals", "Manuals", List.of("9.3"), null);
        Collection codeCol =
            new Collection(UUID.randomUUID(), "OIM - Code", "Code", List.of("9.3"), null);
        when(collectionService.listCollections()).thenReturn(List.of(dbCol, manualsCol, codeCol));
    }

    @Test
    public void testListDocumentsSuccess() {
        UUID docId = UUID.randomUUID();
        DocumentDetails doc = new DocumentDetails(docId, UUID.randomUUID(), "OIM - Manuals",
            "manual", "QBM Configuration", null, "completed", 42L, true, Instant.now());
        when(documentRepository.listDocuments(eq("OIM - Manuals"), eq(100), eq(0), any(), any(),
            any(), any(), any())).thenReturn(List.of(doc));
        when(documentRepository.countDocuments(eq("OIM - Manuals"), any(), any(), any(), any(),
            any())).thenReturn(1L);

        String output =
            listDocumentsTool.listDocuments("OIM - Manuals", null, "9.3", null, null, null);

        assertTrue(output.contains("**OIM - Manuals**"));
        assertTrue(output.contains("showing 1–1 of 1"));
        assertTrue(output.contains("| document_id | kind | title | chunk count |"));
        assertTrue(output.contains(docId.toString()));
        assertTrue(output.contains("manual"));
        assertTrue(output.contains("QBM Configuration"));
        assertTrue(output.contains("42"));
    }

    @Test
    public void testListDocumentsFuzzyQueryIsForwarded() {
        UUID docId = UUID.randomUUID();
        DocumentDetails doc = new DocumentDetails(docId, UUID.randomUUID(), "OIM - Manuals",
            "manual", "VI_Delete Processes", null, "completed", 3L, true, Instant.now());
        // The trimmed query must reach the repository fuzzy-title parameter (last arg).
        when(documentRepository.listDocuments(eq("OIM - Manuals"), eq(100), eq(0), isNull(),
            eq("9.3"), isNull(), isNull(), eq("VI_Delete"))).thenReturn(List.of(doc));
        when(documentRepository.countDocuments(eq("OIM - Manuals"), isNull(), eq("9.3"), isNull(),
            isNull(), eq("VI_Delete"))).thenReturn(1L);

        String output = listDocumentsTool.listDocuments("OIM - Manuals", null, "9.3", null, null,
            "  VI_Delete  ");

        assertTrue(output.contains("VI_Delete Processes"));
        assertTrue(output.contains("showing 1–1 of 1"));
    }

    @Test
    public void testListDocumentsEmpty() {
        when(documentRepository.listDocuments(any(), anyInt(), anyInt(), any(), any(), any(), any(),
            any())).thenReturn(Collections.emptyList());
        when(documentRepository.countDocuments(any(), any(), any(), any(), any(), any()))
            .thenReturn(0L);

        String output =
            listDocumentsTool.listDocuments("OIM - DB", "table", "9.3", null, null, null);
        assertTrue(output.contains("(no documents in **OIM - DB**"));
    }

    @Test
    public void testListDocumentsMissingCollections() {
        String outputNull = listDocumentsTool.listDocuments(null, null, null, null, null, null);
        assertTrue(outputNull.contains("Error: 'collection' parameter is required."));

        String outputEmpty = listDocumentsTool.listDocuments("   ", null, null, null, null, null);
        assertTrue(outputEmpty.contains("Error: 'collection' parameter is required."));
    }

    @Test
    public void testListDocumentsInvalidKind() {
        when(documentRepository.findDistinctKindsInCollection("OIM - Code"))
            .thenReturn(List.of("table", "view", "procedure"));

        String output =
            listDocumentsTool.listDocuments("OIM - Code", "invalid-kind", "9.3", null, null, null);
        assertTrue(output.contains(
            "Error: kind 'invalid-kind' is not valid or does not exist in collection 'OIM - Code'"));
        assertTrue(output.contains("Valid kinds in this collection are: [table, view, procedure]"));
    }

    @Test
    public void testListDocumentsValidKindCaseInsensitive() {
        when(documentRepository.findDistinctKindsInCollection("OIM - Code"))
            .thenReturn(List.of("table", "view", "procedure"));
        UUID docId = UUID.randomUUID();
        DocumentDetails doc = new DocumentDetails(docId, UUID.randomUUID(), "OIM - Code", "table",
            "ADSAccount Table", null, "completed", 5L, true, Instant.now());
        when(documentRepository.listDocuments(eq("OIM - Code"), eq(100), eq(0), eq("TABLE"), any(),
            any(), any(), any())).thenReturn(List.of(doc));
        when(documentRepository.countDocuments(eq("OIM - Code"), eq("TABLE"), any(), any(), any(),
            any())).thenReturn(1L);

        String output =
            listDocumentsTool.listDocuments("OIM - Code", "TABLE", "9.3", null, null, null);
        assertTrue(output.contains("**OIM - Code**"));
        assertTrue(output.contains("TABLE"));
    }

    @Test
    public void testListDocumentsCollectionDoesNotExist() {
        String output =
            listDocumentsTool.listDocuments("Nonexistent-Col", null, null, null, null, null);
        assertTrue(output.contains("Error: Collection 'Nonexistent-Col' does not exist."));
    }

    @Test
    public void testListDocumentsTagDoesNotExist() {
        // Tag "11.0" doesn't exist in OIM - DB which only has tags "9.3" and "10.0"
        String output = listDocumentsTool.listDocuments("OIM - DB", null, "11.0", null, null, null);
        assertTrue(output.contains("Error: Tag '11.0' does not exist in collection 'OIM - DB'."));
    }

    @Test
    public void testListDocumentsSuccessWithMultipleTags() {
        String output =
            listDocumentsTool.listDocuments("OIM - DB", null, "9.3,10.0", null, null, null);
        assertTrue(
            output.contains("Error: Tag '9.3,10.0' does not exist in collection 'OIM - DB'."));
    }

    @Test
    public void testUntaggedCallOnATagScopedCollectionIsRejected() {
        String output =
            listDocumentsTool.listDocuments("OIM - Manuals", null, null, null, null, null);

        assertTrue(output.startsWith("Error:"), "expected a hard error, got:\n" + output);
        assertTrue(output.contains("tag-scoped"));
        assertTrue(output.contains("9.3"));
    }

}
