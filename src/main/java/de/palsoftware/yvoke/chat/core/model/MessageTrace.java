package de.palsoftware.yvoke.chat.core.model;

import de.palsoftware.yvoke.chat.orchestration.AgentRun;
import de.palsoftware.yvoke.chat.orchestration.AgentStep;
import java.util.List;
import java.util.UUID;

public record MessageTrace(String mode,UUID messageId,UUID conversationId,String status,String content,String model,Integer promptTokens,Integer completionTokens,Integer totalTokens,Integer cachedTokens,Integer thoughtTokens,List<UUID>retrievedChunkIds,List<ToolCallRecord>toolCalls,AgentRun agentRun,List<AgentStep>steps){

public static MessageTrace forSingle(Message message,List<ToolCallRecord>toolCalls){return new MessageTrace("single",message.id(),message.conversationId(),message.status(),message.content(),message.model(),message.promptTokens(),message.completionTokens(),message.totalTokens(),message.cachedTokens(),message.thoughtTokens(),message.retrievedChunkIds()!=null?message.retrievedChunkIds():List.of(),toolCalls!=null?toolCalls:List.of(),null,null);}

public static MessageTrace forMas(Message message,AgentRun agentRun,List<AgentStep>steps){Integer prompt=agentRun!=null&&agentRun.promptTokens()!=null?agentRun.promptTokens():message.promptTokens();Integer completion=agentRun!=null&&agentRun.completionTokens()!=null?agentRun.completionTokens():message.completionTokens();Integer total=agentRun!=null&&agentRun.totalTokens()!=null?agentRun.totalTokens():message.totalTokens();Integer cached=agentRun!=null&&agentRun.cachedTokens()!=null?agentRun.cachedTokens():message.cachedTokens();Integer thought=agentRun!=null&&agentRun.thoughtTokens()!=null?agentRun.thoughtTokens():message.thoughtTokens();

return new MessageTrace("mas",message.id(),message.conversationId(),message.status(),message.content(),message.model(),prompt,completion,total,cached,thought,null,null,agentRun,steps!=null?steps:List.of());}}
