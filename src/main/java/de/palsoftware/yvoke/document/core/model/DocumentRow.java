package de.palsoftware.yvoke.document.core.model;

import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record DocumentRow(UUID id,UUID collectionId,String collection,String kind,String title,@Nullable Map<String,Object>metadata,String ingestionStatus,List<String>tags,Instant createdAt){}
