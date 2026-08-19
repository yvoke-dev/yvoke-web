package de.palsoftware.yvoke.llm.core.service;

import de.palsoftware.yvoke.llm.core.LlmRetry;
import de.palsoftware.yvoke.llm.core.model.LlmCallFailedException;
import de.palsoftware.yvoke.llm.core.model.LlmGatewayInfo;
import de.palsoftware.yvoke.llm.core.model.LlmMessage;
import de.palsoftware.yvoke.llm.core.model.LlmRequest;
import de.palsoftware.yvoke.llm.core.model.LlmResponse;
import de.palsoftware.yvoke.llm.core.model.LlmResponseChunk;
import de.palsoftware.yvoke.llm.core.model.LlmTool;
import de.palsoftware.yvoke.llm.core.model.LlmToolCall;
import de.palsoftware.yvoke.llm.core.model.LlmToolCallDelta;
import de.palsoftware.yvoke.llm.core.model.LlmUsage;
import jakarta.annotation.Nullable;


import com.azure.ai.openai.OpenAIAsyncClient;
import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.ai.openai.implementation.accesshelpers.ChatCompletionsOptionsAccessHelper;
import com.azure.ai.openai.models.ChatChoice;
import com.azure.ai.openai.models.ChatCompletionStreamOptions;
import com.azure.ai.openai.models.ChatCompletions;
import com.azure.ai.openai.models.ChatCompletionsFunctionToolCall;
import com.azure.ai.openai.models.ChatCompletionsFunctionToolDefinition;
import com.azure.ai.openai.models.ChatCompletionsFunctionToolDefinitionFunction;
import com.azure.ai.openai.models.ChatCompletionsJsonResponseFormat;
import com.azure.ai.openai.models.ChatCompletionsJsonSchemaResponseFormat;
import com.azure.ai.openai.models.ChatCompletionsJsonSchemaResponseFormatJsonSchema;
import com.azure.ai.openai.models.ChatCompletionsOptions;
import com.azure.ai.openai.models.ChatCompletionsToolCall;
import com.azure.ai.openai.models.ChatCompletionsToolDefinition;
import com.azure.ai.openai.models.ChatRequestAssistantMessage;
import com.azure.ai.openai.models.ChatRequestDeveloperMessage;
import com.azure.ai.openai.models.ChatRequestMessage;
import com.azure.ai.openai.models.ChatRequestSystemMessage;
import com.azure.ai.openai.models.ChatRequestToolMessage;
import com.azure.ai.openai.models.ChatRequestUserMessage;
import com.azure.ai.openai.models.ChatResponseMessage;
import com.azure.ai.openai.models.CompletionsFinishReason;
import com.azure.ai.openai.models.CompletionsUsage;
import com.azure.ai.openai.models.CompletionsUsageCompletionTokensDetails;
import com.azure.ai.openai.models.CompletionsUsagePromptTokensDetails;
import com.azure.ai.openai.models.FunctionCall;
import com.azure.ai.openai.models.ReasoningEffortValue;
import com.azure.core.credential.AzureKeyCredential;
import com.azure.core.http.HttpClient;
import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.HttpResponse;
import com.azure.core.http.jdk.httpclient.JdkHttpClientBuilder;
import com.azure.core.http.policy.FixedDelayOptions;
import com.azure.core.http.policy.RetryOptions;
import com.azure.core.http.rest.RequestOptions;
import com.azure.core.http.rest.Response;
import com.azure.core.util.BinaryData;
import com.azure.core.util.Context;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.net.http.HttpTimeoutException;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * {@link LlmClient} backed by Azure OpenAI's chat-completions API via
 * {@code com.azure:azure-ai-openai}.
 *
 * <p>
 * Deliberate divergences from {@link GeminiLlmClient}, none of which are oversights:
 * <ul>
 * <li><b>No reasoning text.</b> Azure chat completions reports reasoning <i>tokens</i> but never
 * emits the reasoning itself, so {@code LlmResponseChunk.reasoning()} is always {@code null}.</li>
 * <li><b>No thought signatures.</b> {@code LlmResponseChunk.parts()} is always {@code null};
 * {@code RagService} reads {@code parts} only to carry Gemini thought signatures forward.</li>
 * <li><b>No server-side code execution.</b> Azure exposes Code Interpreter through the Assistants
 * API (retiring 2026-08-26), the stateful Foundry Agents service, and the Responses API — none of
 * which is reachable from chat completions. A request asking for it is answered without it, and
 * says so in the log.</li>
 * <li><b>Structured output IS combined with tools</b>, unlike Gemini which cannot do both at
 * once.</li>
 * <li><b>No reasoning effort when tools are present.</b> Chat completions rejects the pair —
 * "Function tools with reasoning_effort are not supported for this model in /v1/chat/completions" —
 * and every agentic turn carries tools, so sending it is a 400 on every answer. The level is
 * dropped and the model reasons at its own default; getting it back means moving to the Responses
 * API.</li>
 * <li><b>Not {@code AutoCloseable}</b>, unlike {@link GeminiLlmClient}. azure-core's
 * {@code HttpClient} is not closeable and {@code JdkHttpClientBuilder} keeps the JDK client it
 * builds private, so there is genuinely nothing to release — a {@code close()} here could only be a
 * no-op that misleads its next reader. {@code OpenRouterLlmClient} is likewise not closeable.</li>
 * </ul>
 */
public class AzureOpenAiLlmClient implements LlmClient {
    private static final Logger log = LoggerFactory.getLogger(AzureOpenAiLlmClient.class);

