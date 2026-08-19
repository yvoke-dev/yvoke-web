package de.palsoftware.yvoke.document.core.service;

import de.palsoftware.yvoke.document.core.model.ChunkPathRow;
import de.palsoftware.yvoke.document.core.model.DocumentRow;
import de.palsoftware.yvoke.document.core.model.TocNode;
import de.palsoftware.yvoke.document.core.repository.ChunkRepository;
import de.palsoftware.yvoke.document.core.repository.DocumentRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import java.time.Instant;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

class TocServiceTest {

    private ChunkRepository chunkRepository;
    private DocumentRepository documentRepository;
    private TocService tocService;

    private UUID docId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        chunkRepository = Mockito.mock(ChunkRepository.class);
        documentRepository = Mockito.mock(DocumentRepository.class);
        JdbcClient jdbcClient = Mockito.mock(JdbcClient.class);
        JdbcClient.StatementSpec statementSpec = Mockito.mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<Object> mappedQuerySpec =
            Mockito.mock(JdbcClient.MappedQuerySpec.class);
        when(jdbcClient.sql(anyString())).thenReturn(statementSpec);
        when(statementSpec.param(anyString(), any())).thenReturn(statementSpec);
        @SuppressWarnings("unchecked")
        RowMapper<Object> rowMapper = any(RowMapper.class);
        when(statementSpec.query(rowMapper)).thenReturn(mappedQuerySpec);
        when(mappedQuerySpec.list()).thenReturn(Collections.emptyList());
        tocService = new TocService(chunkRepository, documentRepository, jdbcClient);
    }

    /** The five-chunk hierarchy the scoping tests below share. Depth 3 is the point of it. */
    private DocumentRow seedHierarchy() {
        DocumentRow doc = new DocumentRow(docId, UUID.randomUUID(), "OIM", "manual", "Manual Title",
            Map.of("tag", "9.3", "source_file", "manual1.md"), "completed", List.of(),
            Instant.now());
        ChunkPathRow c1 = new ChunkPathRow(UUID.randomUUID(), List.of(), "Chapter 1", 10, 100);
        ChunkPathRow c2 =
            new ChunkPathRow(UUID.randomUUID(), List.of("Chapter 1"), "Section A", 20, 200);
        ChunkPathRow c3 = new ChunkPathRow(UUID.randomUUID(), List.of("Chapter 1", "Section A"),
            "Subsection 1", 30, 400);
        ChunkPathRow c4 =
            new ChunkPathRow(UUID.randomUUID(), List.of("Chapter 1"), "Section B", 40, 800);
        ChunkPathRow c5 = new ChunkPathRow(UUID.randomUUID(), List.of(), "Chapter 2", 50, 1600);
        when(documentRepository.findById(eq(docId))).thenReturn(Optional.of(doc));
        when(chunkRepository.findChunkPathsByDocumentId(eq(docId)))
            .thenReturn(List.of(c1, c2, c3, c4, c5));
        return doc;
    }

    /**
     * The whole point of R7. The unscoped TOC is capped at absolute depth 2, so
     * {@code Subsection 1} is invisible: an agent could see {@code Chapter 1 > Section A} and had
     * no way to learn what was inside it short of {@code get_section} on the entire section — which
     * is the expensive pull the scoped TOC exists to avoid. Scoping to a path returns the two
     * levels BELOW it.
     */
    @Test
    void aScopedTocReturnsTheLevelsBelowTheScopeNotTheTopOfTheDocument() {
        seedHierarchy();

        List<TocNode> toc = tocService.getToc(docId, List.of("Chapter 1", "Section A"));

        assertEquals(1, toc.size(), "only Subsection 1 lies below Chapter 1 > Section A");
        assertEquals(List.of("Chapter 1", "Section A", "Subsection 1"), toc.get(0).path(),
            "the path must stay ABSOLUTE - the agent copies it straight into get_section, and a "
                + "relative path would produce an unresolvable heading_path");
    }

    /** Scoping must not change what an unscoped call does. */
    @Test
    void anEmptyScopeIsTheWholeDocumentTocUnchanged() {
        seedHierarchy();

        List<TocNode> scoped = tocService.getToc(docId, List.of());
        List<TocNode> plain = tocService.getToc(docId);

        assertEquals(plain.stream().map(TocNode::path).toList(),
            scoped.stream().map(TocNode::path).toList());
        assertEquals(4, scoped.size());
    }

    /**
     * A scope that matches nothing must fail loudly. Silently falling back to the whole-document
     * TOC is the § 6 "validate leniently, query strictly" shape in reverse: the agent asked about
     * one subtree, would be handed another, and nothing would report the substitution.
     */
    @Test
    void aScopeThatMatchesNothingFailsRatherThanReturningTheWholeDocument() {
        seedHierarchy();

        assertThrows(NoSuchElementException.class,
            () -> tocService.getToc(docId, List.of("Chapter 1", "No Such Section")));
    }

    @Test
    void testGetTocSubtreeCountsAndSorting() {
        DocumentRow doc = new DocumentRow(docId, UUID.randomUUID(), "OIM", "manual", "Manual Title",
            Map.of("tag", "9.3", "source_file", "manual1.md"), "completed", List.of(),
            Instant.now());

        // Seed chunks forming the hierarchy:
        // Chapter 1 (sort_order = 10)
        // Section A (sort_order = 20)
        // Subsection 1 (sort_order = 30)
        // Section B (sort_order = 40)
        // Chapter 2 (sort_order = 50)
        ChunkPathRow c1 = new ChunkPathRow(UUID.randomUUID(), List.of(), "Chapter 1", 10, 100);
        ChunkPathRow c2 =
            new ChunkPathRow(UUID.randomUUID(), List.of("Chapter 1"), "Section A", 20, 200);
        ChunkPathRow c3 = new ChunkPathRow(UUID.randomUUID(), List.of("Chapter 1", "Section A"),
            "Subsection 1", 30, 400);
        ChunkPathRow c4 =
            new ChunkPathRow(UUID.randomUUID(), List.of("Chapter 1"), "Section B", 40, 800);
        ChunkPathRow c5 = new ChunkPathRow(UUID.randomUUID(), List.of(), "Chapter 2", 50, 1600);

        when(documentRepository.findByManual(eq("manual1"), eq("OIM")))
            .thenReturn(Optional.of(doc));
        when(chunkRepository.findChunkPathsByDocumentId(eq(docId)))
            .thenReturn(List.of(c1, c2, c3, c4, c5));

        // Test full TOC (hardcoded depth <= 2)
        List<TocNode> toc = tocService.getToc("manual1", "OIM");

        // Output entries should exclude Subsection 1 because its depth is 3 (pl=3) and maxDepth=2
        // Entries should be: Chapter 1, Chapter 1 > Section A, Chapter 1 > Section B, Chapter 2
        assertEquals(4, toc.size());

        // Validate sorted by minSortOrder: Chapter 1 (10) < Chapter 1 > Section A (20) < Chapter 1
        // >
        // Section B (40) <
        // Chapter 2 (50)
        assertEquals(List.of("Chapter 1"), toc.get(0).path());
        assertEquals(10, toc.get(0).minSortOrder());
        // Subtree count for Chapter 1 includes itself + Section A + Subsection 1 + Section B = 4
        // chunks
        assertEquals(4, toc.get(0).subtreeChunkCount());

        assertEquals(List.of("Chapter 1", "Section A"), toc.get(1).path());
        assertEquals(20, toc.get(1).minSortOrder());
        // Subtree count for Section A includes itself + Subsection 1 = 2 chunks
        assertEquals(2, toc.get(1).subtreeChunkCount());

        assertEquals(List.of("Chapter 1", "Section B"), toc.get(2).path());
        assertEquals(40, toc.get(2).minSortOrder());
        assertEquals(1, toc.get(2).subtreeChunkCount());

        assertEquals(List.of("Chapter 2"), toc.get(3).path());
        assertEquals(50, toc.get(3).minSortOrder());
        assertEquals(1, toc.get(3).subtreeChunkCount());
    }
}
