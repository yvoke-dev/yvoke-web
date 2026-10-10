package de.palsoftware.yvoke.chat.core.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Persisted trace record of a single tool invocation made during an assistant turn.
 *
 * @param createdAt timestamp when the tool call trace record was extracted and persisted
 */
public record ToolCallRecord(UUID id,UUID messageId,int seq,String toolCallId,String toolName,String arguments,String result,boolean isError,Instant createdAt){

public ToolCallRecord{toolCallId=sanitizeNullBytes(toolCallId);toolName=sanitizeNullBytes(toolName);arguments=sanitizeNullBytes(arguments);result=sanitizeNullBytes(result);}

public static String sanitizeNullBytes(String s){return s!=null?s.replace("\u0000",""):null;}}
