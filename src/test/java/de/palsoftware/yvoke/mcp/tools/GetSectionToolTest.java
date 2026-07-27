package de.palsoftware.yvoke.mcp.tools;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import de.palsoftware.yvoke.document.core.model.SectionResponse;
import de.palsoftware.yvoke.document.core.service.SectionService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class GetSectionToolTest {

    private SectionService sectionService;
    private GetSectionTool getSectionTool;

    @BeforeEach
    public void setUp() {
        sectionService = mock(SectionService.class);
        getSectionTool = new GetSectionTool(sectionService);
    }

    @Test
    public void testGetSection() {
        SectionResponse resp = new SectionResponse(List.of("Ch1", "SecA"), "manual.md", "9.3", 1,
            "with sub-sections", "Full section text content.");

        when(sectionService.getSectionByDocumentId(anyString(), any())).thenReturn(resp);

        String output = getSectionTool.getSection("doc-uuid-12345", "Ch1 > SecA", null);
        assertEquals("Full section text content.", output);
    }

    @Test
    public void testGetSectionByChunkId() {
        SectionResponse resp = new SectionResponse(List.of("Ch1", "SecA"), "manual.md", "9.3", 1,
            "with sub-sections", "Chunk content.");

        when(sectionService.getSectionByChunkId(eq("chunk-123"))).thenReturn(resp);

        String output = getSectionTool.getSection(null, null, "chunk-123");
        assertEquals("Chunk content.", output);
    }

    /**
     * Name-based lookup was removed: a document name is not unique (one corpus lookup matched 131
     * rows), so it can never identify a section unambiguously. The tool now says what it needs.
     */
    @Test
    public void testGetSectionWithoutAnIdIsRejected() {
        String output = getSectionTool.getSection(null, null, null);

        assertEquals("Error: Either 'document_id' or 'chunk_id' must be provided.", output);
    }
}
