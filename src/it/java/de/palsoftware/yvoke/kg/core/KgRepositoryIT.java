package de.palsoftware.yvoke.kg.core;
import de.palsoftware.yvoke.kg.core.model.KgCall;
import de.palsoftware.yvoke.kg.core.model.KgEntity;
import de.palsoftware.yvoke.kg.core.model.KgNeighborhood;
import de.palsoftware.yvoke.kg.core.model.KgWalk;
import de.palsoftware.yvoke.kg.core.repository.KgGraphReadRepository;


import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class KgRepositoryIT {

    private static final String COLLECTION = "OIM-TEST";

    @Autowired
    private KgGraphReadRepository kgRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Entity UUIDs (version 9.3)
    private final UUID tableAId = UUID.randomUUID();
    private final UUID tableBId = UUID.randomUUID();
    private final UUID tableCId = UUID.randomUUID();
    private final UUID procXId = UUID.randomUUID();
    private final UUID procYId = UUID.randomUUID();

    private UUID collectionId;

    @BeforeEach
    public void setUp() {
        cleanup();
        collectionId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO collections (id, name, tags) VALUES (?, ?, ARRAY['9.3', '10.0'])",
                collectionId, COLLECTION);

        // 1. Insert Entities (9.3)
        insertEntity(tableAId, COLLECTION, "TableA", "table", "9.3", "Table A description");
        insertEntity(tableBId, COLLECTION, "TableB", "table", "9.3", "Table B description");
        insertEntity(tableCId, COLLECTION, "TableC", "table", "9.3", "Table C description");
        insertEntity(procXId, COLLECTION, "ProcX", "procedure", "9.3", "Proc X description");
        insertEntity(procYId, COLLECTION, "ProcY", "procedure", "9.3", "Proc Y description");

        // 2. Version 10.0 shares the same entity rows — identity is version-independent
        // ((collection, kind, name)); the version is just an extra tag on the row (V2 unique index).
        addTag(tableAId, "10.0");
        addTag(tableBId, "10.0");

        // 3. Insert Relationships (9.3)
        // TableA --fk_to--> TableB
        insertRelationship(UUID.randomUUID(), COLLECTION, "TableA", "fk_to", "TableB", tableAId, tableBId, "9.3",
                "FK from A to B");
        // TableB --fk_to--> TableC
        insertRelationship(UUID.randomUUID(), COLLECTION, "TableB", "fk_to", "TableC", tableBId, tableCId, "9.3",
                "FK from B to C");
        // TableC --fk_to--> TableA (cycle!)
        insertRelationship(UUID.randomUUID(), COLLECTION, "TableC", "fk_to", "TableA", tableCId, tableAId, "9.3",
                "FK from C to A");
        // ProcX --calls--> ProcY
        insertRelationship(UUID.randomUUID(), COLLECTION, "ProcX", "calls", "ProcY", procXId, procYId, "9.3",
                "Proc X calls Y");
        // ProcX --references_table--> TableA
        insertRelationship(UUID.randomUUID(), COLLECTION, "ProcX", "references_table", "TableA", procXId, tableAId,
                "9.3", "Proc X touches TableA");

        // 4. Insert Relationships (10.0) — same shared entity rows, tagged 10.0.
        insertRelationship(UUID.randomUUID(), COLLECTION, "TableA", "fk_to", "TableB", tableAId, tableBId, "10.0",
                "FK from A to B (v10)");
    }

    private void addTag(UUID entityId, String tag) {
        jdbcTemplate.update(
                "UPDATE entities SET tags = array_append(tags, ?) WHERE id = ? AND NOT (? = ANY(tags))",
                tag, entityId, tag);
    }

    @AfterEach
    public void tearDown() {
        cleanup();
    }

    private void cleanup() {
        jdbcTemplate.update("DELETE FROM collections WHERE name = ?", COLLECTION);
    }

    private void insertEntity(UUID id, String collection, String name, String category, String version,
            String description) {
        String sql = "INSERT INTO entities (id, collection_id, name, kind, description, tags, metadata) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)";
        jdbcTemplate.update(sql, id, collectionId, name, category, description, new String[] { version }, "{}");
    }

    private void insertRelationship(UUID id, String collection, String subject, String predicate, String object,
            UUID subjectId, UUID objectId, String version, String description) {
        String sql = "INSERT INTO relationships (id, collection_id, subject, predicate, object, subject_id, object_id, description, tags, metadata) "
                +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)";
        jdbcTemplate.update(sql, id, collectionId, subject, predicate, object, subjectId, objectId, description,
                new String[] { version }, "{}");
    }

    @Test
    public void testFuzzySearchEntities() {
        // Test typo-tolerance: search "TbleA" should find "TableA"
        List<KgEntity> results = kgRepository.fuzzySearchEntities("TbleA", 5, "9.3", COLLECTION);

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).name()).isEqualTo("TableA");
        assertThat(results.get(0).similarity()).isNotNull();
        assertThat(results.get(0).tag()).isEqualTo("9.3");

        // Test sorting: "TableA" similarity should be highest when searching "TableA"
        List<KgEntity> resultsExact = kgRepository.fuzzySearchEntities("TableA", 5, "9.3", COLLECTION);
        assertThat(resultsExact).isNotEmpty();
        assertThat(resultsExact.get(0).name()).isEqualTo("TableA");

        // Test filtering by category: search "TableA" with category "table"
        List<KgEntity> resultsTable = kgRepository.fuzzySearchEntities("TableA", 5, "9.3", COLLECTION, "table");
        assertThat(resultsTable).isNotEmpty();
        assertThat(resultsTable.get(0).name()).isEqualTo("TableA");

        // Test filtering by non-matching category: search "TableA" with category
        // "procedure"
        List<KgEntity> resultsProc = kgRepository.fuzzySearchEntities("TableA", 5, "9.3", COLLECTION, "procedure");
        assertThat(resultsProc).isEmpty();
    }

    @Test
    public void testGetNeighborhood() {
        KgNeighborhood neighborhood = kgRepository.getNeighborhood("TableA", "9.3", COLLECTION);

        assertThat(neighborhood).isNotNull();
        assertThat(neighborhood.entity().name()).isEqualTo("TableA");
        assertThat(neighborhood.entity().description()).isEqualTo("Table A description");

        // Outgoing FK (TableA -> TableB)
        assertThat(neighborhood.outgoing()).hasSize(1);
        assertThat(neighborhood.outgoing().get(0).object()).isEqualTo("TableB");

        // Incoming relationships (TableC -> TableA [FK] and ProcX -> TableA
        // [references_table])
        assertThat(neighborhood.incoming()).hasSize(2);
        assertThat(neighborhood.incoming().stream().anyMatch(r -> "TableC".equals(r.subject()))).isTrue();
        assertThat(neighborhood.incoming().stream().anyMatch(r -> "ProcX".equals(r.subject()))).isTrue();
    }

    @Test
    public void testGetNeighborhoodGracefulDegradation() {
        // TableD does not exist in entities table but let's insert a relationship
        // mentioning it
        UUID relId = UUID.randomUUID();
        insertRelationship(relId, COLLECTION, "TableD", "fk_to", "TableA", null, tableAId, "9.3", "FK from D to A");

        KgNeighborhood neighborhood = kgRepository.getNeighborhood("TableD", "9.3", COLLECTION);

        assertThat(neighborhood).isNotNull();
        assertThat(neighborhood.entity().name()).isEqualTo("TableD");
        assertThat(neighborhood.entity().description()).contains("no entity node in the graph");
        assertThat(neighborhood.outgoing()).hasSize(1);
        assertThat(neighborhood.outgoing().get(0).object()).isEqualTo("TableA");
    }

    @Test
    public void testGetProcsForTable() {
        List<KgEntity> procs = kgRepository.getProcsForTable("TableA", 10, "9.3", COLLECTION);

        assertThat(procs).hasSize(1);
        assertThat(procs.get(0).name()).isEqualTo("ProcX");
        assertThat(procs.get(0).category()).isEqualTo("procedure");
        assertThat(procs.get(0).description()).isEqualTo("Proc X description");
    }

    @Test
    public void testGetCalls() {
        // Test callers
        List<KgCall> callers = kgRepository.getCalls("ProcY", "callers", 10, "9.3", COLLECTION);
        assertThat(callers).hasSize(1);
        assertThat(callers.get(0).name()).isEqualTo("ProcX");
        assertThat(callers.get(0).relationType()).isEqualTo("caller");

        // Test callees
        List<KgCall> callees = kgRepository.getCalls("ProcX", "callees", 10, "9.3", COLLECTION);
        assertThat(callees).hasSize(1);
        assertThat(callees.get(0).name()).isEqualTo("ProcY");
        assertThat(callees.get(0).relationType()).isEqualTo("callee");

        // Test both
        List<KgCall> both = kgRepository.getCalls("ProcX", "both", 10, "9.3", COLLECTION);
        assertThat(both).hasSize(1);
        assertThat(both.get(0).name()).isEqualTo("ProcY");
        assertThat(both.get(0).relationType()).isEqualTo("callee");
    }

    @Test
    public void testGetFkWalkRecursive() {
        // 1 hop from TableA should return:
        // TableA -> TableB (depth 1)
        // TableC -> TableA (depth 1)
        List<KgWalk> walks1 = kgRepository.getFkWalk("TableA", 1, "9.3", COLLECTION);
        assertThat(walks1).hasSize(2);
        assertThat(walks1.get(0).depth()).isEqualTo(1);
        assertThat(walks1.stream().anyMatch(w -> w.path().contains("→ TableB"))).isTrue();
        assertThat(walks1.stream().anyMatch(w -> w.path().contains("← TableC"))).isTrue();

        // 2 hops from TableA should walk:
        // TableA -> TableB -> TableC (depth 2)
        // TableC -> TableA -> TableB (depth 2)
        List<KgWalk> walks2 = kgRepository.getFkWalk("TableA", 2, "9.3", COLLECTION);
        assertThat(walks2.size()).isGreaterThan(2);
        assertThat(walks2.stream().anyMatch(w -> w.depth() == 2 && w.path().toString().contains("TableC"))).isTrue();
    }

    @Test
    public void testGetFkWalkDepthClamp() {
        // Requesting 10 hops on a 3-node cycle graph: the Java-side clamp caps the SQL
        // :hops param
        // at 5, and cycle detection terminates traversal before that — max returned
        // depth must be ≤ 5.
        List<KgWalk> walks = kgRepository.getFkWalk("TableA", 10, "9.3", COLLECTION);
        assertThat(walks).isNotEmpty();
        int maxDepth = walks.stream().mapToInt(KgWalk::depth).max().orElse(0);
        assertThat(maxDepth).isLessThanOrEqualTo(5);
    }

    @Test
    public void testVersionScoping() {
        // TableA is a single entity row shared by both versions (tags {9.3, 10.0}); TableC/ProcX are
        // 9.3-only. Version scoping is by tag membership.
        List<KgEntity> search10 = kgRepository.fuzzySearchEntities("TableA", 5, "10.0", COLLECTION);
        assertThat(search10).isNotEmpty();
        assertThat(search10.stream().allMatch(e -> e.tags().contains("10.0"))).isTrue();
        assertThat(search10.get(0).name()).isEqualTo("TableA");
        assertThat(search10.get(0).description()).isEqualTo("Table A description");

        List<KgEntity> search93 = kgRepository.fuzzySearchEntities("TableA", 5, "9.3", COLLECTION);
        assertThat(search93).isNotEmpty();
        assertThat(search93.stream().allMatch(e -> e.tags().contains("9.3"))).isTrue();
        assertThat(search93.get(0).name()).isEqualTo("TableA");
        assertThat(search93.get(0).description()).isEqualTo("Table A description");

        // TableC is 9.3-only: it must appear at 9.3 but never at 10.0 (fuzzy name matches are
        // tag-filtered, so the 9.3-only TableC row is excluded from a 10.0-scoped search).
        List<KgEntity> tableC93 = kgRepository.fuzzySearchEntities("TableC", 5, "9.3", COLLECTION);
        assertThat(tableC93).anyMatch(e -> "TableC".equals(e.name()));
        List<KgEntity> tableC10 = kgRepository.fuzzySearchEntities("TableC", 5, "10.0", COLLECTION);
        assertThat(tableC10).noneMatch(e -> "TableC".equals(e.name()));

        // Version 10.0 neighborhood should only return v10-tagged relationships.
        KgNeighborhood neighborhood10 = kgRepository.getNeighborhood("TableA", "10.0", COLLECTION);
        assertThat(neighborhood10.outgoing()).hasSize(1);
        assertThat(neighborhood10.outgoing().get(0).object()).isEqualTo("TableB");
        assertThat(neighborhood10.outgoing().get(0).tag()).isEqualTo("10.0");
        assertThat(neighborhood10.incoming()).isEmpty(); // ProcX references TableA only in 9.3
    }
}
