package de.palsoftware.yvoke.document.core;

import static org.assertj.core.api.Assertions.assertThat;

import de.palsoftware.yvoke.document.core.model.DocumentDetails;
import de.palsoftware.yvoke.document.core.repository.DocumentRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Covers the fuzzy document-title filter added for {@code list_documents}: an approximate title
 * query (case-insensitive substring OR trigram similarity) ranked best-first, with the count kept
 * consistent with the filtered list.
 */
@SpringBootTest
public class DocumentFuzzyTitleIT {

    private static final String COLLECTION = "OIM-FUZZYTITLE-TEST";

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID collectionId;

    @BeforeEach
    public void setUp() {
        cleanup();
        collectionId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO collections (id, name) VALUES (?, ?)", collectionId,
            COLLECTION);
        insertDoc("PRC_Person_Delete_Chain");
        insertDoc("PRC_Person_Delete_Chain_Extended");
        insertDoc("Completely Unrelated Manual");
        insertDoc("Another Guide");
    }

    @AfterEach
    public void tearDown() {
        cleanup();
    }

    private void cleanup() {
        jdbcTemplate.update("DELETE FROM collections WHERE name = ?", COLLECTION);
    }

    private void insertDoc(String title) {
        jdbcTemplate.update(
            "INSERT INTO documents (id, collection_id, kind, title, ingestion_status, metadata, tags) "
                + "VALUES (?, ?, 'manual', ?, 'completed', '{}'::jsonb, ARRAY['9.3'])",
            UUID.randomUUID(), collectionId, title);
    }

    @Test
    public void fuzzyQueryFiltersRanksAndKeepsCountConsistent() {
        List<DocumentDetails> docs = documentRepository.listDocuments(COLLECTION, 100, 0, null, null,
            null, null, "PRC_Person_Delete_Chain");
        long count = documentRepository.countDocuments(COLLECTION, null, null, null, null,
            "PRC_Person_Delete_Chain");

        List<String> titles = docs.stream().map(DocumentDetails::title).toList();
        assertThat(titles).contains("PRC_Person_Delete_Chain", "PRC_Person_Delete_Chain_Extended");
        assertThat(titles).doesNotContain("Completely Unrelated Manual", "Another Guide");
        // Best match (shortest exact-prefix title) ranks first via similarity DESC, then title ASC.
        assertThat(titles.get(0)).isEqualTo("PRC_Person_Delete_Chain");
        // Count is consistent with the filtered list.
        assertThat(count).isEqualTo(titles.size());
    }

    @Test
    public void fuzzyQueryMatchesOnTrigramSimilarityDespiteTypo() {
        // "Persn" is a typo (missing 'o'): the substring ILIKE would miss it, but trigram
        // similarity(> 0.25) still surfaces the intended documents.
        List<DocumentDetails> docs = documentRepository.listDocuments(COLLECTION, 100, 0, null, null,
            null, null, "PRC_Persn_Delete_Chain");

        List<String> titles = docs.stream().map(DocumentDetails::title).toList();
        assertThat(titles).contains("PRC_Person_Delete_Chain");
        assertThat(titles).doesNotContain("Completely Unrelated Manual", "Another Guide");
    }

    @Test
    public void nullFuzzyQueryLeavesListingUnfiltered() {
        List<DocumentDetails> docs = documentRepository.listDocuments(COLLECTION, 100, 0, null, null,
            null, null, null);
        assertThat(docs).hasSize(4);
    }
}
