package de.palsoftware.yvoke.jsonobject.core.repository;

import static org.assertj.core.api.Assertions.assertThat;

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
        java.util.List<JsonObject> objs = new java.util.ArrayList<>();
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
        java.util.List<JsonObject> objs = new java.util.ArrayList<>();
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
