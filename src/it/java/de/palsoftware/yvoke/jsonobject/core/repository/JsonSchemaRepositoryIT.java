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
