package de.palsoftware.yvoke.mcp.tools;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import de.palsoftware.yvoke.document.core.repository.DocumentRepository;
import de.palsoftware.yvoke.document.core.model.DocumentRow;
import de.palsoftware.yvoke.document.core.model.TocNode;
import de.palsoftware.yvoke.document.core.service.TocService;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class GetTocToolTest {

    private DocumentRepository documentRepository;
    private TocService tocService;
    private GetTocTool getTocTool;

    @BeforeEach
    public void setUp() {
        documentRepository = mock(DocumentRepository.class);
        tocService = mock(TocService.class);
        getTocTool = new GetTocTool(documentRepository, tocService);
    }

    @Test
    public void testGetToc() {
        java.util.UUID mockDocId = java.util.UUID.randomUUID();
        DocumentRow mockDoc =
            new DocumentRow(mockDocId, java.util.UUID.randomUUID(), "OIM - Manuals", "manual",
                "Manual Title", java.util.Map.of("tag", "9.3", "source_file", "manual.md"),
                "completed", Collections.emptyList(), java.time.Instant.now());
        when(documentRepository.findById(eq(mockDocId))).thenReturn(Optional.of(mockDoc));

        TocNode node = new TocNode(List.of("Ch1", "SecA"), 10, 5, null);
        when(tocService.getToc(eq(mockDocId))).thenReturn(List.of(node));

        // Test new direct UUID method
        String outputDirect = getTocTool.getToc(mockDocId.toString());
        assertTrue(outputDirect.contains("# Table of contents:"));
        assertTrue(outputDirect.contains("- SecA  _(5 chunks)_"));
    }

    @Test
    public void testGetTocDirectValidationAndNotFound() {
        String out1 = getTocTool.getToc("");
        assertTrue(out1.contains("Error: 'document_id' parameter is required"));

        String out2 = getTocTool.getToc("invalid-uuid");
        assertTrue(out2.contains("Error: Invalid UUID format"));

        java.util.UUID missingId = java.util.UUID.randomUUID();
        when(documentRepository.findById(eq(missingId))).thenReturn(Optional.empty());
        String out3 = getTocTool.getToc(missingId.toString());
        assertTrue(out3.contains("not found"));
    }
}
