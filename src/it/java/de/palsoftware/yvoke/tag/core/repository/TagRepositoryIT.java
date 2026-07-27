package de.palsoftware.yvoke.tag.core.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.repository.CollectionRepository;

@SpringBootTest(
        properties = {
            "spring.flyway.enabled=true",
            "spring.flyway.locations=filesystem:docker/db/migration"
        })
public class TagRepositoryIT {

    private static final String COLLECTION_NAME = "TAG-DELETE-TEST-COLL";

    @Autowired
    private TagRepository tagRepository;

    @Autowired
    private CollectionRepository collectionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID collectionId;

    @BeforeEach
    public void setUp() {
        cleanup();
        collectionRepository.create(COLLECTION_NAME, "Test Collection for Tag Deletion");
        collectionId = collectionRepository.findByName(COLLECTION_NAME).map(Collection::id).orElseThrow();
        
        // Add tags to the collection
        tagRepository.addTagToCollection(collectionId, "v1");
        tagRepository.addTagToCollection(collectionId, "release");
    }

    @AfterEach
    public void tearDown() {
        cleanup();
    }

    private void cleanup() {
        if (collectionId != null) {
            jdbcTemplate.update("DELETE FROM relationships WHERE collection_id = ?", collectionId);
            jdbcTemplate.update("DELETE FROM entities WHERE collection_id = ?", collectionId);
            jdbcTemplate.update("DELETE FROM json_objects WHERE collection_id = ?", collectionId);
            jdbcTemplate.update("DELETE FROM documents WHERE collection_id = ?", collectionId);
            jdbcTemplate.update("DELETE FROM collections WHERE id = ?", collectionId);
        }
    }

    @Test
    public void testRemoveTagFromCollectionDetachesTagFromCollectionOnly() {
        // Insert a document with 'release' tag
        UUID docId1 = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO documents (id, collection_id, kind, title, tags) VALUES (?, ?, ?, ?, ?::text[])",
            docId1, collectionId, "manual", "doc-tagged", new String[]{"release"}
        );

