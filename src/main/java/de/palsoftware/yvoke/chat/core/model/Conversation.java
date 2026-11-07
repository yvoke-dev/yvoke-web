package de.palsoftware.yvoke.chat.core.model;

import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record Conversation(UUID id,UUID userId,String title,Map<String,Object>settings,Instant createdAt,Instant updatedAt,List<String>tags,String source,@Nullable String area){

public Conversation(UUID id,UUID userId,String title,Map<String,Object>settings,Instant createdAt,Instant updatedAt,List<String>tags,String source){this(id,userId,title,settings,createdAt,updatedAt,tags,source,null);}

public Conversation(UUID id,UUID userId,String title,Map<String,Object>settings,Instant createdAt,Instant updatedAt,List<String>tags){this(id,userId,title,settings,createdAt,updatedAt,tags,"web",null);}

public static @Nullable String normalizeVersion(@Nullable Object raw){if(!(raw instanceof String str)){return null;}String trimmed=str.trim();while(!trimmed.isEmpty()&&(trimmed.charAt(0)=='v'||trimmed.charAt(0)=='V')){trimmed=trimmed.substring(1).trim();}return trimmed.isEmpty()?null:trimmed;}

public static String formatSourceBadge(@Nullable String source,@Nullable Object rawVersion){String effectiveSource=(source==null||source.isBlank())?"web":source;String v=normalizeVersion(rawVersion);return v!=null?effectiveSource+" v"+v:effectiveSource;}

public @Nullable String clientVersion(){if(settings==null||settings.isEmpty()){return null;}if("desktop".equals(source)){return normalizeVersion(settings.get(ConversationSetting.DESKTOP_VERSION.key()));}if("claude-plugin".equals(source)){return normalizeVersion(settings.get(ConversationSetting.PLUGIN_VERSION.key()));}return null;}

public String sourceBadge(){return formatSourceBadge(source,clientVersion());}}
