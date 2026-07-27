package de.palsoftware.yvoke.shared.jobengine.model;

import jakarta.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record IngestionJob(UUID id,String kind,String sourceRef,@Nullable List<String>tags,UUID collectionId,String collectionName,JobStatus status,@Nullable JobStep step,int progress,int attempts,@Nullable String error,@Nullable JobCounts counts,OffsetDateTime createdAt,@Nullable OffsetDateTime startedAt,@Nullable OffsetDateTime finishedAt,@Nullable Map<String,Object>settings,@Nullable String summary){

/** Pre-summary callers; a job with no recorded summary reads as null, which is what it is. */
public IngestionJob(UUID id,String kind,String sourceRef,@Nullable List<String>tags,UUID collectionId,String collectionName,JobStatus status,@Nullable JobStep step,int progress,int attempts,@Nullable String error,@Nullable JobCounts counts,OffsetDateTime createdAt,@Nullable OffsetDateTime startedAt,@Nullable OffsetDateTime finishedAt,@Nullable Map<String,Object>settings){this(id,kind,sourceRef,tags,collectionId,collectionName,status,step,progress,attempts,error,counts,createdAt,startedAt,finishedAt,settings,null);}

public IngestionJob(UUID id,String kind,String sourceRef,@Nullable List<String>tags,UUID collectionId,String collectionName,JobStatus status,@Nullable JobStep step,int progress,int attempts,@Nullable String error,@Nullable JobCounts counts,OffsetDateTime createdAt,@Nullable OffsetDateTime startedAt,@Nullable OffsetDateTime finishedAt){this(id,kind,sourceRef,tags,collectionId,collectionName,status,step,progress,attempts,error,counts,createdAt,startedAt,finishedAt,Map.of());}

// Overloaded constructor for single tag backward compatibility
public IngestionJob(UUID id,String kind,String sourceRef,@Nullable String tag,UUID collectionId,String collectionName,JobStatus status,@Nullable JobStep step,int progress,int attempts,@Nullable String error,@Nullable JobCounts counts,OffsetDateTime createdAt,@Nullable OffsetDateTime startedAt,@Nullable OffsetDateTime finishedAt){this(id,kind,sourceRef,tag==null?List.of():List.of(tag),collectionId,collectionName,status,step,progress,attempts,error,counts,createdAt,startedAt,finishedAt,Map.of());}

// Legacy constructor mapping String collection to generated UUID collectionId
public IngestionJob(UUID id,String kind,String sourceRef,@Nullable List<String>tags,String collection,JobStatus status,@Nullable JobStep step,int progress,int attempts,@Nullable String error,@Nullable JobCounts counts,OffsetDateTime createdAt,@Nullable OffsetDateTime startedAt,@Nullable OffsetDateTime finishedAt){this(id,kind,sourceRef,tags,UUID.randomUUID(),collection,status,step,progress,attempts,error,counts,createdAt,startedAt,finishedAt,Map.of());}

// Legacy constructor mapping String collection to generated UUID collectionId (single tag version)
public IngestionJob(UUID id,String kind,String sourceRef,@Nullable String tag,String collection,JobStatus status,@Nullable JobStep step,int progress,int attempts,@Nullable String error,@Nullable JobCounts counts,OffsetDateTime createdAt,@Nullable OffsetDateTime startedAt,@Nullable OffsetDateTime finishedAt){this(id,kind,sourceRef,tag==null?List.of():List.of(tag),UUID.randomUUID(),collection,status,step,progress,attempts,error,counts,createdAt,startedAt,finishedAt,Map.of());}

public String collection(){return collectionName;}

public String tag(){return tags!=null&&!tags.isEmpty()?String.join(", ",tags):null;}

@Override public List<String>tags(){return tags!=null?tags:List.of();}

@Override public Map<String,Object>settings(){return settings!=null?settings:Map.of();}}
