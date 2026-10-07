package de.palsoftware.yvoke.rag.prompt;

import java.time.Instant;

/**
 * A stored system prompt. {@code area} is the area it belongs to; {@code null} only on a value not
 * read from the database.
 */
public record SystemPrompt(String name,SystemPromptType type,String systemPrompt,String description,Instant createdAt,Instant updatedAt,boolean readOnly,String area){public SystemPrompt(String name,SystemPromptType type,String systemPrompt,String description,Instant createdAt,Instant updatedAt,boolean readOnly){this(name,type,systemPrompt,description,createdAt,updatedAt,readOnly,null);}

public SystemPrompt(String name,SystemPromptType type,String systemPrompt,String description){this(name,type,systemPrompt,description,Instant.now(),Instant.now(),false,null);}}
