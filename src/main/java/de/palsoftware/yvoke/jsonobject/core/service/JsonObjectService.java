package de.palsoftware.yvoke.jsonobject.core.service;

import de.palsoftware.yvoke.jsonobject.core.model.JsonObject;
import de.palsoftware.yvoke.jsonobject.core.model.JsonSchema;
import de.palsoftware.yvoke.jsonobject.core.repository.JsonObjectRepository;
import de.palsoftware.yvoke.jsonobject.core.repository.JsonSchemaRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JsonObjectService {

    private final JsonObjectRepository jsonObjectRepository;
    private final JsonSchemaRepository jsonSchemaRepository;
    private final JsonSchemaExtractor schemaExtractor;

    public JsonObjectService(JsonObjectRepository jsonObjectRepository,
        JsonSchemaRepository jsonSchemaRepository, JsonSchemaExtractor schemaExtractor) {
        this.jsonObjectRepository = jsonObjectRepository;
        this.jsonSchemaRepository = jsonSchemaRepository;
        this.schemaExtractor = schemaExtractor;
    }

    @Transactional
    public void importObjects(UUID collectionId, String collectionName,
        List<Map<String, Object>> objects, String sourceFile) {
        importObjects(collectionId, collectionName, objects, sourceFile, List.of(), null);
    }

    @Transactional
    public void importObjects(UUID collectionId, String collectionName,
        List<Map<String, Object>> objects, String sourceFile, List<String> tags) {
        importObjects(collectionId, collectionName, objects, sourceFile, tags, null);
    }

    @Transactional
    public void importObjects(UUID collectionId, String collectionName,
        List<Map<String, Object>> objects, String sourceFile, List<String> tags,
        String uniqueFieldPath) {

        if (objects.isEmpty())
            return;

        List<JsonObject> toInsert = new ArrayList<>();
        List<JsonObject> toUpdate = new ArrayList<>();
        OffsetDateTime now = OffsetDateTime.now();

        boolean hasUniqueField = uniqueFieldPath != null && !uniqueFieldPath.isBlank();

        // PRF-02: resolve ALL existing objects by unique value in ONE query up front, instead of
        // one
        // probe per imported object (which made a large import into a populated collection
        // quadratic).
        Map<String, UUID> existingIdByValue = Map.of();
        if (hasUniqueField) {
            List<String> uniqueValues = new ArrayList<>();
            for (Map<String, Object> data : objects) {
                String uv = extractUniqueValue(data, uniqueFieldPath);
                if (uv != null) {
                    uniqueValues.add(uv);
                }
            }
            existingIdByValue = jsonObjectRepository.findIdsByJsonField(collectionId,
                uniqueFieldPath, uniqueValues);
        }

        for (Map<String, Object> data : objects) {
            boolean updated = false;
            if (hasUniqueField) {
                String uniqueValue = extractUniqueValue(data, uniqueFieldPath);
                if (uniqueValue != null) {
                    UUID existingId = existingIdByValue.get(uniqueValue);
                    if (existingId != null) {
                        toUpdate.add(new JsonObject(existingId, collectionId, collectionName, data,
                            sourceFile, tags, now));
                        updated = true;
                    }
                }
            }
            if (!updated) {
                toInsert.add(new JsonObject(UUID.randomUUID(), collectionId, collectionName, data,
                    sourceFile, tags, now));
            }
        }

        // 1. Batch save and update objects
        if (!toInsert.isEmpty()) {
            jsonObjectRepository.saveBatch(toInsert);
        }
        if (!toUpdate.isEmpty()) {
            jsonObjectRepository.updateBatch(toUpdate);
        }

        // 2. Extract schema from new objects
        Map<String, Object> extractedSchema = schemaExtractor.extractSchema(objects);

        // 3. Upsert schema (merge if exists and is inferred)
        if (tags == null || tags.isEmpty()) {
            upsertSchemaForTag(collectionId, null, extractedSchema);
        } else {
            for (String tag : tags) {
                upsertSchemaForTag(collectionId, tag, extractedSchema);
            }
        }
    }

    private String extractUniqueValue(Map<String, Object> data, String uniqueFieldPath) {
        String[] parts = uniqueFieldPath.split("\\.");
        Object current = data;
        for (String part : parts) {
            if (current instanceof Map m) {
                current = m.get(part);
            } else {
                return null;
            }
        }
        return current != null ? current.toString() : null;
    }

    private void upsertSchemaForTag(UUID collectionId, String tag,
        Map<String, Object> extractedSchema) {
        Optional<JsonSchema> existingSchemaOpt =
            jsonSchemaRepository.findByCollectionId(collectionId, tag);

        if (existingSchemaOpt.isPresent()) {
            JsonSchema existing = existingSchemaOpt.get();
            if ("inferred".equals(existing.source())) {
                schemaExtractor.mergeSchema(existing.schemaData(), extractedSchema);
                jsonSchemaRepository.upsert(new JsonSchema(existing.id(), collectionId, tag,
                    existing.schemaData(), "inferred", OffsetDateTime.now()));
            }
            // If source is 'manual', we don't automatically merge/overwrite it.
        } else {
            jsonSchemaRepository.upsert(new JsonSchema(UUID.randomUUID(), collectionId, tag,
                extractedSchema, "inferred", OffsetDateTime.now()));
        }
    }

    public Optional<JsonObject> getObject(UUID id) {
        return jsonObjectRepository.findById(id);
    }

    public List<JsonObject> listObjects(UUID collectionId, int page, int size) {
        return listObjects(collectionId, null, page, size);
    }

    public List<JsonObject> listObjects(UUID collectionId, List<String> tags, int page, int size) {
        return jsonObjectRepository.findByCollectionId(collectionId, tags, size, page * size);
    }

    /**
     * Lists objects at an exact row {@code offset}, as opposed to the page-based
     * {@link #listObjects(UUID, List, int, int)} above.
     *
     * <p>
     * Both exist deliberately. The admin UI pages (it renders page numbers and a total-page count),
     * while a tool caller paginates by offset. Deriving one from the other with
     * {@code offset/limit} silently rounds any non-multiple offset DOWN to the start of its page —
     * {@code offset=250,
     * limit=500} re-returned rows 0-499 — so the two semantics must not be bridged by division.
     */
    public List<JsonObject> listObjectsByOffset(UUID collectionId, List<String> tags, int limit,
        int offset) {
        return jsonObjectRepository.findByCollectionId(collectionId, tags, limit, offset);
    }

    public long countObjects(UUID collectionId) {
        return countObjects(collectionId, null);
    }

    public long countObjects(UUID collectionId, List<String> tags) {
        return jsonObjectRepository.countByCollectionId(collectionId, tags);
    }

    public List<JsonObject> searchObjects(UUID collectionId, String query, int page, int size) {
        return searchObjects(collectionId, query, null, page, size);
    }

    public List<JsonObject> searchObjects(UUID collectionId, String query, List<String> tags,
        int page, int size) {
        if (query == null || query.isBlank()) {
            return listObjects(collectionId, tags, page, size);
        }
        if (query.trim().startsWith("$")) {
            return jsonObjectRepository.queryByJsonPath(collectionId, query.trim(), tags, size,
                page * size);
        }
        return jsonObjectRepository.search(collectionId, query, tags, size, page * size);
    }

    public long countSearchObjects(UUID collectionId, String query) {
        return countSearchObjects(collectionId, query, null);
    }

    public long countSearchObjects(UUID collectionId, String query, List<String> tags) {
        if (query == null || query.isBlank()) {
            return countObjects(collectionId, tags);
        }
        if (query.trim().startsWith("$")) {
            return jsonObjectRepository.countByJsonPath(collectionId, query.trim(), tags);
        }
        return jsonObjectRepository.countSearch(collectionId, query, tags);
    }

    public Map<String, Long> countGroupedObjects(UUID collectionId, String query,
        String groupByField, List<String> tags) {
        String path = null;
        if (query != null && !query.isBlank() && query.trim().startsWith("$")) {
            path = query.trim();
        }
        return jsonObjectRepository.countGroupedByJsonPath(collectionId, path, groupByField, tags);
    }

    public List<JsonObject> queryObjects(UUID collectionId, String jsonPath, int limit,
        int offset) {
        return queryObjects(collectionId, jsonPath, null, limit, offset);
    }

    public List<JsonObject> queryObjects(UUID collectionId, String jsonPath, List<String> tags,
        int limit, int offset) {
        return jsonObjectRepository.queryByJsonPath(collectionId, jsonPath, tags, limit, offset);
    }

    public List<JsonObject> queryObjectsByFilter(UUID collectionId, Map<String, Object> filter,
        int limit, int offset) {
        return queryObjectsByFilter(collectionId, filter, null, limit, offset);
    }

    public List<JsonObject> queryObjectsByFilter(UUID collectionId, Map<String, Object> filter,
        List<String> tags, int limit, int offset) {
        return jsonObjectRepository.queryByContainment(collectionId, filter, tags, limit, offset);
    }

    public Optional<JsonSchema> getSchema(UUID collectionId, String tag) {
        return jsonSchemaRepository.findByCollectionId(collectionId, tag);
    }

    /**
     * Re-infer the schema for a collection (optionally scoped to a tag) from ALL objects currently
     * stored, replacing any previously inferred schema rather than merging into it. Use this to
     * recover from a schema that has drifted — e.g. retaining keys or a type from data that has
     * since been deleted or re-shaped, which the additive {@link #importObjects} merge cannot undo.
     * A manual schema is never overwritten.
     *
     * @return {@code true} if the schema was rebuilt; {@code false} if it was left untouched
     *         because an existing schema is manually maintained.
     */
    @Transactional
    public boolean rebuildSchema(UUID collectionId, String tag) {
        String normalizedTag = (tag != null && tag.isBlank()) ? null : tag;
        Optional<JsonSchema> existing =
            jsonSchemaRepository.findByCollectionId(collectionId, normalizedTag);
        if (existing.isPresent() && "manual".equals(existing.get().source())) {
            return false;
        }

        List<String> tagList = (normalizedTag != null) ? List.of(normalizedTag) : null;
        List<JsonObject> objects =
            jsonObjectRepository.findByCollectionId(collectionId, tagList, Integer.MAX_VALUE, 0);
        List<Map<String, Object>> data = new ArrayList<>(objects.size());
        for (JsonObject obj : objects) {
            data.add(obj.data());
        }

        Map<String, Object> schema = schemaExtractor.extractSchema(data);
        UUID id = existing.map(JsonSchema::id).orElseGet(UUID::randomUUID);
        jsonSchemaRepository.upsert(new JsonSchema(id, collectionId, normalizedTag, schema,
            "inferred", OffsetDateTime.now()));
        return true;
    }

    @Transactional
    public void saveManualSchema(UUID collectionId, String tag, Map<String, Object> schemaData) {
        Optional<JsonSchema> existing = jsonSchemaRepository.findByCollectionId(collectionId, tag);
        UUID id = existing.map(JsonSchema::id).orElseGet(UUID::randomUUID);
        jsonSchemaRepository.upsert(
            new JsonSchema(id, collectionId, tag, schemaData, "manual", OffsetDateTime.now()));
    }

    @Transactional
    public void deleteObjectsByCollection(UUID collectionId) {
        // FK constraint cascading will handle schema, but explicitly deleting helps audit/logic
        jsonObjectRepository.deleteByCollectionId(collectionId);
        jsonSchemaRepository.deleteByCollectionId(collectionId);
    }

    /**
     * Tag-aware removal of a tag's json objects within a collection (see repository for semantics).
     */
    @Transactional
    public void removeTagAndPurgeOrphans(UUID collectionId, String tag) {
        jsonObjectRepository.removeTagAndPurgeOrphans(collectionId, tag);
    }

    public List<String> getDistinctValues(UUID collectionId, String fieldName) {
        return jsonObjectRepository.getDistinctValues(collectionId, fieldName);
    }
}
