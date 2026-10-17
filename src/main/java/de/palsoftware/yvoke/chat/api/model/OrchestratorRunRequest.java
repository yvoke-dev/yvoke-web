package de.palsoftware.yvoke.chat.api.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * A completed multi-agent run reported by the desktop client (which runs orchestration locally on
 * the Claude SDK). Persisted into {@code agent_runs} + {@code agent_steps} so desktop runs show in
 * the same admin trace viewer as web runs. The final answer already lives in {@code messages}; this
 * links to it via {@code messageId}. JSONB payloads ({@code config}, {@code finalVerdict}, per-step
 * {@code messages}/{@code verdict}) are accepted as free-form objects and stored verbatim.
 */
public record OrchestratorRunRequest(@NotNull UUID conversationId,UUID messageId,@NotNull @Size(max=255)String profileName,@Size(max=200)String status,Object config,Integer reviewRounds,Object finalVerdict,Integer promptTokens,Integer completionTokens,Integer totalTokens,Integer cachedTokens,Integer thoughtTokens,String error,@Size(max=200)List<@Valid Step>steps){

public record Step(Integer seq,@Size(max=200)String role,Integer round,@Size(max=255)String playbookName,@Min(1)Integer playbookVersion,@Size(max=200)String model,@Size(max=200)String thinkingLevel,@Size(max=1_000_000)String input,@Size(max=1_000_000)String output,Object messages,Object verdict,Integer promptTokens,Integer completionTokens,Integer totalTokens,Integer cachedTokens,Integer thoughtTokens){

public Step{playbookName=(playbookName!=null&&!playbookName.isBlank())?playbookName.trim():null;if(playbookName==null){playbookVersion=null;}}

public Step(Integer seq,String role,Integer round,String playbookName,String model,String thinkingLevel,String input,String output,Object messages,Object verdict,Integer promptTokens,Integer completionTokens,Integer totalTokens,Integer cachedTokens,Integer thoughtTokens){this(seq,role,round,playbookName,null,model,thinkingLevel,input,output,messages,verdict,promptTokens,completionTokens,totalTokens,cachedTokens,thoughtTokens);}}}

