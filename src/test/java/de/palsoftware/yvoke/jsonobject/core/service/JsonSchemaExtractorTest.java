package de.palsoftware.yvoke.jsonobject.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.LinkedHashMap;

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

    /**
     * Four branches of the inference/merge rules that the existing cases (string, integer,
     * array-of-object, widening) never reach, all of them decisions the agent is then told as fact
     * by {@code get_json_schema} — and the schema declaration is a separate deliverable from the
     * data, so nothing ever compares it back against the rows.
     *
     * <p>
     * A non-integral {@code Number} must declare {@code "number"}: a {@code 1.5} declared as
     * {@code "integer"} tells the agent the field is whole, and the admin filter builder then
     * quotes numeric comparisons as strings, so {@code @.price > 10} matches nothing at all. An
     * EMPTY array must default its {@code items} to {@code {"type":"object"}} rather than leaving
     * the placeholder {@code "null"} in the published schema. An incoming JSON {@code null} must be
     * ignored rather than widening a known type to {@code ["null","number"]}. And a slot first seen
     * as {@code null} must adopt the first real shape it sees WHOLESALE — including its nested
     * {@code properties} — or a field that is null in record one and an object in record two
     * freezes as {@code {"type":"null"}} and the agent is told it has no structure.
     */
    @Test
    public void aFractionalNumberIsNotAnIntegerAndANullSlotAdoptsTheFirstShapeItSees() {
        // Map.of rejects null values, so the record carrying an explicit JSON null is built by
        // hand.
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("price", 1.5d); // Double -> "number"
        first.put("ratio", new BigDecimal("0.25")); // BigDecimal -> "number"
        first.put("count", 7); // Integer -> "integer" (control)
        first.put("tags", List.of()); // empty array -> items default
        first.put("meta", null); // unknown slot
        Map<String, Object> second = Map.of("meta", Map.of("a", "x"));
        Map<String, Object> third = new LinkedHashMap<>();
        third.put("price", null); // an incoming null must not widen "number"

        Map<String, Object> schema = extractor.extractSchema(List.of(first, second, third));

        assertThat(nested(schema, "properties", "price").get("type"))
            .as("a fractional Double must be 'number', not 'integer'").isEqualTo("number");
        assertThat(nested(schema, "properties", "ratio").get("type"))
            .as("a BigDecimal is a Number but not an integer").isEqualTo("number");
        assertThat(nested(schema, "properties", "count").get("type"))
            .as("control: an Integer must stay 'integer'").isEqualTo("integer");
        assertThat(nested(schema, "properties", "tags", "items").get("type"))
            .as("an empty array's items must default to object, never to the 'null' placeholder")
            .isEqualTo("object");

        Map<String, Object> meta = nested(schema, "properties", "meta");
        assertThat(meta.get("type")).as("a null-first slot must adopt the object it later sees")
            .isEqualTo("object");
        @SuppressWarnings("unchecked")
        Map<String, Object> metaProps = (Map<String, Object>) meta.get("properties");
        assertThat(metaProps).as("adoption must be wholesale — the nested structure comes with it")
            .containsKey("a");
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
