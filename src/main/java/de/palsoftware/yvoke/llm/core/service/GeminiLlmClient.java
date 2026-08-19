package de.palsoftware.yvoke.llm.core.service;

import de.palsoftware.yvoke.llm.core.LlmRetry;
import de.palsoftware.yvoke.llm.core.model.LlmCallFailedException;
import de.palsoftware.yvoke.llm.core.model.LlmGatewayInfo;
import de.palsoftware.yvoke.llm.core.model.LlmMessage;
import de.palsoftware.yvoke.llm.core.model.LlmPart;
import de.palsoftware.yvoke.llm.core.model.LlmRequest;
import de.palsoftware.yvoke.llm.core.model.LlmResponse;
import de.palsoftware.yvoke.llm.core.model.LlmResponseChunk;
import de.palsoftware.yvoke.llm.core.model.LlmTool;
import de.palsoftware.yvoke.llm.core.model.LlmToolCall;
import de.palsoftware.yvoke.llm.core.model.LlmToolCallDelta;
import de.palsoftware.yvoke.llm.core.model.LlmUsage;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.ClientOptions;
import com.google.genai.types.HttpResponse;
import com.google.genai.ResponseStream;
import com.google.genai.types.*;
import jakarta.annotation.PreDestroy;
import okhttp3.OkHttpClient;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;