    /**
     * Idle budget for a single Azure OpenAI call, matching the 300s the Gemini and OpenRouter
     * clients allow for long generations.
     *
     * <p>
     * azure-core applies its own defaults — 10s connect, 60s write, 60s read, 60s response — to
     * every transport builder unless each one is set explicitly, and the 60s response timeout
     * <i>cancels the call</i>, which would kill any model taking longer than a minute to produce
     * its first token. Zero disables a timeout; the per-read timeout is the one bound kept, because
     * for SSE it means "the stream went silent" rather than "the answer took too long".
     */
    static final int HTTP_TIMEOUT_MS = 300_000;

    /**
     * How many times a turn that produces neither content nor a tool call is attempted. Bounded at
     * two because an empty turn that repeats is systematic rather than a glitch, and these prompts
     * run to hundreds of thousands of tokens — re-sending one three times to discover the same
     * nothing costs more than the round the retry exists to save.
     */
    private static final int EMPTY_TURN_ATTEMPTS = 2;

    /**
     * Finish reasons known to end a stream normally.
     *
     * <p>
     * Documentation of what is recognised, NOT the failure test — see {@link #FATAL_FINISH_REASONS}
     * for that. The two sets are deliberately not complements: {@link CompletionsFinishReason} is
     * an {@code ExpandableStringEnum}, so {@code fromString} mints an instance for any wire value
     * and a reason outside both sets is simply one this client has not learned.
     */
    private static final Set<CompletionsFinishReason> BENIGN_FINISH_REASONS =
        Set.of(CompletionsFinishReason.STOPPED, CompletionsFinishReason.TOKEN_LIMIT_REACHED,
            CompletionsFinishReason.TOOL_CALLS, CompletionsFinishReason.FUNCTION_CALL);

    /**
     * Finish reasons that fail the call. A CLOSED set, tested positively.
     *
     * <p>
     * Asking "not benign?" instead failed closed over an open enum: a vendor-specific or
     * newly-introduced value destroyed an answer already on the user's screen, and destructively,
     * since the parser ran before the chunk was handed on so the content on that same delta went
     * with it. Only a reason this client positively recognises as fatal may end a turn; anything
     * unrecognised is delivered with a warning. The sibling {@code GeminiLlmClient} had the
     * mirror-image bug — failing OPEN — so the two providers behaved oppositely on identical events
     * by accident rather than by policy.
     */
    private static final Set<CompletionsFinishReason> FATAL_FINISH_REASONS =
        Set.of(CompletionsFinishReason.CONTENT_FILTERED);

    /**
     * Recognises a reasoning model by deployment name, used only when no explicit list is
     * configured. Reasoning models reject {@code temperature} and accept {@code reasoning_effort};
     * ordinary models are the exact opposite, so being wrong either way is a 400 on every call.
     *
     * <p>
     * The o-series names are matched as whole tokens rather than bare substrings, so
     * {@code o3-mini} and {@code prod-o4} match while {@code gpt-4o} and
     * {@code text-embedding-3-large} do not. The GPT-5 family is matched by prefix, which covers
     * {@code gpt-5}, {@code gpt-5-mini} and named variants such as {@code gpt-5.6-luna}.
     */
    private static final Pattern REASONING_MODEL_PATTERN =
        Pattern.compile("(?:^|[^a-z0-9])o[1345](?:[^a-z0-9]|$)|gpt-5|reasoning");

    /**
     * Every deadline this client applies.
     *
     * <p>
     * There is deliberately no value meaning "wait forever" — the constructor rejects null, zero and
     * negative. That is the exact opposite of {@link JdkHttpClientBuilder}, where {@code ZERO} is
     * normalised to "no timeout", and the inversion is the point: the defect this type exists to
     * prevent is an unbounded wait, so it must not be expressible.
     *
     * @param establish how long the service has to produce RESPONSE HEADERS on a streamed call,
     *        measured from the moment the request is issued — so it covers connect, the whole
     *        request upload (which {@code writeTimeout(ZERO)} leaves unbounded) and time-to-first-
     *        byte, and nothing after it. Three {@link LlmRetry} attempts plus backoff fit inside
     *        {@code ChatSseController}'s 600s emitter for the first call of an answer.
     * @param wholeAnswer the same wait for a NON-streaming call. Azure sends no headers for a
     *        buffered completion until the answer is finished, so there this necessarily caps total
     *        generation: it is a policy budget, not a liveness signal, and must be generous.
     */
    record Deadlines(Duration establish, Duration wholeAnswer) {

        static final Deadlines DEFAULT =
            new Deadlines(Duration.ofSeconds(180), Duration.ofSeconds(600));

        Deadlines {
            requirePositive(establish, "establish");
            requirePositive(wholeAnswer, "wholeAnswer");
        }

        private static void requirePositive(Duration value, String name) {
            if (value == null || value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException(name + " must be positive; there is no value "
                    + "meaning 'wait forever', because that is the defect this type prevents");
            }
        }
    }

    /** Per-call override of the transport's default deadline, carried on the azure-core Context. */
    private static final String DEADLINE_KEY = "yvoke.response-headers-deadline";

    private final OpenAIAsyncClient client;
    private final ObjectMapper objectMapper;
    private final boolean enableThinking;
    private final String thinkingLevel;
    private final Set<String> reasoningModels;
    private final Deadlines deadlines;

    public AzureOpenAiLlmClient(String endpoint, String apiKey, ObjectMapper objectMapper,
        boolean enableThinking, String thinkingLevel, String reasoningModelsCsv) {
        this(endpoint, apiKey, objectMapper, enableThinking, thinkingLevel, reasoningModelsCsv,
            defaultTransport());
    }

