package de.palsoftware.yvoke.document.core.model;

import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record DocumentDetails(UUID id,UUID collectionId,String collection,String kind,String title,@Nullable Map<String,Object>metadata,String ingestionStatus,long chunkCount,boolean kgProcessed,long kgFailedChunks,List<String>tags,Instant createdAt){

public DocumentDetails(UUID id,UUID collectionId,String collection,String kind,String title,@Nullable Map<String,Object>metadata,String ingestionStatus,long chunkCount,boolean kgProcessed,Instant createdAt){this(id,collectionId,collection,kind,title,metadata,ingestionStatus,chunkCount,kgProcessed,0L,Collections.emptyList(),createdAt);}}
