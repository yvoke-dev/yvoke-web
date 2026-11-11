package de.palsoftware.yvoke.chat.core.model;

import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record Conversation(UUID id,UUID userId,String title,Map<String,Object>settings,Instant createdAt,Instant updatedAt,List<String>tags,String source,@Nullable String area){

public static final int MAX_CLIENT_VERSION_LENGTH=256;

public Conversation(UUID id,UUID userId,String title,Map<String,Object>settings,Instant createdAt,Instant updatedAt,List<String>tags,String source){this(id,userId,title,settings,createdAt,updatedAt,tags,source,null);}

public Conversation(UUID id,UUID userId,String title,Map<String,Object>settings,Instant createdAt,Instant updatedAt,List<String>tags){this(id,userId,title,settings,createdAt,updatedAt,tags,ConversationSource.WEB.getValue(),null);}

public static @Nullable String normalizeVersion(@Nullable Object raw){if(!(raw instanceof String str)){return null;}int start=0;int end=str.length();while(start<end&&Character.isWhitespace(str.charAt(start))){start++;}while(end>start&&Character.isWhitespace(str.charAt(end-1))){end--;}int len=end-start;if(len==0||len>MAX_CLIENT_VERSION_LENGTH){return null;}return str.substring(start,end);}

public static String formatSourceBadge(@Nullable String source,@Nullable Object rawVersion){String effectiveSource=(source==null||source.isBlank())?ConversationSource.WEB.getValue():source;String v=normalizeVersion(rawVersion);return v!=null?effectiveSource+" "+v:effectiveSource;}

public @Nullable String clientVersion(){if(settings==null||settings.isEmpty()){return null;}if(ConversationSource.DESKTOP.getValue().equals(source)){return normalizeVersion(settings.get(ConversationSetting.DESKTOP_VERSION.getValue()));}if(ConversationSource.CLAUDE_PLUGIN.getValue().equals(source)){return normalizeVersion(settings.get(ConversationSetting.PLUGIN_VERSION.getValue()));}return null;}

public String sourceBadge(){return formatSourceBadge(source,clientVersion());}}
