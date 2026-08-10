package de.palsoftware.yvoke.kg.core;
import de.palsoftware.yvoke.kg.core.repository.KgWriteRepository;
import de.palsoftware.yvoke.kg.core.service.KgConsolidator;


import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=filesystem:docker/db/migration",
        "app.worker.enabled=false"
})
public class KgConsolidatorIT {

    private static final String COLLECTION = "CONSOLIDATE-TEST-COL";
    private static final String VERSION = "9.3";

    @Autowired
    private KgConsolidator kgConsolidator;

    @Autowired
    private KgWriteRepository kgRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    public void setUp() {
        cleanup();
        // Collections are no longer auto-created by resolveCollectionId; it must pre-exist.
        jdbcTemplate.update(
                "INSERT INTO collections (id, name) VALUES (?, ?) ON CONFLICT (name) DO NOTHING",
                UUID.randomUUID(), COLLECTION);
    }

    @AfterEach
    public void tearDown() {
        cleanup();
    }

    private void cleanup() {
        jdbcTemplate.update("DELETE FROM collections WHERE name = ?", COLLECTION);
    }

    /**
     * Graph identity is tag-scoped: one collection holds two product versions separated ONLY by
     * tag, and two same-name/same-kind rows under DIFFERENT tags are two legitimate entities, not a
     * duplicate. Consolidation therefore MUST keep {@code kg_canonical_tags(e.tags)} in its GROUP
     * BY and in the edge-collapse PARTITION BY. Dropping either re-merges the tag split and then
     * hard-DELETEs one row, cascading its edges away — the destructive shape that motivated the
     * tag-scoped unique index in the first place. This runs consolidation with a null tag (the
     * "consolidate everything" path), which is where a tag-blind GROUP BY would do the most damage.
     */
    @Test
    public void consolidationNeverMergesTwoTagScopesEvenWhenTheTagFilterIsOff() {
        UUID colId = jdbcTemplate.queryForObject("SELECT id FROM collections WHERE name = ?",
            UUID.class, COLLECTION);
        UUID v93 = UUID.randomUUID();
        UUID v10 = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO entities (id, collection_id, name, kind, description, tags) VALUES (?, ?, 'Person', 'table', 'the 9.3 Person table', ?)",
            v93, colId, new String[] {"9.3"});
        jdbcTemplate.update(
            "INSERT INTO entities (id, collection_id, name, kind, description, tags) VALUES (?, ?, 'Person', 'table', 'the 10.0 Person table', ?)",
            v10, colId, new String[] {"10.0"});
        jdbcTemplate.update(
            "INSERT INTO relationships (id, collection_id, subject, predicate, object, subject_id, object_id, description, tags) VALUES (?, ?, 'Person', 'has_column', 'Ident', ?, NULL, 'edge in 9.3', ?)",
            UUID.randomUUID(), colId, v93, new String[] {"9.3"});
        jdbcTemplate.update(
            "INSERT INTO relationships (id, collection_id, subject, predicate, object, subject_id, object_id, description, tags) VALUES (?, ?, 'Person', 'has_column', 'Ident', ?, NULL, 'edge in 10.0', ?)",
            UUID.randomUUID(), colId, v10, new String[] {"10.0"});

