package de.palsoftware.yvoke.jsonobject.core.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import de.palsoftware.yvoke.jsonobject.core.model.JsonObject;
import de.palsoftware.yvoke.collection.core.repository.CollectionRepository;
import de.palsoftware.yvoke.collection.core.model.Collection;
import org.springframework.dao.DataAccessException;
import java.util.ArrayList;
import de.palsoftware.yvoke.jsonobject.core.service.JsonObjectService;

@SpringBootTest(
        properties = {
            "spring.flyway.enabled=true",
            "spring.flyway.locations=filesystem:docker/db/migration"
        })
public class JsonObjectRepositoryIT {

    private static final String COLLECTION_NAME = "OIM-JSON-TEST";

    @Autowired
    private JsonObjectRepository jsonObjectRepository;

    @Autowired
    private CollectionRepository collectionRepository;

    @Autowired
    private JsonObjectService jsonObjectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID collectionId;

    @BeforeEach
    public void setUp() {
        cleanup();
        collectionRepository.create(COLLECTION_NAME, "Test Collection");
        collectionId = collectionRepository.findByName(COLLECTION_NAME).map(Collection::id).orElseThrow();
    }

    @AfterEach
    public void tearDown() {
        cleanup();
    }

    /**
     * {@code getDistinctValues} is what fills the corpus browser's filter-value picker, so the
     * operator reads it as "these are the values this field can have". It is capped at
     * {@code LIMIT 100} with no marker of any kind — no count, no probe row, no flag — which is
     * precisely the failure §12's own "What MUST NOT happen" list names: a truncated list presented
     * as complete. On a high-cardinality field the admin then builds a filter believing the offered
     * values are the whole vocabulary, gets a plausible non-empty result, and never learns that the
     * value they actually wanted was simply never shown. Note the contrast with the grouped count
     * next door, which deliberately fetches {@code MAX_GROUPS + 1} so the caller CAN detect
     * truncation; this query has no such affordance, so the cap has to be pinned as behaviour.
     *
     * <p>
     * The only existing coverage is {@code distinctValuesReturnsAnEmptyListForAnUnknownCollection},
     * which never inserts a row, so neither the cap nor the shape of the lookup has a witness.
     *
     * <p>
     * The dotted-path case is the second half and fails in the opposite direction. The field name
     * is a BOUND parameter to {@code data->>:fieldName}, which is a top-level key lookup, not a
     * jsonpath — so {@code customer.name} matches a literal key spelled with a dot, finds nothing,
     * and returns an empty list. Empty reads as "this field has no values" rather than "this is not
     * a supported path", and the caller cannot tell the two apart. Rewriting the lookup as a
     * jsonpath (the natural "fix" once someone notices nested fields are unreachable) would also
     * turn the bound parameter into concatenated SQL, which is the injection surface
     * {@code aJsonPathCarryingSqlMetacharactersIsBoundRatherThanConcatenated} exists to prevent.
     *
     * <p>
     * The third assertion pins NULL exclusion: a row that simply lacks the field must not appear as
     * a selectable blank value in a filter dropdown.
     */
    @Test
    public void distinctValuesIsSilentlyCappedAtOneHundredAndOnlyEverReadsATopLevelKey() {
        List<JsonObject> objs = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            objs.add(new JsonObject(UUID.randomUUID(), collectionId, COLLECTION_NAME,
                Map.of("category", String.format("cat-%03d", i), "customer",
                    Map.of("name", "nested-" + i)),
                "bulk.json", OffsetDateTime.now()));
        }
        // A row that simply does not carry the field at all.
        objs.add(new JsonObject(UUID.randomUUID(), collectionId, COLLECTION_NAME,
            Map.of("unrelated", "x"), "bulk.json", OffsetDateTime.now()));
        jsonObjectRepository.saveBatch(objs);

        List<String> values = jsonObjectRepository.getDistinctValues(collectionId, "category");

        assertThat(values)
            .as("150 distinct values exist; the picker silently shows 100 and says nothing about"
                + " the other 50")
            .hasSize(100);
        // ORDER BY 1 then cut: the first 100 in ascending order, so cat-099 is in and cat-100 out.
        assertThat(values).contains("cat-000", "cat-099");
        assertThat(values)
            .as("the cut is by sort order, so everything past the hundredth value is unreachable")
            .doesNotContain("cat-100", "cat-149");
        assertThat(values)
            .as("a row lacking the field must not appear as a selectable blank filter value")
            .doesNotContainNull();

