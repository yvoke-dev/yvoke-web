package de.palsoftware.yvoke.chat.core.model;

import java.time.Instant;
import java.util.UUID;

public record ToolCallRecord(UUID id,UUID messageId,int seq,String toolCallId,String toolName,String arguments,String result,boolean isError,Instant createdAt){}
