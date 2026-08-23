package de.palsoftware.yvoke.document.core;

import de.palsoftware.yvoke.document.core.model.DocumentDetails;
import de.palsoftware.yvoke.document.core.model.DocumentRow;
import de.palsoftware.yvoke.document.core.model.SectionChunks;
import de.palsoftware.yvoke.document.core.model.SectionResponse;
import de.palsoftware.yvoke.document.core.model.TocNode;
import de.palsoftware.yvoke.document.core.repository.DocumentRepository;
import de.palsoftware.yvoke.document.core.service.SectionService;
import de.palsoftware.yvoke.document.core.service.TocService;


import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.PreparedStatement;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
public class HierarchicalRetrievalIT {

    private static final String COLLECTION = "OIM-HIER-TEST";

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private SectionService sectionService;

    @Autowired
    private TocService tocService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final UUID docId1 = UUID.randomUUID();
    private final UUID docId2 = UUID.randomUUID();

    private final UUID chunkId1 = UUID.randomUUID();
    private final UUID chunkId2 = UUID.randomUUID();
    private final UUID chunkId3 = UUID.randomUUID();
    private final UUID chunkId4 = UUID.randomUUID();
    private final UUID chunkId5 = UUID.randomUUID();
    private final UUID chunkId6 = UUID.randomUUID();

    @BeforeEach
    public void setUp() {
        cleanup();

        UUID collectionId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO collections (id, name) VALUES (?, ?)", collectionId,
            COLLECTION);

        // 1. Insert documents
        jdbcTemplate.update(
            "INSERT INTO documents (id, collection_id, kind, title, ingestion_status, tags) "
                + "VALUES (?, ?, ?, ?, ?, ARRAY['9.3']::TEXT[])",
            docId1, collectionId, "manual", "Manual A Title", "completed");
        jdbcTemplate.update(
            "INSERT INTO documents (id, collection_id, kind, title, ingestion_status, tags) "
                + "VALUES (?, ?, ?, ?, ?, ARRAY['10.0']::TEXT[])",
            docId2, collectionId, "manual", "Manual B Title", "completed");

        // 2. Insert chunks for Document 1 (9.3)
        // Chapter 1 (sortOrder = 10)
        insertChunk(chunkId1, docId1, "> Section path: Chapter 1\nChapter 1 Intro text",
            new String[] {}, "Chapter 1", 1, 10, "9.3", "manualA_93.md");
        // Chapter 1 > Section 1.1 (split into 2 parts: sortOrder = 20, 21)
        insertChunk(chunkId2, docId1,
            "> Section path: Chapter 1 > Section 1.1\nSection 1.1 Text Part 1",
            new String[] {"Chapter 1"}, "Section 1.1 (part 1/2)", 2, 20, "9.3", "manualA_93.md");
        insertChunk(chunkId3, docId1,
            "> Section path: Chapter 1 > Section 1.1\nSection 1.1 Text Part 2",
            new String[] {"Chapter 1"}, "Section 1.1 (part 2/2)", 2, 21, "9.3", "manualA_93.md");
        // Chapter 1 > Section 1.2 (sortOrder = 30)
        insertChunk(chunkId4, docId1, "> Section path: Chapter 1 > Section 1.2\nSection 1.2 Text",
            new String[] {"Chapter 1"}, "Section 1.2", 2, 30, "9.3", "manualA_93.md");
        // Chapter 2 (sortOrder = 40)
        insertChunk(chunkId5, docId1, "> Section path: Chapter 2\nChapter 2 Text", new String[] {},
            "Chapter 2", 1, 40, "9.3", "manualA_93.md");