    /**
     * Test seam. {@code KeyCredentialPolicy} refuses to attach the key to a non-HTTPS request — a
     * guard worth keeping, since it is what stops a mistyped {@code http://} endpoint from leaking
     * the credential — so a test that wants a plain-HTTP mock server supplies a transport that
     * rewrites the scheme after the credential policy has already run.
     */
    AzureOpenAiLlmClient(String endpoint, String apiKey, ObjectMapper objectMapper,
        boolean enableThinking, String thinkingLevel, String reasoningModelsCsv,
        HttpClient transport) {
        this(endpoint, apiKey, objectMapper, enableThinking, thinkingLevel, reasoningModelsCsv,
            transport, Deadlines.DEFAULT);
    }

    AzureOpenAiLlmClient(String endpoint, String apiKey, ObjectMapper objectMapper,
        boolean enableThinking, String thinkingLevel, String reasoningModelsCsv,
        HttpClient transport, Deadlines deadlines) {
        if (endpoint == null || endpoint.isBlank()) {
            // OpenAIClientBuilder decides Azure-vs-public-OpenAI purely from the endpoint: with
            // none
            // set it targets api.openai.com and sends the credential as a bearer token. A missing
            // endpoint would therefore ship the Azure key to a third party rather than merely fail.
            throw new IllegalArgumentException("Azure OpenAI endpoint is required (set "
                + "AZURE_OPENAI_ENDPOINT or app.ai.azure-openai.endpoint); without it the SDK would "
                + "send the API key to the public OpenAI service instead");
        }
        this.objectMapper = objectMapper;
        this.enableThinking = enableThinking;
        this.thinkingLevel = thinkingLevel;
        this.reasoningModels = parseReasoningModels(reasoningModelsCsv);
        this.deadlines = deadlines;
        log.info(
            "Initializing AzureOpenAiLlmClient with endpoint={}, thinkingLevel={}, reasoningModels={}",
            endpoint, thinkingLevel, reasoningModels.isEmpty() ? "auto-detect" : reasoningModels);

        this.client =
            new OpenAIClientBuilder().endpoint(endpoint).credential(new AzureKeyCredential(apiKey))
                // Wrapped HERE rather than in defaultTransport(): this is the only path from an
                // HttpClient to the SDK, so a transport without a headers deadline cannot be handed
                // to
                // this client by production code, by a test, or by a call site added later.
                .httpClient(boundedTransport(transport, deadlines.establish()))
                // LlmRetry is the single retry authority. Leaving the SDK's own exponential policy
                // in
                // place would multiply the two layers, re-uploading a large prompt into the very
                // quota
                // that just returned 429 — the mistake already recorded for google-genai.
                .retryOptions(new RetryOptions(new FixedDelayOptions(0, Duration.ZERO)))
                // The ASYNC client, deliberately. azure-core's SyncRestProxy has no
                // text/event-stream
                // branch and hands the body to BinaryData whole, while AsyncRestProxy detects the
                // content type and keeps the body an un-buffered Flux — so only the async client
                // can
                // deliver an answer token by token.
                .buildAsyncClient();
    }

    static HttpClient defaultTransport() {
        return new JdkHttpClientBuilder().connectionTimeout(Duration.ofSeconds(10))
            // Unbounded upload, deliberately: azure-core's 60s default is an inter-chunk idle timer
            // over 8 KiB slices and a ~600k-token prompt trips it. The upload strictly precedes
            // response headers, so boundedTransport's deadline already covers it.
            .writeTimeout(Duration.ZERO)
            // Per read, and armed only once the body Flux is subscribed — which happens strictly
            // AFTER the headers Mono has emitted. That is precisely why it could never bound
            // time-to-first-byte, and why boundedTransport exists.
            .readTimeout(Duration.ofMillis(HTTP_TIMEOUT_MS))
            // java.net.http.HttpRequest.timeout(), which the JDK keeps armed until the response
            // BODY is consumed — so any value here is a hard cap on total generation time, not a
            // liveness check. Left disabled; the bound we need is in boundedTransport.
            .responseTimeout(Duration.ZERO).build();
    }

    /**
     * The one transport decorator: it bounds the wait for response headers, and makes the SDK's
     * read-only response buffers array-backed.
     *
     * <p>
     * Both must apply to every transport this client uses, and two separate wrappers are two
     * chances to apply one and forget the other — so they are one function, applied at the single
     * constructor chokepoint.
     *
     * <p>
     * <b>Why the bound lives here and nowhere higher.</b> The Mono returned by {@code send}
     * completes at response-headers-received, carrying a body Flux nobody has subscribed to yet, so
     * a timeout at this layer is a pure time-to-first-byte deadline — disarmed the instant headers
     * arrive, and structurally unable to touch body consumption. It is also the only layer at which
     * a cancel reaches the wire: {@code OpenAIServerSentEvents} ends its pipeline in
     * {@code .cache()}, whose downstream cancel is not propagated upstream, so a timeout placed at
     * or above {@code establish()} would free the calling thread and leak the in-flight exchange
     * forever. (The same {@code .cache()} is why the {@code establish()} javadoc's claim that
     * closing the Stream releases the connection only becomes true with this wrapper in place.)
     *
     * <p>
     * {@code sendSync} is deliberately NOT overridden: the interface default is
     * {@code send(request, context).block()}, so it inherits both the bound and the array-backing.
     * Overriding it would create a second, unbounded route; deleting the override removes the
     * ability to express one.
     */
    static HttpClient boundedTransport(HttpClient delegate, Duration defaultDeadline) {
        Objects.requireNonNull(delegate, "delegate");
        Deadlines.requirePositive(defaultDeadline, "defaultDeadline");
        return new HttpClient() {
            @Override
            public Mono<HttpResponse> send(HttpRequest request) {
                return bound(delegate.send(request), defaultDeadline);
            }

            @Override
            public Mono<HttpResponse> send(HttpRequest request, Context context) {
                return bound(delegate.send(request, context), deadlineOf(context));
            }

            private Duration deadlineOf(Context context) {
                if (context == null) {
                    return defaultDeadline;
                }
                return context.getData(DEADLINE_KEY).filter(Duration.class::isInstance)
                    .map(Duration.class::cast).orElse(defaultDeadline);
            }

            private Mono<HttpResponse> bound(Mono<HttpResponse> response, Duration deadline) {
                // No fallback publisher on purpose: only the no-fallback branch of reactor's
                // timeout
                // cancels upstream, and that cancel is what aborts the JDK exchange instead of
                // merely abandoning it.
                return response.timeout(deadline).onErrorMap(TimeoutException.class, e -> {
                    // HttpTimeoutException for two reasons: it is what this transport already
                    // raises
                    // for the sibling read timeout, so both silence failures are one type; and it
                    // extends IOException, which is how LlmRetry classifies it from a TYPE rather
                    // than from a message substring.
                    HttpTimeoutException timeout =
                        new HttpTimeoutException("Azure OpenAI sent no response headers within "
                            + deadline.toMillis() + " ms (connection established, request sent)");
                    timeout.addSuppressed(e);
                    return timeout;
                }).map(ArrayBackedResponse::new);
            }
        };
    }

