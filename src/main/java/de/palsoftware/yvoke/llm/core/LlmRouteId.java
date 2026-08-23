package de.palsoftware.yvoke.llm.core;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The provider clients a model can be routed to.
 *
 * <p>
 * One vocabulary for both {@code app.ai.provider} (the default route) and
 * {@code app.ai.model-routes} (per-model overrides), so the two cannot drift into naming the same
 * client differently. Retired providers are deliberately absent rather than present-and-rejected:
 * an id that is not in this enum cannot be routed to at all, which is what stops a stale manifest
 * quietly reaching a client that is no longer wired.
 */
public enum LlmRouteId {

    /** Google Gemini, direct. */
    GEMINI("gemini"),

    /** Azure OpenAI through the Responses API. */
    AZURE_OPENAI_RESPONSES("azure-openai-responses");

    private final String wire;

    LlmRouteId(String wire) {
        this.wire = wire;
    }

    /** The spelling an operator writes in a manifest or an environment variable. */
    public String wire() {
        return wire;
    }

    /**
     * Case-insensitive, because the value is typed by hand into a compose file or a Kubernetes
     * manifest where {@code Azure-OpenAI-Responses} is a natural spelling. Returns empty rather
     * than throwing so each caller can say what an unrecognised value means for it.
     */
    public static Optional<LlmRouteId> fromWire(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String trimmed = value.trim();
        return Arrays.stream(values()).filter(id -> id.wire.equalsIgnoreCase(trimmed)).findFirst();
    }

    /** For error messages, so a rejection can say what the operator could have written instead. */
    public static String wireSpellings() {
        return Arrays.stream(values()).map(LlmRouteId::wire).collect(Collectors.joining(", "));
    }
}
