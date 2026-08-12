package de.palsoftware.yvoke.llm.core;

import com.azure.core.exception.HttpResponseException;
import com.azure.core.http.HttpHeaderName;
import com.azure.core.http.HttpHeaders;
import java.util.Locale;

/**
 * What a provider said about a rate-limit rejection, read from its response headers.
 *
 * <p>
 * The body of an Azure 429 says only {@code "Your requests to <deployment> ... have exceeded rate
 * limit."} — it names neither which budget was exhausted nor when to come back. That left a real
 * incident undiagnosable from the record: a deployment sitting at 7 requests/minute against a 250
 * RPM quota, its portal reporting no rate limiting at all, and the app failing on 429 regardless.
 * The headers carry what the body omits, so they are read once here and used by both consumers —
 * {@link LlmRetry} to wait the interval the server actually asked for, and
 * {@link LlmFailureSummary} to put it in front of an admin. One extractor rather than two, because
 * the alternative is two hand-maintained readers that quietly disagree.
 *
 * <p>
 * Header lookup is case-insensitive throughout: HTTP/2 sends lowercase names while a mock server
 * built on {@code com.sun.net.httpserver} rewrites them to {@code Retry-After}, so an exact-case
 * match is green in production and red in tests, or the reverse.
 */
public record ProviderRateLimit(Long retryAfterSeconds,String remainingRequests,String remainingTokens,String requestId){

/** An Azure {@code Retry-After} of an hour must not become an hour-long sleep. */
static final long MAX_RETRY_AFTER_SECONDS=300L;

private static final ProviderRateLimit EMPTY=new ProviderRateLimit(null,null,null,null);

public boolean isEmpty(){return retryAfterSeconds==null&&remainingRequests==null&&remainingTokens==null&&requestId==null;}

/**
 * The interval the server asked us to wait, clamped to {@link #MAX_RETRY_AFTER_SECONDS}, or
 * {@code null} when it did not say.
 */
public Long retryAfterMillisClamped(){if(retryAfterSeconds==null||retryAfterSeconds<0){return null;}return Math.min(retryAfterSeconds,MAX_RETRY_AFTER_SECONDS)*1000L;}

/** One line for the log and the admin trace; empty string when the provider said nothing. */
public String describe(){if(isEmpty()){return"";}StringBuilder sb=new StringBuilder("rate-limit:");if(retryAfterSeconds!=null){sb.append(" retry-after=").append(retryAfterSeconds).append('s');}if(remainingRequests!=null){sb.append(" remaining-requests=").append(remainingRequests);}if(remainingTokens!=null){sb.append(" remaining-tokens=").append(remainingTokens);}if(requestId!=null){
// The id Azure support asks for. Useless to us, decisive in a support case.
sb.append(" request-id=").append(requestId);}return sb.toString();}

/**
 * Reads the rate-limit headers off the first provider HTTP failure in the cause chain. Never null;
 * {@link #isEmpty()} when there is nothing to report.
 */
public static ProviderRateLimit from(Throwable t){for(Throwable c=t;c!=null;c=c.getCause()){if(c instanceof HttpResponseException http&&http.getResponse()!=null){return fromHeaders(http.getResponse().getHeaders());}if(c.getCause()==c){break;}}return EMPTY;}

private static ProviderRateLimit fromHeaders(HttpHeaders headers){if(headers==null){return EMPTY;}return new ProviderRateLimit(parseSeconds(value(headers,"retry-after")),value(headers,"x-ratelimit-remaining-requests"),value(headers,"x-ratelimit-remaining-tokens"),value(headers,"apim-request-id"));}

private static String value(HttpHeaders headers,String name){
// HttpHeaderName matches case-insensitively by construction, which a hand-rolled scan over
// getName() does not: azure-core preserves the sender's capitalisation, so HTTP/2's
// lowercase and a mock server's "Retry-After" are the same header to the SDK and two
// different ones to String.equals.
String v=headers.getValue(HttpHeaderName.fromString(name));return v==null||v.isBlank()?null:v.strip();}

/**
 * {@code Retry-After} is either a count of seconds or an HTTP date. Only the numeric form is
 * honoured; a date falls back to the caller's own schedule rather than risking a parse that yields
 * a nonsensical wait, which is what {@code ConfluenceClientService} already does.
 */
private static Long parseSeconds(String raw){if(raw==null){return null;}try{long seconds=Long.parseLong(raw.strip().toLowerCase(Locale.ROOT));return seconds<0?null:seconds;}catch(NumberFormatException e){return null;}}}
