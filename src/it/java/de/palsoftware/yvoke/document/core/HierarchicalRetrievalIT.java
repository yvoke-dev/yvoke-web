package de.palsoftware.yvoke.document.core;

import de.palsoftware.yvoke.document.core.model.DocumentDetails;
import de.palsoftware.yvoke.document.core.model.DocumentRow;
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
     * A citation click in chat and an MCP {@code get_section(chunk_id)} call both land in
     * {@link SectionService#getSectionByChunkId(String)}, which must return the ONE section the
     * cited chunk belongs to. The method passes a hard-coded "not the full document" flag into the
     * shared assembly routine; if that flag ever flips, both callers silently return the ENTIRE
     * manual. The citation dialog — whose whole job is to show the single passage behind a single
     * claim — would render the complete document, and every MCP get_section(chunk_id) would burn
     * thousands of tokens on a whole manual with the cited passage buried in it. Nothing throws and
     * nothing logs: the response looks perfectly successful either way, which is why no caller can
     * detect the regression. The input is deliberately an 8-character PREFIX, because that is the
     * production input shape — citation-render.js linkifies truncated chunk ids and
     * CitationVerifier classifies exactly that form as UNVERIFIED rather than fabricated. The
     * doesNotContain assertions are the load-bearing half: asserting only that both Section 1.1
     * parts are present would still pass on the full document, which contains them too. Nothing
     * else covers this — GetSectionToolTest and CitationControllerTest both stub SectionService
     * with a canned response, so the real resolution never runs, and this class's other section
     * assertions all go through the name-based overload, which derives the flag from its
     * heading_path argument instead.
     */
    @Test
    public void aCitedChunkIdResolvesToItsOwnSectionNotTheWholeDocument() {
        String prefix = chunkId2.toString().substring(0, 8);

        SectionResponse section = sectionService.getSectionByChunkId(prefix);

        // The path is reconstructed from the chunk's own heading_path + heading, with the
        // "(part 1/2)" suffix stripped, so the two parts collapse onto one section.
        assertThat(section.headingPath()).containsExactly("Chapter 1", "Section 1.1");
        assertThat(section.documentTitle()).isEqualTo("Manual A Title");
        assertThat(section.tag()).isEqualTo("9.3");
        assertThat(section.scope()).isEqualTo("with sub-sections");
        assertThat(section.chunkCount()).isEqualTo(2);

        // The header names the section, not the document.
        assertThat(section.text()).startsWith("# Section: Chapter 1 > Section 1.1\n");

        // Both parts of Section 1.1, in sort_order (20 before 21).
        assertThat(section.text()).contains("Section 1.1 Text Part 1");
        assertThat(section.text()).contains("Section 1.1 Text Part 2");
        assertThat(section.text().indexOf("Section 1.1 Text Part 1"))
            .isLessThan(section.text().indexOf("Section 1.1 Text Part 2"));

        // …and nothing else: not the parent chapter's own prose, not the sibling section, not the
        // later chapter, and not the same chapter in the other version's document.
        assertThat(section.text()).doesNotContain("Chapter 1 Intro text");
        assertThat(section.text()).doesNotContain("Section 1.2 Text");
        assertThat(section.text()).doesNotContain("Chapter 2 Text");
        assertThat(section.text()).doesNotContain("Chapter 1 v10 Text");
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