public class GeminiLlmClient implements LlmClient, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(GeminiLlmClient.class);

    /**
     * How long a single socket read may block: the wait for response headers, and thereafter the
     * gap between SSE tokens. NOT a budget for the whole answer — see {@link #httpClientBuilder}.
     */
    static final int HTTP_TIMEOUT_MS = 300_000;

    /** TCP/TLS only, matching {@code AzureOpenAiLlmClient}. */
    static final int CONNECT_TIMEOUT_MS = 10_000;

    /**
     * The one place the HTTP timeouts for this client family are decided.
     *
     * <p>
     * The shape matters more than the numbers. A <b>read</b> timeout in OkHttp is the socket
     * {@code SO_TIMEOUT}: it bounds each individual read, which covers both the wait for the
     * response headers and every later gap between SSE tokens. That is exactly the pair of bounds
     * an LLM call needs — "the service never answered" and "the stream went silent" — and it is
     * expressible here in one setting because, unlike azure-core's read timeout, OkHttp's applies
     * before the body exists.
     *
     * <p>
     * A <b>call</b> timeout is the one bound that must NOT be set. It spans the entire call
     * including draining the body, so for SSE it is a hard wall-clock cap on how long an answer may
     * take: a perfectly healthy generation emitting a token every second died at 300s with
     * {@code InterruptedIOException: timeout}, and since {@code RagService} does not catch there,
     * the whole agentic run died with it and every prior turn's tool results were discarded. It is
     * left at zero deliberately — the liveness question is "has anything arrived recently?", never
     * "has this taken too long?".
     *
     * <p>
     * This is also why {@code HttpOptions.timeout()} is no longer set: the SDK turns that value
     * into precisely the {@code callTimeout} above, but only on the client it builds itself — so it
     * produced the wrong bound on the plain path and silently no bound at all on the Cloudflare
     * one, two different wrong answers from one setting. Supplying the client here makes both paths
     * take the same configuration by construction.
     */
    static OkHttpClient.Builder httpClientBuilder(int readTimeoutMs) {
        return new OkHttpClient.Builder().connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
            .readTimeout(Duration.ofMillis(readTimeoutMs)).writeTimeout(Duration.ZERO)
            .callTimeout(Duration.ZERO);
    }

    /**
     * How many HTTP attempts the SDK itself may make per call.
     *
     * <p>
     * The SDK installs a {@code RetryInterceptor} on every client — the {@code addInterceptor} call
     * in {@code ApiClient.createHttpClient} sits outside the custom-vs-default branch, so a custom
     * {@link okhttp3.OkHttpClient} does not opt out of it — and its default of 5 attempts retries
     * exactly the codes {@link de.palsoftware.yvoke.llm.core.LlmRetry} does. Left alone the two
     * layers multiply: 3 {@code LlmRetry} attempts became up to 15 requests, each re-uploading a
     * ~600k-token prompt into the quota that had just returned 429, so the window never reset.
     * Pinning this to 1 leaves {@code LlmRetry} the single retry authority.
     */
    static final int SDK_HTTP_ATTEMPTS = 1;

    /**
     * Finish reasons that end a stream normally, held as RAW wire strings.
     *
     * <p>
     * Deliberately not {@code FinishReason.Known} constants. {@code FinishReason(String)} maps
     * every value it does not recognise onto {@code Known.FINISH_REASON_UNSPECIFIED}, which is
     * itself benign — so a comparison against the enum cannot distinguish "the model stopped for a
     * reason this SDK version has not learned" from "unspecified", and answers the question wrongly
     * in the direction that hides the failure. The raw string is the only form that survives an SDK
     * that is older than the service.
     */
    private static final Set<String> BENIGN_FINISH_REASONS =
        Set.of("STOP", "MAX_TOKENS", "FINISH_REASON_UNSPECIFIED");

    private final Client client;
    private final ObjectMapper objectMapper;
    private final boolean enableThinking;
    private final String thinkingLevel;
    private volatile boolean closed = false;

    public GeminiLlmClient(String apiKey, ObjectMapper objectMapper, boolean enableThinking,
        String thinkingLevel) {
        this(apiKey, objectMapper, enableThinking, thinkingLevel, null);
    }

    public GeminiLlmClient(String apiKey, ObjectMapper objectMapper, boolean enableThinking,
        String thinkingLevel, String baseUrl) {
        this(apiKey, objectMapper, enableThinking, thinkingLevel, baseUrl, null);
    }

    public GeminiLlmClient(String apiKey, ObjectMapper objectMapper, boolean enableThinking,
        String thinkingLevel, String baseUrl, Map<String, String> headers) {
        this(apiKey, objectMapper, enableThinking, thinkingLevel, baseUrl, headers, null);
    }

    public GeminiLlmClient(String apiKey, ObjectMapper objectMapper, boolean enableThinking,
        String thinkingLevel, String baseUrl, Map<String, String> headers,
        ClientOptions clientOptions) {
        this.enableThinking = enableThinking;
        this.thinkingLevel = thinkingLevel;
        log.info(
            "Initializing GeminiLlmClient (Google AI Studio / Developer API) with thinkingLevel={}, baseUrl={}, headers={}",
            thinkingLevel, baseUrl, headers != null ? headers.keySet() : "none");

        // No timeout() here: see httpClientBuilder. The SDK would convert it into a callTimeout —
        // a cap on total generation — and only on a client it builds itself, so the same setting
        // meant two different wrong things on the two paths.
        HttpOptions.Builder httpOptionsBuilder = HttpOptions.builder()
            .retryOptions(HttpRetryOptions.builder().attempts(SDK_HTTP_ATTEMPTS).build());
        if (baseUrl != null && !baseUrl.isBlank()) {
            httpOptionsBuilder.baseUrl(baseUrl);
        }
        if (headers != null && !headers.isEmpty()) {
            httpOptionsBuilder.headers(headers);
        }

        // A ClientOptions is ALWAYS supplied, so the SDK's own transport branch — the one that
        // applies a callTimeout — is never taken. Both this client and CloudflareGeminiLlmClient
        // therefore get their timeouts from httpClientBuilder and cannot diverge.
        ClientOptions resolvedOptions = clientOptions != null ? clientOptions
            : ClientOptions.builder().customHttpClient(httpClientBuilder(HTTP_TIMEOUT_MS).build())
                .build();
        this.client = Client.builder().apiKey(apiKey).httpOptions(httpOptionsBuilder.build())
            .clientOptions(resolvedOptions).build();

        this.objectMapper = objectMapper.copy().registerModule(new Jdk8Module())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Releases the underlying HTTP client / connection pool. Invoked by Spring on context shutdown
     * because this client is registered as a singleton bean.
     */
    @Override
    @PreDestroy
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            client.close();
        } catch (Exception e) {
            log.warn("Failed to close Gemini Client", e);
        }
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        log.info("Sending non-streaming request to Gemini: model={}", request.model());
        List<Content> contents = buildContents(request);
        if (contents.isEmpty()) {
            throw new IllegalArgumentException(
                "Cannot call Gemini with empty contents (request contained no user/assistant/tool "
                    + "messages)");
        }
        GenerateContentConfig config = buildConfig(request);
        return LlmRetry.withRetry("Gemini.generate", 3, () -> {
            GenerateContentResponse response =
                client.models.generateContent(request.model(), contents, config);

            String text = null;
            Exception textError = null;
            try {
                text = response.text();
            } catch (Exception e) {
                // response.text() can throw on empty/blocked candidates; keep the cause for
                // context.
                textError = e;
            }
            int promptTokens = 0;
            int completionTokens = 0;
            int totalTokens = 0;
            int cachedTokens = 0;
            int thoughtTokens = 0;

            if (response.usageMetadata().isPresent()) {
                GenerateContentResponseUsageMetadata usageMetadata = response.usageMetadata().get();
                promptTokens = promptTokensOf(usageMetadata);
                completionTokens = usageMetadata.candidatesTokenCount().orElse(0);
                totalTokens = usageMetadata.totalTokenCount().orElse(0);
                cachedTokens = usageMetadata.cachedContentTokenCount().orElse(0);
                thoughtTokens = usageMetadata.thoughtsTokenCount().orElse(0);
            }

            LlmUsage usage = new LlmUsage(promptTokens, completionTokens, totalTokens, cachedTokens,
                thoughtTokens);

            if (text == null) {
                // Surface safety blocks / recitation / empty candidates instead of returning a
                // silent null that NPEs downstream. Usage is read FIRST and carried on the
                // exception: the provider billed for these tokens, and throwing before reading it
                // left the call with no llm_call_logs row at all.
                throw new LlmCallFailedException(
                    "Gemini returned no text content (" + describeMissingText(response) + ")",
                    textError, usage);
            }

            return new LlmResponse(text, usage, gatewayInfo(response));
        });
    }

    /**
     * Prompt tokens, INCLUDING what server-side tool results fed back into the model.
     *
     * <p>
     * {@code toolUsePromptTokenCount} is documented as "the number of tokens in the results from
     * tool executions, which are provided back to the model as input", and {@code totalTokenCount}
     * as the sum of prompt + candidates + toolUse + thoughts — so it is disjoint from
     * {@code promptTokenCount}, not contained in it. Dropping it left the four components unable to
     * sum to the total, and since {@code CostCalculationService} re-derives the total from those
     * components at current rates, the difference was priced at zero on exactly the runs that use
     * it: code execution, where a result can run to thousands of tokens.
     *
     * <p>
     * Folded in here rather than carried as a sixth {@link LlmUsage} field because that is how the
     * provider bills it — it is prompt input — so it needs no column, no migration and no pricing
     * rule, and both the persisted ledger and the dashboard become correct in one place. Read by
     * BOTH the blocking and streaming paths, which are otherwise two independent mappings of one
     * contract.
     */
    private static int promptTokensOf(GenerateContentResponseUsageMetadata metadata) {
        return metadata.promptTokenCount().orElse(0) + metadata.toolUsePromptTokenCount().orElse(0);
    }

    /**
     * Whether a streamed finish reason ends the turn normally.
     *
     * <p>
     * Reads {@link FinishReason#toString()}, which the SDK defines as the raw wire value, and never
     * {@code knownEnum()}. This is the single place that decision is made, so the enum-vs-string
     * mistake cannot be reintroduced at a second call site. Case-insensitive, matching the SDK's
     * own {@code Ascii.equalsIgnoreCase} lookup. A blank value is treated as benign: an absent
     * reason already means "still generating" and never reaches here.
     */
    private static boolean isBenignFinishReason(FinishReason finishReason) {
        String raw = finishReason.toString();
        if (raw == null || raw.isBlank()) {
            return true;
        }
        return BENIGN_FINISH_REASONS.contains(raw.trim().toUpperCase(Locale.ROOT));
    }

    /** Builds a human-readable explanation for a response that carries no text. */
    private static String describeMissingText(GenerateContentResponse response) {
        if (response.candidates().isEmpty() || response.candidates().get().isEmpty()) {
            return "no candidates; promptFeedback="
                + response.promptFeedback().map(Object::toString).orElse("none");
        }
        Optional<FinishReason> finishReason = response.candidates().get().get(0).finishReason();
        return "finishReason=" + finishReason.map(FinishReason::toString).orElse("unknown");
    }

    @Override
    public void generateStream(LlmRequest request, Consumer<LlmResponseChunk> onChunk) {
        String streamId = UUID.randomUUID().toString().substring(0, 8);
        log.info("Sending streaming request to Gemini: model={}, streamId={}", request.model(),
            streamId);
        List<Content> contents = buildContents(request);
        if (contents.isEmpty()) {
            throw new IllegalArgumentException(
                "Cannot call Gemini with empty contents (request contained no user/assistant/tool "
                    + "messages)");
        }
        GenerateContentConfig config = buildConfig(request);

        // Retry only the stream establishment; a transient failure here throws before any chunk is
        // emitted, so retrying cannot duplicate already-streamed output. Mid-stream failures are
        // intentionally NOT retried (they cannot be safely resumed). Blocks on the calling thread.
        ResponseStream<GenerateContentResponse> stream = LlmRetry.withRetry("Gemini.generateStream",
            3, () -> client.models.generateContentStream(request.model(), contents, config));
        StreamOutcome outcome = new StreamOutcome();
        try {
            for (GenerateContentResponse res : stream) {
                if (Thread.currentThread().isInterrupted()) {
                    log.info("Gemini stream interrupted, stopping chunk processing (streamId={})",
                        streamId);
                    throw new CancellationException("Gemini stream interrupted");
                }
                onChunk.accept(parseChunk(res, streamId, outcome));
            }
        } catch (RuntimeException e) {
            if (Thread.currentThread().isInterrupted() || e instanceof CancellationException
                || e.getCause() instanceof InterruptedException
                || e.getCause() instanceof CancellationException) {
                log.info("Gemini stream cancelled/interrupted (streamId={})", streamId);
                throw new CancellationException("Gemini stream interrupted");
            }
            log.error("Gemini stream error for model={}, streamId={}", request.model(), streamId,
                e);
            throw e;
        } finally {
            try {
                stream.close();
            } catch (Exception e) {
                log.warn("Failed to close response stream (streamId={})", streamId, e);
            }
        }

        // A stream that ends having produced neither text nor a tool call is a failure, not a quiet
        // success. Only this client can tell: by the time the turn reaches RagService the evidence
        // is a `thought` part like any other, its parts-keyed guard passes, the loop ends, the
        // message is persisted `done`, and the UI hides <think> — so the user is shown a blank
        // message with no error at all. Usage rides the exception because those tokens were billed;
        // AccountingLlmClient has already observed the same snapshot off the chunks.
        //
        // Deliberately NOT re-requested, unlike AzureOpenAiLlmClient. That client's bounded
        // retry is safe for one precise reason: "empty" there means nothing whatsoever
        // reached the consumer. That reason does not hold here. Gemini streams its reasoning,
        // so the <think> block is already on the user's screen, and a second attempt would
        // replay one underneath it. Recovery, if it is ever wanted, belongs in RagService,
        // which can re-prompt with the reasoning suppressed.
        if (outcome.isEmpty()) {
            log.warn("Gemini produced an empty turn for model={}, streamId={} ({})",
                request.model(), streamId, outcome.describe());
            throw new LlmCallFailedException(
                "Gemini produced no content and no tool calls (" + outcome.describe() + ")", null,
                outcome.usage);
        }
        log.info("Gemini stream completed successfully for model={}, streamId={}", request.model(),
            streamId);
    }

    /**
     * What a stream actually produced, so an empty one can say why instead of being reported as a
     * success the caller then has to guess about. Mirrors
     * {@code AzureOpenAiLlmClient.StreamOutcome} — the two clients answer the same question and
     * must answer it the same way.
     */
    private static final class StreamOutcome {
        private boolean sawContent;
        private boolean sawToolCall;
        private String finishReason;
        private int events;
        private LlmUsage usage;

        /**
         * A thought part is deliberately not content. A turn that only thought produced nothing the
         * caller can use, and that is precisely the condition being detected — counting reasoning
         * here would make the check unable to fire on the one case it exists for.
         */
        boolean isEmpty() {
            return !sawContent && !sawToolCall;
        }

        String describe() {
            StringBuilder out = new StringBuilder();
            out.append("finishReason=").append(finishReason == null ? "none" : finishReason);
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

    /**
     * Reads the AI-gateway headers the SDK preserves on the response.
     *
     * <p>
     * {@code Models.processResponseForPrivateGenerateContent} attaches the HTTP headers to the
     * parsed response, and the streaming path stamps them onto every chunk, so no interceptor or
     * ThreadLocal is involved — the value travels with the response object itself. Returns
     * {@code null} when no gateway header is present.
     */
    private static LlmGatewayInfo gatewayInfo(GenerateContentResponse response) {
        if (response == null) {
            return null;
        }
        return response.sdkHttpResponse().flatMap(HttpResponse::headers)
            .map(LlmGatewayInfo::fromHeaders).orElse(null);
    }

    private LlmResponseChunk parseChunk(GenerateContentResponse res, String streamId,
        StreamOutcome outcome) {
        outcome.events++;
        StringBuilder contentBuilder = new StringBuilder();
        StringBuilder reasoningBuilder = new StringBuilder();
        List<LlmToolCallDelta> toolDeltas = null;
        LlmUsage usage = null;
        List<LlmPart> chunkParts = new ArrayList<>();

        // Read FIRST, before anything below can throw. A blocked or otherwise abnormal turn still
        // burned the whole prompt, and the finish-reason check further down is a throw site — with
        // the read left at the bottom those tokens reached neither a chunk nor the exception, so
        // AccountingLlmClient's null guard skipped the write and the call left no llm_call_logs
        // row.
        // Gemini reports an absolute whole-request snapshot on every event that carries usage,
        // never a per-event delta, so the last one seen is the total for the call.
        if (res.usageMetadata().isPresent()) {
            GenerateContentResponseUsageMetadata metadata = res.usageMetadata().get();
            usage = new LlmUsage(promptTokensOf(metadata),
                metadata.candidatesTokenCount().orElse(0), metadata.totalTokenCount().orElse(0),
                metadata.cachedContentTokenCount().orElse(0),
                metadata.thoughtsTokenCount().orElse(0));
            outcome.usage = usage;
        }

        // A prompt-side block arrives with promptFeedback and NO candidates, so the branch below
        // skips it entirely and the blockReason — the only thing that says why — is discarded.
        // RagService still fails the turn descriptively, so this is diagnosis, not correctness:
        // without it a safety block and a malformed function call are indistinguishable in the log.
        if (res.candidates().isEmpty() || res.candidates().get().isEmpty()) {
            res.promptFeedback()
                .ifPresent(feedback -> log.warn(
                    "Gemini returned no candidates for streamId={}; promptFeedback={}", streamId,
                    feedback));
        }

        if (res.candidates().isPresent() && !res.candidates().get().isEmpty()) {
            Candidate candidate = res.candidates().get().get(0);
            if (candidate.content().isPresent() && candidate.content().get().parts().isPresent()) {
                List<Part> parts = candidate.content().get().parts().get();
                for (Part part : parts) {
                    String type = "text";
                    String text = null;
                    LlmToolCall toolCall = null;
                    String thoughtSig = null;

                    if (part.thoughtSignature().isPresent()) {
                        thoughtSig =
                            Base64.getEncoder().encodeToString(part.thoughtSignature().get());
                    }

                    if (part.thought().orElse(false)) {
                        type = "thought";
                        text = part.text().orElse(null);
                        if (text != null) {
                            reasoningBuilder.append(text);
                        }
                    } else if (part.text().isPresent()) {
                        type = "text";
                        text = part.text().get();
                        contentBuilder.append(text);
                    }

                    if (part.functionCall().isPresent()) {
                        type = "function_call";
                        FunctionCall fc = part.functionCall().get();
                        String name = fc.name().orElse(null);
                        String id = fc.id().orElse(null);
                        if (id == null || id.isEmpty()) {
                            id = "call_" + (name != null ? name : "fn") + "_" + UUID.randomUUID();
                        }

                        String argsStr = "{}";
                        if (fc.args().isPresent()) {
                            try {
                                argsStr = objectMapper.writeValueAsString(fc.args().get());
                            } catch (Exception e) {
                                log.error("Failed to serialize function call args to string", e);
                            }
                        }
                        log.debug("Gemini returned function call: name={}, id={}, streamId={}",
                            name, id, streamId);

                        if (toolDeltas == null) {
                            toolDeltas = new ArrayList<>();
                        }
                        // Gemini emits complete (non-incremental) function calls. Mark the delta
                        // complete so the consumer keys by call id and replaces (not appends) the
                        // final arguments — robust even if a call id is re-delivered. index is
                        // unused.
                        toolDeltas
                            .add(new LlmToolCallDelta(-1, id, name, argsStr, thoughtSig, true));
                        toolCall = new LlmToolCall(id, "function", name, argsStr,
                            thoughtSig != null ? Map.of("thoughtSignature", thoughtSig) : null);
                    }

                    // Built-in server-side code execution: surface the generated code and its
                    // output as visible markdown so the run is transparent to the user.
                    if (part.executableCode().isPresent()) {
                        type = "text";
                        String code = part.executableCode().get().code().orElse("");
                        text = "\n\n<code-execution>\n```python\n" + code
                            + "\n```\n</code-execution>\n\n";
                        contentBuilder.append(text);
                    } else if (part.codeExecutionResult().isPresent()) {
                        type = "text";
                        String output = part.codeExecutionResult().get().output().orElse("");
                        text = "\n\n<code-execution>\n**Code execution result:**\n```\n" + output
                            + "\n```\n</code-execution>\n\n";
                        contentBuilder.append(text);
                    }

                    chunkParts.add(new LlmPart(type, text, toolCall, thoughtSig));
                }
            }

            candidate.finishReason().ifPresent(finishReason -> {
                // Recorded before the abnormality check so an empty turn can name the reason that
                // made it look like a success.
                outcome.finishReason = finishReason.toString();
                if (!isBenignFinishReason(finishReason)) {
                    log.warn("Gemini stream finished abnormally: finishReason={}, streamId={}",
                        finishReason, streamId);
                    // LlmCallFailedException, not a bare RuntimeException: this turn was billed and
                    // the usage read above is the only thing that can still report it. A bare
                    // RuntimeException has nowhere to put the tokens, which is how the ordering
                    // defect stayed invisible — the type made the loss unrepairable at the throw.
                    throw new LlmCallFailedException(
                        "Gemini stream finished abnormally: finishReason=" + finishReason, null,
                        outcome.usage);
                }
            });
        }

        String content = contentBuilder.length() > 0 ? contentBuilder.toString() : null;
        String reasoning = reasoningBuilder.length() > 0 ? reasoningBuilder.toString() : null;

        // Read off the finished values rather than flagged inside the part loop, so executable code
        // and its result — which append to contentBuilder — count as content by construction.
        if (content != null) {
            outcome.sawContent = true;
        }
        if (toolDeltas != null && !toolDeltas.isEmpty()) {
            outcome.sawToolCall = true;
        }
        return new LlmResponseChunk(content, reasoning, toolDeltas, usage, chunkParts,
            gatewayInfo(res));
    }

    @SuppressWarnings("unchecked")
    private List<Content> buildContents(LlmRequest request) {
        List<Content> contents = new ArrayList<>();
        for (LlmMessage msg : request.messages()) {
            if ("system".equalsIgnoreCase(msg.role())) {
                // System instructions are passed in GenerateContentConfig
                continue;
            }

            String role = "user";
            if ("assistant".equalsIgnoreCase(msg.role())) {
                role = "model";
            } else if ("tool".equalsIgnoreCase(msg.role())) {
                // Function-response turns must use role "user" per the Gemini Content contract
                // (role must be either "user" or "model").
                role = "user";
            }

            List<Part> parts = new ArrayList<>();

            if (msg.parts() != null && !msg.parts().isEmpty()) {
                for (LlmPart lp : msg.parts()) {
                    Part.Builder partBuilder = Part.builder();
                    if ("thought".equals(lp.type())) {
                        partBuilder.thought(true).text(lp.text());
                    } else if ("text".equals(lp.type())) {
                        partBuilder.text(lp.text());
                    } else if ("function_call".equals(lp.type())) {
                        LlmToolCall tc = lp.toolCall();
                        if (tc != null) {
                            try {
                                Map<String, Object> argsMap =
                                    objectMapper.readValue(tc.arguments(), Map.class);
                                FunctionCall fc =
                                    FunctionCall.builder().name(tc.name()).args(argsMap).build();
                                partBuilder.functionCall(fc);
                            } catch (Exception e) {
                                log.error("Failed to parse function call args for tool {}",
                                    tc.name(), e);
                            }
                        }
                    } else if ("function_response".equals(lp.type())) {
                        try {
                            Map<String, Object> responseMap =
                                Map.of("output", lp.text() != null ? lp.text() : "");
                            FunctionResponse fr = FunctionResponse.builder()
                                .name(resolveFunctionName(msg)).response(responseMap).build();
                            partBuilder.functionResponse(fr);
                        } catch (Exception e) {
                            log.error("Failed to construct function response in parts", e);
                        }
                    }

                    if (lp.thoughtSignature() != null && !lp.thoughtSignature().isEmpty()) {
                        try {
                            byte[] sigBytes = Base64.getDecoder().decode(lp.thoughtSignature());
                            partBuilder.thoughtSignature(sigBytes);
                        } catch (Exception e) {
                            log.error("Failed to decode thought signature in parts", e);
                        }
                    }
                    parts.add(partBuilder.build());
                }
            } else {
                if (msg.content() != null && !msg.content().isEmpty()
                    && !"tool".equalsIgnoreCase(msg.role())) {
                    parts.add(Part.builder().text(msg.content()).build());
                }

                if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                    for (LlmToolCall tc : msg.toolCalls()) {
                        try {
                            Map<String, Object> argsMap =
                                objectMapper.readValue(tc.arguments(), Map.class);
                            FunctionCall fc =
                                FunctionCall.builder().name(tc.name()).args(argsMap).build();
                            Part.Builder partBuilder = Part.builder().functionCall(fc);
                            if (tc.extraContent() != null
                                && tc.extraContent().containsKey("thoughtSignature")) {
                                String base64Sig =
                                    (String) tc.extraContent().get("thoughtSignature");
                                if (base64Sig != null && !base64Sig.isEmpty()) {
                                    byte[] sigBytes = Base64.getDecoder().decode(base64Sig);
                                    partBuilder.thoughtSignature(sigBytes);
                                }
                            }
                            parts.add(partBuilder.build());
                        } catch (Exception e) {
                            log.error("Failed to parse function call args for tool {}", tc.name(),
                                e);
                        }
                    }
                }

                if ("tool".equalsIgnoreCase(msg.role())) {
                    try {
                        Map<String, Object> responseMap =
                            Map.of("output", msg.content() != null ? msg.content() : "");
                        FunctionResponse fr = FunctionResponse.builder()
                            .name(resolveFunctionName(msg)).response(responseMap).build();
                        parts.add(Part.builder().functionResponse(fr).build());
                    } catch (Exception e) {
                        log.error("Failed to construct function response for call {}",
                            msg.toolCallId(), e);
                    }
                }
            }

            if (!parts.isEmpty()) {
                contents.add(Content.builder().role(role).parts(parts).build());
            }
        }
        return contents;
    }

    /**
     * Resolves the function name for a function-response turn.
     *
     * <p>
     * Gemini pairs a response to its call by NAME and ORDER. {@code FunctionCall.id} does exist in
     * the SDK — an earlier version of this comment claimed it did not — but the SDK's own round
     * trip is name-based, its core never reads the field, and only the Live API requires the id. It
     * is deliberately not propagated: {@code parseChunk} mints a synthetic
     * {@code call_<name>_<uuid>} when the model supplies none, so forwarding it would assert a
     * match to a call id the model never issued. The invariant this rests on is that
     * {@code RagService.executeToolCalls} emits exactly one response per call, in order.
     */
    private String resolveFunctionName(LlmMessage msg) {
        if (msg.toolName() != null && !msg.toolName().isBlank()) {
            return msg.toolName();
        }
        log.warn(
            "Function response is missing toolName; falling back to toolCallId={} which will NOT "
                + "match a declared function and may confuse the model",
            msg.toolCallId());
        return msg.toolCallId();
    }

    GenerateContentConfig buildConfig(LlmRequest request) {
        GenerateContentConfig.Builder builder = GenerateContentConfig.builder();

        // Apply sampling temperature (callers set deterministic 0.0 for extraction/RAG).
        builder.temperature((float) request.temperature());

        // Only cap output tokens when a positive value is provided; sending 0 would truncate
        // output.
        if (request.maxTokens() > 0) {
            builder.maxOutputTokens(request.maxTokens());
        }

        // Optional decoding seed for reproducible output on deterministic calls.
        if (request.seed() != null) {
            builder.seed(request.seed());
        }

        boolean hasTools = request.tools() != null && !request.tools().isEmpty();

        // Structured output (JSON mode / response schema). Gemini does not support this together
        // with
        // function calling, so it is only applied when no tools are supplied.
        boolean wantsStructuredOutput =
            (request.responseMimeType() != null && !request.responseMimeType().isBlank())
                || (request.responseSchema() != null && !request.responseSchema().isEmpty());
        if (wantsStructuredOutput && hasTools) {
            log.warn("Ignoring responseMimeType/responseSchema because tools are present "
                + "(Gemini does not support structured output with function calling)");
        } else if (wantsStructuredOutput) {
            if (request.responseMimeType() != null && !request.responseMimeType().isBlank()) {
                builder.responseMimeType(request.responseMimeType());
            }
            if (request.responseSchema() != null && !request.responseSchema().isEmpty()) {
                // Raw JSON Schema (responseJsonSchema) preserves the full schema; responseSchema()
                // would silently drop keywords outside Gemini's OpenAPI subset
                // (additionalProperties,
                // $ref, oneOf, ...).
                builder.responseJsonSchema(request.responseSchema());
            }
        }

        if (modelSupportsThinking(request.model())) {
            // A thinkingConfig is sent either way, because "send nothing" does not mean "do not
            // think" — it means "use the model's own default", which on a thinking model is to
            // think. The three fields are independent: includeThoughts governs whether the thoughts
            // come BACK, thinkingBudget is the only lever that actually turns thinking off, and
            // thinkingLevel sets how hard. Gating the whole object on the flag therefore made
            // enable-thinking=false a no-op that ALSO discarded the configured level, so a
            // summarize
            // or KG-extract call asking for `low` could end up reasoning harder, not less.
            ThinkingConfig.Builder thinkingBuilder =
                ThinkingConfig.builder().includeThoughts(enableThinking);
            if (enableThinking) {
                // Prefer the per-request thinking level; fall back to the client-wide default.
                String requestedLevel =
                    (request.thinkingLevel() != null && !request.thinkingLevel().isBlank())
                        ? request.thinkingLevel()
                        : this.thinkingLevel;
                ThinkingLevel.Known level = parseThinkingLevel(requestedLevel);
                if (level != null) {
                    thinkingBuilder.thinkingLevel(level);
                }
            } else {
                thinkingBuilder.thinkingBudget(0);
            }
            builder.thinkingConfig(thinkingBuilder.build());
        }

        // Extract system prompt
        String systemPrompt = null;
        for (LlmMessage msg : request.messages()) {
            if ("system".equalsIgnoreCase(msg.role())) {
                systemPrompt = msg.content();
                break;
            }
        }

        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            builder.systemInstruction(Content.builder()
                .parts(List.of(Part.builder().text(systemPrompt).build())).build());
        }

        // Add tools: custom function declarations and/or the built-in code-execution tool.
        List<Tool> toolsList = new ArrayList<>();
        List<FunctionDeclaration> functionDeclarations = new ArrayList<>();

        if (request.tools() != null && !request.tools().isEmpty()) {
            for (LlmTool tool : request.tools()) {
                // Pass the raw JSON Schema so keywords outside Gemini's OpenAPI subset
                // (additionalProperties, $ref, oneOf, ...) are preserved rather than silently
                // dropped
                // by a lossy Schema round-trip.
                FunctionDeclaration.Builder fdBuilder =
                    FunctionDeclaration.builder().name(tool.name()).description(tool.description());
                if (tool.inputSchema() != null && !tool.inputSchema().isEmpty()) {
                    fdBuilder.parametersJsonSchema(tool.inputSchema());
                }
                log.debug("GeminiLlmClient registered tool for API: {}", tool.name());
                functionDeclarations.add(fdBuilder.build());
            }
        }

        if (!functionDeclarations.isEmpty()) {
            toolsList.add(Tool.builder().functionDeclarations(functionDeclarations).build());
        }
        if (request.codeExecution()) {
            toolsList
                .add(Tool.builder().codeExecution(ToolCodeExecution.builder().build()).build());
            log.debug("GeminiLlmClient enabled built-in code execution tool");
        }

        if (!toolsList.isEmpty()) {
            builder.tools(toolsList);
            // Combining the built-in code-execution tool with custom function declarations requires
            // opting into server-side tool invocations (Gemini 3+). Harmless when only one is
            // present.
            if (request.codeExecution() && !functionDeclarations.isEmpty()) {
                builder.toolConfig(
                    ToolConfig.builder().includeServerSideToolInvocations(true).build());
            }
        } else {
            builder.toolConfig(ToolConfig.builder()
                .functionCallingConfig(FunctionCallingConfig.builder()
                    .mode(new FunctionCallingConfigMode(FunctionCallingConfigMode.Known.NONE))
                    .build())
                .build());
        }

        return builder.build();
    }

    /**
     * Validates a thinking-level string against the SDK's known values (MINIMAL/LOW/MEDIUM/HIGH).
     * Returns {@code null} (thinking level left to the model default) for blank or invalid values.
     */
    private ThinkingLevel.Known parseThinkingLevel(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            ThinkingLevel.Known known =
                ThinkingLevel.Known.valueOf(value.trim().toUpperCase(Locale.ROOT));
            if (known == ThinkingLevel.Known.THINKING_LEVEL_UNSPECIFIED) {
                return null;
            }
            return known;
        } catch (IllegalArgumentException e) {
            log.warn("Invalid thinkingLevel '{}'; expected one of MINIMAL/LOW/MEDIUM/HIGH. "
                + "Leaving thinking level unset.", value);
            return null;
        }
    }

    /**
     * Conservatively determines whether a model accepts a thinking configuration. Only the legacy
     * families that definitively lack thinking support (Gemini 1.x / 2.0, embedding models) are
     * excluded, so 2.5/3.x thinking models are never wrongly downgraded. Attaching a thinkingConfig
     * to a non-thinking model yields a 400 from the API.
     */
    private static boolean modelSupportsThinking(String model) {
        if (model == null) {
            return true;
        }
        String m = model.toLowerCase(Locale.ROOT);
        // Explicit thinking variants (e.g. gemini-2.0-flash-thinking-exp) support thinking despite
        // matching a legacy family substring below.
        if (m.contains("thinking")) {
            return true;
        }
        boolean legacy =
            m.contains("gemini-1.0") || m.contains("gemini-1.5") || m.contains("gemini-2.0")
                || m.contains("gemini-pro-vision") || m.contains("embedding");
        return !legacy;
    }
}
