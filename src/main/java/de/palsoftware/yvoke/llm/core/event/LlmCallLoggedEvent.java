package de.palsoftware.yvoke.llm.core.event;

import de.palsoftware.yvoke.llm.core.model.GatewayCacheStatus;
import java.util.UUID;

/**
 * One LLM call, as observed by {@code AccountingLlmClient}.
 *
 * @param gatewayCacheStatus what an AI gateway did with the call, or {@code null} when none was in
 *        the path. {@link GatewayCacheStatus#REPLAYED} means the provider was never reached and
 *        billed nothing, so the tokens below were reported by a stored response body rather than
 *        purchased.
 * @param gatewayLogId {@code cf-aig-log-id}, for reconciling this row against the gateway's own log
 */
public record LlmCallLoggedEvent(UUID conversationId,UUID messageId,UUID agentRunId,UUID userId,String source,String role,String model,int promptTokens,int completionTokens,int cachedTokens,int thoughtTokens,int totalTokens,Integer durationMs,GatewayCacheStatus gatewayCacheStatus,String gatewayLogId){

public LlmCallLoggedEvent(UUID conversationId,UUID messageId,UUID agentRunId,UUID userId,String source,String role,String model,int promptTokens,int completionTokens,int cachedTokens,int thoughtTokens,int totalTokens,Integer durationMs){this(conversationId,messageId,agentRunId,userId,source,role,model,promptTokens,completionTokens,cachedTokens,thoughtTokens,totalTokens,durationMs,null,null);}

public LlmCallLoggedEvent(String source,String role,String model,int promptTokens,int completionTokens,int cachedTokens,int thoughtTokens){this(null,null,null,null,source,role,model,promptTokens,completionTokens,cachedTokens,thoughtTokens,promptTokens+completionTokens,null);}

public LlmCallLoggedEvent(UUID conversationId,UUID messageId,UUID agentRunId,String source,String role,String model,int promptTokens,int completionTokens,int cachedTokens,int thoughtTokens){this(conversationId,messageId,agentRunId,null,source,role,model,promptTokens,completionTokens,cachedTokens,thoughtTokens,promptTokens+completionTokens,null);}

public LlmCallLoggedEvent(UUID conversationId,UUID messageId,UUID agentRunId,UUID userId,String source,String role,String model,int promptTokens,int completionTokens,int cachedTokens,int thoughtTokens){this(conversationId,messageId,agentRunId,userId,source,role,model,promptTokens,completionTokens,cachedTokens,thoughtTokens,promptTokens+completionTokens,null);}

/** True only when the provider was never called, and therefore charged nothing. */
public boolean replayedByGateway(){return gatewayCacheStatus==GatewayCacheStatus.REPLAYED;}}
