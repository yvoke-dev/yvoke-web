package de.palsoftware.yvoke.llm.core;

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
 * Adding a provider means adding one branch to {@link #at}, and every consumer gains it at once.
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
return new ProviderFault(api.getClass().getSimpleName(),api.code(),api.status(),api.message(),api.getMessage(),null);}if(c instanceof OpenAIServiceException openAi){return new ProviderFault(c.getClass().getSimpleName(),openAi.statusCode(),optional(openAi::type),optional(openAi::code),c.getMessage(),name->firstHeader(openAi,name));}return null;}

/**
 * Reads an openai-java structured error field defensively.
 */
private static String optional(Supplier<Optional<String>>field){try{return field.get().orElse(null);}catch(RuntimeException e){return null;}}

private static String firstHeader(OpenAIServiceException e,String name){List<String>values=e.headers().values(name);return values==null||values.isEmpty()?null:values.get(0);}

/** The named response header, or {@code null} when absent or unavailable for this SDK. */
public String header(String name){if(headerLookup==null){return null;}String value=headerLookup.apply(name);return value==null||value.isBlank()?null:value.strip();}}
