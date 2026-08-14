package de.palsoftware.yvoke.rag.core.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class AgenticChatContext implements SeenChunks {
    private final List<UUID> retrievedChunkIds = new ArrayList<>();
    private final List<UUID> searchIds = new ArrayList<>();

    /**
     * Chunks already rendered in full into this conversation. Deliberately NOT
     * {@link #retrievedChunkIds}, which is a different thing that merely looks like the same thing:
     * it is written by {@code SearchCorpusTool} <em>before</em> the result is rendered, so reading
     * it back would report every chunk of the very first search as already shown and elide the
     * whole result set — silently, since the output stays well-formed and every citation still
     * resolves. It is also telemetry: it keeps duplicates on purpose, is persisted to
     * {@code messages.retrieved_chunk_ids} and rendered per element in the admin log.
     *
     * <p>
     * A plain {@link HashSet} because one context is touched by exactly one thread for its whole
     * life — {@code executeToolCalls} is a sequential loop and a nested specialist runs on the same
     * thread. A concurrent set here would advertise sharing that must never happen.
     */
    private final Set<UUID> shownChunkIds = new HashSet<>();

    @Override
    public boolean firstSighting(UUID chunkId) {
        return shownChunkIds.add(chunkId);
    }

    private String clarifyingQuestion;
    private List<String> clarifyingOptions;
    private boolean haltRequested;

    public List<UUID> getRetrievedChunkIds() {
        return retrievedChunkIds;
    }

    public List<UUID> getSearchIds() {
        return searchIds;
    }

    public String getClarifyingQuestion() {
        return clarifyingQuestion;
    }

    public void setClarifyingQuestion(String clarifyingQuestion) {
        this.clarifyingQuestion = clarifyingQuestion;
    }

    public List<String> getClarifyingOptions() {
        return clarifyingOptions;
    }

    public void setClarifyingOptions(List<String> clarifyingOptions) {
        this.clarifyingOptions = clarifyingOptions;
    }

    /**
     * Signals the agentic loop to stop after the current tool batch (e.g. a review verdict was
     * submitted). A set clarifying question implies a halt too.
     */
    public boolean isHaltRequested() {
        return haltRequested || clarifyingQuestion != null;
    }

    public void setHaltRequested(boolean haltRequested) {
        this.haltRequested = haltRequested;
    }
}
