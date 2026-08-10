package de.palsoftware.yvoke.jsonobject.core.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import de.palsoftware.yvoke.jsonobject.core.model.JsonSchema;
import de.palsoftware.yvoke.collection.core.repository.CollectionRepository;
import de.palsoftware.yvoke.collection.core.model.Collection;

@SpringBootTest(
        properties = {
            "spring.flyway.enabled=true",
            "spring.flyway.locations=filesystem:docker/db/migration"
        })
public class JsonSchemaRepositoryIT {

    private static final String COLLECTION_NAME = "OIM-SCHEMA-TEST";

    @Autowired
    private JsonSchemaRepository jsonSchemaRepository;

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
        jdbcTemplate.update("DELETE FROM json_schemas");
        jdbcTemplate.update("DELETE FROM collections WHERE name = ?", COLLECTION_NAME);
    }

    @Test
    public void testUpsertAndFind() {
        Map<String, Object> schemaData = Map.of(
            "type", "object",
            "properties", Map.of("name", Map.of("type", "string"))
        );

        JsonSchema schema = new JsonSchema(UUID.randomUUID(), collectionId, null, schemaData, "inferred", null);
        jsonSchemaRepository.upsert(schema);

        JsonSchema found = jsonSchemaRepository.findByCollectionId(collectionId, null).orElse(null);
        assertThat(found).isNotNull();
        assertThat(found.source()).isEqualTo("inferred");
        assertThat(found.schemaData().get("type")).isEqualTo("object");
        
        // Upsert should update existing
        Map<String, Object> manualSchema = Map.of(
            "type", "object",
            "properties", Map.of("name", Map.of("type", "string"), "age", Map.of("type", "integer"))
        );
        JsonSchema manual = new JsonSchema(found.id(), collectionId, null, manualSchema, "manual", null);
        jsonSchemaRepository.upsert(manual);

        found = jsonSchemaRepository.findByCollectionId(collectionId, null).orElse(null);
        assertThat(found).isNotNull();
        assertThat(found.source()).isEqualTo("manual");
        
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) found.schemaData().get("properties");
        assertThat(props).containsKey("age");
    }

    /**
     * {@code NULLS NOT DISTINCT} is the entire constraint for an untagged collection. In standard
     * SQL {@code NULL <> NULL}, so a plain unique index on {@code (collection_id, tag)} does not
     * constrain untagged rows AT ALL — and {@code ON CONFLICT (collection_id, tag)} infers the
     * index purely by its column list, so the upsert statement compiles, runs and reports success
     * either way. Nothing in Java changes; the property lives only in the migration, which no test
     * reads.
     *
     * <p>
     * Lose it and every re-import appends another declaration row instead of replacing one, which
     * breaks the schema in two directions at once. {@code findByCollectionId(id, null)} ends in
     * {@code .optional()} over {@code WHERE tag IS NULL}, so the moment a second row exists it
     * throws {@code IncorrectResultSizeDataAccessException} — MCP {@code get_json_schema} starts
     * failing outright for that collection — and until it throws, the declaration an agent is
     * handed is whichever duplicate the plan returned. That matters more here than it looks: the
     * declaration is already a separate deliverable from the data (a hand-authored
     * {@code source='imported'} row is frozen against field/enum drift), so an operator applying a
     * corrected declaration would be adding a rival to the stale one rather than replacing it.
     *
     * <p>
     * The tail pins the other half of the identity rule: the key is {@code (collection_id, tag)},
     * not {@code collection_id}, so a tagged declaration is a SECOND row and must not overwrite the
     * untagged one — that is what lets one collection carry a per-version schema.
     */
    @Test
    public void aSecondUntaggedUpsertReplacesTheSchemaInsteadOfAddingARow() {
        UUID firstId = UUID.randomUUID();
        jsonSchemaRepository.upsert(new JsonSchema(firstId, collectionId, null,
            Map.of("type", "object", "properties", Map.of("via", Map.of("type", "string"))),
            "imported", null));

        UUID secondId = UUID.randomUUID();
        jsonSchemaRepository.upsert(new JsonSchema(secondId, collectionId, null,
            Map.of("type", "object", "properties",
                Map.of("via", Map.of("type", "string"), "category", Map.of("type", "string"))),
            "manual", null));

        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM json_schemas WHERE collection_id = ?", Integer.class,
            collectionId)).as("an untagged collection holds exactly ONE schema row").isEqualTo(1);

        JsonSchema found = jsonSchemaRepository.findByCollectionId(collectionId, null).orElseThrow();
        assertThat(found.source()).isEqualTo("manual");
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) found.schemaData().get("properties");
        assertThat(props).as("the surviving row carries the SECOND payload").containsOnlyKeys("via",
            "category");
        // The arbiter is (collection_id, tag), so the row keeps the id it was created with — the
        // caller's freshly minted id is not what identifies a declaration.
        assertThat(found.id()).isEqualTo(firstId).isNotEqualTo(secondId);

        // A TAGGED declaration is a different row: tag is part of the key, not a payload field.
        jsonSchemaRepository.upsert(new JsonSchema(UUID.randomUUID(), collectionId, "10.0",
            Map.of("type", "object", "properties", Map.of("kitVersion", Map.of("type", "string"))),
            "imported", null));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM json_schemas WHERE collection_id = ?", Integer.class,
            collectionId)).isEqualTo(2);
        assertThat(jsonSchemaRepository.findByCollectionId(collectionId, null).orElseThrow().id())
            .as("a tagged declaration must not overwrite the untagged one").isEqualTo(firstId);
        assertThat(jsonSchemaRepository.findByCollectionId(collectionId, "10.0")).isPresent();
    }

    @Test
    public void testDeleteByCollectionId() {
        Map<String, Object> schemaData = Map.of("type", "object");
        JsonSchema schema = new JsonSchema(UUID.randomUUID(), collectionId, null, schemaData, "inferred", null);
        jsonSchemaRepository.upsert(schema);

        jsonSchemaRepository.deleteByCollectionId(collectionId);

        JsonSchema found = jsonSchemaRepository.findByCollectionId(collectionId, null).orElse(null);
        assertThat(found).isNull();
    }
}
