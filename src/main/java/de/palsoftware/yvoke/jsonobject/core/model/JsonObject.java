package de.palsoftware.yvoke.jsonobject.core.model;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record JsonObject(UUID id,UUID collectionId,String collectionName,Map<String,Object>data,String sourceFile,List<String>tags,OffsetDateTime createdAt){public JsonObject(UUID id,UUID collectionId,String collectionName,Map<String,Object>data,String sourceFile,OffsetDateTime createdAt){this(id,collectionId,collectionName,data,sourceFile,List.of(),createdAt);}

public String getDisplayValue(String path){if(path==null||path.isBlank()||data==null||data.isEmpty()){return null;}

String trimmedPath=path.trim();

// 1. Direct key lookup (exact)
if(data.containsKey(trimmedPath)){Object direct=data.get(trimmedPath);return direct!=null?direct.toString():null;}

// 2. Direct key lookup (case-insensitive)
for(Map.Entry<String,Object>entry:data.entrySet()){if(entry.getKey().equalsIgnoreCase(trimmedPath)){return entry.getValue()!=null?entry.getValue().toString():null;}}

// 3. Dot / Array index traversal
String normalizedPath=trimmedPath.replaceAll("\\[(\\d+)\\]",".$1");String[]tokens=normalizedPath.split("\\.");

Object current=data;for(String token:tokens){if(token.isBlank()){continue;}if(current==null){return null;}

if(current instanceof Map<?,?>map){Object next=map.get(token);if(next==null){for(Map.Entry<?,?>entry:map.entrySet()){if(entry.getKey()!=null&&entry.getKey().toString().equalsIgnoreCase(token)){next=entry.getValue();break;}}}current=next;}else if(current instanceof List<?>list){try{int index=Integer.parseInt(token);if(index>=0&&index<list.size()){current=list.get(index);}else{return null;}}catch(NumberFormatException e){return null;}}else{return null;}}

if(current==null){return null;}return current.toString();}}

