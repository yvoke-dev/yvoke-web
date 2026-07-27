package de.palsoftware.yvoke.llm.core.model;

import java.util.List;

public record LlmMessage(String role,String content,List<LlmPart>parts,List<LlmToolCall>toolCalls,String toolCallId,String toolName){public LlmMessage(String role,String content){this(role,content,null,null,null,null);}

public LlmMessage(String role,String content,List<LlmToolCall>toolCalls,String toolCallId,String toolName){this(role,content,null,toolCalls,toolCallId,toolName);}}
