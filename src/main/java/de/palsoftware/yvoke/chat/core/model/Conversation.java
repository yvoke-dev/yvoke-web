package de.palsoftware.yvoke.chat.core.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record Conversation(UUID id,UUID userId,String title,Map<String,Object>settings,Instant createdAt,Instant updatedAt,List<String>tags,String source){public Conversation(UUID id,UUID userId,String title,Map<String,Object>settings,Instant createdAt,Instant updatedAt,List<String>tags){this(id,userId,title,settings,createdAt,updatedAt,tags,"web");}}


