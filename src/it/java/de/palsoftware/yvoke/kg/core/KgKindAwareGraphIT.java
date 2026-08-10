package de.palsoftware.yvoke.kg.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.palsoftware.yvoke.kg.core.model.KgEntity;
import de.palsoftware.yvoke.kg.core.model.KgEntityKindEdgeCount;
import de.palsoftware.yvoke.kg.core.model.KgNeighborEdges;
import de.palsoftware.yvoke.kg.core.repository.KgGraphReadRepository;
import de.palsoftware.yvoke.kg.core.repository.KgWriteRepository;
import de.palsoftware.yvoke.kg.core.repository.KgWriteRepository.EntityUpsert;
import de.palsoftware.yvoke.kg.core.repository.KgWriteRepository.RelationshipUpsert;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * End-to-end coverage of the kind-aware knowledge-graph identity (V2): same-named entities of
 * different kinds persist as distinct rows and resolve independently; edges are identified by their
 * endpoint ids (so homonym edges neither collapse at insert nor get another edge's tag); and
 * neighbor reads resolve direction and counterpart by id, with no name-text fallback.
 */
@SpringBootTest
public class KgKindAwareGraphIT {

    private static final String COLLECTION = "OIM-KINDAWARE-TEST";
    private static final String TAG = "9.3";

    @Autowired
    private KgWriteRepository kgWriteRepository;

    @Autowired
    private KgGraphReadRepository kgReadRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID collectionId;

    @BeforeEach
    public void setUp() {
        cleanup();
        collectionId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO collections (id, name, tags) VALUES (?, ?, ARRAY['9.3'])",
            collectionId, COLLECTION);
    }

    @AfterEach
    public void tearDown() {
        cleanup();
    }

    private void cleanup() {
        jdbcTemplate.update("DELETE FROM collections WHERE name = ?", COLLECTION);
    }

    /**
     * Within ONE {@code upsertEntitiesBatch} call, several mentions of the same identity collapse
     * to one entity and the FIRST of them wins.
     *
     * <p>
     * The dedup is a {@code LinkedHashMap.putIfAbsent} keyed on {@code lower(kind):lower(name)},
     * and it does two jobs at once. It makes the returned map — which is what the job reports as
     * its entity count — count distinct identities rather than mentions: an extractor that names
     * {@code Person} in forty chunks of one document produces one node, not forty. And it decides
     * which spec is actually written, because the description, embedding and
     * {@code metadata.document_id} of the inserted row all come from the surviving entry.
     *
     * <p>
     * Neither half is observed anywhere. Every existing case in this class and in
     * {@code KgWriteRepositoryTagIdentityIT} passes distinct identities, or repeats the whole call
     * — and a repeated CALL is absorbed by the SELECT-then-insert resolve step and by
     * {@code ON CONFLICT ... DO NOTHING}, so it proves nothing about the in-batch map. Swap
     * {@code putIfAbsent} for {@code put} (the obvious "same thing, fewer characters" edit) and
     * last-mention-wins silently: the row keeps the LAST mention's spelling and description while
     * every earlier, usually better-described mention is discarded. Break the key instead — drop
     * the {@code toLowerCase}, or drop the kind — and the count inflates to the number of mentions
     * while the database quietly absorbs the surplus through the same {@code ON CONFLICT} clause,
     * so the job reports a graph far larger than the one it built.
     *
     * <p>
     * Case variants are the realistic shape here: the OIM corpus writes the same table as
     * {@code Person}, {@code PERSON} and {@code person} across frontmatter, jsonl and prose.
     */
    @Test
    public void oneBatchCountsIdentitiesNotMentionsAndTheFirstOccurrenceWins() {
        Map<String, UUID> byKey = kgWriteRepository.upsertEntitiesBatch(COLLECTION, List.of(TAG),
            List.of(new EntityUpsert("Person", "table", "first mention - this one must win"),
                new EntityUpsert("PERSON", "Table", "second mention of the same identity"),
                new EntityUpsert("person", "TABLE", "third mention of the same identity")));

        assertThat(byKey)
            .as("three mentions of one identity are ONE entity - the count is identities, not"
                + " mentions")
            .hasSize(1).containsKey("table:person");
        assertThat(entityCount("Person"))
            .as("the database must not be left to absorb the surplus through ON CONFLICT")
            .isEqualTo(1);

        Map<String, Object> stored = jdbcTemplate.queryForMap(
            "SELECT e.name, e.description FROM entities e "
                + "JOIN collections c ON e.collection_id = c.id "
                + "WHERE c.name = ? AND lower(e.name) = 'person'",
            COLLECTION);
        assertThat(stored.get("name")).as("first occurrence wins, so its spelling is the row's")
            .isEqualTo("Person");
        assertThat(stored.get("description"))
            .as("last-mention-wins silently discards every earlier, usually richer mention")
            .isEqualTo("first mention - this one must win");
    }

    private int entityCount(String name) {
        Integer n = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM entities e JOIN collections c ON e.collection_id = c.id "
                + "WHERE c.name = ? AND lower(e.name) = lower(?)",
            Integer.class, COLLECTION, name);
        return n == null ? 0 : n;
    }

    @Test
    public void sameNamedEntitiesOfDifferentKindsBothPersistAndResolve() {
        Map<String, UUID> byKey = kgWriteRepository.upsertEntitiesBatch(COLLECTION, List.of(TAG),
            List.of(new EntityUpsert("ADS", "module", "the ADS module"),
                new EntityUpsert("ADS", "connector", "the ADS connector")));

        // Both homonyms survived (the pre-V2 code silently dropped one) and are keyed by kind.
        assertThat(byKey).containsKeys("module:ads", "connector:ads");
        UUID moduleId = byKey.get("module:ads");
        UUID connectorId = byKey.get("connector:ads");
        assertThat(moduleId).isNotEqualTo(connectorId);
        assertThat(entityCount("ADS")).isEqualTo(2);

        // A second upsert is idempotent per (kind, name) — no duplicate rows.
        Map<String, UUID> again = kgWriteRepository.upsertEntitiesBatch(COLLECTION, List.of(TAG),
            List.of(new EntityUpsert("ADS", "module", "the ADS module"),
                new EntityUpsert("ADS", "connector", "the ADS connector")));
        assertThat(again.get("module:ads")).isEqualTo(moduleId);
        assertThat(again.get("connector:ads")).isEqualTo(connectorId);
        assertThat(entityCount("ADS")).isEqualTo(2);
    }

    @Test
    public void relationshipEndpointsLinkTheCorrectHomonymNotASelfLoop() {
        Map<String, UUID> byKey = kgWriteRepository.upsertEntitiesBatch(COLLECTION, List.of(TAG),
            List.of(new EntityUpsert("ADS", "module", "module"),
                new EntityUpsert("ADS", "connector", "connector")));
        UUID moduleId = byKey.get(KgWriteRepository.entityKey("module", "ADS"));
        UUID connectorId = byKey.get(KgWriteRepository.entityKey("connector", "ADS"));

        kgWriteRepository.insertRelationshipsBatch(COLLECTION, List.of(TAG),
            List.of(new RelationshipUpsert("ADS", "HAS_CONNECTOR", "ADS", moduleId, connectorId,
                "module ADS owns connector ADS", null)));

        UUID storedSubject = jdbcTemplate.queryForObject(
            "SELECT r.subject_id FROM relationships r JOIN collections c ON r.collection_id = c.id "
                + "WHERE c.name = ? AND r.predicate = 'HAS_CONNECTOR'",
            UUID.class, COLLECTION);
        UUID storedObject = jdbcTemplate.queryForObject(
            "SELECT r.object_id FROM relationships r JOIN collections c ON r.collection_id = c.id "
                + "WHERE c.name = ? AND r.predicate = 'HAS_CONNECTOR'",
            UUID.class, COLLECTION);
        assertThat(storedSubject).isEqualTo(moduleId);
        assertThat(storedObject).isEqualTo(connectorId);
        assertThat(storedSubject).isNotEqualTo(storedObject);
    }

    @Test
    public void getEntityRelationshipsMatchesByIdAndKindWithAmbiguityKinds() {
        Map<String, UUID> byKey = kgWriteRepository.upsertEntitiesBatch(COLLECTION, List.of(TAG),
            List.of(new EntityUpsert("ADS", "module", "module"),
                new EntityUpsert("ADS", "connector", "connector")));
        UUID moduleId = byKey.get("module:ads");
        UUID connectorId = byKey.get("connector:ads");
        kgWriteRepository.insertRelationshipsBatch(COLLECTION, List.of(TAG),
            List.of(new RelationshipUpsert("ADS", "HAS_CONNECTOR", "ADS", moduleId, connectorId,
                "owns", null)));

        // Kind filter pins the connector node; the edge is matched via object_id.
        KgNeighborEdges connectorView = kgReadRepository.getEntityRelationships("ADS", "connector",
            TAG, COLLECTION, null, "both", 200);
        assertThat(connectorView.matchedKinds()).containsExactly("connector");
        assertThat(connectorView.edges()).hasSize(1);
        KgNeighborEdges.Edge edge = connectorView.edges().get(0);
        assertThat(edge.predicate()).isEqualTo("HAS_CONNECTOR");
        assertThat(edge.subject()).isEqualTo("ADS");
        assertThat(edge.object()).isEqualTo("ADS");
        assertThat(edge.counterpartKind()).isEqualTo("module");

        // No kind: the name is ambiguous — both kinds are reported so callers can disambiguate.
        KgNeighborEdges ambiguous =
            kgReadRepository.getEntityRelationships("ADS", null, TAG, COLLECTION, null, "both", 200);
        assertThat(ambiguous.matchedKinds()).containsExactlyInAnyOrder("module", "connector");
        assertThat(ambiguous.edges()).hasSize(1);
    }

    /**
     * Builds the collided-name fixture: "Person" as a table (2 outgoing + 1 incoming edge at 9.3,
     * plus 1 outgoing edge at 10.0), as a ui_form (1 outgoing edge at 9.3) and as a notification
     * (no edges at all). All entities carry both tags so tag filtering is exercised on the edges,
     * not on the nodes.
     */
    private Map<String, UUID> seedCollidedPersonGraph(String documentId) {
        List<String> bothTags = List.of(TAG, "10.0");
        Map<String, UUID> byKey = kgWriteRepository.upsertEntitiesBatch(COLLECTION, bothTags,
            List.of(
                new EntityUpsert("Person", "table", "person table", null,
                    Map.of("document_id", documentId)),
                new EntityUpsert("Person", "ui_forms", "person form"),
                new EntityUpsert("Person", "notification", "person notification"),
                new EntityUpsert("Org", "table", "org table"),
                new EntityUpsert("Dept", "table", "dept table"),
                new EntityUpsert("Legacy", "table", "legacy table")));

        UUID personTable = byKey.get(KgWriteRepository.entityKey("table", "Person"));
        UUID personForm = byKey.get(KgWriteRepository.entityKey("ui_forms", "Person"));
        UUID org = byKey.get(KgWriteRepository.entityKey("table", "Org"));
        UUID dept = byKey.get(KgWriteRepository.entityKey("table", "Dept"));
        UUID legacy = byKey.get(KgWriteRepository.entityKey("table", "Legacy"));

        kgWriteRepository.insertRelationshipsBatch(COLLECTION, List.of(TAG), List.of(
            new RelationshipUpsert("Person", "fk_to", "Org", personTable, org, "fk", null),
            new RelationshipUpsert("Person", "fk_to", "Dept", personTable, dept, "fk", null),
            new RelationshipUpsert("Org", "references_table", "Person", org, personTable, "ref",
                null),
            new RelationshipUpsert("Person", "shows", "Org", personForm, org, "form", null)));
        // A 10.0-only edge of the same subject: the tag-append must leave the 9.3 edges above alone.
        kgWriteRepository.insertRelationshipsBatch(COLLECTION, List.of("10.0"), List
            .of(new RelationshipUpsert("Person", "fk_to", "Legacy", personTable, legacy, "fk", null)));
        return byKey;
    }

    private static List<String> kinds(List<KgEntityKindEdgeCount> rows) {
        return rows.stream().map(KgEntityKindEdgeCount::kind).toList();
    }

    private static long edgesOf(List<KgEntityKindEdgeCount> rows, String kind) {
        return rows.stream().filter(r -> r.kind().equals(kind))
            .map(KgEntityKindEdgeCount::edgeCount).findFirst().orElseThrow();
    }

    @Test
    public void findEntityKindsWithEdgeCountsReportsOneRowPerKindOrderedByEdges() {
        String documentId = UUID.randomUUID().toString();
        seedCollidedPersonGraph(documentId);

        List<KgEntityKindEdgeCount> rows = kgReadRepository.findEntityKindsWithEdgeCounts("person",
            TAG, COLLECTION, null, "both");

        // One row per colliding kind, most-connected first, kind ASC on ties.
        assertThat(kinds(rows)).containsExactly("table", "ui_forms", "notification");
        assertThat(edgesOf(rows, "table")).isEqualTo(3);
        assertThat(edgesOf(rows, "ui_forms")).isEqualTo(1);
        assertThat(edgesOf(rows, "notification")).isZero();
        // The document the node came from is carried through so callers read the right document.
        assertThat(rows.get(0).documentId()).isEqualTo(documentId);
        assertThat(rows.get(1).documentId()).isNull();
    }

    @Test
    public void findEntityKindsWithEdgeCountsHonorsTagDirectionAndRelationType() {
        seedCollidedPersonGraph(UUID.randomUUID().toString());

        // Tag: the 10.0 edge is invisible at 9.3 and vice versa; untagged sees both.
        assertThat(edgesOf(
            kgReadRepository.findEntityKindsWithEdgeCounts("Person", "10.0", COLLECTION, null,
                "both"),
            "table")).isEqualTo(1);
        assertThat(edgesOf(
            kgReadRepository.findEntityKindsWithEdgeCounts("Person", null, COLLECTION, null, "both"),
            "table")).isEqualTo(4);

        // Direction: 2 outgoing + 1 incoming at 9.3.
        assertThat(edgesOf(kgReadRepository.findEntityKindsWithEdgeCounts("Person", TAG, COLLECTION,
            null, "outgoing"), "table")).isEqualTo(2);
        assertThat(edgesOf(kgReadRepository.findEntityKindsWithEdgeCounts("Person", TAG, COLLECTION,
            null, "incoming"), "table")).isEqualTo(1);
        assertThat(edgesOf(kgReadRepository.findEntityKindsWithEdgeCounts("Person", TAG, COLLECTION,
            null, "incoming"), "ui_forms")).isZero();

        // Relation type: only the two fk_to edges of the table remain.
        List<KgEntityKindEdgeCount> fkOnly =
            kgReadRepository.findEntityKindsWithEdgeCounts("Person", TAG, COLLECTION, "fk_to",
                "both");
        assertThat(edgesOf(fkOnly, "table")).isEqualTo(2);
        assertThat(edgesOf(fkOnly, "ui_forms")).isZero();

        // The counts match what a kind-scoped re-query actually returns.
        assertThat(kgReadRepository
            .getEntityRelationships("Person", "table", TAG, COLLECTION, "fk_to", "both", 200)
            .edges()).hasSize(2);
    }

    @Test
    public void findEntityKindsWithEdgeCountsIsEmptyForUnknownNameAndSingleForUniqueName() {
        seedCollidedPersonGraph(UUID.randomUUID().toString());

        assertThat(kgReadRepository.findEntityKindsWithEdgeCounts("NoSuchEntity", TAG, COLLECTION,
            null, "both")).isEmpty();
        assertThat(kinds(kgReadRepository.findEntityKindsWithEdgeCounts("Org", TAG, COLLECTION, null,
            "both"))).containsExactly("table");
    }

    @Test
    public void fuzzySearchOrderingIsStableAcrossRepeatedIdenticalCalls() {
        seedCollidedPersonGraph(UUID.randomUUID().toString());

        // All three "Person" rows tie on similarity AND name length, so without the kind tiebreaker
        // the winning row (and the document_id the model then reads) is arbitrary per call.
        List<String> first = null;
        for (int i = 0; i < 5; i++) {
            List<String> kinds = kgReadRepository.fuzzySearchEntities("Person", 20, TAG, COLLECTION)
                .stream().filter(e -> "Person".equalsIgnoreCase(e.name())).map(KgEntity::kind)
                .toList();
            assertThat(kinds).containsExactly("notification", "table", "ui_forms");
            if (first == null) {
                first = kinds;
            }
            assertThat(kinds).isEqualTo(first);
        }
    }

    /**
     * A name-only legacy row (no entity node, no endpoint ids) has no identity the kind-aware read
     * can trust, and the text-name fallback that used to surface it also handed a kind-scoped node
     * ANOTHER kind's edges. No edges is the truthful answer.
     */
    @Test
    public void getEntityRelationshipsReturnsNothingForNameOnlyLegacyRows() {
        jdbcTemplate.update(
            "INSERT INTO relationships (id, collection_id, subject, predicate, object, subject_id, "
                + "object_id, tags) VALUES (?, ?, 'LegacyTable', 'fk_to', 'OtherTable', NULL, NULL, ARRAY['9.3'])",
            UUID.randomUUID(), collectionId);

        KgNeighborEdges view = kgReadRepository.getEntityRelationships("LegacyTable", null, TAG,
            COLLECTION, null, "both", 200);
        assertThat(view.matchedKinds()).isEmpty();
        assertThat(view.edges()).isEmpty();
    }

    /**
     * {@code module:ADS ─HAS_CONNECTOR→ connector:ADS} joins two DIFFERENT nodes that share a name.
     * Deriving the direction by comparing the requested name to subject/object matched both sides
     * and reported 'self' with the node itself as counterpart; resolving against the endpoint ids
     * gives each side its true direction and its true counterpart kind.
     */
    @Test
    public void homonymEdgeResolvesDirectionAndCounterpartFromEndpointIds() {
        Map<String, UUID> byKey = kgWriteRepository.upsertEntitiesBatch(COLLECTION, List.of(TAG),
            List.of(new EntityUpsert("ADS", "module", "module"),
                new EntityUpsert("ADS", "connector", "connector")));
        UUID moduleId = byKey.get(KgWriteRepository.entityKey("module", "ADS"));
        UUID connectorId = byKey.get(KgWriteRepository.entityKey("connector", "ADS"));
        kgWriteRepository.insertRelationshipsBatch(COLLECTION, List.of(TAG),
            List.of(new RelationshipUpsert("ADS", "HAS_CONNECTOR", "ADS", moduleId, connectorId,
                "module ADS owns connector ADS", null)));

        KgNeighborEdges.Edge fromModule = kgReadRepository
            .getEntityRelationships("ADS", "module", TAG, COLLECTION, null, "both", 200).edges()
            .get(0);
        assertThat(fromModule.direction()).isEqualTo(KgNeighborEdges.Direction.OUTGOING);
        assertThat(fromModule.counterpart()).isEqualTo("ADS");
        assertThat(fromModule.counterpartKind()).isEqualTo("connector");

        KgNeighborEdges.Edge fromConnector = kgReadRepository
            .getEntityRelationships("ADS", "connector", TAG, COLLECTION, null, "both", 200).edges()
            .get(0);
        assertThat(fromConnector.direction()).isEqualTo(KgNeighborEdges.Direction.INCOMING);
        assertThat(fromConnector.counterpart()).isEqualTo("ADS");
        assertThat(fromConnector.counterpartKind()).isEqualTo("module");
    }

    /** 'self' means one node pointing at itself — {@code subject_id = object_id}, nothing else. */
    @Test
    public void genuineSelfReferenceIsReportedAsSelf() {
        Map<String, UUID> byKey = kgWriteRepository.upsertEntitiesBatch(COLLECTION, List.of(TAG),
            List.of(new EntityUpsert("Person", "table", "person table")));
        UUID personTable = byKey.get(KgWriteRepository.entityKey("table", "Person"));
        kgWriteRepository.insertRelationshipsBatch(COLLECTION, List.of(TAG),
            List.of(new RelationshipUpsert("Person", "fk_to", "Person", personTable, personTable,
                "manager fk", null)));

        KgNeighborEdges.Edge edge = kgReadRepository
            .getEntityRelationships("Person", "table", TAG, COLLECTION, null, "both", 200).edges()
            .get(0);
        assertThat(edge.direction()).isEqualTo(KgNeighborEdges.Direction.SELF);
        assertThat(edge.counterpart()).isEqualTo("Person");
        assertThat(edge.counterpartKind()).isEqualTo("table");
    }

    /**
     * The name-text fallback handed a kind-scoped node ANOTHER kind's edges: notification 'Person'
     * has no edges of its own, and the fallback matched {@code lower(subject)/lower(object)}
     * ignoring kind — so all of table 'Person''s edges came back as the notification's neighbors.
     * No edges is the truthful answer.
     */
    @Test
    public void kindScopedNodeWithoutEdgesDoesNotBorrowAnotherKindsEdges() {
        seedCollidedPersonGraph(UUID.randomUUID().toString());

        KgNeighborEdges view = kgReadRepository.getEntityRelationships("Person", "notification", TAG,
            COLLECTION, null, "both", 200);

        assertThat(view.matchedKinds()).containsExactly("notification");
        assertThat(view.edges()).isEmpty();
    }

    /**
     * Edge identity is the endpoint ids, not the name text. The corpus carries BOTH
     * {@code notification:Person ─references_table→ table:Person} and
     * {@code object_methods:Person ─references_table→ table:Person}; keyed by bare names the two
     * collapse onto one key and the second edge is silently dropped at insert.
     */
    @Test
    public void homonymSubjectsProduceTwoDistinctEdgesInsteadOfCollapsingToOne() {
        Map<String, UUID> byKey = kgWriteRepository.upsertEntitiesBatch(COLLECTION, List.of(TAG),
            List.of(new EntityUpsert("Person", "table", "person table"),
                new EntityUpsert("Person", "notification", "person notification"),
                new EntityUpsert("Person", "object_methods", "person object methods")));
        UUID personTable = byKey.get(KgWriteRepository.entityKey("table", "Person"));
        UUID personNotification =
            byKey.get(KgWriteRepository.entityKey("notification", "Person"));
        UUID personObjectMethods =
            byKey.get(KgWriteRepository.entityKey("object_methods", "Person"));

        int inserted = kgWriteRepository.insertRelationshipsBatch(COLLECTION, List.of(TAG),
            List.of(
                new RelationshipUpsert("Person", "references_table", "Person", personNotification,
                    personTable, "notification references the table", null),
                new RelationshipUpsert("Person", "references_table", "Person", personObjectMethods,
                    personTable, "object method references the table", null)));

        assertThat(inserted).isEqualTo(2);
        List<UUID> subjectIds = jdbcTemplate.queryForList(
            "SELECT r.subject_id FROM relationships r JOIN collections c ON r.collection_id = c.id "
                + "WHERE c.name = ? AND r.predicate = 'references_table'",
            UUID.class, COLLECTION);
        assertThat(subjectIds).containsExactlyInAnyOrder(personNotification, personObjectMethods);
    }

    /**
     * The existing-edge prefetch over-fetches by subject; stamping the batch's tag onto every
     * fetched row (rather than only the rows whose key is in THIS batch) put a version tag on edges
     * that never appeared under it.
     */
    @Test
    public void tagAppendTouchesOnlyTheEdgesPresentInThisBatch() {
        Map<String, UUID> byKey = kgWriteRepository.upsertEntitiesBatch(COLLECTION, List.of(TAG),
            List.of(new EntityUpsert("Person", "table", "person table"),
                new EntityUpsert("Org", "table", "org table"),
                new EntityUpsert("Dept", "table", "dept table")));
        UUID person = byKey.get(KgWriteRepository.entityKey("table", "Person"));
        UUID org = byKey.get(KgWriteRepository.entityKey("table", "Org"));
        UUID dept = byKey.get(KgWriteRepository.entityKey("table", "Dept"));

        kgWriteRepository.insertRelationshipsBatch(COLLECTION, List.of(TAG), List
            .of(new RelationshipUpsert("Person", "fk_to", "Org", person, org, "9.3 only", null)));

        // A later batch for a DIFFERENT edge that merely shares the subject must not retag the
        // first one.
        kgWriteRepository.insertRelationshipsBatch(COLLECTION, List.of("10.0"), List
            .of(new RelationshipUpsert("Person", "fk_to", "Dept", person, dept, "10.0 only", null)));

        assertThat(tagsOfEdge(person, org)).containsExactly(TAG);
        assertThat(tagsOfEdge(person, dept)).containsExactly("10.0");
    }

    /**
     * A predicate is the edge's only name. {@code relKey} keys the batch by
     * {@code subject_id|lower(predicate)|object_id} and the neighbor reads hand the model
     * {@code predicate} verbatim as the relation type, so an edge whose predicate is null or blank
     * has no identity at all: every blank-predicate edge between the same two nodes collapses onto
     * ONE key, and the row that survives tells the model two entities are related by {@code ""} —
     * which reads as a real but unnamed relation rather than as missing data, and is unfilterable
     * by {@code relationType} forever after. Extraction produces these routinely: an LLM emitting
     * {@code "relation": ""} and a jsonl record whose predicate column is absent both land here.
     *
     * <p>
     * Dropping the predicate half of the guard is a plausible tidy-up — the endpoint-id half looks
     * like the whole point of the check — and nothing would notice: {@code relationships.predicate}
     * is only {@code NOT NULL}, so {@code ''} inserts happily, the job reports a normal edge count,
     * and {@link #relationshipWithAnUnresolvedEndpointIsSkippedInsteadOfInserted()} covers only the
     * endpoint half. The null case is worse than a bad row: {@code relKey} lower-cases the
     * predicate unconditionally, so an unguarded null NPEs and takes the whole batch — every valid
     * edge in it included — down with it, turning one malformed record into a failed ingest.
     */
    @Test
    public void anEdgeWithABlankPredicateIsSkippedInsteadOfInsertedWithAnEmptyPredicate() {
        Map<String, UUID> byKey = kgWriteRepository.upsertEntitiesBatch(COLLECTION, List.of(TAG),
            List.of(new EntityUpsert("Person", "table", "person table"),
                new EntityUpsert("Org", "table", "org table")));
        UUID person = byKey.get(KgWriteRepository.entityKey("table", "Person"));
        UUID org = byKey.get(KgWriteRepository.entityKey("table", "Org"));

        int inserted = kgWriteRepository.insertRelationshipsBatch(COLLECTION, List.of(TAG),
            List.of(
                new RelationshipUpsert("Person", "fk_to", "Org", person, org, "a real edge", null),
                new RelationshipUpsert("Person", "   ", "Org", person, org, "blank predicate",
                    null),
                new RelationshipUpsert("Person", null, "Org", person, org, "null predicate",
                    null)));

        assertThat(inserted).as("only the identifiable edge counts as written").isEqualTo(1);
        List<String> predicates = jdbcTemplate.queryForList(
            "SELECT r.predicate FROM relationships r JOIN collections c ON r.collection_id = c.id "
                + "WHERE c.name = ?",
            String.class, COLLECTION);
        assertThat(predicates).as("an unnamed edge must never reach the graph")
            .containsExactly("fk_to");
    }

    /**
     * An edge whose endpoint could not be resolved to an entity has no id-based identity; inserting
     * it anyway would reintroduce a name-only row that no kind-aware read can match.
     */
    @Test
    public void relationshipWithAnUnresolvedEndpointIsSkippedInsteadOfInserted() {
        Map<String, UUID> byKey = kgWriteRepository.upsertEntitiesBatch(COLLECTION, List.of(TAG),
            List.of(new EntityUpsert("Person", "table", "person table")));
        UUID person = byKey.get(KgWriteRepository.entityKey("table", "Person"));

        int inserted = kgWriteRepository.insertRelationshipsBatch(COLLECTION, List.of(TAG), List.of(
            new RelationshipUpsert("Person", "fk_to", "Ghost", person, null, "unresolved", null)));

        assertThat(inserted).isZero();
        Integer edges = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM relationships r JOIN collections c ON r.collection_id = c.id "
                + "WHERE c.name = ?",
            Integer.class, COLLECTION);
        assertThat(edges).isZero();
    }

    /**
     * V3 makes {@code entities.kind} NOT NULL. A kind-less row splits one logical entity into a
     * kinded row plus a kind-NULL row that the kind-aware consolidator refuses to merge, and it can
     * never be found by a (kind, name) lookup — the schema now refuses it outright, and the write
     * repository refuses it before it ever reaches the schema.
     */
    @Test
    public void kindLessEntityIsRejectedByBothTheSchemaAndTheWriteRepository() {
        assertThatThrownBy(() -> jdbcTemplate.update(
            "INSERT INTO entities (id, collection_id, name, kind, tags) "
                + "VALUES (?, ?, 'KindLess', NULL, ARRAY['9.3'])",
            UUID.randomUUID(), collectionId)).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("kind");

        assertThatThrownBy(() -> kgWriteRepository.upsertEntitiesBatch(COLLECTION, List.of(TAG),
            List.of(new EntityUpsert("KindLess", null, "no kind at all"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("KindLess");

        assertThat(entityCount("KindLess")).isZero();
    }

    private List<String> tagsOfEdge(UUID subjectId, UUID objectId) {
        return jdbcTemplate.queryForList(
            "SELECT unnest(tags) FROM relationships WHERE subject_id = ? AND object_id = ?",
            String.class, subjectId, objectId);
    }
}
