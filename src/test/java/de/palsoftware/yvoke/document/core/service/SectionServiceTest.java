package de.palsoftware.yvoke.document.core.service;

import de.palsoftware.yvoke.document.core.model.ChunkPathRow;
import de.palsoftware.yvoke.document.core.model.ChunkRow;
import de.palsoftware.yvoke.document.core.model.DocumentRow;
import de.palsoftware.yvoke.document.core.model.SectionResponse;
import de.palsoftware.yvoke.document.core.repository.ChunkRepository;
import de.palsoftware.yvoke.document.core.repository.DocumentRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class SectionServiceTest {

    private ChunkRepository chunkRepository;
    private DocumentRepository documentRepository;
    private SectionService sectionService;

    private UUID docId = UUID.randomUUID();
    private UUID chunkId1 = UUID.randomUUID();
    private UUID chunkId2 = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        chunkRepository = Mockito.mock(ChunkRepository.class);
        documentRepository = Mockito.mock(DocumentRepository.class);
        sectionService = new SectionService(chunkRepository, documentRepository);
    }

    @Test
    void testGetSectionByManualName() {
        DocumentRow docRow = new DocumentRow(docId, UUID.randomUUID(), "OIM", "manual",
            "Manual Title", Map.of("tag", "9.3", "source_file", "manual1.md"), "completed",
            Collections.emptyList(), Instant.now());

        ChunkRow chunk1 = new ChunkRow(chunkId1, docId,
            "> Section path: Introduction\nIntro Text Part 1", Collections.emptyList(),
            "Introduction (part 1/2)", 1, 10, "9.3", "manual1.md", "manual", "OIM", null, 0.0);

        ChunkRow chunk2 = new ChunkRow(chunkId2, docId,
            "> Section path: Introduction\nIntro Text Part 2", Collections.emptyList(),
            "Introduction (part 2/2)", 1, 20, "9.3", "manual1.md", "manual", "OIM", null, 0.0);

        ChunkPathRow path1 =
            new ChunkPathRow(chunkId1, Collections.emptyList(), "Introduction (part 1/2)", 10);
        ChunkPathRow path2 =
            new ChunkPathRow(chunkId2, Collections.emptyList(), "Introduction (part 2/2)", 20);

        when(documentRepository.findByManual("manual1", "OIM")).thenReturn(Optional.of(docRow));
        when(chunkRepository.findChunkPathsByDocumentId(eq(docId)))
            .thenReturn(List.of(path1, path2));
        when(chunkRepository.findChunksByIds(eq(List.of(chunkId1, chunkId2))))
            .thenReturn(List.of(chunk1, chunk2));

        SectionResponse response = sectionService.getSection("OIM", "manual1", "Introduction");
        assertEquals(List.of("Introduction"), response.headingPath());
        assertEquals(2, response.chunkCount());

        // Assert text is in correct reading order and breadcrumbs stripped
        String expectedText = "# Section: Introduction\n"
            + "_(document: manual1.md  ·  tag: 9.3  ·  2 chunk(s)  ·  with sub-sections)_\n\n"
            + "Intro Text Part 1\n" + "Intro Text Part 2\n";
        assertEquals(expectedText, response.text());
    }
}
