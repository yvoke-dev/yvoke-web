package de.palsoftware.yvoke.llm.core.model;

/**
 * One completed non-streaming LLM call.
 *
 * @param gateway what an AI gateway did with this call, or {@code null} when none was in the path.
 *        Deliberately beside {@code usage} rather than inside it: a replayed response still reports
 *        the original call's token counts, so the two are independent facts.
 */
public record LlmResponse(String content,LlmUsage usage,LlmGatewayInfo gateway){

public LlmResponse(String content,LlmUsage usage){this(content,usage,null);}}
