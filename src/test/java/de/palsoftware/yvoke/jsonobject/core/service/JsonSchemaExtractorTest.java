package de.palsoftware.yvoke.jsonobject.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class JsonSchemaExtractorTest {

    private final JsonSchemaExtractor extractor = new JsonSchemaExtractor();

    @Test
    public void testInferSchema() {
        List<Map<String, Object>> objects = List.of(Map.of("name", "Alice", "age", 30),
            Map.of("name", "Bob", "isActive", true, "tags", List.of("admin", "user")));

        Map<String, Object> schema = extractor.extractSchema(objects);

        assertThat(schema.get("type")).isEqualTo("object");

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");

        assertThat(properties).containsKeys("name", "age", "isActive", "tags");

        @SuppressWarnings("unchecked")
        Map<String, Object> nameProp = (Map<String, Object>) properties.get("name");
        assertThat(nameProp.get("type")).isEqualTo("string");

        @SuppressWarnings("unchecked")
        Map<String, Object> ageProp = (Map<String, Object>) properties.get("age");
        assertThat(ageProp.get("type")).isEqualTo("integer");

        @SuppressWarnings("unchecked")
        Map<String, Object> tagsProp = (Map<String, Object>) properties.get("tags");
        assertThat(tagsProp.get("type")).isEqualTo("array");

        @SuppressWarnings("unchecked")
        Map<String, Object> tagsItems = (Map<String, Object>) tagsProp.get("items");
        assertThat(tagsItems.get("type")).isEqualTo("string");
    }

    @Test
    public void arrayOfObjectsKeepsFirstElementProperties() {
        // Regression: previously the first array element's properties were dropped because a
        // "null" items slot only copied the type, not the structure.
        List<Map<String, Object>> objects =
            List.of(Map.of("servers", List.of(Map.of("name", "MAIL", "roles", List.of("SMTP host")),
                Map.of("name", "HOME", "roles", List.of("Home server")))));

        Map<String, Object> schema = extractor.extractSchema(objects);
        Map<String, Object> items = nested(schema, "properties", "servers", "items");
        assertThat(items.get("type")).isEqualTo("object");
        @SuppressWarnings("unchecked")
        Map<String, Object> itemProps = (Map<String, Object>) items.get("properties");
        assertThat(itemProps).containsKeys("name", "roles");
    }

    @Test
    public void conflictingPrimitiveTypesWidenToTypeList() {
        List<Map<String, Object>> objects = List.of(Map.of("id", 2), // integer
            Map.of("id", "two") // string
        );

        Map<String, Object> schema = extractor.extractSchema(objects);
        Map<String, Object> idProp = nested(schema, "properties", "id");
        assertThat(idProp.get("type")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<String> types = (List<String>) idProp.get("type");
        assertThat(types).containsExactly("integer", "string"); // sorted, deterministic
    }

    @Test
    public void objectToArrayConflictNoLongerFreezesToFirstSeenType() {
        // Simulates a re-shaped export: a field first seen as an object, later as an array. The
        // merge must surface both shapes instead of silently keeping only the object form.
        Map<String, Object> objectShape = mutableSchema("object");
        objectShape.put("properties", new HashMap<>(Map.of("Count", mutableSchema("integer"))));

        Map<String, Object> arrayShape = mutableSchema("array");
        arrayShape.put("items", mutableSchema("object"));

        extractor.mergeSchema(objectShape, arrayShape);

        assertThat(objectShape.get("type")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<String> types = (List<String>) objectShape.get("type");
        assertThat(types).containsExactly("array", "object");
        assertThat(objectShape).containsKeys("properties", "items");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(Map<String, Object> root, String... path) {
        Map<String, Object> current = root;
        for (String key : path) {
            current = (Map<String, Object>) current.get(key);
            assertThat(current).as("path segment '%s' in %s", key, Arrays.toString(path))
                .isNotNull();
        }
        return current;
    }

    private static Map<String, Object> mutableSchema(String type) {
        Map<String, Object> m = new HashMap<>();
        m.put("type", type);
        return m;
    }
}
