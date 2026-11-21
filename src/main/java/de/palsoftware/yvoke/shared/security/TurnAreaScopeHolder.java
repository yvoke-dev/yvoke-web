package de.palsoftware.yvoke.shared.security;

import java.util.Set;

/**
 * Thread-local holder carrying the active agentic turn's allowed lowercase area scope (e.g. the
 * single area of a selected playbook or orchestrator profile) so scanned {@code @Tool} beans
 * invoked via Spring AI's {@code ToolCallback.call(String)} enforce the exact same turn-level
 * boundary as {@code ContextAwareToolCallback} tools.
 */
public final class TurnAreaScopeHolder {

    private static final ThreadLocal<Set<String>> ALLOWED_AREAS_LOWER = new ThreadLocal<>();

    private TurnAreaScopeHolder() {}

    public static void set(Set<String> allowedAreasLower) {
        if (allowedAreasLower == null) {
            ALLOWED_AREAS_LOWER.remove();
        } else {
            ALLOWED_AREAS_LOWER.set(allowedAreasLower);
        }
    }

    public static Set<String> get() {
        return ALLOWED_AREAS_LOWER.get();
    }

    public static void clear() {
        ALLOWED_AREAS_LOWER.remove();
    }
}
