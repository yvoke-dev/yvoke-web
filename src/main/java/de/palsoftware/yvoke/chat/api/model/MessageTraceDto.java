package de.palsoftware.yvoke.chat.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.palsoftware.yvoke.chat.core.model.MessageTrace;
import de.palsoftware.yvoke.chat.orchestration.AgentRun;
import de.palsoftware.yvoke.chat.orchestration.AgentStep;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)public record MessageTraceDto(String mode,UUID messageId,UUID conversationId,String status,String content,String model,TokenUsageDto tokens,List<UUID>retrievedChunkIds,List<ToolCallTraceDto>toolCalls,AgentRun agentRun,List<AgentStep>steps){

public static MessageTraceDto from(MessageTrace trace){TokenUsageDto tokens=new TokenUsageDto(trace.promptTokens()!=null?trace.promptTokens():0,trace.completionTokens()!=null?trace.completionTokens():0,trace.totalTokens()!=null?trace.totalTokens():0,trace.cachedTokens()!=null?trace.cachedTokens():0,trace.thoughtTokens()!=null?trace.thoughtTokens():0);

List<ToolCallTraceDto>toolCalls=null;if(trace.toolCalls()!=null){toolCalls=trace.toolCalls().stream().map(r->new ToolCallTraceDto(r.seq(),r.toolCallId(),r.toolName(),r.arguments()!=null?r.arguments():"{}",r.result()!=null?r.result():"",r.isError())).toList();}

return new MessageTraceDto(trace.mode(),trace.messageId(),trace.conversationId(),trace.status(),trace.content(),trace.model(),tokens,trace.retrievedChunkIds(),toolCalls,trace.agentRun(),trace.steps());}}
