package de.palsoftware.yvoke.chat.api.model;

public record ToolCallTraceDto(int seq,String id,String name,String arguments,String result,boolean isError){}
