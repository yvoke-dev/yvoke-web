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
import java.util.Arrays;

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

    /**
     * {@code CustomIngestService} deletes and recreates every document with a NEW uuid on each run,
     * but entity rows survive — so a re-ingest MUST repoint the surviving row's
     * {@code metadata->>'document_id'} at the freshly created document. With no update branch at
     * all, a re-ingested collection's entities pointed at deleted documents and every
     * graph-to-document navigation dead-ended, while the job still reported every entity written.
     */
    @Test
    void reIngestRepointsTheDocumentLinkOnTheSurvivingRow() {
        writeRepository.upsertEntitiesBatch(COLLECTION, List.of(V1), List.of(table("doc-old")));

        writeRepository.upsertEntitiesBatch(COLLECTION, List.of(V1), List.of(table("doc-new")));

        assertThat(rows()).hasSize(1);
        assertThat(rows().get(0).get("document_id"))
            .as("the surviving entity must follow the recreated document").isEqualTo("doc-new");
    }

    /**
     * The refresh is {@code coalesce(?::jsonb, metadata)} on purpose. The LLM extraction path
     * (`DocumentIngestService.persistGraph`) uses the 3-arg {@code EntityUpsert}, i.e. metadata is
     * null — running kg-extract over a collection the custom path had already linked MUST NOT blank
     * the document link it wrote. Replacing the COALESCE with a plain assignment orphans every
     * entity the extractor touches.
     */
    @Test
    void anLlmPathUpsertWithNoMetadataMustNotWipeAnExistingDocumentLink() {
        writeRepository.upsertEntitiesBatch(COLLECTION, List.of(V1), List.of(table("doc-9.3.1")));

        writeRepository.upsertEntitiesBatch(COLLECTION, List.of(V1), List.of(
            new KgWriteRepository.EntityUpsert("ADSAccount", "table", "a model-written description")));

        assertThat(rows()).hasSize(1);
        assertThat(rows().get(0).get("document_id"))
            .as("kg-extract must not orphan a link the custom ingest established")
            .isEqualTo("doc-9.3.1");
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

    /**
     * {@code kg_canonical_tags(TEXT[])} is the identity expression of the unique index on
     * {@code entities} (V1) and on {@code documents} (V4), and of the scope guard the consolidator
     * evaluates over {@code relationships} — while {@code KgWriteRepository.canonicalTags} is a
     * hand-written Java mirror of it that every {@code ON CONFLICT} and every scope-resolving probe
     * depends on agreeing with. Only the Java half is tested today
     * ({@code KgWriteRepositoryCanonicalTagsTest}); nothing anywhere executes the SQL function, so
     * the two are free to drift on trimming, blank-dropping, de-duplication or the
     * {@code COLLATE "C"} ordering with no compile error, no startup error and no failing test. When
     * they do, one tag scope forks into two across three tables at once: the ingest reports
     * perfectly normal counts while the second product version's entities either land on rows of
     * their own or resolve onto the first version's and overwrite its document links — the exact
     * incident tag-scoped identity was introduced to fix.
     *
     * <p>The input carries every case the two implementations have to agree on: padding
     * ({@code " 10.0 "} must land in the same scope as {@code "10.0"}), a duplicate that only
     * appears after trimming, an empty and a whitespace-only tag, a NULL element, and mixed case to
     * pin the byte ordering — under a locale-aware collation {@code "alpha"} sorts before
     * {@code "Beta"}, which is a different array and therefore a different index key. The permuted
     * second call pins the property that actually matters: identity is the tag SET, so array order
     * must never be able to fork a scope. The volatility check is the precondition for all of it —
     * a function that is not IMMUTABLE cannot back a unique index at all.
     */
    @Test
    void theCanonicalTagsSqlFunctionAgreesWithItsJavaMirrorOnTrimmingBlanksDuplicatesAndOrder() {
        List<String> fromSql = jdbcTemplate.queryForList(
            "SELECT t FROM unnest(kg_canonical_tags("
                + "ARRAY[' 10.0 ', '10.0', '9.3.1', '', '  ', NULL, '9.3.1', 'Beta', 'alpha']"
                + ")) WITH ORDINALITY AS u(t, ord) ORDER BY ord",
            String.class);
        String[] fromJava = KgWriteRepository.canonicalTags(
            Arrays.asList(" 10.0 ", "10.0", "9.3.1", "", "  ", null, "9.3.1", "Beta", "alpha"));

        assertThat(fromSql).as("the SQL expression the unique indexes actually evaluate")
            .containsExactly("10.0", "9.3.1", "Beta", "alpha");
        assertThat(fromJava).as("the Java mirror every ON CONFLICT is written against")
            .containsExactly("10.0", "9.3.1", "Beta", "alpha");

        // Identity is the SET, so permuting the write's tag list must not change the index key.
        List<String> permuted = jdbcTemplate.queryForList(
            "SELECT t FROM unnest(kg_canonical_tags("
                + "ARRAY['alpha', '9.3.1', NULL, 'Beta', '  ', '10.0', '', ' 10.0 ']"
                + ")) WITH ORDINALITY AS u(t, ord) ORDER BY ord",
            String.class);
        assertThat(permuted).as("array order must not be able to fork one logical scope into two")
            .containsExactly("10.0", "9.3.1", "Beta", "alpha");

        assertThat(jdbcTemplate.queryForObject("SELECT provolatile::text || proparallel::text "
            + "FROM pg_proc WHERE proname = 'kg_canonical_tags'", String.class))
                .as("IMMUTABLE PARALLEL SAFE — an index expression cannot be anything else")
                .isEqualTo("is");
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

    /**
     * The invariant that makes the whole tag-scoped graph safe, and which nothing asserts today.
     *
     * <p>
     * {@code KgConsolidator} merges duplicate entities and then repoints their edges onto the
     * canonical row before deleting the aliases. Both repoint UPDATEs are guarded by
     * {@code kg_canonical_tags(relationships.tags) = kg_canonical_tags(canonical.tags)} — an edge in
     * a different tag scope is deliberately NOT repointed. That guard is correct only while an
     * edge's tag scope always equals its endpoints': an edge tagged {@code {10.0}} that pointed at a
     * {@code {9.3.1}} entity would be skipped by the repoint and then hard-deleted by
     * {@code fk_relationships_subject/object ON DELETE CASCADE} when that entity is removed as an
     * alias. Silently, with the consolidation stats reporting a perfectly ordinary merge.
     *
     * <p>
     * No code path can build such a row — but only because both write paths pass the SAME tag list
     * to {@code upsertEntitiesBatch} (which resolves strictly within
     * {@code kg_canonical_tags(tags)}) and then to {@code insertRelationshipsBatch} (which stamps
     * that list onto the edge). Two independent one-line edits break it: letting entity resolution
     * cross scopes hands one version's ids to the other version's edge insert, and writing anything
     * but the batch's tag set onto the edge desynchronises it directly. Both compile, both keep the
     * existing tests green — {@code relationshipEndpointsResolveToTheirOwnVersionsRows} only counts
     * edges and never inspects a {@code tags} column — and the damage shows up later, in a
     * consolidation run, as edges that simply are not there any more.
     *
     * <p>
     * The second half re-checks the invariant across {@code deleteTagGraph}, the only writer that
     * changes either {@code tags} column after insert. It shrinks relationships before entities, in
     * that order, within one call; a partial shrink — or an order change that let the entity DELETE
     * cascade before the edges were retired — would leave exactly the cross-scope row this test
     * forbids.
     */
    @Test
    void everyEdgesTagScopeEqualsItsEndpointEntitiesTagScope() {
        Map<String, UUID> v931 = writeRepository.upsertEntitiesBatch(COLLECTION, List.of(V1),
            List.of(table("doc-9.3.1"), new KgWriteRepository.EntityUpsert("ADSGroup", "table", null,
                null, Map.of("document_id", "doc-9.3.1-b"))));
        Map<String, UUID> v10 = writeRepository.upsertEntitiesBatch(COLLECTION, List.of(V2),
            List.of(table("doc-10.0"), new KgWriteRepository.EntityUpsert("ADSGroup", "table", null,
                null, Map.of("document_id", "doc-10.0-b"))));

        // The documented result-map key is lower(kind) + ":" + lower(name), with no tag component —
        // one call is one scope, so the two calls must hand back DIFFERENT ids for the same key.
        assertThat(v931.get("table:adsaccount")).isNotNull()
            .isNotEqualTo(v10.get("table:adsaccount"));
        assertThat(v931.get("table:adsgroup")).isNotNull().isNotEqualTo(v10.get("table:adsgroup"));

        writeRepository.insertRelationshipsBatch(COLLECTION, List.of(V1),
            List.of(new KgWriteRepository.RelationshipUpsert("ADSAccount", "FK_TO", "ADSGroup",
                v931.get("table:adsaccount"), v931.get("table:adsgroup"), null, null)));
        writeRepository.insertRelationshipsBatch(COLLECTION, List.of(V2),
            List.of(new KgWriteRepository.RelationshipUpsert("ADSAccount", "FK_TO", "ADSGroup",
                v10.get("table:adsaccount"), v10.get("table:adsgroup"), null, null)));

        assertThat(edgeCount()).as("one edge per version — the same triple twice, not a duplicate")
            .isEqualTo(2);
        assertThat(crossScopeEdges())
            .as("an edge whose scope differs from its endpoints' is skipped by the consolidator's"
                + " repoint and then cascade-deleted with the alias")
            .isZero();

        // The only post-insert writer of either tags column must not be able to create one either.
        writeRepository.deleteTagGraph(COLLECTION, V1);

        assertThat(edgeCount()).as("9.3.1's edge went with its entities; 10.0's is untouched")
            .isEqualTo(1);
        assertThat(crossScopeEdges()).isZero();
    }

    /** Edges whose canonical tag set differs from either endpoint entity's — must always be 0. */
    private int crossScopeEdges() {
        Integer n = jdbcTemplate.queryForObject("""
            SELECT count(*) FROM relationships r
            JOIN entities s ON s.id = r.subject_id
            JOIN entities o ON o.id = r.object_id
            WHERE r.collection_id = ?
              AND (kg_canonical_tags(r.tags) <> kg_canonical_tags(s.tags)
                OR kg_canonical_tags(r.tags) <> kg_canonical_tags(o.tags))
            """, Integer.class, collectionId);
        return n == null ? 0 : n;
    }

    private int edgeCount() {
        Integer n = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM relationships WHERE collection_id = ?", Integer.class,
            collectionId);
        return n == null ? 0 : n;
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
