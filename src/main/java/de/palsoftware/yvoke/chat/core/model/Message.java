package de.palsoftware.yvoke.chat.core.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import de.palsoftware.yvoke.rag.core.service.CitationVerifier.CitationCheckResult;

public record Message(UUID id,UUID conversationId,String role,String content,String playbook,List<UUID>retrievedChunkIds,List<CitationCheckResult>citations,Instant createdAt,Integer promptTokens,Integer completionTokens,Integer totalTokens,Integer cachedTokens,Integer thoughtTokens,String status,String model){public Message(UUID id,UUID conversationId,String role,String content,List<UUID>retrievedChunkIds,List<CitationCheckResult>citations,Instant createdAt){this(id,conversationId,role,content,null,retrievedChunkIds,citations,createdAt,null,null,null,null,null,"done",null);}

public Message(UUID id,UUID conversationId,String role,String content,List<UUID>retrievedChunkIds,List<CitationCheckResult>citations,Instant createdAt,Integer promptTokens,Integer completionTokens,Integer totalTokens){this(id,conversationId,role,content,null,retrievedChunkIds,citations,createdAt,promptTokens,completionTokens,totalTokens,null,null,"done",null);}

public Message(UUID id,UUID conversationId,String role,String content,List<UUID>retrievedChunkIds,List<CitationCheckResult>citations,Instant createdAt,Integer promptTokens,Integer completionTokens,Integer totalTokens,Integer cachedTokens,Integer thoughtTokens){this(id,conversationId,role,content,null,retrievedChunkIds,citations,createdAt,promptTokens,completionTokens,totalTokens,cachedTokens,thoughtTokens,"done",null);}

public Message(UUID id,UUID conversationId,String role,String content,String playbook,List<UUID>retrievedChunkIds,List<CitationCheckResult>citations,Instant createdAt){this(id,conversationId,role,content,playbook,retrievedChunkIds,citations,createdAt,null,null,null,null,null,"done",null);}

public Message(UUID id,UUID conversationId,String role,String content,String playbook,List<UUID>retrievedChunkIds,List<CitationCheckResult>citations,Instant createdAt,Integer promptTokens,Integer completionTokens,Integer totalTokens,Integer cachedTokens,Integer thoughtTokens){this(id,conversationId,role,content,playbook,retrievedChunkIds,citations,createdAt,promptTokens,completionTokens,totalTokens,cachedTokens,thoughtTokens,"done",null);}public Message(UUID id,UUID conversationId,String role,String content,String playbook,List<UUID>retrievedChunkIds,List<CitationCheckResult>citations,Instant createdAt,Integer promptTokens,Integer completionTokens,Integer totalTokens,Integer cachedTokens,Integer thoughtTokens,String status){this(id,conversationId,role,content,playbook,retrievedChunkIds,citations,createdAt,promptTokens,completionTokens,totalTokens,cachedTokens,thoughtTokens,status,null);}}
