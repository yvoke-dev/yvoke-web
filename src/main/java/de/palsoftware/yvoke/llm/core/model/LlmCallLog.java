package de.palsoftware.yvoke.llm.core.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One persisted LLM call.
 *
 * @param totalCost what was ACTUALLY billed — zero for a call the gateway replayed from its cache
 * @param costAvoided what a replayed call would have cost; zero for a call that reached the
 *        provider. {@code SUM(costAvoided)} over any slice is the money caching saved, and
 *        {@code totalCost + costAvoided} is list price.
 * @param gatewayCacheStatus {@link GatewayCacheStatus} name, or {@code null} when no AI gateway was
 *        in the path — which is not the same as a miss
 */
public record LlmCallLog(UUID id,UUID conversationId,UUID messageId,UUID agentRunId,UUID userId,String source,String role,String model,int promptTokens,int completionTokens,int cachedTokens,int thoughtTokens,int totalTokens,BigDecimal promptPricePerMillion,BigDecimal completionPricePerMillion,BigDecimal cachedPricePerMillion,BigDecimal thoughtPricePerMillion,BigDecimal totalCost,Integer callDurationMs,Instant createdAt,String gatewayCacheStatus,String gatewayLogId,BigDecimal costAvoided){

public LlmCallLog(UUID id,UUID conversationId,UUID messageId,UUID agentRunId,UUID userId,String source,String role,String model,int promptTokens,int completionTokens,int cachedTokens,int thoughtTokens,int totalTokens,BigDecimal promptPricePerMillion,BigDecimal completionPricePerMillion,BigDecimal cachedPricePerMillion,BigDecimal thoughtPricePerMillion,BigDecimal totalCost,Integer callDurationMs,Instant createdAt){this(id,conversationId,messageId,agentRunId,userId,source,role,model,promptTokens,completionTokens,cachedTokens,thoughtTokens,totalTokens,promptPricePerMillion,completionPricePerMillion,cachedPricePerMillion,thoughtPricePerMillion,totalCost,callDurationMs,createdAt,null,null,BigDecimal.ZERO);}}
