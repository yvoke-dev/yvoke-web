package de.palsoftware.yvoke.kg.core.model;

import jakarta.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record KgEntity(@Nullable UUID id,UUID collectionId,String collection,String name,@Nullable String kind,List<String>tags,@Nullable String description,Map<String,Object>metadata,@Nullable Double similarity){@Nullable public String category(){return kind;}

@Nullable public String tag(){return(tags!=null&&!tags.isEmpty())?tags.get(0):null;}

@Nullable public String displayTag(@Nullable String activeTag){if(activeTag!=null&&!activeTag.isBlank()&&tags!=null&&tags.contains(activeTag.trim())){return activeTag.trim();}if(tags!=null&&!tags.isEmpty()){return String.join(", ",tags);}return null;}

public KgEntity(@Nullable UUID id,String collection,String name,@Nullable String category,@Nullable String tag,@Nullable String description,Map<String,Object>metadata,@Nullable Double similarity){this(id,UUID.nameUUIDFromBytes(collection.getBytes(StandardCharsets.UTF_8)),collection,name,category,tag!=null?List.of(tag):List.of(),description,metadata,similarity);}}
