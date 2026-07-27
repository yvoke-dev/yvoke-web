package de.palsoftware.yvoke.rag.prompt;

import java.time.Instant;

public record SystemPrompt(String name,SystemPromptType type,String systemPrompt,String description,Instant createdAt,Instant updatedAt,boolean readOnly){public SystemPrompt(String name,SystemPromptType type,String systemPrompt,String description){this(name,type,systemPrompt,description,Instant.now(),Instant.now(),false);}}
