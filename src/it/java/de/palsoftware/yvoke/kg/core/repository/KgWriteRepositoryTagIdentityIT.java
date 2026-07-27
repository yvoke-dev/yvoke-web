package de.palsoftware.yvoke.kg.core.repository;

import de.palsoftware.yvoke.kg.core.model.KgEntity;

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

/**
 * Graph entity identity is scoped by tag.
 *
 * <p>Regression: the OIM corpus puts two product versions of the same installation kit into ONE
 * collection, separated only by tag. Identity used to be {@code (collection_id, kind, lower(name))}
 * with no tag component, and {@code upsertEntitiesBatch} resolved pre-existing identities, appended
 * the job's tags to them, and inserted only what was missing — it never updated {@code metadata} on
 * a row that already existed. Since the per-version document link lives in
 * {@code metadata.document_id}, the link was written exactly once, on the first ingest: 9,431 of
 * 10.0's 9,904 entities kept pointing at 9.3.1's documents, and the job still reported 9,904.
 *
 * <p>The read paths were never the problem — they already filter with {@code :tag = ANY(tags)}. The
 * shared row simply carried BOTH tags, so it matched either query and handed back the one
 * document_id it had. Making the tag part of identity is what lets that existing filter select the
 * right row.
 */
@SpringBootTest
public class KgWriteRepositoryTagIdentityIT {

    private static final String COLLECTION = "OIM-TAG-IDENTITY-TEST";
    private static final String V1 = "9.3.1";
    private static final String V2 = "10.0";

    @Autowired
    private KgWriteRepository writeRepository;

    @Autowired
    private KgGraphReadRepository readRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID collectionId;

    @BeforeEach
    void setUp() {
        cleanup();
        collectionId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO collections (id, name, tags) VALUES (?, ?, ARRAY['9.3.1', '10.0'])",
            collectionId, COLLECTION);
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    private void cleanup() {
        jdbcTemplate.update("DELETE FROM collections WHERE name = ?", COLLECTION);
    }

    /** One entity of the OIM corpus: same kind and name in both kit versions, different document. */
    private static KgWriteRepository.EntityUpsert table(String docId) {
        return new KgWriteRepository.EntityUpsert("ADSAccount", "table", null, null,
            Map.of("document_id", docId));
    }

    private List<Map<String, Object>> rows() {
        return jdbcTemplate.queryForList(
            "SELECT name, kind, tags, metadata->>'document_id' AS document_id "
                + "FROM entities WHERE collection_id = ? ORDER BY metadata->>'document_id'",
            collectionId);
    }

    // ---------------------------------------------------------------- identity

    @Test
    void sameEntityUnderTwoTagsBecomesTwoRows() {
        Map<String, UUID> first = writeRepository.upsertEntitiesBatch(COLLECTION, List.of(V1),
            List.of(table("doc-9.3.1")));
        Map<String, UUID> second = writeRepository.upsertEntitiesBatch(COLLECTION, List.of(V2),
            List.of(table("doc-10.0")));

        assertThat(rows()).hasSize(2);
        assertThat(first.values()).doesNotContainAnyElementsOf(second.values());
    }

    @Test
    void eachTagKeepsItsOwnDocumentLink() {
        writeRepository.upsertEntitiesBatch(COLLECTION, List.of(V1), List.of(table("doc-9.3.1")));
        writeRepository.upsertEntitiesBatch(COLLECTION, List.of(V2), List.of(table("doc-10.0")));

        assertThat(rows()).extracting(r -> r.get("document_id"))
            .containsExactly("doc-10.0", "doc-9.3.1");
    }

    @Test
    void aRowCarriesOnlyItsOwnTag() {
        writeRepository.upsertEntitiesBatch(COLLECTION, List.of(V1), List.of(table("doc-9.3.1")));
        writeRepository.upsertEntitiesBatch(COLLECTION, List.of(V2), List.of(table("doc-10.0")));

        List<String> tagsOf931 = jdbcTemplate.queryForList(
            "SELECT unnest(tags) FROM entities WHERE collection_id = ? "
                + "AND metadata->>'document_id' = 'doc-9.3.1'",
            String.class, collectionId);
        assertThat(tagsOf931).containsExactly(V1);
    }

    // ------------------------------------------------------- the reported bug

    @Test
    void aTagScopedReadReturnsThatVersionsDocument() {
        writeRepository.upsertEntitiesBatch(COLLECTION, List.of(V1), List.of(table("doc-9.3.1")));
        writeRepository.upsertEntitiesBatch(COLLECTION, List.of(V2), List.of(table("doc-10.0")));

        List<KgEntity> at100 = readRepository.listEntities(COLLECTION, V2, "table", 10, 0);
        assertThat(at100).hasSize(1);
        assertThat(at100.get(0).metadata()).containsEntry("document_id", "doc-10.0");

        List<KgEntity> at931 = readRepository.listEntities(COLLECTION, V1, "table", 10, 0);
        assertThat(at931).hasSize(1);
        assertThat(at931.get(0).metadata()).containsEntry("document_id", "doc-9.3.1");
    }

