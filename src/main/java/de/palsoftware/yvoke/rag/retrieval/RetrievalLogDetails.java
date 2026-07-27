package de.palsoftware.yvoke.rag.retrieval;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RetrievalLogDetails(UUID id,UUID messageId,UUID collectionId,String collectionName,String tag,String pools,String finalVal,String rerank,Instant createdAt,String messageContent,Integer feedbackRating,String feedbackComment,List<UUID>retrievedChunkIds){
// Legacy constructor mapping String collection to generated UUID collectionId
public RetrievalLogDetails(UUID id,UUID messageId,String collection,String tag,String pools,String finalVal,String rerank,Instant createdAt,String messageContent,Integer feedbackRating,String feedbackComment,List<UUID>retrievedChunkIds){this(id,messageId,UUID.randomUUID(),collection,tag,pools,finalVal,rerank,createdAt,messageContent,feedbackRating,feedbackComment,retrievedChunkIds);}

public String collection(){return collectionName;}

public String getTruncatedMessageContent(int maxChars){if(messageContent==null){return null;}if(messageContent.length()<=maxChars){return messageContent;}return messageContent.substring(0,maxChars)+"...";}}
