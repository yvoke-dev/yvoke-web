package de.palsoftware.yvoke.chat.orchestration;

import java.time.Instant;
import java.util.UUID;

/** A single orchestrated run (one user question). JSONB columns are surfaced as raw text. */
public record AgentRun(UUID id,UUID conversationId,UUID messageId,UUID assistantMessageId,String profileName,String status,String config,int reviewRounds,String finalVerdict,Integer promptTokens,Integer completionTokens,Integer totalTokens,Integer cachedTokens,Integer thoughtTokens,String error,Instant startedAt,Instant finishedAt){public AgentRun(UUID id,UUID conversationId,UUID messageId,String profileName,String status,String config,int reviewRounds,String finalVerdict,Integer promptTokens,Integer completionTokens,Integer totalTokens,Integer cachedTokens,Integer thoughtTokens,String error,Instant startedAt,Instant finishedAt){this(id,conversationId,messageId,null,profileName,status,config,reviewRounds,finalVerdict,promptTokens,completionTokens,totalTokens,cachedTokens,thoughtTokens,error,startedAt,finishedAt);}}
