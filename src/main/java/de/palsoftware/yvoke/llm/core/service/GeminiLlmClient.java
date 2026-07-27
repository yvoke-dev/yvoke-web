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
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GeminiLlmClient implements LlmClient, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(GeminiLlmClient.class);

    /**
     * Overall timeout for a single Gemini call. Subclasses that supply their own
     * {@link okhttp3.OkHttpClient} via {@link ClientOptions} must apply this themselves: the SDK
     * only configures timeouts on the client it builds itself, and silently keeps OkHttp's defaults
     * for a custom one. See {@code CloudflareGeminiLlmClient#buildClientOptions}.
     */
    static final int HTTP_TIMEOUT_MS = 300_000;

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

        HttpOptions.Builder httpOptionsBuilder = HttpOptions.builder().timeout(HTTP_TIMEOUT_MS)
            .retryOptions(HttpRetryOptions.builder().attempts(SDK_HTTP_ATTEMPTS).build());
        if (baseUrl != null && !baseUrl.isBlank()) {
            httpOptionsBuilder.baseUrl(baseUrl);
        }
        if (headers != null && !headers.isEmpty()) {
            httpOptionsBuilder.headers(headers);
        }

        Client.Builder builder =
            Client.builder().apiKey(apiKey).httpOptions(httpOptionsBuilder.build());
        if (clientOptions != null) {
            builder.clientOptions(clientOptions);
        }
        this.client = builder.build();

        this.objectMapper = objectMapper.copy()
            .registerModule(new com.fasterxml.jackson.datatype.jdk8.Jdk8Module()).configure(
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                false);
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
                promptTokens = usageMetadata.promptTokenCount().orElse(0);
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
        try {
            for (GenerateContentResponse res : stream) {
                if (Thread.currentThread().isInterrupted()) {
                    log.info("Gemini stream interrupted, stopping chunk processing (streamId={})",
                        streamId);
                    throw new CancellationException("Gemini stream interrupted");
                }
                onChunk.accept(parseChunk(res, streamId));
            }
            log.info("Gemini stream completed successfully for model={}, streamId={}",
                request.model(), streamId);
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

    private LlmResponseChunk parseChunk(GenerateContentResponse res, String streamId) {
        StringBuilder contentBuilder = new StringBuilder();
        StringBuilder reasoningBuilder = new StringBuilder();
        List<LlmToolCallDelta> toolDeltas = null;
        LlmUsage usage = null;
        List<LlmPart> chunkParts = new ArrayList<>();

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
                FinishReason.Known known = finishReason.knownEnum();
                if (known != FinishReason.Known.STOP && known != FinishReason.Known.MAX_TOKENS
                    && known != FinishReason.Known.FINISH_REASON_UNSPECIFIED) {
                    log.warn("Gemini stream finished abnormally: finishReason={}, streamId={}",
                        finishReason, streamId);
                    throw new RuntimeException(
                        "Gemini stream finished abnormally: finishReason=" + finishReason);
                }
            });
        }

        if (res.usageMetadata().isPresent()) {
            GenerateContentResponseUsageMetadata metadata = res.usageMetadata().get();
            usage = new LlmUsage(metadata.promptTokenCount().orElse(0),
                metadata.candidatesTokenCount().orElse(0), metadata.totalTokenCount().orElse(0),
                metadata.cachedContentTokenCount().orElse(0),
                metadata.thoughtsTokenCount().orElse(0));
        }

        String content = contentBuilder.length() > 0 ? contentBuilder.toString() : null;
        String reasoning = reasoningBuilder.length() > 0 ? reasoningBuilder.toString() : null;
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
     * Resolves the function name for a function-response turn. Gemini matches responses to calls by
     * NAME (there are no OpenAI-style call ids), so the declared tool name must be used; falling
     * back to the synthetic tool-call id would never match a declaration.
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

        if (enableThinking && modelSupportsThinking(request.model())) {
            ThinkingConfig.Builder thinkingBuilder = ThinkingConfig.builder().includeThoughts(true);
            // Prefer the per-request thinking level; fall back to the client-wide default.
            String requestedLevel =
                (request.thinkingLevel() != null && !request.thinkingLevel().isBlank())
                    ? request.thinkingLevel()
                    : this.thinkingLevel;
            ThinkingLevel.Known level = parseThinkingLevel(requestedLevel);
            if (level != null) {
                thinkingBuilder.thinkingLevel(level);
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
