package de.palsoftware.yvoke.llm.core.model;

import java.util.Map;

public record LlmToolCall(String id,String type,String name,String arguments,Map<String,Object>extraContent){public LlmToolCall(String id,String type,String name,String arguments){this(id,type,name,arguments,null);}}
