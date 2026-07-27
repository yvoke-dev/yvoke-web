package de.palsoftware.yvoke.chat.api.model;

import de.palsoftware.yvoke.chat.core.model.Conversation;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ConversationDto(UUID id,String title,Map<String,Object>settings,Instant createdAt,Instant updatedAt,List<String>tags){public static ConversationDto from(Conversation c){return new ConversationDto(c.id(),c.title(),c.settings(),c.createdAt(),c.updatedAt(),c.tags());}}

