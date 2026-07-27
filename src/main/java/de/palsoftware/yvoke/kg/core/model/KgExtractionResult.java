package de.palsoftware.yvoke.kg.core.model;

import java.util.List;

public record KgExtractionResult(List<ExtractedEntity>entities,List<ExtractedRelationship>relationships,int skipped,List<ChunkStatus>chunkStatuses){

public KgExtractionResult(List<ExtractedEntity>entities,List<ExtractedRelationship>relationships,int skipped){this(entities,relationships,skipped,List.of());}

public record ChunkStatus(int index,boolean ok,String model){}

public record ExtractedEntity(String name,String kind,String description){}

public record ExtractedRelationship(String subject,String predicate,String object,String description){}}
