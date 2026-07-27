package de.palsoftware.yvoke.llm.core.model;

/**
 * What an AI gateway did with one HTTP request/response pair.
 *
 * <p>
 * Deliberately not named HIT/MISS. Those are Cloudflare's wire tokens, and this concept has to stay
 * clearly distinct from {@link LlmUsage#cachedTokens()}, which is Gemini's <i>context</i> caching —
 * a completely different thing: those tokens <b>were</b> billed, just at the discounted rate.
 * REPLAYED/FORWARDED name the gateway's <i>action</i> (edge-side), which is the axis on which the
 * two differ. No constant here contains the word "cache".
 */
public enum GatewayCacheStatus {

    /**
     * {@code cf-aig-cache-status: HIT} — the gateway returned a stored response body. The provider
     * never saw the request and billed nothing, so this call's tokens are <b>not</b> billable.
     */
    REPLAYED,

    /** {@code cf-aig-cache-status: MISS} — forwarded to the provider, and billed. */
    FORWARDED,

    /**
     * The header was present but carried a value outside the whitelist. Billed in full: a value the
     * app cannot parse must never zero a real charge.
     */
    UNRECOGNIZED
}
