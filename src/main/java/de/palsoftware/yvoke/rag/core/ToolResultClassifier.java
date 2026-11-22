package de.palsoftware.yvoke.rag.core;

import jakarta.annotation.Nullable;

/**
 * Classifies whether a tool's returned string payload represents an error. Shared by in-app RAG
 * tool execution and the MCP sync tool adapter so both surfaces agree on error signalling.
 */
public final class ToolResultClassifier {

    private ToolResultClassifier() {}

    /**
     * Recognizes a tool-returned error payload across both raw callback strings ({@code "Error: …"}
     * / {@code "ERROR: …"}) and JSON-encoded strings ({@code "\"Error: …\""}) produced by Spring
     * AI's {@code DefaultToolCallResultConverter}.
     */
    public static boolean isErrorResult(@Nullable String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return false;
        }
        String text = rawText.trim();
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length() - 1).trim();
        }
        return text.startsWith("Error:") || text.startsWith("ERROR:") || text.startsWith("Error ");
    }
}
