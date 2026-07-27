package de.palsoftware.yvoke.document.core.model;

import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ChunkRow(UUID id,UUID documentId,String text,List<String>headingPath,@Nullable String heading,@Nullable Integer depth,@Nullable Integer sortOrder,@Nullable String tag,@Nullable String documentTitle,@Nullable String kind,String collection,@Nullable Map<String,Object>metadata,double score){}
