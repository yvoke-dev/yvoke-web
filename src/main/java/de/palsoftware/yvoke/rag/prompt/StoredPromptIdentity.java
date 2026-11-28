package de.palsoftware.yvoke.rag.prompt;

/**
 * Unfiltered identity and classification of a row in {@code system_prompts}, including retired
 * legacy rows (such as {@code CHAT}) that are hidden from normal read paths. Used by write-side
 * ownership and collision checks so legacy rows can neither be hijacked across areas nor silently
 * overwritten.
 */
public record StoredPromptIdentity(String name, String area, boolean ingestionType) {}
