package de.palsoftware.yvoke.rag.core.service;

import de.palsoftware.yvoke.llm.core.model.LlmToolCall;
import de.palsoftware.yvoke.llm.core.model.LlmToolCallDelta;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reconciles streamed {@link LlmToolCallDelta}s into finished {@link LlmToolCall}s for one agentic
 * turn (MNT-13, extracted from {@code RagService}). Handles both provider shapes: whole-call deltas
 * (Gemini — {@code complete=true}, identified by id, replace-on-redelivery) and incremental deltas
 * (OpenAI-style — positional {@code index}, argument fragments appended).
 *
 * <p>
 * This holds mutable per-turn state and is <b>not</b> thread-safe and <b>not</b> a Spring bean:
 * create one {@code ToolCallAccumulator} per streaming turn, driven from the single calling thread.
 */
final class ToolCallAccumulator {

    private static final class ToolCallBuilder {
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();
        String thoughtSignature;
        Map<String, Object> extraContent;
    }

    private final List<ToolCallBuilder> builders = new ArrayList<>();

    boolean isEmpty() {
        return builders.isEmpty();
    }

    /** Merges one streamed tool-call delta into the running set. */
    void accept(LlmToolCallDelta delta) {
        if (delta.complete()) {
            // Provider delivered a whole tool call (e.g. Gemini): identity is the call id and the
            // arguments are final, so replace rather than append (idempotent on re-delivery).
            int idx = -1;
            if (delta.id() != null) {
                for (int i = 0; i < builders.size(); i++) {
                    if (delta.id().equals(builders.get(i).id)) {
                        idx = i;
                        break;
                    }
                }
            }
            if (idx < 0) {
                idx = builders.size();
                builders.add(new ToolCallBuilder());
            }
            ToolCallBuilder builder = builders.get(idx);
            if (delta.id() != null) {
                builder.id = delta.id();
            }
            if (delta.name() != null) {
                builder.name = delta.name();
            }
            if (delta.argumentsDelta() != null) {
                builder.arguments.setLength(0);
                builder.arguments.append(delta.argumentsDelta());
            }
            if (delta.thoughtSignature() != null) {
                builder.thoughtSignature = delta.thoughtSignature();
            }
            return;
        }

        // Incremental (OpenAI-style) deltas: key by positional index and append argument fragments.
        int idx = delta.index();
        if (idx < 0) {
            if (delta.id() != null) {
                int foundIdx = -1;
                for (int i = 0; i < builders.size(); i++) {
                    if (delta.id().equals(builders.get(i).id)) {
                        foundIdx = i;
                        break;
                    }
                }
                idx = foundIdx >= 0 ? foundIdx : builders.size();
            } else {
                idx = Math.max(0, builders.size() - 1);
            }
        }
        while (builders.size() <= idx) {
            builders.add(new ToolCallBuilder());
        }
        ToolCallBuilder builder = builders.get(idx);
        if (delta.id() != null) {
            builder.id = delta.id();
        }
        if (delta.name() != null) {
            builder.name = delta.name();
        }
        if (delta.argumentsDelta() != null) {
            builder.arguments.append(delta.argumentsDelta());
        }
        if (delta.thoughtSignature() != null) {
            builder.thoughtSignature = delta.thoughtSignature();
        }
    }

    /** Finalizes the accumulated deltas into immutable tool-call records once the stream ends. */
    List<LlmToolCall> assemble() {
        List<LlmToolCall> toolCalls = new ArrayList<>();
        for (ToolCallBuilder builder : builders) {
            Map<String, Object> extraContent = builder.extraContent;
            if (builder.thoughtSignature != null) {
                if (extraContent == null) {
                    extraContent = new HashMap<>();
                }
                extraContent.put("thoughtSignature", builder.thoughtSignature);
            }
            toolCalls.add(new LlmToolCall(builder.id, "function", builder.name,
                builder.arguments.toString(), extraContent));
        }
        return toolCalls;
    }
}