        // 3. Insert chunk for Document 2 (10.0)
        insertChunk(chunkId6, docId2, "> Section path: Chapter 1\nChapter 1 v10 Text",
            new String[] {}, "Chapter 1", 1, 10, "10.0", "manualB_100.md");
    }

    @AfterEach
    public void tearDown() {
        cleanup();
    }

    private void cleanup() {
        jdbcTemplate.update(
            "DELETE FROM chunks WHERE collection_id IN (SELECT id FROM collections WHERE name = ?)",
            COLLECTION);
        jdbcTemplate.update(
            "DELETE FROM documents WHERE collection_id IN (SELECT id FROM collections WHERE name = ?)",
            COLLECTION);
        jdbcTemplate.update("DELETE FROM collections WHERE name = ?", COLLECTION);
    }

    private void insertChunk(UUID id, UUID docId, String text, String[] headingPath, String heading,
        int depth, int sortOrder, String version, String srcFile) {
        UUID collectionId = jdbcTemplate.queryForObject("SELECT id FROM collections WHERE name = ?",
            UUID.class, COLLECTION);
        jdbcTemplate.execute((ConnectionCallback<Void>) conn -> {
            String sql =
                "INSERT INTO chunks (id, document_id, text, heading_path, heading, depth, sort_order, collection_id) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setObject(1, id);
                ps.setObject(2, docId);
                ps.setString(3, text);
                ps.setArray(4, conn.createArrayOf("text", headingPath));
                ps.setString(5, heading);
                ps.setInt(6, depth);
                ps.setInt(7, sortOrder);
                ps.setObject(8, collectionId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Test
    public void testDocumentRepositoryLookups() {
        // Find by exact ID
        Optional<DocumentRow> doc1 = documentRepository.findById(docId1);
        assertThat(doc1).isPresent();
        assertThat(doc1.get().title()).isEqualTo("Manual A Title");

        // Find by short ID prefix
        String prefix = docId1.toString().substring(0, 8);
        Optional<DocumentDetails> details = documentRepository.findByIdPrefix(prefix);
        assertThat(details).isPresent();
        assertThat(details.get().chunkCount()).isEqualTo(5);

        // Find by manual title substring
        Optional<DocumentRow> manualMatch = documentRepository.findByManual("Manual A");
        assertThat(manualMatch).isPresent();
        assertThat(manualMatch.get().id()).isEqualTo(docId1);

        // Ambiguous manual lookup: the caller is told to pass document_id, so each candidate must
        // print the FULL id plus the kind and tags that actually tell same-titled documents apart.
        assertThatThrownBy(() -> documentRepository.findByManual("manual"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("multiple documents match — pass document_id for one:")
            .hasMessageContaining(docId1 + "  [manual]  Manual A Title  (9.3)")
            .hasMessageContaining(docId2 + "  [manual]  Manual B Title  (10.0)");

        // Ambiguous prefix lookup
        // Querying with empty string prefix should match multiple documents in database
        assertThatThrownBy(() -> documentRepository.findByIdPrefix(""))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Ambiguous ID");

        // List documents
        List<DocumentDetails> docs = documentRepository.listDocuments(COLLECTION, 10, 0, "manual");
        assertThat(docs).hasSize(2);
    }

    @Test
    public void testSectionServiceAssembly() {
        // Assemble Chapter 1 with descendants
        SectionResponse response = sectionService.getSection(COLLECTION, "Manual A", "Chapter 1");
        assertThat(response.chunkCount()).isEqualTo(4);
        assertThat(response.text()).contains("Chapter 1 Intro text");
        assertThat(response.text()).contains("Section 1.1 Text Part 1");
        assertThat(response.text()).contains("Section 1.1 Text Part 2");
        assertThat(response.text()).contains("Section 1.2 Text");
        // Verify reading order: chunk 2 (sortOrder 20) before chunk 3 (sortOrder 21) before chunk 4
        // (sortOrder 30)
        int idx1 = response.text().indexOf("Section 1.1 Text Part 1");
        int idx2 = response.text().indexOf("Section 1.1 Text Part 2");
        int idx3 = response.text().indexOf("Section 1.2 Text");
        assertThat(idx1).isLessThan(idx2);
        assertThat(idx2).isLessThan(idx3);

        // Assemble Section 1.1 (should strip part suffixes and match parts)
        SectionResponse section11 =
            sectionService.getSection(COLLECTION, "Manual A", "Chapter 1 > Section 1.1");
        assertThat(section11.chunkCount()).isEqualTo(2);
        assertThat(section11.text()).contains("Section 1.1 Text Part 1");
        assertThat(section11.text()).contains("Section 1.1 Text Part 2");
    }

    /**
     * A citation click lands in {@link SectionService#getChunkContent(String)}, which must return
     * the ONE cited passage — not its section, and not the document.
     *
     * <p>
     * It used to return the whole section, by turning the chunk into a heading path and running the
     * shared prefix-matching assembly. What came back therefore grew with the breadth of the
     * chunk's heading rather than with anything about the citation: on one real answer a cited
     * passage of 1,357 characters arrived inside 220 passages and 314,064 characters. Nothing threw
     * and nothing logged — the response looked perfectly successful at any size, which is why no
     * caller could detect it.
     *
     * <p>
     * The load-bearing assertion is that the SIBLING PART is absent. Part 1 and part 2 of
     * "Section 1.1" are one heading split at ingest, so they are the closest thing to the cited
     * passage that still is not it; a test that only checked "not the whole document" would pass
     * with them both present, which is the exact regression this replaces. The input is an
     * 8-character PREFIX because that is the production input shape — a shortened citation link
     * carries a truncated id.
     *
     * <p>
     * Nothing else covers this. {@code GetSectionToolTest} and {@code CitationControllerTest} both
     * stub {@code SectionService} with a canned response, so the real resolution never runs, and
     * this class's other section assertions go through the name-based overload. The agent-facing
     * {@code getSectionChunksByChunkId} still expands on purpose and is covered separately.
     */
    @Test
    public void aCitedChunkIdResolvesToThatPassageAlone() {
        String prefix = chunkId2.toString().substring(0, 8);

        SectionResponse passage = sectionService.getChunkContent(prefix);

        // The breadcrumb still names where the passage sits, with the "(part 1/2)" suffix stripped.
        assertThat(passage.headingPath()).containsExactly("Chapter 1", "Section 1.1");
        assertThat(passage.documentTitle()).isEqualTo("Manual A Title");
        assertThat(passage.tag()).isEqualTo("9.3");
        assertThat(passage.scope()).isEqualTo("this passage only");
        assertThat(passage.chunkCount()).isEqualTo(1);

        // Exactly the cited chunk's own text, with its "> Section path: …" breadcrumb stripped and
        // no "# Section: …" header restating what the panel's own header row already shows.
        assertThat(passage.text()).isEqualTo("Section 1.1 Text Part 1");

        // The sibling part of the SAME heading is the assertion that matters: it is not the cited
        // passage, and it was never in front of the model that cited part 1.
        assertThat(passage.text()).doesNotContain("Section 1.1 Text Part 2");

        // …nor the parent chapter's prose, the sibling section, the later chapter, or the same
        // chapter in the other version's document.
        assertThat(passage.text()).doesNotContain("Chapter 1 Intro text");
        assertThat(passage.text()).doesNotContain("Section 1.2 Text");
        assertThat(passage.text()).doesNotContain("Chapter 2 Text");
        assertThat(passage.text()).doesNotContain("Chapter 1 v10 Text");
    }

    /**
     * The agent-facing read of the same id keeps expanding to the section, which is what
     * {@code get_section}'s description promises: "a chunk_id returns the whole section containing
     * that chunk, not just the chunk". Asserted next to the citation test so the two contracts are
     * visibly opposite and neither can be "fixed" into the other by someone who saw only one.
     */
    @Test
    public void anAgentReadingTheSameChunkIdStillGetsTheWholeSection() {
        SectionChunks section =
            sectionService.getSectionChunksByChunkId(chunkId2.toString().substring(0, 8));

        assertThat(section.chunks()).hasSize(2);
        assertThat(section.chunks().stream().map(SectionChunks.SectionChunk::text))
            .anySatisfy(t -> assertThat(t).contains("Section 1.1 Text Part 1"))
            .anySatisfy(t -> assertThat(t).contains("Section 1.1 Text Part 2"));
        // Every passage carries its own id, which is what lets an agent cite the one it used.
        assertThat(section.chunks()).allSatisfy(c -> assertThat(c.id()).isNotNull());
    }

    @Test
    public void testTocServiceReconstruction() {
        // Full TOC
        List<TocNode> toc = tocService.getToc("Manual A", COLLECTION);
        // Chunks are Chapter 1, Chapter 1 > Section 1.1 (2 parts), Chapter 1 > Section 1.2, Chapter
        // 2
        // Distinct heading paths: Chapter 1, Chapter 1 > Section 1.1, Chapter 1 > Section 1.2,
        // Chapter 2 (4 nodes)
        assertThat(toc).hasSize(4);

        // Chapter 1 (index 0)
        assertThat(toc.get(0).path()).isEqualTo(List.of("Chapter 1"));
        assertThat(toc.get(0).minSortOrder()).isEqualTo(10);
        assertThat(toc.get(0).subtreeChunkCount()).isEqualTo(4); // itself (1) + Section 1.1 (2) +
                                                                 // Section 1.2 (1)

        // Chapter 1 > Section 1.1 (index 1)
        assertThat(toc.get(1).path()).isEqualTo(List.of("Chapter 1", "Section 1.1"));
        assertThat(toc.get(1).minSortOrder()).isEqualTo(20);
        assertThat(toc.get(1).subtreeChunkCount()).isEqualTo(2); // 2 parts

        // Chapter 1 > Section 1.2 (index 2)
        assertThat(toc.get(2).path()).isEqualTo(List.of("Chapter 1", "Section 1.2"));
        assertThat(toc.get(2).minSortOrder()).isEqualTo(30);
        assertThat(toc.get(2).subtreeChunkCount()).isEqualTo(1);

        // Chapter 2 (index 3)
        assertThat(toc.get(3).path()).isEqualTo(List.of("Chapter 2"));
        assertThat(toc.get(3).minSortOrder()).isEqualTo(40);
        assertThat(toc.get(3).subtreeChunkCount()).isEqualTo(1);
    }

    @Test
    public void testVersionIsolation() {
        // Section service getSection for Chapter 1 on manualA (9.3)
        SectionResponse section93 = sectionService.getSection(COLLECTION, "Manual A", "Chapter 1");
        assertThat(section93.text()).contains("Chapter 1 Intro text");
        assertThat(section93.text()).doesNotContain("Chapter 1 v10 Text");

        // Section service getSection for Chapter 1 on manualB (10.0)
        SectionResponse section100 = sectionService.getSection(COLLECTION, "Manual B", "Chapter 1");
        assertThat(section100.text()).contains("Chapter 1 v10 Text");
        assertThat(section100.text()).doesNotContain("Chapter 1 Intro text");
    }
}
