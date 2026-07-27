package de.palsoftware.yvoke.llm.core.model;

import java.util.Map;

public record LlmTool(String name,String description,Map<String,Object>inputSchema){}