    // ------------------------------------------------------------ idempotency

    @Test
    void reIngestingTheSameTagIsIdempotent() {
        Map<String, UUID> first = writeRepository.upsertEntitiesBatch(COLLECTION, List.of(V1),
            List.of(table("doc-9.3.1")));
        Map<String, UUID> again = writeRepository.upsertEntitiesBatch(COLLECTION, List.of(V1),
            List.of(table("doc-9.3.1")));

        assertThat(rows()).hasSize(1);
        assertThat(again).containsExactlyInAnyOrderEntriesOf(first);
    }

    @Test
    void anUntaggedIngestStillWorks() {
        writeRepository.upsertEntitiesBatch(COLLECTION, List.of(), List.of(table("doc-untagged")));
        writeRepository.upsertEntitiesBatch(COLLECTION, List.of(), List.of(table("doc-untagged")));

        assertThat(rows()).hasSize(1);
        assertThat(rows().get(0).get("document_id")).isEqualTo("doc-untagged");
    }

    // --------------------------------------------- invariants that must survive

    @Test
    void differentKindsUnderOneTagRemainSeparateRows() {
        // Pre-existing invariant: a name alone is ambiguous in this corpus — Person is a table, an
        // entity_model, a ui_forms, an object_methods AND a notification.
        writeRepository.upsertEntitiesBatch(COLLECTION, List.of(V1), List.of(
            new KgWriteRepository.EntityUpsert("Person", "table", null, null,
                Map.of("document_id", "doc-table")),
            new KgWriteRepository.EntityUpsert("Person", "notification", null, null,
                Map.of("document_id", "doc-notification"))));

        assertThat(rows()).hasSize(2);
    }

    /** Entity id by its document link — independent of how the upsert keys its result map. */
    private UUID idOf(String documentId) {
        return jdbcTemplate.queryForObject(
            "SELECT id FROM entities WHERE collection_id = ? AND metadata->>'document_id' = ?",
            UUID.class, collectionId, documentId);
    }

    @Test
    void removingATagRetiresTheScopeThatWouldCollideWithAnExistingOne() {
        // A row left over from before identity was tag-scoped carries BOTH versions. Removing one
        // tag shrinks it to {10.0} — a scope the real 10.0 row already occupies. Tag removal must
        // retire the shrinking row rather than fail on the unique index.
        UUID legacy = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO entities (id, collection_id, name, kind, metadata, tags) "
                + "VALUES (?, ?, 'ADSAccount', 'table', ?::jsonb, ARRAY['9.3.1','10.0'])",
            legacy, collectionId, "{\"document_id\":\"doc-merged\"}");
        writeRepository.upsertEntitiesBatch(COLLECTION, List.of(V2), List.of(table("doc-10.0")));

        writeRepository.deleteTagGraph(COLLECTION, V1);

        assertThat(rows()).extracting(r -> r.get("document_id")).containsExactly("doc-10.0");
    }

    @Test
    void relationshipEndpointsResolveToTheirOwnVersionsRows() {
        // Relationship identity is (subjectId, predicate, objectId); once the endpoints are
        // tag-scoped rows, the edges separate by version without any change of their own.
        writeRepository.upsertEntitiesBatch(COLLECTION, List.of(V1),
            List.of(table("doc-9.3.1"),
                new KgWriteRepository.EntityUpsert("ADSGroup", "table", null, null,
                    Map.of("document_id", "doc-9.3.1-b"))));
        writeRepository.upsertEntitiesBatch(COLLECTION, List.of(V2),
            List.of(table("doc-10.0"),
                new KgWriteRepository.EntityUpsert("ADSGroup", "table", null, null,
                    Map.of("document_id", "doc-10.0-b"))));

        writeRepository.insertRelationshipsBatch(COLLECTION, List.of(V1),
            List.of(new KgWriteRepository.RelationshipUpsert("ADSAccount", "FK_TO", "ADSGroup",
                idOf("doc-9.3.1"), idOf("doc-9.3.1-b"), null, null)));
        writeRepository.insertRelationshipsBatch(COLLECTION, List.of(V2),
            List.of(new KgWriteRepository.RelationshipUpsert("ADSAccount", "FK_TO", "ADSGroup",
                idOf("doc-10.0"), idOf("doc-10.0-b"), null, null)));

        Integer edges = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM relationships WHERE collection_id = ?", Integer.class,
            collectionId);
        assertThat(edges).isEqualTo(2);
    }
}