        kgConsolidator.consolidate(COLLECTION, null);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM entities WHERE collection_id = ? AND name = 'Person'",
            Integer.class, colId)).as("both product versions of the entity must survive")
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM entities WHERE id IN (?, ?)", Integer.class, v93, v10))
                .as("neither original row may be deleted as an alias of the other").isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM relationships WHERE collection_id = ? AND predicate = 'has_column'",
            Integer.class, colId))
                .as("the same triple under two tags is two edges, not a duplicate to collapse")
                .isEqualTo(2);
    }

    /**
     * Post kind-aware identity (V2), the entities table enforces uniqueness on
     * {@code (collection_id, coalesce(kind,''), lower(name))}, so same-kind case-variant duplicates
     * no longer arise; only trim/whitespace variants (which differ under lower(name) but collapse
     * under LOWER(TRIM)) remain as a consolidation target. This verifies that consolidation
     * (a) still merges such a same-kind trim-variant duplicate and collapses duplicate relationship
     * triples, while (b) NOT merging a same-named entity of a DIFFERENT kind — that homonym is the
     * whole point of the kind-aware graph.
     */
    @Test
    public void testConsolidationMergesTrimVariantsButPreservesDifferentKindHomonyms() {
        UUID collectionId = kgRepository.resolveCollectionId(COLLECTION, VERSION);

        // Canonical module 'ADS' + a trim-variant 'ADS ' (same kind) -> a consolidatable group.
        UUID moduleId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID moduleDupId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        // A DIFFERENT kind 'connector' sharing the name 'ADS' -> must survive untouched.
        UUID connectorId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        jdbcTemplate.update(
                "INSERT INTO entities (id, collection_id, name, kind, description, tags) VALUES (?, ?, 'ADS', 'module', 'short', ?)",
                moduleId, collectionId, new String[] { VERSION });
        jdbcTemplate.update(
                "INSERT INTO entities (id, collection_id, name, kind, description, tags) VALUES (?, ?, 'ADS ', 'module', 'a much longer module description', ?)",
                moduleDupId, collectionId, new String[] { VERSION });
        jdbcTemplate.update(
                "INSERT INTO entities (id, collection_id, name, kind, description, tags) VALUES (?, ?, 'ADS', 'connector', 'the ADS connector', ?)",
                connectorId, collectionId, new String[] { VERSION });

        // Duplicate relationship triples (same subject/predicate/object, different descriptions).
        jdbcTemplate.update(
                "INSERT INTO relationships (id, collection_id, subject, predicate, object, subject_id, object_id, description, tags) VALUES (?, ?, 'ADS', 'HAS_CONNECTOR', 'ADS', ?, ?, 'short', ?)",
                UUID.randomUUID(), collectionId, moduleId, connectorId, new String[] { VERSION });
        jdbcTemplate.update(
                "INSERT INTO relationships (id, collection_id, subject, predicate, object, subject_id, object_id, description, tags) VALUES (?, ?, 'ADS', 'HAS_CONNECTOR', 'ADS', ?, ?, 'a much longer and more detailed edge description', ?)",
                UUID.randomUUID(), collectionId, moduleId, connectorId, new String[] { VERSION });

        KgConsolidator.ConsolidationStats stats = kgConsolidator.consolidate(COLLECTION, VERSION);

        // Exactly one group (the module trim-variant); the connector homonym is a separate group of
        // one and is NOT merged.
        assertThat(stats.groupsProcessed()).isEqualTo(1);
        assertThat(stats.rowsDeleted()).isEqualTo(1);
        assertThat(stats.relationshipsCollapsedCount()).isEqualTo(1);

        // The trim-variant is gone; the canonical module and the different-kind connector remain.
        List<Map<String, Object>> entities = jdbcTemplate.queryForList(
                "SELECT e.id::text, e.name, e.kind, e.description FROM entities e JOIN collections c ON e.collection_id = c.id WHERE c.name = ? ORDER BY e.kind",
                COLLECTION);
        assertThat(entities).hasSize(2);
        assertThat(entities).extracting(m -> m.get("kind"))
                .containsExactlyInAnyOrder("module", "connector");
        Map<String, Object> module = entities.stream()
                .filter(m -> "module".equals(m.get("kind"))).findFirst().orElseThrow();
        assertThat(module.get("id")).isEqualTo(moduleId.toString());
        // Description merged to the longest non-empty among the group.
        assertThat(module.get("description")).isEqualTo("a much longer module description");

        // Only the longest-description copy of the triple survives.
        List<Map<String, Object>> rels = jdbcTemplate.queryForList(
                "SELECT r.description FROM relationships r JOIN collections c ON r.collection_id = c.id WHERE c.name = ?",
                COLLECTION);
        assertThat(rels).hasSize(1);
        assertThat(rels.get(0).get("description"))
                .isEqualTo("a much longer and more detailed edge description");
    }

    /**
     * Repointing is what makes merging a duplicate safe: every edge still referencing an alias row
     * (by name AND by {@code subject_id}/{@code object_id}) must be moved onto the canonical entity
     * before the alias is deleted, or the edge disappears with it. Under kind-aware grouping the
     * only duplicates left are same-kind trim/case variants, so the fixture builds one and points
     * edges at the alias from BOTH sides.
     */
    @Test
    public void testConsolidationRepointsAliasEdgesOntoTheCanonicalEntityBeforeDeletingIt() {
        UUID collectionId = kgRepository.resolveCollectionId(COLLECTION, VERSION);

        // Same kind, same trimmed name -> one consolidation group; the lowest id is canonical.
        UUID canonicalId = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
        UUID aliasId = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
        UUID otherId = UUID.fromString("cccccccc-0000-0000-0000-000000000003");
        jdbcTemplate.update(
                "INSERT INTO entities (id, collection_id, name, kind, description, tags) VALUES (?, ?, 'ADS', 'module', 'short', ?)",
                canonicalId, collectionId, new String[] { VERSION });
        jdbcTemplate.update(
                "INSERT INTO entities (id, collection_id, name, kind, description, tags) VALUES (?, ?, 'ADS ', 'module', 'a much longer module description', ?)",
                aliasId, collectionId, new String[] { VERSION });
        jdbcTemplate.update(
                "INSERT INTO entities (id, collection_id, name, kind, description, tags) VALUES (?, ?, 'Other', 'table', 'other table', ?)",
                otherId, collectionId, new String[] { VERSION });

        // 1. Outgoing edge OF the alias, 2. the same triple already on the canonical row (so the
        // repointed edge becomes a duplicate that step 2 collapses), 3. incoming edge TO the alias.
        jdbcTemplate.update(
                "INSERT INTO relationships (id, collection_id, subject, predicate, object, subject_id, object_id, description, tags) VALUES (?, ?, 'ADS ', 'part_of', 'Other', ?, ?, 'short', ?)",
                UUID.randomUUID(), collectionId, aliasId, otherId, new String[] { VERSION });
        jdbcTemplate.update(
                "INSERT INTO relationships (id, collection_id, subject, predicate, object, subject_id, object_id, description, tags) VALUES (?, ?, 'ADS', 'part_of', 'Other', ?, ?, 'a much longer and more detailed edge description', ?)",
                UUID.randomUUID(), collectionId, canonicalId, otherId, new String[] { VERSION });
        jdbcTemplate.update(
                "INSERT INTO relationships (id, collection_id, subject, predicate, object, subject_id, object_id, description, tags) VALUES (?, ?, 'Other', 'references', 'ADS ', ?, ?, 'incoming', ?)",
                UUID.randomUUID(), collectionId, otherId, aliasId, new String[] { VERSION });

        KgConsolidator.ConsolidationStats stats = kgConsolidator.consolidate(COLLECTION, VERSION);

        assertThat(stats.groupsProcessed()).isEqualTo(1);
        assertThat(stats.rowsDeleted()).isEqualTo(1);
        // One subject-side and one object-side edge moved off the alias.
        assertThat(stats.relationshipsRepointCount()).isEqualTo(2);
        assertThat(stats.relationshipsCollapsedCount()).isEqualTo(1);

        // No edge is left dangling on the deleted alias row.
        List<Map<String, Object>> rels = jdbcTemplate.queryForList(
                "SELECT r.subject, r.predicate, r.object, r.subject_id::text, r.object_id::text, r.description "
                        + "FROM relationships r JOIN collections c ON r.collection_id = c.id WHERE c.name = ? ORDER BY r.predicate",
                COLLECTION);
        assertThat(rels).hasSize(2);
        assertThat(rels).noneMatch(r -> aliasId.toString().equals(r.get("subject_id"))
                || aliasId.toString().equals(r.get("object_id")));

        Map<String, Object> outgoing = rels.get(0);
        assertThat(outgoing.get("predicate")).isEqualTo("part_of");
        assertThat(outgoing.get("subject")).isEqualTo("ADS");
        assertThat(outgoing.get("subject_id")).isEqualTo(canonicalId.toString());
        assertThat(outgoing.get("description"))
                .isEqualTo("a much longer and more detailed edge description");

        Map<String, Object> incoming = rels.get(1);
        assertThat(incoming.get("predicate")).isEqualTo("references");
        assertThat(incoming.get("object")).isEqualTo("ADS");
        assertThat(incoming.get("object_id")).isEqualTo(canonicalId.toString());
    }
}