    /**
     * Note {@code array()} is read whole by the SSE parser, ignoring position and limit, so the
     * copy must be exactly the remaining bytes — a larger backing array would feed it trailing
     * padding it would try to parse.
     */
    private static ByteBuffer arrayBackedCopy(ByteBuffer source) {
        if (source.hasArray() && !source.isReadOnly() && source.arrayOffset() == 0
            && source.array().length == source.remaining()) {
            return source;
        }
        byte[] copy = new byte[source.remaining()];
        source.duplicate().get(copy);
        return ByteBuffer.wrap(copy);
    }

    private static final class ArrayBackedResponse extends HttpResponse {
        private final HttpResponse delegate;

        private ArrayBackedResponse(HttpResponse delegate) {
            super(delegate.getRequest());
            this.delegate = delegate;
        }

        @Override
        public int getStatusCode() {
            return delegate.getStatusCode();
        }

        @Override
        @SuppressWarnings("deprecation")
        public String getHeaderValue(String name) {
            return delegate.getHeaderValue(name);
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }

        @Override
        public Flux<ByteBuffer> getBody() {
            return delegate.getBody().map(AzureOpenAiLlmClient::arrayBackedCopy);
        }

        @Override
        public Mono<byte[]> getBodyAsByteArray() {
            return delegate.getBodyAsByteArray();
        }

        @Override
        public Mono<String> getBodyAsString() {
            return delegate.getBodyAsString();
        }

        @Override
        public Mono<String> getBodyAsString(Charset charset) {
            return delegate.getBodyAsString(charset);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        log.info("Sending non-streaming request to Azure OpenAI: deployment={}", request.model());
        ChatCompletionsOptions options = buildOptions(request);
        return LlmRetry.withRetry("AzureOpenAI.generate", 3, () -> {
            // A buffered completion is not answered until it is finished, so at the transport
            // time-to-headers IS time-to-whole-answer: the streaming establishment budget would be
            // a
            // generation cap here, and would fail a legitimate KG extraction. The .block() stays
            // duration-less deliberately — a blocking-read timeout surfaces as
            // IllegalStateException,
            // which LlmRetry can only classify by message text, where the transport's
            // HttpTimeoutException is classified by type.
            Response<ChatCompletions> response =
                client
                    .getChatCompletionsWithResponse(request.model(), options,
                        new RequestOptions()
                            .setContext(new Context(DEADLINE_KEY, deadlines.wholeAnswer())))
                    .block();
            ChatCompletions completions = response == null ? null : response.getValue();

            // Usage is read BEFORE the content check and carried on the exception: a filtered or
            // empty response still burned tokens, and throwing without it leaves no llm_call_logs
            // row at all.
            LlmUsage usage = usageOf(completions == null ? null : completions.getUsage());
            String text = firstChoiceContent(completions);
            if (text == null) {
                throw new LlmCallFailedException("Azure OpenAI returned no text content ("
                    + describeMissingText(completions) + ")", null, usage);
            }
            return new LlmResponse(text, usage, gatewayInfo(response));
        });
    }

