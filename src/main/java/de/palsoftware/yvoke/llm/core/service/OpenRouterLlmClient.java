package de.palsoftware.yvoke.llm.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.core.http.StreamResponse;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.ResponseFormatJsonObject;
import com.openai.models.ResponseFormatJsonSchema;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.chat.completions.ChatCompletionStreamOptions;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import de.palsoftware.yvoke.llm.core.EmptyTurnRetry;
import de.palsoftware.yvoke.llm.core.LlmRetry;
import de.palsoftware.yvoke.llm.core.model.LlmCallFailedException;
import de.palsoftware.yvoke.llm.core.model.LlmMessage;
import de.palsoftware.yvoke.llm.core.model.LlmRequest;
import de.palsoftware.yvoke.llm.core.model.LlmResponse;
import de.palsoftware.yvoke.llm.core.model.LlmResponseChunk;
import de.palsoftware.yvoke.llm.core.model.LlmTool;
import de.palsoftware.yvoke.llm.core.model.LlmToolCall;
import de.palsoftware.yvoke.llm.core.model.LlmToolCallDelta;
import de.palsoftware.yvoke.llm.core.model.LlmUsage;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link LlmClient} backed by OpenRouter ({@code https://openrouter.ai/api/v1}) using
 * {@code com.openai:openai-java}.
 */
public class OpenRouterLlmClient implements LlmClient, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterLlmClient.class);

    static final String DEFAULT_BASE_URL = "https://openrouter.ai/api/v1";
    static final String APP_TITLE = "Yvoke";
    static final String APP_REFERER = "https://app.yvoke.dev";
    static final Duration HTTP_TIMEOUT = Duration.ofSeconds(300);
    static final Set<String> FATAL_FINISH_REASONS = Set.of("content_filter");
    static final Set<String> BENIGN_FINISH_REASONS =
        Set.of("stop", "end_turn", "length", "tool_calls", "function_call");
    static final Pattern OPENAI_O_SERIES_PATTERN =
        Pattern.compile("(?:^|/)o[1345](?:-[a-z0-9]+)*(?:$|:)", Pattern.CASE_INSENSITIVE);
    static final Pattern REASONING_MODEL_PATTERN = Pattern.compile(
        "(?:^|/)o[1345](?:[^a-z0-9]|$)|gpt-5|reasoning|r1|deepseek-v4", Pattern.CASE_INSENSITIVE);
    private static final List<String> VALID_THINKING_LEVELS =
        List.of("low", "medium", "high", "max");

    private final OpenAIClient client;
    private final ObjectMapper objectMapper;
    private final boolean enableThinking;
    private final String thinkingLevel;
    private final Set<String> reasoningModels;
    private volatile boolean closed;

    static String resolveBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()
            || baseUrl.toLowerCase(Locale.ROOT).contains("placeholder")) {
            return DEFAULT_BASE_URL;
        }
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.isEmpty() ? DEFAULT_BASE_URL : trimmed;
    }

    public OpenRouterLlmClient(String baseUrl, String apiKey, ObjectMapper objectMapper) {
        this(baseUrl, apiKey, objectMapper, true, "high", "");
    }

    public OpenRouterLlmClient(String baseUrl, String apiKey, ObjectMapper objectMapper,
        boolean enableThinking, String thinkingLevel, String reasoningModelsCsv) {
        this(buildClient(baseUrl, apiKey), objectMapper, enableThinking, thinkingLevel,
            reasoningModelsCsv);
    }

    OpenRouterLlmClient(OpenAIClient client, ObjectMapper objectMapper, boolean enableThinking,
        String thinkingLevel, String reasoningModelsCsv) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.enableThinking = enableThinking;
        this.thinkingLevel = thinkingLevel;
        this.reasoningModels = parseReasoningModels(reasoningModelsCsv);
    }

    private static OpenAIClient buildClient(String baseUrl, String apiKey) {
        String resolvedUrl = resolveBaseUrl(baseUrl);
        log.info("Initializing OpenRouterLlmClient with baseUrl: {}", resolvedUrl);
        return OpenAIOkHttpClient.builder().baseUrl(resolvedUrl)
            .apiKey(apiKey != null ? apiKey : "").putHeader("HTTP-Referer", APP_REFERER)
            .putHeader("X-Title", APP_TITLE).maxRetries(0).timeout(HTTP_TIMEOUT).build();
    }

    private static Set<String> parseReasoningModels(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        Set<String> names = new LinkedHashSet<>();
        for (String name : csv.split(",")) {
            String trimmed = name.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                names.add(trimmed);
            }
        }
        return names;
    }

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
            log.warn("Failed to close OpenRouter OpenAIClient", e);
        }
    }

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

    String resolveReasoningEffort(LlmRequest request) {
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
        if ("minimal".equals(normalized)) {
            return "low";
        }
        if (!VALID_THINKING_LEVELS.contains(normalized)) {
            log.warn("Invalid thinkingLevel '{}'; expected one of LOW/MEDIUM/HIGH/MAX. "
                + "Leaving reasoning effort unset.", requested);
            return null;
        }
        return normalized;
    }

    /**
     * Executes a non-streaming completion request.
     *
     * @throws LlmCallFailedException if OpenRouter returns a fatal finish reason (e.g.
     *         {@code content_filter}) or empty content
     */
    @Override
    public LlmResponse generate(LlmRequest request) {
        if (closed) {
            throw new IllegalStateException("Client is closed");
        }
        log.info("Sending non-streaming request to OpenRouter: model={}", request.model());
        ChatCompletionCreateParams params = buildCreateParams(request, false);
        return LlmRetry.withRetry("OpenRouter.generate", 3, () -> {
            ChatCompletion completion = client.chat().completions().create(params);
            String content = "";
            String finishReason = null;
            if (!completion.choices().isEmpty()) {
                var choice = completion.choices().get(0);
                var message = choice.message();
                content = message.content().orElse("");
                if (message.refusal().isPresent() && !message.refusal().get().isEmpty()) {
                    content = content.isEmpty() ? message.refusal().get()
                        : content + message.refusal().get();
                }
                if (choice.finishReason() != null) {
                    finishReason = choice.finishReason().asString();
                }
            }
            LlmUsage usage = parseUsage(completion.usage().orElse(null));

            if (finishReason != null
                && FATAL_FINISH_REASONS.contains(finishReason.toLowerCase(Locale.ROOT))) {
                throw new LlmCallFailedException(
                    "OpenRouter call failed: finish_reason=" + finishReason, null, usage);
            }
            if (content.isEmpty()) {
                throw new LlmCallFailedException(
                    "OpenRouter returned empty content (finishReason=" + finishReason + ")", null,
                    usage);
            }
            return new LlmResponse(content, usage);
        });
    }

    @Override
    public void generateStream(LlmRequest request, Consumer<LlmResponseChunk> onChunk) {
        if (closed) {
            throw new IllegalStateException("Client is closed");
        }
        log.info("Sending streaming request to OpenRouter: model={}", request.model());
        ChatCompletionCreateParams params = buildCreateParams(request, true);

        EmptyTurnRetry.run("OpenRouter", request.model(), attempt -> {
            StreamOutcome outcome = new StreamOutcome();

            StreamResponse<ChatCompletionChunk> established =
                LlmRetry.withRetry("OpenRouter.generateStream", 3,
                    () -> client.chat().completions().createStreaming(params));

            try (StreamResponse<ChatCompletionChunk> stream = established) {
                stream.stream().forEach(chunk -> {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new CancellationException("OpenRouter stream interrupted");
                    }
                    parseChunk(chunk, outcome, onChunk);
                });
            } catch (RuntimeException e) {
                if (Thread.currentThread().isInterrupted() || e instanceof CancellationException
                    || e.getCause() instanceof InterruptedException
                    || e.getCause() instanceof CancellationException) {
                    log.info("OpenRouter stream cancelled/interrupted");
                    throw new CancellationException("OpenRouter stream interrupted");
                }
                log.error("OpenRouter streaming call failed", e);
                throw e;
            }

            // Closes this HTTP call for accounting BEFORE evaluating verdict
            onChunk.accept(LlmResponseChunk.endOfCall(outcome.usage, null));

            if (outcome.fatalFinishReason != null) {
                throw new LlmCallFailedException(
                    "OpenRouter stream failed with finish_reason=" + outcome.fatalFinishReason,
                    null, outcome.usage);
            }

            return new EmptyTurnRetry.Turn(!outcome.isEmpty(), outcome.cleanlyCompleted(),
                outcome.usage, outcome.describe());
        });
    }

    private void parseChunk(ChatCompletionChunk chunk, StreamOutcome outcome,
        Consumer<LlmResponseChunk> onChunk) {
        outcome.events++;
        String content = null;
        String reasoning = null;
        List<LlmToolCallDelta> toolDeltas = null;
        LlmUsage usage = null;

        if (!chunk.choices().isEmpty()) {
            ChatCompletionChunk.Choice choice = chunk.choices().get(0);
            ChatCompletionChunk.Choice.Delta delta = choice.delta();
            content = delta.content().orElse(null);

            String refusal = delta.refusal().orElse(null);
            if (refusal != null && !refusal.isEmpty()) {
                content = content == null ? refusal : content + refusal;
            }

            // Extract reasoning tokens from unmapped properties
            Map<String, JsonValue> additional = delta._additionalProperties();
            if (additional.containsKey("reasoning")) {
                JsonValue val = additional.get("reasoning");
                if (val != null) {
                    reasoning = (String) val.convert(String.class);
                }
            } else if (additional.containsKey("reasoning_content")) {
                JsonValue val = additional.get("reasoning_content");
                if (val != null) {
                    reasoning = (String) val.convert(String.class);
                }
            }

            // Also check choice-level reasoning
            if (reasoning == null && choice._additionalProperties().containsKey("reasoning")) {
                JsonValue val = choice._additionalProperties().get("reasoning");
                if (val != null) {
                    reasoning = (String) val.convert(String.class);
                }
            }

            if (delta.toolCalls().isPresent()) {
                List<ChatCompletionChunk.Choice.Delta.ToolCall> sdkCalls = delta.toolCalls().get();
                toolDeltas = new ArrayList<>();
                for (ChatCompletionChunk.Choice.Delta.ToolCall sc : sdkCalls) {
                    String id = sc.id().orElse(null);
                    String name = null;
                    String argDelta = null;
                    if (sc.function().isPresent()) {
                        name = sc.function().get().name().orElse(null);
                        argDelta = sc.function().get().arguments().orElse(null);
                    }
                    toolDeltas.add(new LlmToolCallDelta((int) sc.index(), id, name, argDelta));
                }
            }

            if (content != null && !content.isEmpty()) {
                outcome.sawContent = true;
            }
            if (toolDeltas != null && !toolDeltas.isEmpty()) {
                outcome.sawToolCall = true;
            }

            if (choice.finishReason().isPresent()) {
                String rawReason = choice.finishReason().get().asString();
                outcome.finishReason = rawReason;
                if (rawReason != null) {
                    String normalized = rawReason.trim().toLowerCase(Locale.ROOT);
                    if (FATAL_FINISH_REASONS.contains(normalized)) {
                        log.warn("OpenRouter stream finished abnormally: finishReason={}",
                            rawReason);
                        outcome.fatalFinishReason = rawReason;
                    } else if (!BENIGN_FINISH_REASONS.contains(normalized)) {
                        log.warn(
                            "OpenRouter reported an unrecognised finishReason: {}. Delivering the answer — an unknown reason is not evidence of a failure.",
                            rawReason);
                    }
                }
            }
        }

        if (chunk.usage().isPresent()) {
            usage = parseUsage(chunk.usage().get());
            outcome.usage = usage;
        }

        if (content != null || reasoning != null || toolDeltas != null || usage != null) {
            onChunk.accept(new LlmResponseChunk(content, reasoning, toolDeltas, usage));
        }
    }

    private ChatCompletionCreateParams buildCreateParams(LlmRequest request, boolean stream) {
        ChatCompletionCreateParams.Builder builder =
            ChatCompletionCreateParams.builder().model(request.model());

        if (request.maxTokens() > 0) {
            builder.maxCompletionTokens(request.maxTokens());
        }

        // Omit temperature ONLY for OpenAI o-series models
        if (!OPENAI_O_SERIES_PATTERN.matcher(request.model()).find()) {
            builder.temperature(request.temperature());
        }

        if (request.seed() != null) {
            builder.seed(request.seed().longValue());
        }

        // Messages
        for (LlmMessage msg : request.messages()) {
            if ("system".equalsIgnoreCase(msg.role())) {
                builder.addMessage(ChatCompletionMessageParam.ofSystem(
                    ChatCompletionSystemMessageParam.builder().content(msg.content()).build()));
            } else if ("user".equalsIgnoreCase(msg.role())) {
                builder.addMessage(ChatCompletionMessageParam.ofUser(
                    ChatCompletionUserMessageParam.builder().content(msg.content()).build()));
            } else if ("assistant".equalsIgnoreCase(msg.role())) {
                ChatCompletionAssistantMessageParam.Builder assistantMsgBuilder =
                    ChatCompletionAssistantMessageParam.builder().content(msg.content());

                if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                    for (LlmToolCall tc : msg.toolCalls()) {
                        assistantMsgBuilder.addToolCall(ChatCompletionMessageToolCall
                            .ofFunction(ChatCompletionMessageFunctionToolCall.builder().id(tc.id())
                                .function(ChatCompletionMessageFunctionToolCall.Function.builder()
                                    .name(tc.name()).arguments(tc.arguments()).build())
                                .build()));
                    }
                }

                builder.addMessage(
                    ChatCompletionMessageParam.ofAssistant(assistantMsgBuilder.build()));
            } else if ("tool".equalsIgnoreCase(msg.role())) {
                builder.addMessage(ChatCompletionMessageParam.ofTool(ChatCompletionToolMessageParam
                    .builder().toolCallId(msg.toolCallId()).content(msg.content()).build()));
            }
        }

        // Tools
        if (request.tools() != null && !request.tools().isEmpty()) {
            for (LlmTool tool : request.tools()) {
                FunctionParameters.Builder paramsBuilder = FunctionParameters.builder();
                if (tool.inputSchema() != null) {
                    for (Map.Entry<String, Object> entry : tool.inputSchema().entrySet()) {
                        paramsBuilder.putAdditionalProperty(entry.getKey(),
                            JsonValue.from(entry.getValue()));
                    }
                }
                builder.addFunctionTool(FunctionDefinition.builder().name(tool.name())
                    .description(tool.description()).parameters(paramsBuilder.build()).build());
            }
        }

        // Structured output (suppressed when tools present)
        applyResponseFormat(request, builder);

        // Reasoning configuration
        if (isReasoningModel(request.model()) && enableThinking) {
            builder.putAdditionalBodyProperty("include_reasoning", JsonValue.from(true));
            String effort = resolveReasoningEffort(request);
            if (effort != null) {
                builder.putAdditionalBodyProperty("reasoning",
                    JsonValue.from(Map.of("effort", effort)));
            }
        }

        if (stream) {
            builder.streamOptions(ChatCompletionStreamOptions.builder().includeUsage(true).build());
        }

        return builder.build();
    }

    private void applyResponseFormat(LlmRequest request,
        ChatCompletionCreateParams.Builder builder) {
        boolean hasSchema = request.responseSchema() != null && !request.responseSchema().isEmpty();
        boolean wantsJson =
            request.responseMimeType() != null && !request.responseMimeType().isBlank();
        if (!hasSchema && !wantsJson) {
            return;
        }
        if (request.tools() != null && !request.tools().isEmpty()) {
            log.warn("Ignoring structured output (schema / json mode) because tools are present");
            return;
        }
        if (hasSchema) {
            ResponseFormatJsonSchema.JsonSchema.Schema.Builder schemaBuilder =
                ResponseFormatJsonSchema.JsonSchema.Schema.builder();
            for (Map.Entry<String, Object> entry : request.responseSchema().entrySet()) {
                schemaBuilder.putAdditionalProperty(entry.getKey(),
                    JsonValue.from(entry.getValue()));
            }
            ResponseFormatJsonSchema jsonSchema =
                ResponseFormatJsonSchema
                    .builder().jsonSchema(ResponseFormatJsonSchema.JsonSchema.builder()
                        .name("response").schema(schemaBuilder.build()).strict(false).build())
                    .build();
            builder.responseFormat(jsonSchema);
            return;
        }
        builder.responseFormat(ResponseFormatJsonObject.builder().build());
    }

    private LlmUsage parseUsage(Object usageObj) {
        if (usageObj == null) {
            return null;
        }
        int promptTokens = 0;
        int completionTokens = 0;
        int totalTokens = 0;
        int cachedTokens = 0;
        int thoughtTokens = 0;
        try {
            String json = objectMapper.writeValueAsString(usageObj);
            Map<?, ?> map = objectMapper.readValue(json, Map.class);
            if (map.containsKey("prompt_tokens")) {
                promptTokens = ((Number) map.get("prompt_tokens")).intValue();
            }
            if (map.containsKey("completion_tokens")) {
                completionTokens = ((Number) map.get("completion_tokens")).intValue();
            }
            if (map.containsKey("total_tokens")) {
                totalTokens = ((Number) map.get("total_tokens")).intValue();
            }
            if (map.containsKey("prompt_tokens_details")) {
                Map<?, ?> ptd = (Map<?, ?>) map.get("prompt_tokens_details");
                if (ptd != null && ptd.containsKey("cached_tokens")) {
                    cachedTokens = ((Number) ptd.get("cached_tokens")).intValue();
                }
            }
            if (map.containsKey("completion_tokens_details")) {
                Map<?, ?> ctd = (Map<?, ?>) map.get("completion_tokens_details");
                if (ctd != null && ctd.containsKey("reasoning_tokens")) {
                    thoughtTokens = ((Number) ctd.get("reasoning_tokens")).intValue();
                }
            }
        } catch (Exception e) {
            log.debug("Failed to parse usage details", e);
        }
        return new LlmUsage(promptTokens, completionTokens, totalTokens, cachedTokens,
            thoughtTokens);
    }

    private static final class StreamOutcome {
        private boolean sawContent;
        private boolean sawToolCall;
        private String finishReason;
        private String fatalFinishReason;
        private int events;
        private LlmUsage usage;

        boolean isEmpty() {
            return !sawContent && !sawToolCall;
        }

        boolean cleanlyCompleted() {
            if (finishReason == null) {
                return false;
            }
            String normalized = finishReason.trim().toLowerCase(Locale.ROOT);
            return "stop".equals(normalized) || "end_turn".equals(normalized);
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
}

