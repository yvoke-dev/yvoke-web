package de.palsoftware.yvoke.llm.core.model;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Gateway-observed facts about <b>one</b> HTTP call, read from the response headers.
 *
 * <p>
 * A {@code null} instance means "no AI gateway in the path" (plain Gemini, OpenRouter, Voyage) —
 * which is not the same as a miss, and the two must stay distinguishable so that a gateway that
 * silently stops stamping headers shows up as a regression rather than as free traffic.
 *
 * @param cacheStatus what the gateway did with the request
 * @param logId {@code cf-aig-log-id}, the key that joins this call to its entry in Cloudflare's own
 *        log — the only way to reconcile this ledger against their dashboard
 */
public record LlmGatewayInfo(GatewayCacheStatus cacheStatus,String logId){

private static final String CACHE_STATUS_HEADER="cf-aig-cache-status";private static final String LOG_ID_HEADER="cf-aig-log-id";

/** Bound on the free-form log id copied out of the network response. */
private static final int MAX_LOG_ID_LENGTH=64;

private static final String HIT="HIT";private static final String MISS="MISS";

/**
 * Classifies the gateway headers of one response, or returns {@code null} when no
 * {@code cf-aig-cache-status} is present — i.e. the call did not traverse an AI gateway.
 *
 * <p>
 * Case-insensitive on both header <b>name</b> and <b>value</b>. Names arrive lowercase over HTTP/2,
 * but {@code com.sun.net.httpserver} — the mock-server rig used by the client tests — rewrites them
 * to {@code Cf-aig-…}, so an exact-case lookup would be green in production and red in tests.
 *
 * <p>
 * The value whitelist is strict and fail-closed: only {@code HIT} and {@code MISS} classify, and
 * everything else becomes {@link GatewayCacheStatus#UNRECOGNIZED}, which is billed. This header is
 * untrusted upstream input driving a billing decision.
 */
public static LlmGatewayInfo fromHeaders(Map<String,String>headers){if(headers==null||headers.isEmpty()){return null;}

Map<String,String>byLowerName=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);byLowerName.putAll(headers);

String rawStatus=byLowerName.get(CACHE_STATUS_HEADER);if(rawStatus==null||rawStatus.isBlank()){return null;}

String normalized=rawStatus.trim().toUpperCase(Locale.ROOT);GatewayCacheStatus status=switch(normalized){case HIT->GatewayCacheStatus.REPLAYED;case MISS->GatewayCacheStatus.FORWARDED;default->GatewayCacheStatus.UNRECOGNIZED;};

return new LlmGatewayInfo(status,cleanLogId(byLowerName.get(LOG_ID_HEADER)));}

private static String cleanLogId(String raw){if(raw==null){return null;}String trimmed=raw.trim();if(trimmed.isEmpty()){return null;}return trimmed.length()>MAX_LOG_ID_LENGTH?trimmed.substring(0,MAX_LOG_ID_LENGTH):trimmed;}

/** True only when the provider was never called, and therefore billed nothing. */
public boolean replayed(){return cacheStatus==GatewayCacheStatus.REPLAYED;}}