    @Override
    public void generateStream(LlmRequest request, Consumer<LlmResponseChunk> onChunk) {
        String streamId = UUID.randomUUID().toString().substring(0, 8);
        log.info("Sending streaming request to Azure OpenAI: deployment={}, streamId={}",
            request.model(), streamId);
        ChatCompletionsOptions options = buildOptions(request);
        // Token counts only arrive on a streamed call when stream_options.include_usage is set, and
        // the public overload that accepts it returns no HTTP headers — which is where an AI
        // gateway reports whether it replayed a cached answer. Setting it through the access helper
        // is the only way to have both; if a future SDK moves that class this stops compiling.
        ChatCompletionsOptionsAccessHelper.setStreamOptions(options,
            new ChatCompletionStreamOptions().setIncludeUsage(true));

        // Only stream establishment is retried. A transient failure here throws before any chunk is
        // emitted, so retrying cannot duplicate already-streamed output; a mid-stream failure
        // cannot be resumed and is deliberately left to fail. Every element of the Flux shares one
        // set of response headers, so the gateway verdict is read once, off the first.
        //
        // The subscription MUST happen inside the retry loop. getChatCompletionsStreamWithResponse
        // returns a cold Flux — assembling it performs no I/O whatsoever — so retrying that call
        // alone retried nothing at all, and the request was issued afterwards by the subscription,
        // outside the loop. Awaiting the first element here is what makes establishment a real,
        // retryable operation; on the blocking path .block() already does this.
        // An EMPTY turn is the one mid-stream outcome that is safe to re-request, and it is safe
        // for
        // exactly the reason the paragraph above gives: "empty" means neither content nor a tool
        // call was seen, so by construction the consumer has been handed nothing that a second
        // attempt could replay. It cannot be expressed as an LlmRetry classification — the throw
        // happens here, while consuming, not inside the establish() call withRetry wraps — and
        // widening withRetry to cover this loop would retry genuine mid-stream faults too, since a
        // truncated SSE event surfaces as a Jackson IOException that isTransient already accepts.
        LlmUsage abandoned = null;
        for (int attempt = 1;; attempt++) {
            StreamOutcome outcome = new StreamOutcome();
            EstablishedStream established = LlmRetry.withRetry("AzureOpenAI.generateStream", 3,
                () -> establish(request, options));

            // try-with-resources on the Stream is what actually cancels the subscription and
            // releases the connection; abandoning the Iterator alone would leave the response open.
            try (Stream<Response<ChatCompletions>> stream = established.stream()) {
                Iterator<Response<ChatCompletions>> iterator = established.iterator();
                LlmGatewayInfo gateway = null;
                boolean first = true;
                while (iterator.hasNext()) {
                    if (Thread.currentThread().isInterrupted()) {
                        log.info("Azure OpenAI stream interrupted, stopping chunk processing "
                            + "(streamId={})", streamId);
                        throw new CancellationException("Azure OpenAI stream interrupted");
                    }
                    Response<ChatCompletions> event = iterator.next();
                    if (first) {
                        gateway = gatewayInfo(event);
                        first = false;
                    }
                    onChunk.accept(
                        carry(parseChunk(event.getValue(), gateway, streamId, outcome), abandoned));
                }
            } catch (RuntimeException e) {
                if (Thread.currentThread().isInterrupted() || e instanceof CancellationException
                    || e.getCause() instanceof InterruptedException
                    || e.getCause() instanceof CancellationException) {
                    log.info("Azure OpenAI stream cancelled/interrupted (streamId={})", streamId);
                    throw new CancellationException("Azure OpenAI stream interrupted");
                }
                log.error("Azure OpenAI stream error for deployment={}, streamId={}",
                    request.model(), streamId, e);
                throw e;
            }

            // Taken here rather than mid-parse so the trailing usage-only event has already been
            // read: those tokens were billed, and throwing before pulling it left the call with no
            // llm_call_logs row at all — the same loss generate() carries its usage on the
            // exception to avoid. Not re-requested: a filtered turn repeats.
            if (outcome.fatalFinishReason != null) {
                throw new LlmCallFailedException(
                    "Azure OpenAI stream finished abnormally: finishReason="
                        + outcome.fatalFinishReason + " (" + outcome.describe() + ")",
                    null, plus(abandoned, outcome.usage));
            }

            if (!outcome.isEmpty()) {
                log.info("Azure OpenAI stream completed successfully for deployment={}, "
                    + "streamId={}", request.model(), streamId);
                return;
            }

            // A stream that ends having produced neither text nor a tool call is a failure, not a
            // quiet success. Reporting it as success pushed the diagnosis downstream, where the
            // caller could only guess at a cause it has no evidence for — an orchestrated run died
            // as "possible MALFORMED_FUNCTION_CALL or safety block" when the finish reason and the
            // token split were sitting right here. Usage rides on the exception, and accumulates
            // across attempts, so every token the provider charged for is still recorded.
            LlmUsage burned = plus(abandoned, outcome.usage);
            if (attempt >= EMPTY_TURN_ATTEMPTS) {
                throw new LlmCallFailedException("Azure OpenAI produced no content and no tool "
                    + "calls after " + attempt + " attempt(s) (" + outcome.describe() + ")", null,
                    burned);
            }
            log.warn(
                "Azure OpenAI produced an empty turn for deployment={}, streamId={} ({}); "
                    + "nothing reached the caller, so re-requesting once",
                request.model(), streamId, outcome.describe());
            abandoned = burned;
        }
    }

    /**
     * Adds an abandoned attempt's usage onto a chunk that carries usage of its own, so the winning
     * attempt reports what the whole operation cost. The accounting decorator above this client
     * keeps only the LAST usage it observes and publishes one row per call, so without this a
     * recovered turn would be billed as though the abandoned attempt had never run.
     */
    private static LlmResponseChunk carry(LlmResponseChunk chunk, @Nullable LlmUsage abandoned) {
        if (abandoned == null || chunk.usage() == null) {
            return chunk;
        }
        return new LlmResponseChunk(chunk.content(), chunk.reasoning(), chunk.toolCallDeltas(),
            plus(abandoned, chunk.usage()), chunk.parts(), chunk.gateway());
    }

    private static LlmUsage plus(@Nullable LlmUsage a, @Nullable LlmUsage b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return new LlmUsage(a.promptTokens() + b.promptTokens(),
            a.completionTokens() + b.completionTokens(), a.totalTokens() + b.totalTokens(),
            a.cachedTokens() + b.cachedTokens(), a.thoughtTokens() + b.thoughtTokens());
    }

    /**
     * A subscribed stream together with the iterator that owns its first element. The two travel as
     * one because {@link Stream#iterator()} may be called only once, and the iterator must survive
     * the establishment attempt that produced it — re-deriving it from the stream would throw.
     */
    private record EstablishedStream(Stream<Response<ChatCompletions>> stream,
        Iterator<Response<ChatCompletions>> iterator) {}

