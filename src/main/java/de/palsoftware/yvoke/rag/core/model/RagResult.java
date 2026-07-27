package de.palsoftware.yvoke.rag.core.model;

import de.palsoftware.yvoke.llm.core.model.LlmMessage;
import java.util.List;
import java.util.UUID;

/**
 * Metadata returned after an agentic answer completes. Tokens are delivered via the caller's sink
 * during generation; this record carries only the post-run bookkeeping.
 */
public record RagResult(List<UUID>retrievedChunkIds,List<LlmMessage>messages,UUID searchId,List<UUID>searchIds,int promptTokens,int completionTokens,int totalTokens,int cachedTokens,int thoughtTokens,String clarifyingQuestion,List<String>clarifyingOptions){

public RagResult(List<UUID>retrievedChunkIds,List<LlmMessage>messages,UUID searchId,List<UUID>searchIds,int promptTokens,int completionTokens,int totalTokens,int cachedTokens,int thoughtTokens){this(retrievedChunkIds,messages,searchId,searchIds,promptTokens,completionTokens,totalTokens,cachedTokens,thoughtTokens,null,null);}

/** Convenience for tests / simple callers that only carry chunk ids + search ids. */
public RagResult(List<UUID>retrievedChunkIds,List<UUID>searchIds){this(retrievedChunkIds,List.of(),null,searchIds,0,0,0,0,0,null,null);}}
