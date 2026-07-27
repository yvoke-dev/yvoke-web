package de.palsoftware.yvoke.lifecycle.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.repository.CollectionRepository;
import de.palsoftware.yvoke.tag.core.repository.TagRepository;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * MNT-01: removing a tag from a collection must be tag-aware — content shared with other tags is
 * only detached from the tag and MUST survive; only content whose sole tag was the removed one is
 * deleted. A destructive hard-delete (the previous behaviour) would wipe multi-tagged content.
 */
@SpringBootTest(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=filesystem:docker/db/migration"
})
public class LifecycleTagRemovalIT {

    private static final String COLLECTION_NAME = "LIFECYCLE-TAG-REMOVAL-IT";

    @Autowired
    private LifecycleService lifecycleService;

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
        collectionRepository.create(COLLECTION_NAME, "Tag removal IT");
        collectionId =
            collectionRepository.findByName(COLLECTION_NAME).map(Collection::id).orElseThrow();
        tagRepository.addTagToCollection(collectionId, "v1");
        tagRepository.addTagToCollection(collectionId, "v2");
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
            jdbcTemplate.update("DELETE FROM audit_log WHERE target = ?", collectionId.toString());
            jdbcTemplate.update("DELETE FROM collections WHERE id = ?", collectionId);
        }
    }

    @Test
    public void removeTagKeepsMultiTaggedContentAndDeletesOnlyExclusiveContent() {
        UUID docShared = insertDocument("doc-shared", "v1", "v2");
        UUID docV1Only = insertDocument("doc-v1only", "v1");
        UUID docV2Only = insertDocument("doc-v2only", "v2");

        UUID entShared = insertEntity("ent-shared", "v1", "v2");
        UUID entV1Only = insertEntity("ent-v1only", "v1");

        UUID relShared = insertRelationship("v1", "v2");
        UUID relV1Only = insertRelationship("v1");

        UUID jsonShared = insertJson("v1", "v2");
        UUID jsonV1Only = insertJson("v1");

        lifecycleService.removeTagFromCollection(collectionId, "v1");

        // The tag is detached from the collection itself.
        assertThat(collectionRepository.findByName(COLLECTION_NAME).orElseThrow().tags())
            .containsExactly("v2");

        // Multi-tagged content SURVIVES and is now tagged only with the remaining tag.
        assertThat(exists("documents", docShared)).isTrue();
        assertThat(tagsOf("documents", docShared)).isEqualTo("v2");
        assertThat(exists("entities", entShared)).isTrue();
        assertThat(tagsOf("entities", entShared)).isEqualTo("v2");
        assertThat(exists("relationships", relShared)).isTrue();
        assertThat(exists("json_objects", jsonShared)).isTrue();

        // Content whose ONLY tag was the removed one is deleted.
        assertThat(exists("documents", docV1Only)).isFalse();
        assertThat(exists("entities", entV1Only)).isFalse();
        assertThat(exists("relationships", relV1Only)).isFalse();
        assertThat(exists("json_objects", jsonV1Only)).isFalse();

        // Content exclusively carrying a different tag is untouched.
        assertThat(exists("documents", docV2Only)).isTrue();

        // The destructive operation is now audited (previously it was not).
        Integer audits = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action = 'REMOVE_COLLECTION_TAG' AND target = ?",
            Integer.class, collectionId.toString());
        assertThat(audits).isGreaterThanOrEqualTo(1);
    }

    private boolean exists(String table, UUID id) {
        Integer c = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM " + table + " WHERE id = ?", Integer.class, id);
        return c != null && c > 0;
    }

    private String tagsOf(String table, UUID id) {
        return jdbcTemplate.queryForObject(
            "SELECT array_to_string(tags, ',') FROM " + table + " WHERE id = ?", String.class, id);
    }

    private UUID insertDocument(String title, String... tags) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO documents (id, collection_id, kind, title, tags) VALUES (?, ?, 'manual', ?, ?::text[])",
            id, collectionId, title, tags);
        return id;
    }

    private UUID insertEntity(String name, String... tags) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO entities (id, collection_id, name, kind, tags) VALUES (?, ?, ?, 'table', ?::text[])",
            id, collectionId, name, tags);
        return id;
    }

    private UUID insertRelationship(String... tags) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO relationships (id, collection_id, subject, predicate, object, tags) VALUES (?, ?, 'A', 'rel', 'B', ?::text[])",
            id, collectionId, tags);
        return id;
    }

    private UUID insertJson(String... tags) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO json_objects (id, collection_id, data, tags) VALUES (?, ?, '{\"k\":\"v\"}'::jsonb, ?::text[])",
            id, collectionId, tags);
        return id;
    }
}