    /**
     * Opens the stream and blocks until the service has answered, so a rejected request fails here
     * — inside the retry loop — rather than later, on the first read. A failed attempt closes its
     * own stream: each retry subscribes afresh, and the abandoned subscription would otherwise hold
     * its connection until the pool evicted it.
     */
    private EstablishedStream establish(LlmRequest request, ChatCompletionsOptions options) {
        Flux<Response<ChatCompletions>> events = client
            .getChatCompletionsStreamWithResponse(request.model(), options, new RequestOptions());
        Stream<Response<ChatCompletions>> stream = events.toStream(1);
        Iterator<Response<ChatCompletions>> iterator = stream.iterator();
        try {
            iterator.hasNext();
            return new EstablishedStream(stream, iterator);
        } catch (RuntimeException e) {
            stream.close();
            throw e;
        }
    }

    /**
     * Reads the AI-gateway headers off the HTTP response. Unlike Gemini, where the SDK re-stamps
     * headers onto every chunk, one streamed call has exactly one set of response headers, so this
     * is resolved once and carried on each chunk. Returns {@code null} when no gateway is in path.
     */
    private static LlmGatewayInfo gatewayInfo(Response<?> response) {
        if (response == null || response.getHeaders() == null) {
            return null;
        }
        return LlmGatewayInfo.fromHeaders(response.getHeaders().toMap());
    }

    /**
     * What a stream actually produced, so an empty one can say why instead of being reported as a
     * success the caller then has to guess about.
     */
    private static final class StreamOutcome {
        private boolean sawContent;
        private boolean sawToolCall;
        private CompletionsFinishReason finishReason;
        /**
         * Set when a {@link #FATAL_FINISH_REASONS} value was seen; the verdict is taken after the
         * stream drains, so the usage-only trailing event is still read.
         */
        private CompletionsFinishReason fatalFinishReason;
        private int events;
        private LlmUsage usage;

        boolean isEmpty() {
            return !sawContent && !sawToolCall;
        }

        String describe() {
            StringBuilder out = new StringBuilder();
            out.append("finishReason=")
                .append(finishReason == null ? "none" : finishReason.toString());
            out.append(", events=").append(events);
            if (usage != null) {
                out.append(", completionTokens=").append(usage.completionTokens())
                    .append(" of which reasoning=").append(usage.thoughtTokens());
            }
            if (finishReason == null) {
                out.append(" — the stream ended without a finish reason, which means it was cut "
                    + "short rather than completed");
            }
            return out.toString();
        }
    }

    private LlmResponseChunk parseChunk(ChatCompletions completions, LlmGatewayInfo gateway,
        String streamId, StreamOutcome outcome) {
        outcome.events++;
        String content = null;
        List<LlmToolCallDelta> toolDeltas = null;

        List<ChatChoice> choices = completions.getChoices();
        if (choices != null && !choices.isEmpty()) {
            ChatChoice choice = choices.get(0);
            ChatResponseMessage delta = choice.getDelta();
            if (delta != null) {
                content = delta.getContent();
                toolDeltas = toolCallDeltas(delta.getToolCalls(), streamId);
                // A refusal is visible output the model chose to send; counting it keeps a refused
                // turn from being reported as an empty one.
                if (delta.getRefusal() != null && !delta.getRefusal().isEmpty()) {
                    content = content == null ? delta.getRefusal() : content + delta.getRefusal();
                }
            }
            if (content != null && !content.isEmpty()) {
                outcome.sawContent = true;
            }
            if (toolDeltas != null && !toolDeltas.isEmpty()) {
                outcome.sawToolCall = true;
            }
            CompletionsFinishReason finishReason = choice.getFinishReason();
            if (finishReason != null) {
                outcome.finishReason = finishReason;
                // Recorded, never thrown from here. The token counts arrive on a LATER
                // choices-empty event — stream_options.include_usage sends usage on exactly one
                // additional chunk and null on every other — so throwing mid-parse cancelled
                // iteration before that event was pulled and made the usage unreachable by
                // construction. parseChunk is total; generateStream owns every verdict, after the
                // stream has drained.
                if (FATAL_FINISH_REASONS.contains(finishReason)) {
                    log.warn(
                        "Azure OpenAI stream finished abnormally: finishReason={}, streamId={}",
                        finishReason, streamId);
                    outcome.fatalFinishReason = finishReason;
                } else if (!BENIGN_FINISH_REASONS.contains(finishReason)) {
                    log.warn(
                        "Azure OpenAI reported a finishReason this client does not recognise: {} "
                            + "(streamId={}). Delivering the answer — an unknown reason is not "
                            + "evidence of a failure.",
                        finishReason, streamId);
                }
            }
        }

        LlmUsage usage = completions.getUsage() == null ? null : usageOf(completions.getUsage());
        if (usage != null) {
            outcome.usage = usage;
        }
        return new LlmResponseChunk(content, null, toolDeltas, usage, null, gateway);
    }

