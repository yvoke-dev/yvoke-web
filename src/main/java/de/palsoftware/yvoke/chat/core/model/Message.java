package de.palsoftware.yvoke.chat.core.model;

import de.palsoftware.yvoke.rag.core.service.CitationVerifier.CitationCheckResult;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Message(UUID id,UUID conversationId,String role,String content,String playbook,Integer playbookVersion,List<UUID>retrievedChunkIds,List<CitationCheckResult>citations,Instant createdAt,Integer promptTokens,Integer completionTokens,Integer totalTokens,Integer cachedTokens,Integer thoughtTokens,String status,String model){

public Message{if(playbookVersion!=null&&playbookVersion<1){throw new IllegalArgumentException("playbookVersion must be >= 1");}playbook=(playbook!=null&&!playbook.isBlank())?playbook.trim():null;playbookVersion=playbook!=null?playbookVersion:null;}

public Message(UUID id,UUID conversationId,String role,String content,String playbook,List<UUID>retrievedChunkIds,List<CitationCheckResult>citations,Instant createdAt,Integer promptTokens,Integer completionTokens,Integer totalTokens,Integer cachedTokens,Integer thoughtTokens,String status,String model){this(id,conversationId,role,content,playbook,null,retrievedChunkIds,citations,createdAt,promptTokens,completionTokens,totalTokens,cachedTokens,thoughtTokens,status,model);}

public Message(UUID id,UUID conversationId,String role,String content,List<UUID>retrievedChunkIds,List<CitationCheckResult>citations,Instant createdAt){this(id,conversationId,role,content,null,null,retrievedChunkIds,citations,createdAt,null,null,null,null,null,"done",null);}

public Message(UUID id,UUID conversationId,String role,String content,List<UUID>retrievedChunkIds,List<CitationCheckResult>citations,Instant createdAt,Integer promptTokens,Integer completionTokens,Integer totalTokens){this(id,conversationId,role,content,null,null,retrievedChunkIds,citations,createdAt,promptTokens,completionTokens,totalTokens,null,null,"done",null);}

public Message(UUID id,UUID conversationId,String role,String content,List<UUID>retrievedChunkIds,List<CitationCheckResult>citations,Instant createdAt,Integer promptTokens,Integer completionTokens,Integer totalTokens,Integer cachedTokens,Integer thoughtTokens){this(id,conversationId,role,content,null,null,retrievedChunkIds,citations,createdAt,promptTokens,completionTokens,totalTokens,cachedTokens,thoughtTokens,"done",null);}

public Message(UUID id,UUID conversationId,String role,String content,String playbook,List<UUID>retrievedChunkIds,List<CitationCheckResult>citations,Instant createdAt){this(id,conversationId,role,content,playbook,null,retrievedChunkIds,citations,createdAt,null,null,null,null,null,"done",null);}

public Message(UUID id,UUID conversationId,String role,String content,String playbook,Integer playbookVersion,List<UUID>retrievedChunkIds,List<CitationCheckResult>citations,Instant createdAt){this(id,conversationId,role,content,playbook,playbookVersion,retrievedChunkIds,citations,createdAt,null,null,null,null,null,"done",null);}

public Message(UUID id,UUID conversationId,String role,String content,String playbook,List<UUID>retrievedChunkIds,List<CitationCheckResult>citations,Instant createdAt,Integer promptTokens,Integer completionTokens,Integer totalTokens,Integer cachedTokens,Integer thoughtTokens){this(id,conversationId,role,content,playbook,null,retrievedChunkIds,citations,createdAt,promptTokens,completionTokens,totalTokens,cachedTokens,thoughtTokens,"done",null);}

public Message(UUID id,UUID conversationId,String role,String content,String playbook,Integer playbookVersion,List<UUID>retrievedChunkIds,List<CitationCheckResult>citations,Instant createdAt,Integer promptTokens,Integer completionTokens,Integer totalTokens,Integer cachedTokens,Integer thoughtTokens){this(id,conversationId,role,content,playbook,playbookVersion,retrievedChunkIds,citations,createdAt,promptTokens,completionTokens,totalTokens,cachedTokens,thoughtTokens,"done",null);}

public Message(UUID id,UUID conversationId,String role,String content,String playbook,List<UUID>retrievedChunkIds,List<CitationCheckResult>citations,Instant createdAt,Integer promptTokens,Integer completionTokens,Integer totalTokens,Integer cachedTokens,Integer thoughtTokens,String status){this(id,conversationId,role,content,playbook,null,retrievedChunkIds,citations,createdAt,promptTokens,completionTokens,totalTokens,cachedTokens,thoughtTokens,status,null);}}
