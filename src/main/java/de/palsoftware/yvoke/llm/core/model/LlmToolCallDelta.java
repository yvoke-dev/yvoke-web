package de.palsoftware.yvoke.llm.core.model;

/**
 * A streamed tool-call fragment.
 *
 * @param index positional slot for OpenAI-style incremental deltas; ignored when {@code complete}
 *        is {@code true}
 * @param complete {@code true} when the provider delivers the whole tool call in a single delta
 *        (e.g. Gemini) — arguments are final and identity is the call id, so consumers must replace
 *        rather than append; {@code false} for incremental (OpenAI-style) argument fragments that
 *        must be accumulated by index
 */
public record LlmToolCallDelta(int index,String id,String name,String argumentsDelta,String thoughtSignature,boolean complete){

public LlmToolCallDelta(int index,String id,String name,String argumentsDelta){this(index,id,name,argumentsDelta,null,false);}

public LlmToolCallDelta(int index,String id,String name,String argumentsDelta,String thoughtSignature){this(index,id,name,argumentsDelta,thoughtSignature,false);}}
