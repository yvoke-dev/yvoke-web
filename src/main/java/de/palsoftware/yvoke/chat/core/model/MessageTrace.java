package de.palsoftware.yvoke.chat.core.model;

import de.palsoftware.yvoke.chat.orchestration.AgentRun;
import de.palsoftware.yvoke.chat.orchestration.AgentStep;
import java.util.List;
import java.util.UUID;

public record MessageTrace(String mode,UUID messageId,UUID conversationId,String status,String content,String model,Integer promptTokens,Integer completionTokens,Integer totalTokens,Integer cachedTokens,Integer thoughtTokens,List<UUID>retrievedChunkIds,List<ToolCallRecord>toolCalls,AgentRun agentRun,List<AgentStep>steps){

public static MessageTrace forSingle(Message message,List<ToolCallRecord>toolCalls){return new MessageTrace("single",message.id(),message.conversationId(),message.status(),message.content(),message.model(),message.promptTokens(),message.completionTokens(),message.totalTokens(),message.cachedTokens(),message.thoughtTokens(),message.retrievedChunkIds()!=null?message.retrievedChunkIds():List.of(),toolCalls!=null?toolCalls:List.of(),null,null);}

public static MessageTrace forMas(Message message,AgentRun agentRun,List<AgentStep>steps){Integer prompt=message.promptTokens()!=null&&message.promptTokens()>0?message.promptTokens():(agentRun!=null&&agentRun.promptTokens()!=null?agentRun.promptTokens():0);Integer completion=message.completionTokens()!=null&&message.completionTokens()>0?message.completionTokens():(agentRun!=null&&agentRun.completionTokens()!=null?agentRun.completionTokens():0);Integer total=message.totalTokens()!=null&&message.totalTokens()>0?message.totalTokens():(agentRun!=null&&agentRun.totalTokens()!=null?agentRun.totalTokens():0);Integer cached=message.cachedTokens()!=null&&message.cachedTokens()>0?message.cachedTokens():(agentRun!=null&&agentRun.cachedTokens()!=null?agentRun.cachedTokens():0);Integer thought=message.thoughtTokens()!=null&&message.thoughtTokens()>0?message.thoughtTokens():(agentRun!=null&&agentRun.thoughtTokens()!=null?agentRun.thoughtTokens():0);

return new MessageTrace("mas",message.id(),message.conversationId(),message.status(),message.content(),message.model(),prompt,completion,total,cached,thought,message.retrievedChunkIds()!=null?message.retrievedChunkIds():List.of(),null,agentRun,steps!=null?steps:List.of());}}
