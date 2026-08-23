package de.palsoftware.yvoke.llm.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Which provider client answers for which model, parsed from one JSON property
 * ({@code app.ai.model-routes}), e.g.
 * <code>{"gpt-5.4-mini": "azure-openai-responses", "gemini-3.7-flash": "gemini"}</code>.
 *
 * <p>
 * A model with no entry is answered by the default route ({@code app.ai.provider}), so the table
 * ships empty and adds nothing until an operator writes one. Everything it DOES contain is
 * validated at startup: invalid JSON, a non-object, an unknown route id, a non-string route and a
 * duplicated model all fail rather than being skipped, because each of those silently answers a
 * model with the wrong provider — a wrong answer rather than an error.
 *
 * <p>
 * Deliberately stricter than {@code app.ai.provider}, which falls back to Gemini on an unrecognised
 * value so a typo cannot take a running deployment down. That leniency is owed to deployments
 * already carrying the property; this one is new and empty, so strictness costs nothing.
 */
public record LlmModelRoutes(Map<String,LlmRouteId>byModel){

public LlmModelRoutes{byModel=Map.copyOf(byModel);}

/**
 * One string rather than a nested YAML map, because the deployment delivers configuration as flat
 * environment variables ({@code envFrom} over a ConfigMap) — a nested structure could not be set
 * there at all. JSON rather than the comma-separated form used by
 * {@code app.ai.azure-openai.reasoning-models}, because a model name is operator-chosen free text:
 * quoting removes any question of what a separator inside one would mean, and the value grows into
 * an object per model if a route ever becomes more than a name.
 *
 * @param json blank or {@code {}} for "route nothing", which is the shipped configuration
 */
public static LlmModelRoutes parse(ObjectMapper mapper,String json){if(json==null||json.isBlank()){return new LlmModelRoutes(Map.of());}JsonNode root;try{root=mapper.readTree(json);}catch(JsonProcessingException e){throw new IllegalStateException("app.ai.model-routes is not valid JSON: "+e.getOriginalMessage(),e);}if(root==null||root.isNull()){return new LlmModelRoutes(Map.of());}if(!root.isObject()){throw new IllegalStateException("app.ai.model-routes must be a JSON object mapping a model to a route, e.g. {\"gpt-5.6-luna\": \"azure-openai-responses\"}; got "+root.getNodeType());}Map<String,LlmRouteId>parsed=new LinkedHashMap<>();for(Map.Entry<String,JsonNode>entry:root.properties()){String model=entry.getKey().trim();if(model.isEmpty()){throw new IllegalStateException("app.ai.model-routes names a blank model.");}JsonNode value=entry.getValue();if(!value.isTextual()){throw new IllegalStateException("app.ai.model-routes maps model '"+model+"' to "+value.getNodeType()+" rather than a route name. Routes: "+LlmRouteId.wireSpellings()+".");}String route=value.textValue().trim();LlmRouteId id=LlmRouteId.fromWire(route).orElseThrow(()->new IllegalStateException("app.ai.model-routes maps model '"+model+"' to unknown route '"+route+"'. Routes: "+LlmRouteId.wireSpellings()+"."));String key=model.toLowerCase(Locale.ROOT);LlmRouteId existing=parsed.putIfAbsent(key,id);if(existing!=null){throw new IllegalStateException("app.ai.model-routes maps model '"+key+"' twice. Two rules for one model is an ambiguity, not a preference order; note the keys differ only in case.");}}return new LlmModelRoutes(parsed);}

/**
 * Case-insensitive on the model name, matching how {@link LlmRouteId#fromWire} reads a route id:
 * both halves of an entry are hand-typed, and an exact match would leave {@code Gemini-3.6-Flash}
 * unrouted and answered by the default route instead.
 */
public Optional<LlmRouteId>routeFor(String model){if(model==null||model.isBlank()){return Optional.empty();}return Optional.ofNullable(byModel.get(model.trim().toLowerCase(Locale.ROOT)));}

/** The routes actually named, so only those clients need to be constructed. */
public Set<LlmRouteId>declaredRoutes(){return byModel.isEmpty()?EnumSet.noneOf(LlmRouteId.class):EnumSet.copyOf(byModel.values());}

public boolean isEmpty(){return byModel.isEmpty();}}
