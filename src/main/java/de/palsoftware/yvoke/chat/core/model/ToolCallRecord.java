package de.palsoftware.yvoke.chat.core.model;

import java.time.Instant;
import java.util.UUID;

public record ToolCallRecord(UUID id,UUID messageId,int seq,String toolCallId,String toolName,String arguments,String result,boolean isError,Instant createdAt){public ToolCallRecord{toolCallId=sanitizeNullBytes(toolCallId);toolName=sanitizeNullBytes(toolName);arguments=sanitizeNullBytes(arguments);result=sanitizeNullBytes(result);}

public static String sanitizeNullBytes(String s){return s!=null?s.replace("\u0000",""):null;}}
