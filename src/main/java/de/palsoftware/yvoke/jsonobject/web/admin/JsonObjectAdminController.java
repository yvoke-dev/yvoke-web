package de.palsoftware.yvoke.jsonobject.web.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.service.CollectionService;
import de.palsoftware.yvoke.jsonobject.core.model.JsonObject;
import de.palsoftware.yvoke.jsonobject.core.model.JsonSchema;
import de.palsoftware.yvoke.jsonobject.core.service.JsonObjectService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.jdbc.BadSqlGrammarException;


@Controller
@RequestMapping("/admin/json-objects")
public class JsonObjectAdminController {

    private static final Logger log = LoggerFactory.getLogger(JsonObjectAdminController.class);

    private final JsonObjectService jsonObjectService;
    private final CollectionService collectionService;
    private final ObjectMapper objectMapper;

    public JsonObjectAdminController(JsonObjectService jsonObjectService,
        CollectionService collectionService, ObjectMapper objectMapper) {
        this.jsonObjectService = jsonObjectService;
        this.collectionService = collectionService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public String listObjects(@RequestParam(required = false) String collection,
        @RequestParam(required = false) String tag, @RequestParam(required = false) String search,
        @RequestParam(required = false) UUID selectedObject,
        @RequestParam(required = false) String displayField,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
        Model model) {
        log.info("JsonObjectAdminController: Accessing JSON Objects view");
        List<Collection> collections = collectionService.listCollections();
        model.addAttribute("collections", collections);

        String targetCollection = collection;
        if ((targetCollection == null || targetCollection.isBlank()) && !collections.isEmpty()) {
            targetCollection = collections.get(0).name();
        }

        if (targetCollection != null) {
            Optional<Collection> colOpt = collectionService.getCollection(targetCollection);
            if (colOpt.isPresent()) {
                UUID colId = colOpt.get().id();

                // Load tags for the selected collection
                List<String> tags = colOpt.get().tags();
                model.addAttribute("tags", tags);

                List<String> tagsFilter =
                    (tag != null && !tag.isBlank()) ? List.of(tag.trim()) : null;

                List<JsonObject> objects;
                long totalElements;

                if (search != null && !search.isBlank()) {
                    try {
                        objects =
                            jsonObjectService.searchObjects(colId, search, tagsFilter, page, size);
                        totalElements =
                            jsonObjectService.countSearchObjects(colId, search, tagsFilter);
                    } catch (BadSqlGrammarException e) {
                        model.addAttribute("error",
                            "Invalid JSON Path syntax. Note: string literals must be in double quotes (e.g. \"AAD\").");
                        objects = List.of();
                        totalElements = 0;
                    }
                } else {
                    objects = jsonObjectService.listObjects(colId, tagsFilter, page, size);
                    totalElements = jsonObjectService.countObjects(colId, tagsFilter);
                }

                int totalPages = (int) Math.ceil((double) totalElements / size);

                model.addAttribute("jsonObjects", objects);
                model.addAttribute("currentPage", page);
                model.addAttribute("totalPages", totalPages);
                model.addAttribute("totalElements", totalElements);

                // Add schema
                Optional<JsonSchema> schemaOpt = jsonObjectService.getSchema(colId, tag);
                if (schemaOpt.isPresent()) {
                    try {
                        String schemaStr = objectMapper.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(schemaOpt.get().schemaData());
                        model.addAttribute("schemaJson", schemaStr);
                        model.addAttribute("schemaSource", schemaOpt.get().source());

                        Map<String, Object> schemaData = schemaOpt.get().schemaData();
                        List<String> schemaFields = extractSchemaFieldPaths(schemaData);
                        if (!schemaFields.isEmpty()) {
                            model.addAttribute("schemaFields", schemaFields);
                        }
                    } catch (JsonProcessingException e) {
                        model.addAttribute("schemaJson", "{}");
                    }
                }
                // Add active object
                if (selectedObject != null) {
                    Optional<JsonObject> objOpt = jsonObjectService.getObject(selectedObject);
                    if (objOpt.isPresent()) {
                        JsonObject obj = objOpt.get();
                        model.addAttribute("activeObject", obj);
                        try {
                            model.addAttribute("formattedData", objectMapper
                                .writerWithDefaultPrettyPrinter().writeValueAsString(obj.data()));
                        } catch (JsonProcessingException e) {
                            model.addAttribute("formattedData", "{}");
                        }
                    }
                }
            }
        }

        model.addAttribute("selectedCollection", targetCollection);
        model.addAttribute("selectedTag", tag);
        model.addAttribute("searchQuery", search);
        model.addAttribute("selectedObject", selectedObject);
        model.addAttribute("displayField", displayField);
        return "admin/json-objects";
    }



    @GetMapping("/distinct-values")
    @ResponseBody
    public List<String> getDistinctValues(@RequestParam String collection,
        @RequestParam String fieldName) {
        Optional<Collection> colOpt = collectionService.getCollection(collection);
        if (colOpt.isEmpty()) {
            return List.of();
        }
        return jsonObjectService.getDistinctValues(colOpt.get().id(), fieldName);
    }

    @PostMapping("/schema/edit")
    public String editSchema(@RequestParam String collection,
        @RequestParam(required = false) String tag, @RequestParam String schemaJson,
        RedirectAttributes redirectAttributes) throws JsonProcessingException {
        Optional<Collection> colOpt = collectionService.getCollection(collection);
        if (colOpt.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Collection not found.");
            return "redirect:/admin/json-objects";
        }

        Map<String, Object> schemaMap =
            objectMapper.readValue(schemaJson, new TypeReference<Map<String, Object>>() {});
        jsonObjectService.saveManualSchema(colOpt.get().id(), tag, schemaMap);

        redirectAttributes.addFlashAttribute("success", "Schema updated successfully.");

        String redirectUrl = "redirect:/admin/json-objects?collection=" + collection;
        if (tag != null && !tag.isBlank()) {
            redirectUrl += "&tag=" + tag;
        }
        return redirectUrl;
    }

    @PostMapping("/schema/rebuild")
    public String rebuildSchema(@RequestParam String collection,
        @RequestParam(required = false) String tag, RedirectAttributes redirectAttributes) {
        Optional<Collection> colOpt = collectionService.getCollection(collection);
        if (colOpt.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Collection not found.");
            return "redirect:/admin/json-objects";
        }
        boolean rebuilt = jsonObjectService.rebuildSchema(colOpt.get().id(), tag);
        if (rebuilt) {
            redirectAttributes.addFlashAttribute("success",
                "Schema re-inferred from current objects.");
        } else {
            redirectAttributes.addFlashAttribute("error",
                "Schema is manually maintained; rebuild skipped. Switch it to inferred first.");
        }

        String redirectUrl = "redirect:/admin/json-objects?collection=" + collection;
        if (tag != null && !tag.isBlank()) {
            redirectUrl += "&tag=" + tag;
        }
        return redirectUrl;
    }

    private List<String> extractSchemaFieldPaths(Map<String, Object> schemaData) {
        if (schemaData == null) {
            return List.of();
        }
        List<String> paths = new ArrayList<>();
        collectSchemaFieldPaths("", schemaData, paths);
        return paths.stream().distinct().sorted().toList();
    }

    private void collectSchemaFieldPaths(String prefix, Map<String, Object> node,
        List<String> paths) {
        if (node == null) {
            return;
        }
        Object properties = node.get("properties");
        if (properties instanceof Map<?, ?> propsMap) {
            for (Map.Entry<?, ?> entry : propsMap.entrySet()) {
                if (entry.getKey() != null) {
                    String fieldName = entry.getKey().toString();
                    String fullPath = prefix.isEmpty() ? fieldName : prefix + "." + fieldName;
                    paths.add(fullPath);
                    if (entry.getValue() instanceof Map<?, ?> propDetails) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> propMap = (Map<String, Object>) propDetails;
                        collectSchemaFieldPaths(fullPath, propMap, paths);
                    }
                }
            }
        }
    }
}

