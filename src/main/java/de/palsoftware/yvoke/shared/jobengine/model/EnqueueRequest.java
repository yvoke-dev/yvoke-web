package de.palsoftware.yvoke.shared.jobengine.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record EnqueueRequest(@NotBlank @Size(max=200)String kind,@NotBlank @Size(max=2000)String sourceRef,@Nullable List<String>tags,@NotBlank @Size(max=200)String collection,@Nullable Map<String,Object>settings){

/**
 * A blank tag means "no tag", never the tag {@code ""}: a literal empty string is rejected by the
 * collection validator ("tag must not be blank") and never matches the version-skip predicate
 * {@code :tag = ANY(tags)}, which silently re-ingests everything on every sync.
 */
public EnqueueRequest{tags=tags==null?List.of():tags.stream().filter(t->t!=null&&!t.isBlank()).map(String::trim).toList();settings=settings==null?Map.of():settings;}

public EnqueueRequest(String kind,String sourceRef,@Nullable String tag,String collection){this(kind,sourceRef,tag==null?List.of():List.of(tag),collection,Map.of());}

public EnqueueRequest(String kind,String sourceRef,@Nullable String tag,String collection,@Nullable Map<String,Object>settings){this(kind,sourceRef,tag==null?List.of():List.of(tag),collection,settings);}

@JsonCreator public static EnqueueRequest create(@JsonProperty("kind")String kind,@JsonProperty("sourceRef")String sourceRef,@JsonProperty("tag")@JsonAlias("tags")Object tagOrTags,@JsonProperty("collection")String collection,@JsonProperty("settings")Map<String,Object>settings){List<String>tags=new ArrayList<>();if(tagOrTags instanceof String str){tags.add(str);}else if(tagOrTags instanceof List<?>list){for(Object o:list){if(o!=null){tags.add(o.toString());}}}return new EnqueueRequest(kind,sourceRef,tags,collection,settings);}

public String tag(){return tags!=null&&!tags.isEmpty()?String.join(", ",tags):null;}

@Override public List<String>tags(){return tags!=null?tags:List.of();}

@Override public Map<String,Object>settings(){return settings!=null?settings:Map.of();}}
