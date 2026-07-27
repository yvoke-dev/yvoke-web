package de.palsoftware.yvoke.llm.core.model;

import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Map;

/**
 * A provider-agnostic LLM request.
 *
 * @param responseMimeType optional structured-output MIME type (e.g. {@code application/json});
 *        only honored when no tools are supplied
 * @param responseSchema optional JSON-schema (as a map) constraining structured output
 * @param seed optional decoding seed for reproducible output on deterministic (low-temperature)
 *        calls
 * @param codeExecution when {@code true}, enable the provider's built-in server-side code-execution
 *        tool for this request (Gemini only; ignored by providers that don't support it)
 */
public record LlmRequest(String model,List<LlmMessage>messages,double temperature,int maxTokens,List<LlmTool>tools,@Nullable String thinkingLevel,@Nullable String responseMimeType,@Nullable Map<String,Object>responseSchema,@Nullable Integer seed,boolean codeExecution){

public LlmRequest(String model,List<LlmMessage>messages,double temperature,int maxTokens,List<LlmTool>tools){this(model,messages,temperature,maxTokens,tools,null,null,null,null,false);}

public LlmRequest(String model,List<LlmMessage>messages,double temperature,int maxTokens,List<LlmTool>tools,@Nullable String thinkingLevel){this(model,messages,temperature,maxTokens,tools,thinkingLevel,null,null,null,false);}

public LlmRequest(String model,List<LlmMessage>messages,double temperature,int maxTokens,List<LlmTool>tools,@Nullable String thinkingLevel,boolean codeExecution){this(model,messages,temperature,maxTokens,tools,thinkingLevel,null,null,null,codeExecution);}

public LlmRequest(String model,List<LlmMessage>messages,double temperature,int maxTokens,List<LlmTool>tools,@Nullable String thinkingLevel,@Nullable String responseMimeType,@Nullable Map<String,Object>responseSchema,@Nullable Integer seed){this(model,messages,temperature,maxTokens,tools,thinkingLevel,responseMimeType,responseSchema,seed,false);}}
