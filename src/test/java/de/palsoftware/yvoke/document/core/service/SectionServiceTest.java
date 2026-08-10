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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /**
     * {@code get_section}'s {@code heading_path} is PREFIX matching rooted at the top of the
     * document, and the two halves of that sentence fail in opposite directions.
     *
     * <p>
     * Rooted: {@code HierarchyUtils.isSubpathOf(target, chunkPath)} compares segment by segment
     * from index 0, so a leaf name on its own matches nothing however unambiguous it looks to a
     * model — and on this corpus a section name genuinely recurs under several chapters, which is
     * exactly why the rule cannot be relaxed to "contains". Prefix: every chunk UNDER the requested
     * path comes back too, which is what the {@code "with sub-sections"} scope in the response
     * header promises. Loosening the match to {@code isExactPathMatch} — the sibling helper, one
     * identifier away, and the one a reader reaches for when "the path should be the path" —
     * silently truncates every section to its own chunks and drops the sub-sections the agent asked
     * for.
     *
     * <p>
     * Comparison is per-segment via {@code normalizeSegment} (NFKC, whitespace collapsed, trimmed,
     * lowercased) after {@code splitHeadingPath} strips a {@code (part n/m)} suffix, so a
     * breadcrumb copied out of a search hit — which carries exactly that suffix, and whatever
     * spacing the ingest produced — still resolves. Tighten any of those and citations stop
     * resolving for reasons nobody can see from the input.
     *
     * <p>
     * The no-match branch matters as much as the match: it throws with the available paths listed,
     * which is the only feedback a caller gets about why the lookup failed. It is worth knowing
     * that this help does NOT reach the model — {@code GetSectionTool} catches Exception and
     * returns the flat {@code "ERROR: the 'get_section' tool failed to complete the request."}, so
     * the path list goes to the log only — which makes the log line the whole diagnostic, and a
     * change that degrades this message into a bare "not found" removes the last trace of it.
     * Nothing covers any of this today: this class had exactly one test (the happy path), and
     * {@code GetSectionToolTest} covers the happy path, the chunk_id form and the missing-id
     * rejection.
     */
    @Test
    void anUnrootedOrUnknownHeadingPathMatchesNothingAndTheErrorListsTheAvailablePaths() {
        UUID chunkId3 = UUID.randomUUID();
        DocumentRow docRow = new DocumentRow(docId, UUID.randomUUID(), "OIM", "hierarchical",
            "Install Guide", Map.of("tag", "9.3", "source_file", "install.md"), "completed",
            List.of("9.3"), Instant.now());

        // "Section A" exists under TWO different chapters — the shape that makes an unrooted match
        // ambiguous rather than merely lenient.
        ChunkPathRow underChapterOne =
            new ChunkPathRow(chunkId1, List.of("Chapter 1"), "Section A", 10);
        ChunkPathRow deeper =
            new ChunkPathRow(chunkId2, List.of("Chapter 1", "Section A"), "Details", 20);
        ChunkPathRow underChapterTwo =
            new ChunkPathRow(chunkId3, List.of("Chapter 2"), "Section A", 30);

        when(documentRepository.findByManual("install", "OIM")).thenReturn(Optional.of(docRow));
        when(chunkRepository.findChunkPathsByDocumentId(eq(docId)))
            .thenReturn(List.of(underChapterOne, deeper, underChapterTwo));
        when(chunkRepository.findChunksByIds(anyList())).thenReturn(List.of());

        // 1. Unrooted: the leaf segment alone is not a subpath from the root, so nothing matches —
        // and the error must hand back the rooted paths that WOULD have worked.
        assertThatThrownBy(() -> sectionService.getSection("OIM", "install", "Section A"))
            .isInstanceOf(NoSuchElementException.class)
            .hasMessageContaining("no section matched 'Section A'")
            .hasMessageContaining("heading_path must be rooted at the top")
            .hasMessageContaining("Chapter 1 > Section A")
            .hasMessageContaining("Chapter 2 > Section A");

        // 2. A path the document simply does not have fails the same way, listing the same menu.
        assertThatThrownBy(() -> sectionService.getSection("OIM", "install", "Chapter 3 > Nope"))
            .isInstanceOf(NoSuchElementException.class)
            .hasMessageContaining("no section matched 'Chapter 3 > Nope'")
            .hasMessageContaining("Chapter 1 > Section A > Details");

        // 3. Rooted but sloppily typed — wrong case, collapsed whitespace, a copied "(part n/m)"
        // suffix — still matches, AND drags in the sub-section below it.
        ChunkRow sectionA = new ChunkRow(chunkId1, docId, "Section A body", List.of("Chapter 1"),
            "Section A", 2, 10, "9.3", "install.md", "hierarchical", "OIM", null, 0.0);
        ChunkRow details =
            new ChunkRow(chunkId2, docId, "Details body", List.of("Chapter 1", "Section A"),
                "Details", 3, 20, "9.3", "install.md", "hierarchical", "OIM", null, 0.0);
        when(chunkRepository.findChunksByIds(eq(List.of(chunkId1, chunkId2))))
            .thenReturn(List.of(sectionA, details));

        SectionResponse response =
            sectionService.getSection("OIM", "install", "  chapter 1  >   SECTION   a (part 2/3) ");

        assertThat(response.chunkCount())
            .as("the requested section AND everything under it — never the section alone")
            .isEqualTo(2);
        assertThat(response.scope()).isEqualTo("with sub-sections");
        assertThat(response.text()).contains("Section A body").contains("Details body")
            .as("Chapter 2's identically named section must not be pulled in")
            .doesNotContain("Chapter 2");
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
