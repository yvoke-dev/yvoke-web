package de.palsoftware.yvoke.jsonobject.core.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
@SuppressWarnings("unchecked")
public class JsonSchemaExtractor {

    public Map<String, Object> extractSchema(List<Map<String, Object>> objects) {
        Map<String, Object> baseSchema = createEmptyObjectSchema();
        for (Map<String, Object> obj : objects) {
            mergeSchema(baseSchema, extractFromMap(obj));
        }
        return baseSchema;
    }

    public void mergeSchema(Map<String, Object> existingSchema, Map<String, Object> newSchema) {
        if (existingSchema == null || newSchema == null)
            return;

        // An incoming JSON null carries no type information; keep whatever we already know.
        if ("null".equals(typeName(newSchema))) {
            return;
        }
        // A still-unknown ("null") slot adopts the incoming shape wholesale — including any nested
        // "properties"/"items" — so the first observed structure is not lost.
        if ("null".equals(typeName(existingSchema))) {
            existingSchema.clear();
            existingSchema.putAll(newSchema);
            return;
        }

        Set<String> existingTypes = typeSet(existingSchema);
        Set<String> newTypes = typeSet(newSchema);

        // Merge object properties when both sides are (or include) objects.
        if (existingTypes.contains("object") && newTypes.contains("object")) {
            Map<String, Object> existingProps = getOrCreateMap(existingSchema, "properties");
            Map<String, Object> newProps = getOrCreateMap(newSchema, "properties");

            for (Map.Entry<String, Object> entry : newProps.entrySet()) {
                String key = entry.getKey();
                Map<String, Object> newPropSchema = (Map<String, Object>) entry.getValue();

                if (existingProps.containsKey(key)) {
                    mergeSchema((Map<String, Object>) existingProps.get(key), newPropSchema);
                } else {
                    existingProps.put(key, newPropSchema);
                }
            }
        } else if (newTypes.contains("object") && newSchema.containsKey("properties")) {
            // Widening from a non-object to also-object: preserve the object's structure.
            existingSchema.put("properties", newSchema.get("properties"));
        }

        // Merge array items when both sides are (or include) arrays.
        if (existingTypes.contains("array") && newTypes.contains("array")) {
            Map<String, Object> existingItems = getOrCreateMap(existingSchema, "items");
            Map<String, Object> newItems = getOrCreateMap(newSchema, "items");
            mergeSchema(existingItems, newItems);
        } else if (newTypes.contains("array") && newSchema.containsKey("items")) {
            existingSchema.put("items", newSchema.get("items"));
        }

        // Record the union of observed types. A single type stays a plain string (Draft-07
        // friendly); a genuine conflict is surfaced as a sorted type array instead of being
        // silently dropped, which previously froze a field to its first-seen type forever.
        Set<String> union = new LinkedHashSet<>(existingTypes);
        union.addAll(newTypes);
        if (union.size() == 1) {
            existingSchema.put("type", union.iterator().next());
        } else {
            List<String> types = new ArrayList<>(union);
            Collections.sort(types);
            existingSchema.put("type", types);
        }
    }

    /** The declared type when it is a single scalar string, otherwise {@code null}. */
    private String typeName(Map<String, Object> schema) {
        Object t = schema.get("type");
        return (t instanceof String s) ? s : null;
    }

    /** The set of declared types, tolerating either a scalar string or an already-widened list. */
    private Set<String> typeSet(Map<String, Object> schema) {
        Object t = schema.get("type");
        Set<String> set = new LinkedHashSet<>();
        if (t instanceof String s) {
            set.add(s);
        } else if (t instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) {
                    set.add(o.toString());
                }
            }
        }
        return set;
    }

    private Map<String, Object> extractFromValue(Object value) {
        if (value == null) {
            return createTypeSchema("null");
        } else if (value instanceof Map) {
            return extractFromMap((Map<String, Object>) value);
        } else if (value instanceof List) {
            return extractFromArray((List<Object>) value);
        } else if (value instanceof String) {
            return createTypeSchema("string");
        } else if (value instanceof Number) {
            // checking integer vs number
            if (value instanceof Integer || value instanceof Long) {
                return createTypeSchema("integer");
            }
            return createTypeSchema("number");
        } else if (value instanceof Boolean) {
            return createTypeSchema("boolean");
        }
        return createTypeSchema("string");
    }

    private Map<String, Object> extractFromMap(Map<String, Object> map) {
        Map<String, Object> schema = createTypeSchema("object");
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            properties.put(entry.getKey(), extractFromValue(entry.getValue()));
        }
        schema.put("properties", properties);
        return schema;
    }

    private Map<String, Object> extractFromArray(List<Object> list) {
        Map<String, Object> schema = createTypeSchema("array");
        Map<String, Object> itemsSchema = createTypeSchema("null");

        for (Object item : list) {
            Map<String, Object> itemSchema = extractFromValue(item);
            mergeSchema(itemsSchema, itemSchema);
        }

        // if still null, it was empty array. Default to object
        if ("null".equals(itemsSchema.get("type"))) {
            itemsSchema = createTypeSchema("object");
        }

        schema.put("items", itemsSchema);
        return schema;
    }

    private Map<String, Object> createEmptyObjectSchema() {
        Map<String, Object> schema = createTypeSchema("object");
        schema.put("properties", new LinkedHashMap<>());
        return schema;
    }

    private Map<String, Object> createTypeSchema(String type) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", type);
        return map;
    }

    private Map<String, Object> getOrCreateMap(Map<String, Object> parent, String key) {
        return (Map<String, Object>) parent.computeIfAbsent(key, k -> new LinkedHashMap<>());
    }
}
