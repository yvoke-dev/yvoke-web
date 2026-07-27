package de.palsoftware.yvoke.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.service.CollectionService;
import de.palsoftware.yvoke.jsonobject.core.model.JsonSchema;
import de.palsoftware.yvoke.jsonobject.core.service.JsonObjectService;
import de.palsoftware.yvoke.mcp.McpToolUtils;
import jakarta.annotation.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;


@Component
public class GetJsonSchemaTool {

    private static final Logger log = LoggerFactory.getLogger(GetJsonSchemaTool.class);

    private final JsonObjectService jsonObjectService;
    private final CollectionService collectionService;
    private final ObjectMapper objectMapper;

    public GetJsonSchemaTool(JsonObjectService jsonObjectService,
        CollectionService collectionService, ObjectMapper objectMapper) {
        this.jsonObjectService = jsonObjectService;
        this.collectionService = collectionService;
        this.objectMapper = objectMapper;
    }

    @McpTool(name = "get_json_schema",
        description = "Read the inferred or manual JSON schema for a specific collection of JSON objects. Use this to understand the structure, available fields, and data types before querying a collection with 'query_json_objects'. A JSON schema is scoped to a collection and optional tag. Returns a JSON Schema representation.")
    @Tool(name = "get_json_schema",
        description = "Read the inferred or manual JSON schema for a specific collection of JSON objects. Use this to understand the structure, available fields, and data types before querying a collection with 'query_json_objects'. A JSON schema is scoped to a collection and optional tag. Returns a JSON Schema representation.")
    public String getJsonSchema(
        @McpToolParam(description = "The collection name for which to get the schema.",
            required = true)
        @ToolParam(description = "The collection name for which to get the schema.",
            required = true) String collection,

        @McpToolParam(
            description = "Which tag's schema to read. REQUIRED when the collection has tags — each tag has its own schema (e.g. DB-History's 'schema' and 'content' describe different row shapes), so there is no meaningful untagged answer; the error lists the valid values. Omit it only for a collection that has no tags.",
            required = false)
        @ToolParam(
            description = "Which tag's schema to read. REQUIRED when the collection has tags — each tag has its own schema (e.g. DB-History's 'schema' and 'content' describe different row shapes), so there is no meaningful untagged answer; the error lists the valid values. Omit it only for a collection that has no tags.",
            required = false)
        @Nullable String tag) {
        log.info("GetJsonSchemaTool: fetching schema for collection '{}'", collection);

        if (collection == null || collection.isBlank()) {
            return "Error: 'collection' parameter is required.";
        }
        String col = collection.trim();

        try {
            List<Collection> allCols = collectionService.listCollections();
            Collection matchedCol = allCols.stream().filter(c -> c.name().equalsIgnoreCase(col))
                .findFirst().orElse(null);

            if (matchedCol == null) {
                return "Error: Collection '" + col + "' does not exist.";
            }

            // Validate tag (optional)
            String parsedTag = (tag != null && !tag.isBlank()) ? tag.trim() : null;
            // A tag-scoped collection must be read at exactly one tag: untagged reads either
            // duplicate every hit across product versions or merge unrelated datasets, and
            // neither failure is visible in the result. Conditional on the collection, so an
            // untagged collection exempts itself.
            String tagRequired = McpToolUtils.requireTag(matchedCol, parsedTag);
            if (tagRequired != null) {
                return tagRequired;
            }
            if (parsedTag != null) {
                if (matchedCol.tags() == null || !matchedCol.tags().contains(parsedTag)) {
                    return "Error: Tag '" + parsedTag + "' does not exist in collection '" + col
                        + "'.";
                }
            }

            Optional<JsonSchema> schemaOpt =
                jsonObjectService.getSchema(matchedCol.id(), parsedTag);
            if (schemaOpt.isEmpty()) {
                return "No schema available for collection '" + col
                    + "'. The collection might be empty or a schema hasn't been inferred/provided yet.";
            }

            JsonSchema schema = schemaOpt.get();
            String schemaJson = objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(schema.schemaData());

            return "JSON Schema for collection '" + col + "' (source: " + schema.source()
                + "):\n\n```json\n" + schemaJson + "\n```";

        } catch (Exception e) {
            return McpToolUtils.toolError("get_json_schema", e);
        }
    }
}