    /**
     * Maps streamed tool-call fragments onto {@link LlmToolCallDelta}.
     *
     * <p>
     * The wire protocol identifies a fragment by {@code index}, but {@code ChatCompletionsToolCall}
     * models only {@code id} and {@code type} and its deserializer discards every other field — so
     * the index is simply not available, and the SDK's own sample sidesteps this by handling a
     * single tool call only. Emitting {@code index = -1} is what makes parallel calls work anyway:
     * {@code ToolCallAccumulator} then keys an id-bearing fragment by id and appends an id-less one
     * to the most recent call, which is exactly how the service streams them — one call opened by
     * an id-bearing fragment, then its arguments, then the next call.
     */
    private List<LlmToolCallDelta> toolCallDeltas(List<ChatCompletionsToolCall> toolCalls,
        String streamId) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return null;
        }
        List<LlmToolCallDelta> deltas = new ArrayList<>();
        for (ChatCompletionsToolCall toolCall : toolCalls) {
            if (!(toolCall instanceof ChatCompletionsFunctionToolCall functionCall)) {
                log.debug("Ignoring non-function tool call of type {} (streamId={})",
                    toolCall == null ? "null" : toolCall.getType(), streamId);
                continue;
            }
            FunctionCall function = functionCall.getFunction();
            String name = function == null ? null : function.getName();
            String arguments = function == null ? null : function.getArguments();
            if (functionCall.getId() != null) {
                log.debug("Azure OpenAI opened tool call: name={}, id={}, streamId={}", name,
                    functionCall.getId(), streamId);
            }
            deltas.add(new LlmToolCallDelta(-1, functionCall.getId(), name, arguments));
        }
        return deltas.isEmpty() ? null : deltas;
    }

    private static LlmUsage usageOf(CompletionsUsage usage) {
        if (usage == null) {
            return new LlmUsage(0, 0, 0, 0, 0);
        }
        CompletionsUsagePromptTokensDetails promptDetails = usage.getPromptTokensDetails();
        CompletionsUsageCompletionTokensDetails completionDetails =
            usage.getCompletionTokensDetails();
        int cachedTokens = promptDetails == null || promptDetails.getCachedTokens() == null ? 0
            : promptDetails.getCachedTokens();
        int reasoningTokens =
            completionDetails == null || completionDetails.getReasoningTokens() == null ? 0
                : completionDetails.getReasoningTokens();
        return new LlmUsage(usage.getPromptTokens(), usage.getCompletionTokens(),
            usage.getTotalTokens(), cachedTokens, reasoningTokens);
    }

    private static String firstChoiceContent(ChatCompletions completions) {
        if (completions == null || completions.getChoices() == null
            || completions.getChoices().isEmpty()) {
            return null;
        }
        ChatResponseMessage message = completions.getChoices().get(0).getMessage();
        return message == null ? null : message.getContent();
    }

    /** Builds a human-readable explanation for a response that carries no text. */
    private static String describeMissingText(ChatCompletions completions) {
        if (completions == null || completions.getChoices() == null
            || completions.getChoices().isEmpty()) {
            return "no choices";
        }
        ChatChoice choice = completions.getChoices().get(0);
        ChatResponseMessage message = choice.getMessage();
        if (message != null && message.getRefusal() != null) {
            return "refusal=" + message.getRefusal();
        }
        return "finishReason="
            + (choice.getFinishReason() == null ? "unknown" : choice.getFinishReason().toString());
    }

    ChatCompletionsOptions buildOptions(LlmRequest request) {
        boolean reasoning = isReasoningModel(request.model());
        ChatCompletionsOptions options =
            new ChatCompletionsOptions(buildMessages(request, reasoning));

        boolean hasTools = request.tools() != null && !request.tools().isEmpty();
        if (reasoning) {
            // A reasoning model rejects any temperature but its default, so temperature is decided
            // by the MODEL, never by whether a reasoning effort happened to be resolved. Tying the
            // two together would 400 every call whenever thinking is off or the level is unusable.
            ReasoningEffortValue effort = hasTools ? null : resolveReasoningEffort(request);
            if (effort != null) {
                options.setReasoningEffort(effort);
            } else if (hasTools && resolveReasoningEffort(request) != null) {
                // Chat completions rejects the pair outright: "Function tools with reasoning_effort
                // are not supported for this model in /v1/chat/completions. Please use
                // /v1/responses
                // instead." Every agentic turn carries tools, so sending it anyway is a 400 on
                // every
                // answer. The model still reasons at its own default; only the level is given up.
                log.warn(
                    "Dropping reasoning effort for deployment={}: this API rejects a reasoning "
                        + "effort combined with function tools, and tools are required to answer.",
                    request.model());
            }
        } else {
            options.setTemperature(request.temperature());
        }

        // max_completion_tokens rather than the legacy max_tokens, which reasoning models reject.
        // Zero would truncate the answer, so it is only sent when positive.
        if (request.maxTokens() > 0) {
            options.setMaxCompletionTokens(request.maxTokens());
        }
        if (request.seed() != null) {
            options.setSeed(request.seed().longValue());
        }

        applyResponseFormat(request, options);
        applyTools(request, options);

        if (request.codeExecution()) {
            log.warn(
                "Ignoring the playbook's code-execution setting: Azure OpenAI chat completions "
                    + "has no server-side code execution (it lives in the Assistants, Agents and "
                    + "Responses APIs). The answer is produced without it.");
        }

        currentPrincipal().ifPresent(options::setUser);
        return options;
    }

    private List<ChatRequestMessage> buildMessages(LlmRequest request, boolean reasoning) {
        List<ChatRequestMessage> messages = new ArrayList<>();
        for (LlmMessage message : request.messages()) {
            String role = message.role() == null ? "" : message.role();
            if ("system".equalsIgnoreCase(role)) {
                // Reasoning models take instructions as a developer message; `system` is rejected
                // or silently downgraded depending on the model generation.
                messages
                    .add(reasoning ? new ChatRequestDeveloperMessage(nullSafe(message.content()))
                        : new ChatRequestSystemMessage(nullSafe(message.content())));
            } else if ("assistant".equalsIgnoreCase(role)) {
                ChatRequestAssistantMessage assistant =
                    new ChatRequestAssistantMessage(nullSafe(message.content()));
                if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                    List<ChatCompletionsToolCall> toolCalls = new ArrayList<>();
                    for (LlmToolCall toolCall : message.toolCalls()) {
                        toolCalls.add(new ChatCompletionsFunctionToolCall(toolCall.id(),
                            new FunctionCall(toolCall.name(), nullSafe(toolCall.arguments()))));
                    }
                    assistant.setToolCalls(toolCalls);
                }
                messages.add(assistant);
            } else if ("tool".equalsIgnoreCase(role)) {
                messages.add(
                    new ChatRequestToolMessage(nullSafe(message.content()), message.toolCallId()));
            } else {
                messages.add(new ChatRequestUserMessage(nullSafe(message.content())));
            }
        }
        return messages;
    }

    private void applyResponseFormat(LlmRequest request, ChatCompletionsOptions options) {
        boolean hasSchema = request.responseSchema() != null && !request.responseSchema().isEmpty();
        boolean wantsJson =
            request.responseMimeType() != null && !request.responseMimeType().isBlank();
        if (!hasSchema && !wantsJson) {
            return;
        }
        if (hasSchema) {
            BinaryData schema = toBinaryData(request.responseSchema(), "response schema");
            if (schema != null) {
                // Left non-strict on purpose: OpenAI's strict mode additionally requires
                // additionalProperties:false and every property listed as required, which the
                // schemas this app sends do not satisfy.
                options.setResponseFormat(new ChatCompletionsJsonSchemaResponseFormat(
                    new ChatCompletionsJsonSchemaResponseFormatJsonSchema("response")
                        .setSchema(schema)));
                return;
            }
        }
        options.setResponseFormat(new ChatCompletionsJsonResponseFormat());
    }

    private void applyTools(LlmRequest request, ChatCompletionsOptions options) {
        if (request.tools() == null || request.tools().isEmpty()) {
            // Neither tools nor toolChoice: the service rejects a tool_choice with no tools, and an
            // absent tools array already means "do not call functions".
            return;
        }
        List<ChatCompletionsToolDefinition> definitions = new ArrayList<>();
        for (LlmTool tool : request.tools()) {
            ChatCompletionsFunctionToolDefinitionFunction function =
                new ChatCompletionsFunctionToolDefinitionFunction(tool.name());
            if (tool.description() != null) {
                function.setDescription(tool.description());
            }
            if (tool.inputSchema() != null && !tool.inputSchema().isEmpty()) {
                // Serialized verbatim so keywords the service understands but a lossy re-model
                // would drop ($ref, oneOf, additionalProperties) survive.
                BinaryData parameters =
                    toBinaryData(tool.inputSchema(), "input schema for tool " + tool.name());
                if (parameters != null) {
                    function.setParameters(parameters);
                }
            }
            log.debug("AzureOpenAiLlmClient registered tool for API: {}", tool.name());
            definitions.add(new ChatCompletionsFunctionToolDefinition(function));
        }
        options.setTools(definitions);
        // Stated rather than inherited. Left unset, azure-json omits the null Boolean entirely and
        // the SERVICE default applies — which is true, as this app's own captured wire fixtures
        // show. That matters here more than usual: parallel calls are exactly the case the streamed
        // reassembly cannot verify, because ChatCompletionsToolCall's deserializer discards the
        // wire-level index used to tell them apart (see toolCallDeltas). Keeping the current
        // behaviour, but visible in the code and pinned by a test, so that turning it off is a
        // one-line decision rather than an archaeology exercise.
        options.setParallelToolCalls(true);
    }

    private BinaryData toBinaryData(Map<String, Object> value, String what) {
        try {
            return BinaryData.fromString(objectMapper.writeValueAsString(value));
        } catch (Exception e) {
            log.error("Failed to serialize {}; sending the request without it", what, e);
            return null;
        }
    }

    /**
     * Resolves the reasoning effort for this request, preferring the per-request level over the
     * client-wide default. Returns {@code null} when thinking is disabled or the level is unusable,
     * leaving the model's own default in place.
     */
    private ReasoningEffortValue resolveReasoningEffort(LlmRequest request) {
        if (!enableThinking) {
            return null;
        }
        String requested = request.thinkingLevel() != null && !request.thinkingLevel().isBlank()
            ? request.thinkingLevel()
            : thinkingLevel;
        if (requested == null || requested.isBlank()) {
            return null;
        }
        String normalized = requested.trim().toLowerCase(Locale.ROOT);
        // Gemini's vocabulary is MINIMAL/LOW/MEDIUM/HIGH; OpenAI names the first one "minimal" too,
        // so the levels map across one-for-one. Anything else is left to the model's default rather
        // than guessed at, since an unknown value is a 400.
        if (!List.of("minimal", "low", "medium", "high").contains(normalized)) {
            log.warn("Invalid thinkingLevel '{}'; expected one of MINIMAL/LOW/MEDIUM/HIGH. "
                + "Leaving reasoning effort unset.", requested);
            return null;
        }
        return ReasoningEffortValue.fromString(normalized);
    }

    /**
     * Whether a deployment addresses a reasoning model. An explicitly configured list wins
     * outright: Azure deployment names are operator-chosen free text, so a name heuristic alone
     * cannot be relied on, and being wrong in either direction is a 400 on every call.
     */
    boolean isReasoningModel(String model) {
        if (model == null) {
            return false;
        }
        String normalized = model.trim().toLowerCase(Locale.ROOT);
        if (!reasoningModels.isEmpty()) {
            return reasoningModels.contains(normalized);
        }
        return REASONING_MODEL_PATTERN.matcher(normalized).find();
    }

    private static Set<String> parseReasoningModels(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        Set<String> names = new LinkedHashSet<>();
        for (String name : Arrays.asList(csv.split(","))) {
            String trimmed = name.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                names.add(trimmed);
            }
        }
        return names;
    }

    /**
     * The caller's principal, forwarded as the request's {@code user} field for Azure's abuse
     * monitoring — the same attribution {@code CloudflareGeminiLlmClient} sends as gateway
     * metadata.
     */
    private static Optional<String> currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            return Optional.empty();
        }
        return Optional.ofNullable(auth.getName());
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