        // Insert a document with 'v1' tag (should not be deleted)
        UUID docId2 = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO documents (id, collection_id, kind, title, tags) VALUES (?, ?, ?, ?, ?::text[])",
            docId2, collectionId, "manual", "doc-untagged", new String[]{"v1"}
        );

        // Insert a JSON object with 'release' tag
        UUID jsonObjId1 = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO json_objects (id, collection_id, data, tags) VALUES (?, ?, ?::jsonb, ?::text[])",
            jsonObjId1, collectionId, "{\"key\": \"value\"}", new String[]{"release"}
        );

        // Insert a JSON object with 'v1' tag (should not be deleted)
        UUID jsonObjId2 = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO json_objects (id, collection_id, data, tags) VALUES (?, ?, ?::jsonb, ?::text[])",
            jsonObjId2, collectionId, "{\"key\": \"other\"}", new String[]{"v1"}
        );

        // Insert an entity with 'release' tag
        UUID entityId1 = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO entities (id, collection_id, name, kind, tags) VALUES (?, ?, ?, 'table', ?::text[])",
            entityId1, collectionId, "Entity1", new String[]{"release"}
        );

        // Insert an entity with 'v1' tag (should not be deleted)
        UUID entityId2 = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO entities (id, collection_id, name, kind, tags) VALUES (?, ?, ?, 'table', ?::text[])",
            entityId2, collectionId, "Entity2", new String[]{"v1"}
        );

        // Insert a relationship with 'release' tag
        UUID relId1 = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO relationships (id, collection_id, subject, predicate, object, tags) VALUES (?, ?, ?, ?, ?, ?::text[])",
            relId1, collectionId, "Entity1", "links", "Entity2", new String[]{"release"}
        );

        // Insert a relationship with 'v1' tag (should not be deleted)
        UUID relId2 = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO relationships (id, collection_id, subject, predicate, object, tags) VALUES (?, ?, ?, ?, ?, ?::text[])",
            relId2, collectionId, "Entity2", "links", "Entity1", new String[]{"v1"}
        );

        // Ensure everything is present before removing the tag
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM documents WHERE collection_id = ?", Integer.class, collectionId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM json_objects WHERE collection_id = ?", Integer.class, collectionId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM entities WHERE collection_id = ?", Integer.class, collectionId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM relationships WHERE collection_id = ?", Integer.class, collectionId)).isEqualTo(2);

        // Remove the 'release' tag from the collection. TagRepository now ONLY detaches the tag
        // from the collection's own tags array; the cross-domain content cascade moved to
        // LifecycleService (MNT-01), so content must remain untouched here.
        tagRepository.removeTagFromCollection(collectionId, "release");

        // Collection tag array is updated.
        Collection col = collectionRepository.findByName(COLLECTION_NAME).orElseThrow();
        assertThat(col.tags()).containsExactly("v1");

        // Content is NOT deleted by the repository method (no cross-domain cascade any more).
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM documents WHERE collection_id = ?", Integer.class, collectionId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM json_objects WHERE collection_id = ?", Integer.class, collectionId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM entities WHERE collection_id = ?", Integer.class, collectionId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM relationships WHERE collection_id = ?", Integer.class, collectionId)).isEqualTo(2);
    }

    // ---------------------------------------------------------------------
    // tags is part of ux_documents_collection_kind_source_file_tags (V3), so tagging or untagging a
    // document REWRITES part of its identity and can land on a sibling row for the same source
    // file — the documented "two versions of one file, separated only by tag" shape. That used to
    // surface as a raw 23505 (HTTP 500 on an admin form post); it must be an actionable refusal.
    // ---------------------------------------------------------------------

    @Test
    public void addTagToDocumentRefusesWhenASiblingAlreadyHoldsTheTargetTagScope() {
        UUID untagged = insertDocument("kit/install.md");
        UUID tagged = insertDocument("kit/install.md", "release");

        assertThatThrownBy(() -> tagRepository.addTagToDocument(untagged, "release"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kit/install.md")
                .hasMessageContaining("release");

        // Neither row moved.
        assertThat(tagsOf(untagged)).isEmpty();
        assertThat(tagsOf(tagged)).containsExactly("release");
    }

    @Test
    public void addTagToDocumentStillTagsADocumentWithNoSibling() {
        UUID doc = insertDocument("kit/upgrade.md");

        tagRepository.addTagToDocument(doc, "release");

        assertThat(tagsOf(doc)).containsExactly("release");
    }

    @Test
    public void removeTagFromDocumentRefusesWhenTheRewriteWouldCollide() {
        UUID both = insertDocument("kit/install.md", "release", "v1");
        UUID v1Only = insertDocument("kit/install.md", "v1");

        assertThatThrownBy(() -> tagRepository.removeTagFromDocument(both, "release"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kit/install.md");

        assertThat(tagsOf(both)).containsExactly("release", "v1");
        assertThat(tagsOf(v1Only)).containsExactly("v1");
    }

    @Test
    public void removeTagFromDocumentStillUntagsADocumentWithNoSibling() {
        UUID doc = insertDocument("kit/upgrade.md", "release", "v1");

        tagRepository.removeTagFromDocument(doc, "release");

        assertThat(tagsOf(doc)).containsExactly("v1");
    }

    private UUID insertDocument(String sourceFile, String... tags) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO documents (id, collection_id, kind, title, metadata, tags) "
                        + "VALUES (?, ?, 'manual', ?, jsonb_build_object('source_file', ?::text), ?::text[])",
                id, collectionId, sourceFile, sourceFile, tags);
        return id;
    }

    private List<String> tagsOf(UUID docId) {
        return jdbcTemplate.queryForObject(
                "SELECT tags FROM documents WHERE id = ?",
                (rs, rowNum) -> List.of((String[]) rs.getArray("tags").getArray()),
                docId);
    }

    /**
     * The sibling guards must arbitrate on the same expression the unique index does.
     *
     * <p>
     * V4 re-keyed {@code ux_documents_collection_kind_source_file_tags} on
     * {@code kg_canonical_tags(tags)}, which sorts and de-duplicates, so {@code {a,b}} and
     * {@code {b,a}} are ONE scope. While the guards still compared raw {@code text[]} — element by
     * element, order-sensitive — they missed a permuted sibling entirely: the NOT EXISTS passed,
     * the UPDATE ran, and Postgres raised a raw 23505 instead of the actionable rejection. Every
     * pre-existing test used single-element or already-ordered tag sets, so none of them could see
     * it.
     */
    @Test
    public void addTagToDocumentRefusesWhenAPermutedSiblingHoldsTheTargetScope() {
        UUID target = insertDocument("kit/install.md", "10.0");
        UUID permutedSibling = insertDocument("kit/install.md", "9.3.1", "10.0");

        assertThatThrownBy(() -> tagRepository.addTagToDocument(target, "9.3.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kit/install.md");

        assertThat(tagsOf(target)).containsExactly("10.0");
        assertThat(tagsOf(permutedSibling)).containsExactlyInAnyOrder("9.3.1", "10.0");
    }

    @Test
    public void removeTagFromDocumentRefusesWhenThePermutedRewriteWouldCollide() {
        UUID target = insertDocument("kit/install.md", "10.0", "9.3.1", "beta");
        UUID permutedSibling = insertDocument("kit/install.md", "9.3.1", "10.0");

        assertThatThrownBy(() -> tagRepository.removeTagFromDocument(target, "beta"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kit/install.md");

        assertThat(tagsOf(target)).containsExactlyInAnyOrder("10.0", "9.3.1", "beta");
        assertThat(tagsOf(permutedSibling)).containsExactlyInAnyOrder("9.3.1", "10.0");
    }
}
