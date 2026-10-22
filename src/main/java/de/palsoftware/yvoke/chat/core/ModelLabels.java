package de.palsoftware.yvoke.chat.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Human-friendly display labels for wire model identifiers, parsed from one JSON property
 * ({@code app.chat.model-labels}), e.g.
 * <code>{"gemini-3.8-flash": "Gemini - 3.8 Flash", "gpt-5.4-mini": "Azure - GPT 5.4 Mini"}</code>.
 *
 * <p>
 * Wire model IDs stay unchanged everywhere under the hood ({@code <option value="...">}, stored
 * conversation settings, {@code llm_call_logs.model}, {@code chat_model_pricing.model_name}, and
 * {@code AI_MODEL_ROUTES}); this table maps a wire ID to its human-readable label for UI rendering
 * and falls back to the raw wire ID when a model has no entry.
 */
public record ModelLabels(Map<String,String>byModel){

public ModelLabels{byModel=Map.copyOf(byModel);}

public static ModelLabels parse(ObjectMapper mapper,String json){if(json==null||json.isBlank()){return new ModelLabels(Map.of());}JsonNode root;try{root=mapper.readTree(json);}catch(JsonProcessingException e){throw new IllegalStateException("app.chat.model-labels is not valid JSON: "+e.getOriginalMessage(),e);}if(root==null||root.isNull()){return new ModelLabels(Map.of());}if(!root.isObject()){throw new IllegalStateException("app.chat.model-labels must be a JSON object mapping a model to a display label; got "+root.getNodeType());}Map<String,String>parsed=new LinkedHashMap<>();for(Map.Entry<String,JsonNode>entry:root.properties()){String model=entry.getKey().trim();if(model.isEmpty()){throw new IllegalStateException("app.chat.model-labels names a blank model.");}JsonNode value=entry.getValue();if(!value.isTextual()){throw new IllegalStateException("app.chat.model-labels maps model '"+model+"' to "+value.getNodeType()+" rather than a string label.");}String label=value.textValue().trim();if(label.isEmpty()){throw new IllegalStateException("app.chat.model-labels maps model '"+model+"' to a blank label.");}String key=model.toLowerCase(Locale.ROOT);String existing=parsed.putIfAbsent(key,label);if(existing!=null){throw new IllegalStateException("app.chat.model-labels maps model '"+key+"' twice.");}}return new ModelLabels(parsed);}

/**
 * Returns the configured friendly display name for {@code model} (case-insensitive), or
 * {@code model} unchanged when unmapped, null, or blank.
 */
public String labelFor(String model){if(model==null||model.isBlank()){return model;}String label=byModel.get(model.trim().toLowerCase(Locale.ROOT));return label!=null?label:model;}

/**
 * Translates a comma-separated list of model IDs (such as the cost explorer's {@code modelsUsed}
 * column) into comma-separated friendly labels.
 */
public String formatCsv(String modelsCsv){if(modelsCsv==null||modelsCsv.isBlank()){return modelsCsv;}if(!modelsCsv.contains(",")){return labelFor(modelsCsv);}return Arrays.stream(modelsCsv.split(",")).map(String::trim).filter(s->!s.isEmpty()).map(this::labelFor).collect(Collectors.joining(", "));}}
