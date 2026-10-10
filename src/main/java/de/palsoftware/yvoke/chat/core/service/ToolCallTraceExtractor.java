package de.palsoftware.yvoke.chat.core.service;

import de.palsoftware.yvoke.chat.core.model.ToolCallRecord;
import de.palsoftware.yvoke.llm.core.model.LlmMessage;
import de.palsoftware.yvoke.llm.core.model.LlmToolCall;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ToolCallTraceExtractor {

    private static class PendingToolCall {
        final LlmToolCall toolCall;
        LlmMessage matchedResponse;

        PendingToolCall(LlmToolCall toolCall) {
            this.toolCall = toolCall;
        }
    }

    public List<ToolCallRecord> extract(UUID messageId, List<LlmMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return Collections.emptyList();
        }

        List<ToolCallRecord> records = new ArrayList<>();
        List<PendingToolCall> pendingCalls = new ArrayList<>();
        int seq = 0;

        for (LlmMessage msg : messages) {
            if (msg == null) {
                continue;
            }

            if ("tool".equalsIgnoreCase(msg.role())) {
                matchToolResponse(pendingCalls, msg);
            } else {
                // If we encounter a non-tool message (e.g. next assistant turn, user, system),
                // flush any pending tool calls from the previous turn before processing
                seq = flushPending(records, pendingCalls, messageId, seq);

                if ("assistant".equalsIgnoreCase(msg.role()) && msg.toolCalls() != null
                    && !msg.toolCalls().isEmpty()) {
                    for (LlmToolCall tc : msg.toolCalls()) {
                        if (tc != null) {
                            pendingCalls.add(new PendingToolCall(tc));
                        }
                    }
                }
            }
        }

        // Flush any remaining pending tool calls
        flushPending(records, pendingCalls, messageId, seq);

        return records;
    }

    private void matchToolResponse(List<PendingToolCall> pendingCalls, LlmMessage toolMsg) {
        if (pendingCalls.isEmpty()) {
            return;
        }

        String callId = toolMsg.toolCallId();
        PendingToolCall matched = null;

        // 1. Try matching by toolCallId if provided
        if (callId != null && !callId.isBlank()) {
            for (PendingToolCall pending : pendingCalls) {
                if (pending.matchedResponse == null && callId.equals(pending.toolCall.id())) {
                    matched = pending;
                    break;
                }
            }
        }

        // 2. If no ID match or IDs are missing, match by sequential order with first unmatched
        if (matched == null) {
            for (PendingToolCall pending : pendingCalls) {
                if (pending.matchedResponse == null) {
                    matched = pending;
                    break;
                }
            }
        }

        if (matched != null) {
            matched.matchedResponse = toolMsg;
        }
    }

    private int flushPending(List<ToolCallRecord> records, List<PendingToolCall> pendingCalls,
        UUID messageId, int startSeq) {
        int seq = startSeq;
        for (PendingToolCall pending : pendingCalls) {
            String result =
                pending.matchedResponse != null ? pending.matchedResponse.content() : null;
            boolean isError = pending.matchedResponse == null || pending.matchedResponse.isError()
                || (result != null && result.startsWith("Error:"));

            String callId = pending.toolCall.id();
            if (callId == null || callId.isBlank()) {
                callId = "call_" + seq;
            }
            String toolName = pending.toolCall.name();
            if (toolName == null || toolName.isBlank()) {
                toolName = "unknown";
            }

            records.add(new ToolCallRecord(UUID.randomUUID(), messageId, seq++, callId, toolName,
                pending.toolCall.arguments(), result, isError, Instant.now()));
        }
        pendingCalls.clear();
        return seq;
    }
}
