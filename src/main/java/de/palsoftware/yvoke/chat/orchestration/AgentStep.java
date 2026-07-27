package de.palsoftware.yvoke.chat.orchestration;

import java.time.Instant;
import java.util.UUID;

/** One agent invocation within a run (orchestrator / specialist / reviewer). */
public record AgentStep(UUID id,UUID agentRunId,int seq,String role,int round,String playbookName,String model,String thinkingLevel,String input,String output,String messages,String verdict,Integer promptTokens,Integer completionTokens,Integer totalTokens,Integer cachedTokens,Integer thoughtTokens,Instant createdAt,String status,String error){}
