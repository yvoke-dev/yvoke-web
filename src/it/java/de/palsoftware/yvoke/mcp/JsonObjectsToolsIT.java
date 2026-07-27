package de.palsoftware.yvoke.mcp;

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
import de.palsoftware.yvoke.jsonobject.core.repository.JsonObjectRepository;
import de.palsoftware.yvoke.collection.core.repository.CollectionRepository;
import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.mcp.tools.QueryJsonObjectsTool;
import de.palsoftware.yvoke.mcp.tools.GetJsonSchemaTool;
import de.palsoftware.yvoke.tag.core.repository.TagRepository;
import de.palsoftware.yvoke.jsonobject.core.model.JsonSchema;
import de.palsoftware.yvoke.jsonobject.core.repository.JsonSchemaRepository;

@SpringBootTest(properties = {"spring.flyway.enabled=true",
    "spring.flyway.locations=filesystem:docker/db/migration"})
public class JsonObjectsToolsIT {

    private static final String COLLECTION_NAME = "OIM-JSON-TOOLS-TEST";

    @Autowired
    private TagRepository tagRepository;

    @Autowired
    private JsonObjectRepository jsonObjectRepository;

    @Autowired
    private CollectionRepository collectionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private QueryJsonObjectsTool queryTool;

    @Autowired
    private GetJsonSchemaTool schemaTool;

    @Autowired
    private JsonSchemaRepository jsonSchemaRepository;

    private UUID collectionId;

    @BeforeEach
    public void setUp() {
        cleanup();
        collectionRepository.create(COLLECTION_NAME, "Test Collection");
        collectionId =
            collectionRepository.findByName(COLLECTION_NAME).map(Collection::id).orElseThrow();

        Map<String, Object> data = Map.of("name", "Alice", "role", "admin");
        JsonObject obj = new JsonObject(UUID.randomUUID(), collectionId, COLLECTION_NAME, data,
            "users.json", OffsetDateTime.now());
        jsonObjectRepository.save(obj);
    }

    @AfterEach
    public void tearDown() {
        cleanup();
    }

    private void cleanup() {
        jdbcTemplate.update("DELETE FROM json_objects");
        jdbcTemplate.update("DELETE FROM json_schemas");
        jdbcTemplate.update("DELETE FROM collections WHERE name = ?", COLLECTION_NAME);
    }

    @Test
    public void testQueryTool() {
        String response = queryTool.queryJsonObjects(COLLECTION_NAME, "$.role ? (@ == \"admin\")",
            null, "name,role", 10, 0, null, null);

        assertThat(response).contains("Alice");
        assertThat(response).contains(COLLECTION_NAME);
    }

    @Test
    public void testQueryToolWithTags() {
        Map<String, Object> data = Map.of("name", "Bob", "role", "user");
        JsonObject obj = new JsonObject(UUID.randomUUID(), collectionId, COLLECTION_NAME, data,
            "users.json", List.of("release"), OffsetDateTime.now());
        jsonObjectRepository.save(obj);

        tagRepository.addTagToCollection(collectionId, "release");

        String response =
            queryTool.queryJsonObjects(COLLECTION_NAME, null, "release", "name", 10, 0, null, null);
        assertThat(response).contains("Bob");
        assertThat(response).doesNotContain("Alice");
    }

    @Test
    public void testGetSchemaTool() {
        Map<String, Object> schemaData = Map.of("type", "object", "properties", Map.of("name", Map.of("type", "string")));
        JsonSchema schema = new JsonSchema(UUID.randomUUID(), collectionId, null, schemaData, "manual", OffsetDateTime.now());
        jsonSchemaRepository.upsert(schema);

        String response = schemaTool.getJsonSchema(COLLECTION_NAME, null);
        assertThat(response).contains("properties");
        assertThat(response).contains("name");

        String emptyResponse = schemaTool.getJsonSchema("non-existent-collection", null);
        assertThat(emptyResponse).contains("Error");
    }
}
