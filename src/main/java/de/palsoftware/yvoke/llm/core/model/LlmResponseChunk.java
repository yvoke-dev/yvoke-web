package de.palsoftware.yvoke.llm.core.model;

import java.util.List;

/**
 * One streamed fragment of an LLM response.
 *
 * @param gateway what an AI gateway did with this call, or {@code null} when none was in the path.
 *        The SDK stamps response headers onto every chunk, including chunks that carry no
 *        {@code usage} — which is why this cannot live inside {@link LlmUsage}.
 * @param endOfCall marks the end of ONE HTTP call, so {@code AccountingLlmClient} can write one
 *        {@code llm_call_logs} row per call rather than one per {@code generateStream}. A client
 *        that re-requests internally — the empty-turn retry — makes several calls inside one
 *        stream, and without this the abandoned attempt is invisible and its usage has to be summed
 *        onto the winner's. The boundary cannot be inferred from "this chunk carried usage":
 *        {@code GeminiLlmClient} emits an absolute whole-request snapshot on every event that
 *        carries usage, so inferring it there would mint a row per chunk. Build one with
 *        {@link #endOfCall(LlmUsage, LlmGatewayInfo)}; the accounting decorator consumes the marker
 *        rather than forwarding it, which is why it may not carry anything a caller would miss.
 */
public record LlmResponseChunk(String content,String reasoning,List<LlmToolCallDelta>toolCallDeltas,LlmUsage usage,List<LlmPart>parts,LlmGatewayInfo gateway,boolean endOfCall){

public LlmResponseChunk{if(endOfCall&&(content!=null||reasoning!=null||(toolCallDeltas!=null&&!toolCallDeltas.isEmpty())||(parts!=null&&!parts.isEmpty()))){throw new IllegalArgumentException("an end-of-call marker carries only usage and "+"gateway info; the accounting decorator consumes it instead of forwarding it, so "+"anything else on it would be deleted from the answer");}}

/**
 * The end of one HTTP call, carrying what that call cost. Emitted after a stream drains — including
 * a stream that produced nothing, because those tokens were still billed.
 */
public static LlmResponseChunk endOfCall(LlmUsage usage,LlmGatewayInfo gateway){return new LlmResponseChunk(null,null,null,usage,null,gateway,true);}

public LlmResponseChunk(String content,String reasoning,List<LlmToolCallDelta>toolCallDeltas,LlmUsage usage,List<LlmPart>parts,LlmGatewayInfo gateway){this(content,reasoning,toolCallDeltas,usage,parts,gateway,false);}

public LlmResponseChunk(String content,String reasoning,List<LlmToolCallDelta>toolCallDeltas,LlmUsage usage,List<LlmPart>parts){this(content,reasoning,toolCallDeltas,usage,parts,null,false);}

public LlmResponseChunk(String content,String reasoning,List<LlmToolCallDelta>toolCallDeltas,LlmUsage usage){this(content,reasoning,toolCallDeltas,usage,null,null,false);}}
