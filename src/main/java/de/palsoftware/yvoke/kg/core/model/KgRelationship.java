package de.palsoftware.yvoke.kg.core.model;

import jakarta.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record KgRelationship(UUID id,UUID collectionId,String collection,String subject,String predicate,String object,@Nullable UUID subjectId,@Nullable UUID objectId,List<String>tags,@Nullable String description,Map<String,Object>metadata){@Nullable public String tag(){return(tags!=null&&!tags.isEmpty())?tags.get(0):null;}

@Nullable public String displayTag(@Nullable String activeTag){if(activeTag!=null&&!activeTag.isBlank()&&tags!=null&&tags.contains(activeTag.trim())){return activeTag.trim();}if(tags!=null&&!tags.isEmpty()){return String.join(", ",tags);}return null;}

public KgRelationship(UUID id,String collection,String subject,String predicate,String object,@Nullable UUID subjectId,@Nullable UUID objectId,@Nullable String tag,@Nullable String description,Map<String,Object>metadata){this(id,UUID.nameUUIDFromBytes(collection.getBytes(StandardCharsets.UTF_8)),collection,subject,predicate,object,subjectId,objectId,tag!=null?List.of(tag):List.of(),description,metadata);}}
