package de.palsoftware.yvoke.chat.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

/** Slim projection of an AgentStep for API boundary transfer without raw messages JSON blobs. */
@JsonInclude(JsonInclude.Include.NON_NULL)public record AgentStepTraceDto(UUID id,int seq,String role,int round,String playbookName,String model,String thinkingLevel,String input,String output,String verdict,Integer promptTokens,Integer completionTokens,Integer totalTokens,Integer cachedTokens,Integer thoughtTokens,String status,String error,Instant createdAt){}
