package de.palsoftware.yvoke.llm.core;

import com.azure.core.exception.HttpResponseException;
import com.azure.core.http.HttpHeaderName;
import com.google.genai.errors.ApiException;
import com.openai.errors.OpenAIServiceException;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * One provider SDK's account of an HTTP failure, normalised across every SDK this app talks to.
 *
 * <p>
 * This exists because there were three hand-written readers of the same thing and they had already
 * drifted. {@link LlmRetry} was taught to recognise openai-java's exception when the OpenRouter and
 * Responses clients were added; {@link ProviderRateLimit} and {@link LlmFailureSummary} were not.
 * So a 429 from those clients was correctly classified as retryable and then stripped of everything
 * the response actually said — {@code agent_steps.error} lost the provider block, and the admin
 * line degraded from {@code HTTP 429 (rate limit / quota exhausted)} to a bare class name. Exactly
 * the failure {@code ProviderRateLimit}'s own javadoc warns about: "the alternative is two
 * hand-maintained readers that quietly disagree".
 *
 * <p>
 * Adding a provider now means adding one branch to {@link #at}, and every consumer gains it at
 * once.
 *
 * @param type the exception's simple class name, for the admin trace
 * @param code the HTTP status, the one field every SDK supplies reliably
 * @param status the provider's own status token, when it parses one out ({@code null} otherwise)
 * @param message the provider's own error message, when it parses one out
 * @param raw the exception's rendered message, always present
 * @param headerLookup case-insensitive header reader, or {@code null} when the SDK exposes no
 *        response headers. google-genai does not, which is why a Gemini rate limit can never carry
 *        a {@code Retry-After}: the information is discarded before it reaches us, not dropped
 *        here.
 */
public record ProviderFault(String type,int code,String status,String message,String raw,UnaryOperator<String>headerLookup){

/**
 * The first provider HTTP failure in the cause chain, or {@code null} when there is none. Guards
 * against a self-referencing cause, which is otherwise an infinite loop.
 */
public static ProviderFault of(Throwable t){for(Throwable c=t;c!=null;c=c.getCause()){ProviderFault fault=at(c);if(fault!=null){return fault;}if(c.getCause()==c){break;}}return null;}

/**
 * Reads one link of a cause chain, without walking it. Separate from {@link #of} because
 * {@link LlmRetry} interleaves this check with its own per-link type tests and must not skip ahead.
 */
public static ProviderFault at(Throwable c){if(c instanceof ApiException api){
// google-genai exposes no response headers at all.
return new ProviderFault(api.getClass().getSimpleName(),api.code(),api.status(),api.message(),api.getMessage(),null);}if(c instanceof HttpResponseException http&&http.getResponse()!=null){return new ProviderFault(c.getClass().getSimpleName(),http.getResponse().getStatusCode(),null,null,c.getMessage(),name->http.getResponse().getHeaders().getValue(HttpHeaderName.fromString(name)));}if(c instanceof OpenAIServiceException openAi){return new ProviderFault(c.getClass().getSimpleName(),openAi.statusCode(),optional(openAi::type),optional(openAi::code),c.getMessage(),name->firstHeader(openAi,name));}return null;}

/**
 * Reads an openai-java structured error field defensively.
 *
 * <p>
 * The accessors are typed {@code Optional<String>}, which reads as "absent is expected" and is not
 * what the SDK does: a field missing from the response body makes the getter <b>throw</b>
 * {@code OpenAIInvalidData}. A plain transport-level 503 carries no JSON error body at all, so the
 * very failure this record exists to describe is the one that blows up while describing it — and it
 * does so from inside a retry classifier, converting a retryable fault into an immediate hard
 * error.
 */
private static String optional(Supplier<Optional<String>>field){try{return field.get().orElse(null);}catch(RuntimeException e){return null;}}

/**
 * {@code Headers.values} is case-insensitive by construction in openai-java, matching how
 * azure-core's {@code HttpHeaderName} behaves — which matters because HTTP/2 sends lowercase while
 * a {@code com.sun.net.httpserver} mock rewrites to {@code Retry-After}, so an exact-case reader is
 * green in one environment and red in the other.
 */
private static String firstHeader(OpenAIServiceException e,String name){List<String>values=e.headers().values(name);return values==null||values.isEmpty()?null:values.get(0);}

/** The named response header, or {@code null} when absent or unavailable for this SDK. */
public String header(String name){if(headerLookup==null){return null;}String value=headerLookup.apply(name);return value==null||value.isBlank()?null:value.strip();}}
