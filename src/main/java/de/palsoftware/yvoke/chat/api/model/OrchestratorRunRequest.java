package de.palsoftware.yvoke.chat.api.model;

import java.util.List;
import java.util.UUID;

/**
 * A completed multi-agent run reported by the desktop client (which runs orchestration locally on
 * the Claude SDK). Persisted into {@code agent_runs} + {@code agent_steps} so desktop runs show in
 * the same admin trace viewer as web runs. The final answer already lives in {@code messages}; this
 * links to it via {@code messageId}. JSONB payloads ({@code config}, {@code finalVerdict}, per-step
 * {@code messages}/{@code verdict}) are accepted as free-form objects and stored verbatim.
 */
public record OrchestratorRunRequest(UUID conversationId,UUID messageId,String profileName,String status,Object config,Integer reviewRounds,Object finalVerdict,Integer promptTokens,Integer completionTokens,Integer totalTokens,Integer cachedTokens,Integer thoughtTokens,String error,List<Step>steps){

public record Step(Integer seq,String role,Integer round,String playbookName,String model,String thinkingLevel,String input,String output,Object messages,Object verdict,Integer promptTokens,Integer completionTokens,Integer totalTokens,Integer cachedTokens,Integer thoughtTokens){}}
