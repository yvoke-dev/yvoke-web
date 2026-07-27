package de.palsoftware.yvoke.llm.core.model;

public record LlmPart(String type, // "text", "thought", "function_call", "function_response"
String text,LlmToolCall toolCall,String thoughtSignature // Base64 encoded
){}
