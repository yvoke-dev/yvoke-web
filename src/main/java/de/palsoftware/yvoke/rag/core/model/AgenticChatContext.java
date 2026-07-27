package de.palsoftware.yvoke.rag.core.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class AgenticChatContext {
    private final List<UUID> retrievedChunkIds = new ArrayList<>();
    private final List<UUID> searchIds = new ArrayList<>();
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
