package de.palsoftware.yvoke.llm.core.model;

import java.util.List;

/**
 * One streamed fragment of an LLM response.
 *
 * @param gateway what an AI gateway did with this call, or {@code null} when none was in the path.
 *        The SDK stamps response headers onto every chunk, including chunks that carry no
 *        {@code usage} — which is why this cannot live inside {@link LlmUsage}.
 */
public record LlmResponseChunk(String content,String reasoning,List<LlmToolCallDelta>toolCallDeltas,LlmUsage usage,List<LlmPart>parts,LlmGatewayInfo gateway){

public LlmResponseChunk(String content,String reasoning,List<LlmToolCallDelta>toolCallDeltas,LlmUsage usage,List<LlmPart>parts){this(content,reasoning,toolCallDeltas,usage,parts,null);}

public LlmResponseChunk(String content,String reasoning,List<LlmToolCallDelta>toolCallDeltas,LlmUsage usage){this(content,reasoning,toolCallDeltas,usage,null,null);}}
