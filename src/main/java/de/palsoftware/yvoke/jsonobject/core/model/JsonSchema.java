package de.palsoftware.yvoke.jsonobject.core.model;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record JsonSchema(UUID id,UUID collectionId,String tag,Map<String,Object>schemaData,String source,OffsetDateTime updatedAt){}
