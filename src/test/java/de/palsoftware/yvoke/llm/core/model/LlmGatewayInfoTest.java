package de.palsoftware.yvoke.llm.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins the classification of the Cloudflare AI Gateway response headers.
 *
 * <p>
 * This class decides whether a call is billed, from a header an upstream proxy controls, so the
 * whole surface is deliberately fail-closed: only the exact token {@code HIT} may zero a charge.
 */
class LlmGatewayInfoTest {

    @Test
    void testHitIsReplayed() {
        LlmGatewayInfo info = LlmGatewayInfo.fromHeaders(Map.of("cf-aig-cache-status", "HIT"));

        assertEquals(GatewayCacheStatus.REPLAYED, info.cacheStatus());
        assertTrue(info.replayed());
    }

    @Test
    void testMissIsForwarded() {
        LlmGatewayInfo info = LlmGatewayInfo.fromHeaders(Map.of("cf-aig-cache-status", "MISS"));

        assertEquals(GatewayCacheStatus.FORWARDED, info.cacheStatus());
        assertFalse(info.replayed());
    }

    /**
     * The SDK copies header names verbatim, and they arrive lowercase over HTTP/2 — but
     * {@code com.sun.net.httpserver.Headers}, which every mock-server test in this repo uses,
     * rewrites {@code set("cf-aig-cache-status", …)} to {@code Cf-aig-cache-status}. An exact-case
     * lookup is therefore green in production and red in tests, which invites "fixing" the test.
     * Lookup must be case-insensitive on the header NAME.
     */
    @Test
    void testHeaderNameLookupIsCaseInsensitive() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Cf-aig-cache-status", "HIT");
        headers.put("Cf-Aig-Log-Id", "01KZ6A5K");

        LlmGatewayInfo info = LlmGatewayInfo.fromHeaders(headers);

        assertEquals(GatewayCacheStatus.REPLAYED, info.cacheStatus());
        assertEquals("01KZ6A5K", info.logId());
    }

    @Test
    void testHeaderValueIsCaseInsensitiveAndTrimmed() {
        LlmGatewayInfo info = LlmGatewayInfo.fromHeaders(Map.of("cf-aig-cache-status", " hit "));

        assertEquals(GatewayCacheStatus.REPLAYED, info.cacheStatus());
    }

    /**
     * A value the app cannot parse must never zero a real charge — anything outside the whitelist
     * is UNRECOGNIZED, and UNRECOGNIZED is billed in full.
     */
    @Test
    void testUnknownValueIsUnrecognizedAndNotReplayed() {
        for (String value : new String[] {"STALE", "BYPASS", "REVALIDATED", "hit-ish", "1", "-"}) {
            LlmGatewayInfo info = LlmGatewayInfo.fromHeaders(Map.of("cf-aig-cache-status", value));

            assertEquals(GatewayCacheStatus.UNRECOGNIZED, info.cacheStatus(),
                "value '" + value + "' must not classify");
            assertFalse(info.replayed(), "value '" + value + "' must never zero a charge");
        }
    }

    /** No cache-status header at all means no AI gateway in the path — not a miss. */
    @Test
    void testAbsentHeaderYieldsNull() {
        assertNull(LlmGatewayInfo.fromHeaders(Map.of()));
        assertNull(LlmGatewayInfo.fromHeaders(null));
        assertNull(LlmGatewayInfo.fromHeaders(Map.of("content-type", "application/json")));
        assertNull(LlmGatewayInfo.fromHeaders(Map.of("cf-aig-cache-status", "   ")));
    }

    @Test
    void testLogIdIsTrimmedAndTruncated() {
        String overlong = "x".repeat(200);

        LlmGatewayInfo info = LlmGatewayInfo.fromHeaders(
            Map.of("cf-aig-cache-status", "MISS", "cf-aig-log-id", "  " + overlong + "  "));

        assertEquals(64, info.logId().length());
    }

    @Test
    void testBlankLogIdBecomesNull() {
        LlmGatewayInfo info = LlmGatewayInfo
            .fromHeaders(Map.of("cf-aig-cache-status", "MISS", "cf-aig-log-id", "   "));

        assertNull(info.logId());
    }

    @Test
    void testMissingLogIdIsNullButStatusStillClassifies() {
        LlmGatewayInfo info = LlmGatewayInfo.fromHeaders(Map.of("cf-aig-cache-status", "HIT"));

        assertNull(info.logId());
        assertTrue(info.replayed());
    }
}
