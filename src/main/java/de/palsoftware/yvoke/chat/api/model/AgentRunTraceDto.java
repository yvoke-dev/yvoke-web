package de.palsoftware.yvoke.chat.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

/** Slim projection of an AgentRun for API boundary transfer without internal JSON config blobs. */
@JsonInclude(JsonInclude.Include.NON_NULL)public record AgentRunTraceDto(UUID id,UUID conversationId,UUID messageId,UUID assistantMessageId,String profileName,String status,int reviewRounds,String finalVerdict,Integer promptTokens,Integer completionTokens,Integer totalTokens,Integer cachedTokens,Integer thoughtTokens,String error,Instant startedAt,Instant finishedAt){}