        assertThat(jsonObjectRepository.getDistinctValues(collectionId, "customer.name"))
            .as("the field name is a BOUND parameter to data->>:fieldName — a top-level key lookup,"
                + " not a jsonpath — so a dotted path finds a key nobody has and returns empty")
            .isEmpty();
    }

    private void cleanup() {
        jdbcTemplate.update("DELETE FROM json_objects");
        jdbcTemplate.update("DELETE FROM collections WHERE name = ?", COLLECTION_NAME);
    }

    @Test
    public void findIdsByJsonFieldResolvesExistingValuesInOneQuery() {
        // PRF-02: the batched existence probe returns a value -> id map for the values that exist.
        JsonObject a = new JsonObject(UUID.randomUUID(), collectionId, COLLECTION_NAME,
            Map.of("id", "a", "name", "Alice"), "users.json", OffsetDateTime.now());
        JsonObject b = new JsonObject(UUID.randomUUID(), collectionId, COLLECTION_NAME,
            Map.of("id", "b", "name", "Bob"), "users.json", OffsetDateTime.now());
        jsonObjectRepository.saveBatch(List.of(a, b));

        Map<String, UUID> found = jsonObjectRepository.findIdsByJsonField(collectionId, "id",
            List.of("a", "b", "missing"));

        assertThat(found).containsOnlyKeys("a", "b");
        assertThat(found.get("a")).isEqualTo(a.id());
        assertThat(found.get("b")).isEqualTo(b.id());
    }

    /**
     * Paging must be a total order. {@code JsonObjectService.importObjects} stamps every row of one
     * batch with a single {@code OffsetDateTime.now()}, so an import's rows all share
     * {@code created_at} — and an {@code ORDER BY created_at DESC} with no unique tiebreaker leaves
     * the order among the tied rows unspecified, which LIMIT/OFFSET then turns into repeated and
     * skipped rows across pages. Same failure mode as the {@code messages} batch-ordering incident
     * (CLAUDE.md § 6): the sort key is not unique, so the tie-break IS the ordering.
     */
    @Test
    public void pagingAnImportWithTiedTimestampsVisitsEveryRowExactlyOnce() {
        OffsetDateTime sharedStamp = OffsetDateTime.now();
        List<JsonObject> batch = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            batch.add(new JsonObject(UUID.randomUUID(), collectionId, COLLECTION_NAME,
                Map.of("id", "o" + i), "batch.jsonl", sharedStamp));
        }
        jsonObjectRepository.saveBatch(batch);

        // The tie is the precondition — assert it, so this test cannot silently stop testing
        // anything if per-row timestamps are introduced later.
        Integer distinctStamps = jdbcTemplate.queryForObject(
            "SELECT COUNT(DISTINCT created_at) FROM json_objects WHERE collection_id = ?",
            Integer.class, collectionId);
        assertThat(distinctStamps).isEqualTo(1);

        List<UUID> paged = new ArrayList<>();
        for (int offset = 0; offset < 8; offset += 3) {
            jsonObjectRepository.findByCollectionId(collectionId, 3, offset).stream()
                .map(JsonObject::id).forEach(paged::add);
        }

        assertThat(paged).as("every row exactly once, no repeats and no skips").hasSize(8)
            .doesNotHaveDuplicates()
            .containsExactlyInAnyOrderElementsOf(batch.stream().map(JsonObject::id).toList());
    }

    /**
     * The {@code jsonUniqueField} upsert must not reach ACROSS TAGS. One collection deliberately
     * holds several product versions separated only by tag, and a natural key like
     * {@code customer.id} repeats across them by design. The lookup is scoped to
     * {@code collection_id} alone, and {@code updateBatch} rewrites {@code data}, {@code
     * source_file}, {@code created_at} AND {@code tags} wholesale — so importing 10.0 would find
     * 9.3's row, overwrite its payload with 10.0's and re-tag it to 10.0. The 9.3 version simply
     * ceases to exist, the job reports a normal count, and every {@code :tag = ANY(tags)} read for
     * 9.3 then returns nothing. Same shape as the tag-scoped graph-identity incident.
     */
    @Test
    public void importingASecondTagMustNotOverwriteTheFirstTagsRowWithTheSameUniqueValue() {
        jsonObjectService.importObjects(collectionId, COLLECTION_NAME,
            List.of(Map.of("id", "shared-key", "label", "the 9.3 record")), "v93.jsonl",
            List.of("9.3"), "id");
        jsonObjectService.importObjects(collectionId, COLLECTION_NAME,
            List.of(Map.of("id", "shared-key", "label", "the 10.0 record")), "v10.jsonl",
            List.of("10.0"), "id");

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT data->>'label' AS label, tags FROM json_objects WHERE collection_id = ? ORDER BY data->>'label'",
            collectionId);

        assertThat(rows).as("each product version keeps its own row").hasSize(2);
        assertThat(rows).extracting(r -> r.get("label")).containsExactly("the 10.0 record",
            "the 9.3 record");
    }

    /**
     * {@code jsonPath} is the one string in this repository that comes straight from an MCP caller
     * — {@code query_json_objects} forwards whatever the model wrote — and it lands next to the ONE
     * fragment in the class that is genuinely concatenated ({@code data->>'field'} for groupBy,
     * because the right side of {@code ->>} cannot be parameterized). Those two live four lines
     * apart in {@code countGroupedByJsonPath}, which is precisely how a bound parameter gets
     * "simplified" into the concatenation next to it.
     *
     * <p>
     * The proof used here is a round trip rather than an attempted injection: a stored value that
     * CONTAINS a quote, a semicolon and a SQL comment marker is queried for by that exact value. If
     * the path were interpolated, the embedded quote would terminate the SQL literal and the
     * statement would change shape — the row could not come back. Coming back proves the string
     * reached Postgres as jsonpath TEXT, uninterpreted, through all three consumers (page, pager,
     * breakdown), which is the property that matters: a hostile path can at worst raise a jsonpath
     * syntax error, never SQL.
     *
     * <p>
     * The unit-level sibling ({@code JsonObjectRepositoryGroupByTest#theJsonPathFilterUsesTheIndexableContainmentOperator})
     * asserts the SQL TEXT contains {@code data @?? :} with a mocked JdbcClient — it proves the
     * statement is written with a named parameter and nothing about what Postgres then does with the
     * value, and it would stay green against a database that never sees the query at all. This is
     * the runtime half. It also pins the deliberate asymmetry: the value is bound, the groupBy field
     * is REJECTED rather than sanitised, so neither is ever concatenated unchecked.
     */
    @Test
    public void aJsonPathCarryingSqlMetacharactersIsBoundRatherThanConcatenated() {
        // Quote + statement separator + comment marker: everything needed to break out of a
        // concatenated SQL literal, and harmless as data.
        String hostileValue = "'; SELECT 1; --";
        jsonObjectRepository.saveBatch(List.of(
            new JsonObject(UUID.randomUUID(), collectionId, COLLECTION_NAME,
                Map.of("name", hostileValue), "inj.json", OffsetDateTime.now()),
            new JsonObject(UUID.randomUUID(), collectionId, COLLECTION_NAME, Map.of("name", "Bob"),
                "inj.json", OffsetDateTime.now())));

        String hostilePath = "$.name ? (@ == \"" + hostileValue + "\")";

        List<JsonObject> rows = jsonObjectRepository.queryByJsonPath(collectionId, hostilePath, 10, 0);
        assertThat(rows).as("the path reached Postgres as jsonpath text, character for character")
            .hasSize(1);
        assertThat(rows.get(0).data().get("name")).isEqualTo(hostileValue);

        assertThat(jsonObjectRepository.countByJsonPath(collectionId, hostilePath))
            .as("the pager binds it the same way, or the page and its count disagree").isEqualTo(1);
        assertThat(jsonObjectRepository.countGroupedByJsonPath(collectionId, hostilePath, "name",
            null)).as("the grouped count binds the filter separately from the concatenated field")
                .containsEntry(hostileValue, 1L);

        // A path that is not valid jsonpath fails as a JSONPATH, i.e. it was never SQL. The rows are
        // still there afterwards: nothing the caller wrote reached the statement.
        assertThatThrownBy(
            () -> jsonObjectRepository.queryByJsonPath(collectionId, "$.name ? (@ == ", 10, 0))
                .isInstanceOf(DataAccessException.class);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM json_objects WHERE collection_id = ?", Integer.class, collectionId))
                .isEqualTo(2);

        // ...and the one fragment that IS concatenated refuses anything outside the identifier
        // charset instead of silently stripping it into a key no row carries.
        assertThatThrownBy(() -> jsonObjectRepository.countGroupedByJsonPath(collectionId, null,
            "name'; --", null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name'; --");
    }

    /**
     * Re-importing a natural key that already exists at the SAME tag must take {@code updateBatch}'s
     * UPDATE branch and rewrite THAT row in place — the only path in this repository that overwrites
     * a stored corpus payload. The failure is silent by construction: {@code
     * JdbcTemplate.batchUpdate} throws nothing when a statement matches zero rows, and {@code
     * JsonObjectService.importObjects} reports the same object count either way, so a
     * parameter-index or WHERE slip leaves the stale payload in place while the ingest job looks
     * perfectly healthy. An MCP agent then answers {@code query_json_objects} out of a version of
     * the corpus that was supposedly corrected, with nothing anywhere to contradict it.
     *
     * <p>
     * The other half is that the update must not travel across tags: the sibling 10.0 row carries
     * the IDENTICAL natural key by design (a {@code customer.id} is the same id in both kits), and
     * {@code updateBatch} rewrites {@code data}, {@code source_file}, {@code created_at} AND
     * {@code tags} wholesale — so a row selected by anything other than its own id destroys the
     * other product version. Both directions are pinned at once: the 9.3 row keeps its id and gains
     * the new payload, the 10.0 row is untouched, and the collection still holds exactly two rows.
     */
    @Test
    public void reImportingTheSameTagRewritesItsOwnRowInPlaceAndLeavesTheOtherVersionUntouched() {
        jsonObjectService.importObjects(collectionId, COLLECTION_NAME,
            List.of(Map.of("id", "shared-key", "label", "the 9.3 record")), "v93.jsonl",
            List.of("9.3"), "id");
        jsonObjectService.importObjects(collectionId, COLLECTION_NAME,
            List.of(Map.of("id", "shared-key", "label", "the 10.0 record")), "v10.jsonl",
            List.of("10.0"), "id");

        List<JsonObject> before93 =
            jsonObjectRepository.findByCollectionId(collectionId, List.of("9.3"), 10, 0);
        List<JsonObject> before10 =
            jsonObjectRepository.findByCollectionId(collectionId, List.of("10.0"), 10, 0);
        assertThat(before93).as("precondition: one row per product version").hasSize(1);
        assertThat(before10).as("precondition: one row per product version").hasSize(1);
        UUID id93 = before93.get(0).id();
        UUID id10 = before10.get(0).id();
        OffsetDateTime stamp93 = before93.get(0).createdAt();

        jsonObjectService.importObjects(collectionId, COLLECTION_NAME,
            List.of(Map.of("id", "shared-key", "label", "the 9.3 record, corrected")),
            "v93-rev2.jsonl", List.of("9.3"), "id");

        assertThat(jsonObjectRepository.countByCollectionId(collectionId))
            .as("a re-import at a known key updates, it does not insert a third row").isEqualTo(2);

        JsonObject rewritten = jsonObjectRepository.findById(id93).orElseThrow();
        assertThat(rewritten.data().get("label"))
            .as("the stored payload was actually replaced, not silently kept")
            .isEqualTo("the 9.3 record, corrected");
        assertThat(rewritten.sourceFile())
            .as("provenance follows the payload, or the row lies about where it came from")
            .isEqualTo("v93-rev2.jsonl");
        assertThat(rewritten.tags()).as("the row stays inside its own tag scope")
            .containsExactly("9.3");
        assertThat(rewritten.createdAt())
            .as("created_at is the paging sort key, so it must move with the row")
            .isAfterOrEqualTo(stamp93);

        JsonObject untouched = jsonObjectRepository.findById(id10).orElseThrow();
        assertThat(untouched.data().get("label"))
            .as("the other product version is not collateral damage").isEqualTo("the 10.0 record");
        assertThat(untouched.sourceFile()).isEqualTo("v10.jsonl");
        assertThat(untouched.tags()).containsExactly("10.0");
    }

    @Test
    public void findIdsByJsonFieldReturnsEmptyForEmptyInput() {
        assertThat(jsonObjectRepository.findIdsByJsonField(collectionId, "id", List.of())).isEmpty();
    }

    @Test
    public void testImportAndFind() {
        Map<String, Object> data1 = Map.of("name", "Alice", "age", 30);
        Map<String, Object> data2 = Map.of("name", "Bob", "age", 40);

        JsonObject obj1 = new JsonObject(UUID.randomUUID(), collectionId, COLLECTION_NAME, data1, "users.json", OffsetDateTime.now());
        JsonObject obj2 = new JsonObject(UUID.randomUUID(), collectionId, COLLECTION_NAME, data2, "users.json", OffsetDateTime.now());
        jsonObjectRepository.saveBatch(List.of(obj1, obj2));

        List<JsonObject> objects = jsonObjectRepository.findByCollectionId(collectionId, 10, 0);
        assertThat(objects).hasSize(2);
        
        List<JsonObject> allObjects = jsonObjectRepository.findByCollectionId(collectionId, 10, 0);
        assertThat(allObjects).hasSize(2);

        // find by json path
        List<JsonObject> bobs = jsonObjectRepository.queryByJsonPath(collectionId, "$.name ? (@ == \"Bob\")", 10, 0);
        assertThat(bobs).hasSize(1);
        assertThat(bobs.get(0).data().get("name")).isEqualTo("Bob");
    }

    @Test
    public void testFindById() {
        Map<String, Object> data = Map.of("key", "value");
        JsonObject obj = new JsonObject(UUID.randomUUID(), collectionId, COLLECTION_NAME, data, "test.json", OffsetDateTime.now());
        jsonObjectRepository.save(obj);

        List<JsonObject> objects = jsonObjectRepository.findByCollectionId(collectionId, 1, 0);
        assertThat(objects).isNotEmpty();

        JsonObject found = jsonObjectRepository.findById(objects.get(0).id()).orElse(null);
        assertThat(found).isNotNull();
        assertThat(found.data().get("key")).isEqualTo("value");
    }

    @Test
    public void testDeleteByCollectionId() {
        Map<String, Object> data = Map.of("key", "value");
        JsonObject obj = new JsonObject(UUID.randomUUID(), collectionId, COLLECTION_NAME, data, "test.json", OffsetDateTime.now());
        jsonObjectRepository.save(obj);

        jsonObjectRepository.deleteByCollectionId(collectionId);

        List<JsonObject> objects = jsonObjectRepository.findByCollectionId(collectionId, 10, 0);
        assertThat(objects).isEmpty();
    }

    /**
     * The free-text count must apply the SAME tag scope as the page it counts. Nothing pairs the two
     * queries — {@code countSearch} and {@code search} build their predicates independently — and a
     * count that has dropped the tag filter still returns a perfectly plausible number. The admin
     * corpus browser derives its total-page count from it, so a tag-blind count renders page links
     * into another product version's rows and an empty final page. Worse,
     * {@code JsonObjectService.countSearchObjects} feeds the MCP count-only answer, where the model
     * is told "N objects match" for a release scope it believes it asked about — a number an agent
     * will quote verbatim into an answer, with no way to notice it is the corpus-wide total.
     *
     * <p>
     * Two rows carry identical searchable text under different tags, so a scope-losing count reads
     * exactly 2 where the page reads 1. The scoped count is asserted against {@code search(...)
     * .size()} rather than against a literal alone, because the property that matters is that the
     * pager and the page agree, not that either happens to be 1.
     */
    @Test
    public void theFreeTextCountIsScopedToTheSameTagAsThePageItCounts() {
        jsonObjectRepository.saveBatch(List.of(
            new JsonObject(UUID.randomUUID(), collectionId, COLLECTION_NAME,
                Map.of("id", "k1", "note", "CANARY value"), "v93.jsonl", List.of("9.3"),
                OffsetDateTime.now()),
            new JsonObject(UUID.randomUUID(), collectionId, COLLECTION_NAME,
                Map.of("id", "k2", "note", "CANARY value"), "v10.jsonl", List.of("10.0"),
                OffsetDateTime.now())));

        List<JsonObject> page93 =
            jsonObjectRepository.search(collectionId, "CANARY", List.of("9.3"), 50, 0);
        assertThat(page93).as("precondition: the text matches under both tags, the page shows one")
            .hasSize(1);

        assertThat(jsonObjectRepository.countSearch(collectionId, "CANARY", List.of("9.3")))
            .as("the pager and the page it pages must agree").isEqualTo(page93.size());
        assertThat(jsonObjectRepository.countSearch(collectionId, "CANARY", List.of("10.0")))
            .as("...and symmetrically for the other release").isEqualTo(1);
        assertThat(jsonObjectRepository.countSearch(collectionId, "CANARY"))
            .as("unscoped the same text really does match both rows, so the 1 above is the tag "
                + "filter working rather than the text failing to match")
            .isEqualTo(2);
        assertThat(jsonObjectRepository.countSearch(collectionId, "no-such-text", List.of("9.3")))
            .as("the ILIKE predicate still applies alongside the tag filter").isEqualTo(0);
    }

    @Test
    public void testTagsStorageAndFiltering() {
        Map<String, Object> data1 = Map.of("name", "TaggedAlice");
        Map<String, Object> data2 = Map.of("name", "UntaggedBob");

        JsonObject obj1 = new JsonObject(UUID.randomUUID(), collectionId, COLLECTION_NAME, data1, "tagged.json", List.of("v1", "release"), OffsetDateTime.now());
        JsonObject obj2 = new JsonObject(UUID.randomUUID(), collectionId, COLLECTION_NAME, data2, "untagged.json", List.of("v2"), OffsetDateTime.now());
        jsonObjectRepository.saveBatch(List.of(obj1, obj2));

        // 1. Find all in collection with tag 'release'
        List<JsonObject> found = jsonObjectRepository.findByCollectionId(collectionId, List.of("release"), 10, 0);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).data().get("name")).isEqualTo("TaggedAlice");
        assertThat(found.get(0).tags()).contains("v1", "release");

        // 2. Count in collection with tag 'release'
        long count = jsonObjectRepository.countByCollectionId(collectionId, List.of("release"));
        assertThat(count).isEqualTo(1);

        // 3. Search text with tag 'v2'
        List<JsonObject> searchResults = jsonObjectRepository.search(collectionId, "UntaggedBob", List.of("v2"), 10, 0);
        assertThat(searchResults).hasSize(1);

        List<JsonObject> searchResultsWrongTag = jsonObjectRepository.search(collectionId, "UntaggedBob", List.of("release"), 10, 0);
        assertThat(searchResultsWrongTag).isEmpty();

        // 4. Query JSON path with tag 'release'
        List<JsonObject> pathResults = jsonObjectRepository.queryByJsonPath(collectionId, "$.name ? (@ == \"TaggedAlice\")", List.of("release"), 10, 0);
        assertThat(pathResults).hasSize(1);
        
        List<JsonObject> pathResultsWrongTag = jsonObjectRepository.queryByJsonPath(collectionId, "$.name ? (@ == \"TaggedAlice\")", List.of("v2"), 10, 0);
        assertThat(pathResultsWrongTag).isEmpty();
    }

    /**
     * groupBy accepts any top-level key, including high-cardinality ones — 'name' on the live
     * DB-History content tag has 4,008 distinct values. Ungapped, that came back as a single
     * ~4,000-row result. The cap must hold in SQL, not just in the caller.
     */
    @Test
    public void testGroupedCountsAreCappedInSql() {
        int distinct = JsonObjectRepository.MAX_GROUPS + 25;
        List<JsonObject> objs = new ArrayList<>();
        for (int i = 0; i < distinct; i++) {
            objs.add(new JsonObject(UUID.randomUUID(), collectionId, COLLECTION_NAME,
                Map.of("name", "n" + i), "bulk.json", OffsetDateTime.now()));
        }
        jsonObjectRepository.saveBatch(objs);

        Map<String, Long> grouped =
            jsonObjectRepository.countGroupedByJsonPath(collectionId, null, "name", null);

        // One beyond the cap: the probe row that lets the caller detect truncation.
        assertThat(grouped).hasSize(JsonObjectRepository.MAX_GROUPS + 1);
    }

    @Test
    public void testGroupedCountsBelowTheCapReturnEveryGroup() {
        List<JsonObject> objs = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            objs.add(new JsonObject(UUID.randomUUID(), collectionId, COLLECTION_NAME,
                Map.of("name", "n" + i), "bulk.json", OffsetDateTime.now()));
        }
        jsonObjectRepository.saveBatch(objs);

        Map<String, Long> grouped =
            jsonObjectRepository.countGroupedByJsonPath(collectionId, null, "name", null);

        assertThat(grouped).hasSize(7);
    }

}
