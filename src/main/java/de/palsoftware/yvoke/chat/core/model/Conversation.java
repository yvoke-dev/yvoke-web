package de.palsoftware.yvoke.chat.core.model;

import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record Conversation(UUID id,UUID userId,String title,Map<String,Object>settings,Instant createdAt,Instant updatedAt,List<String>tags,String source,@Nullable String area){

public Conversation(UUID id,UUID userId,String title,Map<String,Object>settings,Instant createdAt,Instant updatedAt,List<String>tags,String source){this(id,userId,title,settings,createdAt,updatedAt,tags,source,null);}

public Conversation(UUID id,UUID userId,String title,Map<String,Object>settings,Instant createdAt,Instant updatedAt,List<String>tags){this(id,userId,title,settings,createdAt,updatedAt,tags,ConversationSource.WEB.getValue(),null);}

public static String formatSourceBadge(@Nullable String source,@Nullable String version){String effectiveSource=(source==null||source.isBlank())?ConversationSource.WEB.getValue():source;return(version!=null&&!version.isBlank())?effectiveSource+" "+version:effectiveSource;}

public @Nullable String clientVersion(){if(settings==null||settings.isEmpty()){return null;}String key=ConversationSource.DESKTOP.getValue().equals(source)?ConversationSetting.DESKTOP_VERSION.getValue():ConversationSource.CLAUDE_PLUGIN.getValue().equals(source)?ConversationSetting.PLUGIN_VERSION.getValue():null;return key!=null&&settings.get(key)instanceof String v&&!v.isBlank()?v:null;}

public String sourceBadge(){return formatSourceBadge(source,clientVersion());}}
