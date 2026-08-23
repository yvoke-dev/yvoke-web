package de.palsoftware.yvoke.llm.core.service;

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

import com.openai.azure.credential.AzureApiKeyCredential;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.core.http.StreamResponse;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.ResponseFormatJsonObject;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseCodeInterpreterToolCall;
import com.openai.models.responses.ResponseFormatTextConfig;
import com.openai.models.responses.ResponseFormatTextJsonSchemaConfig;
import com.openai.models.responses.ResponseFunctionCallArgumentsDeltaEvent;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseIncludable;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputItemAddedEvent;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseStatus;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.models.responses.ResponseTextConfig;
import com.openai.models.responses.ResponseUsage;
import com.openai.models.responses.Tool;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link LlmClient} backed by Azure OpenAI's <b>Responses</b> API via
 * {@code com.openai:openai-java}.
 *
 * <p>
 * This exists because {@link AzureOpenAiLlmClient} cannot ask a reasoning model to think hard while
 * it holds tools: chat completions answers that pair with
 * {@code "Function tools with reasoning_effort are not supported for this model in
 * /v1/chat/completions. Please use /v1/responses instead."} — measured on {@code gpt-5.6-luna} at
 * every api-version from {@code 2024-10-21} to {@code 2025-04-01-preview}, so it is the service's
 * rule and not a version or a client defect. Every agentic turn carries tools, so on that surface
 * the configured thinking level is dropped from every answer that matters. Here both travel
 * together.
 *
 * <p>
 * Two deliberate choices about the SDK. It is <b>openai-java</b> rather than
 * {@code com.azure:azure-ai-openai}, which does have a Responses client but cannot reach the
 * reasoning text: its typed request emits the retired {@code reasoning.generate_summary} (a 400,
 * {@code Unknown parameter}), and its stream model has no class for
 * {@code response.reasoning_summary_text.delta}, so those events arrive as the bare
 * {@code ResponsesStreamEvent} base class whose only accessor is {@code getType()} — the text is
 * discarded before any caller sees it. And the base URL is the <b>{@code /openai/v1}</b> surface,
 * which serves Responses with no {@code api-version} at all; openai-java's own
 * {@code azureServiceVersion} builds a path this endpoint answers {@code 404} for.
 *
 * <p>
 * Unlike the chat-completions client this one <i>does</i> populate
 * {@link LlmResponseChunk#reasoning()}, from the model's streamed reasoning summary.
 * {@link LlmResponseChunk#parts()} stays {@code null} — that carries Gemini thought signatures, and
 * this provider has no equivalent.
 */
public class AzureOpenAiResponsesLlmClient implements LlmClient, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(AzureOpenAiResponsesLlmClient.class);

    /** Matches the 300s the Gemini, OpenRouter and chat-completions clients allow. */
    static final Duration HTTP_TIMEOUT = Duration.ofSeconds(300);

    /**
     * Azure serves Responses at {@code {resource-root}/openai/v1/responses}, unversioned. The
     * builder is handed this whole prefix because openai-java appends only {@code /responses}.
     */
    static final String V1_SUFFIX = "/openai/v1";

    /** Same detection as {@link AzureOpenAiLlmClient}; see that field for why it is shaped so. */
    private static final Pattern REASONING_MODEL_PATTERN =
        Pattern.compile("(?:^|[^a-z0-9])o[1345](?:[^a-z0-9]|$)|gpt-5|reasoning");

    private static final List<String> VALID_THINKING_LEVELS =
        List.of("minimal", "low", "medium", "high");

    /**
     * One re-request for an empty turn, and no more. An empty turn that repeats is systematic
     * rather than a glitch, and re-sending a large prompt a third time to discover the same nothing
     * costs more than the round the retry exists to save.
     */
    private static final int EMPTY_TURN_ATTEMPTS = 2;

    private final OpenAIClient client;
    private final boolean enableThinking;
    private final String thinkingLevel;
    private final Set<String> reasoningModels;
    private volatile boolean closed;

    public AzureOpenAiResponsesLlmClient(String endpoint, String apiKey, boolean enableThinking,
        String thinkingLevel, String reasoningModelsCsv) {
        this(buildClient(endpoint, apiKey), enableThinking, thinkingLevel, reasoningModelsCsv);
    }

    /** Test seam: lets a test point the client at a local mock server, or supply a stub. */
    AzureOpenAiResponsesLlmClient(OpenAIClient client, boolean enableThinking, String thinkingLevel,
        String reasoningModelsCsv) {
        this.client = client;
        this.enableThinking = enableThinking;
        this.thinkingLevel = thinkingLevel;
        this.reasoningModels = parseReasoningModels(reasoningModelsCsv);
    }

    private static OpenAIClient buildClient(String endpoint, String apiKey) {
        log.info("Initializing AzureOpenAiResponsesLlmClient with endpoint={}", endpoint);
        return OpenAIOkHttpClient.builder().baseUrl(baseUrl(endpoint))
            .credential(AzureApiKeyCredential.create(apiKey))
            // LlmRetry is the single retry authority, as for every other provider here: leaving the
            // SDK's own policy on would multiply the layers and re-upload a large prompt into the
            // quota that just rejected it.
            .maxRetries(0).timeout(HTTP_TIMEOUT).build();
    }

    /**
     * Turns a resource root into the Responses base URL.
     *
     * <p>
     * A blank endpoint is fatal rather than defaulted: openai-java falls back to
     * {@code api.openai.com}, so it would ship the Azure key to a third party instead of merely
     * failing — the same trap {@link AzureOpenAiLlmClient} guards against.
     */
    static String baseUrl(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("Azure OpenAI endpoint is required (set "
                + "AZURE_OPENAI_ENDPOINT or app.ai.azure-openai.endpoint); without it the SDK would "
                + "send the API key to the public OpenAI service instead");
        }
        String trimmed = endpoint.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.endsWith(V1_SUFFIX) ? trimmed : trimmed + V1_SUFFIX;
    }

    /**
     * Releases the OkHttp connection pool. Spring calls this on context shutdown because the client
     * is a singleton bean. Unlike {@link AzureOpenAiLlmClient} — whose azure-core transport really
     * does have nothing to release — {@code OpenAIClient} owns a pool, so this is not a no-op.
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
            log.warn("Failed to close the Azure OpenAI Responses client", e);
        }
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        log.info("Sending non-streaming Responses request: model={}", request.model());
        ResponseCreateParams params = buildParams(request);
        return LlmRetry.withRetry("AzureOpenAiResponses.generate", 3, () -> {
            Response response = client.responses().create(params);
            LlmUsage usage = usageOf(response.usage().orElse(null));
            // The same verdict the streaming path takes, for the same reason: a Response carries a
            // terminal status, and reading only the completed shape returns a failed or truncated
            // turn as though it had finished. Truncation stays benign — the partial answer is
            // delivered, matching AzureOpenAiLlmClient's BENIGN_FINISH_REASONS — while a failure
            // throws with the usage attached, so the accounting seam still records what it cost.
            String failure = describeFailure(response);
            if (failure != null) {
                throw new LlmCallFailedException(failure, null, usage);
            }
            warnIfIncomplete(response);
            StringBuilder text = new StringBuilder();
            for (ResponseOutputItem item : response.output()) {
                item.message().ifPresent(message -> appendText(text, message));
            }
            return new LlmResponse(text.toString(), usage);
        });
    }

    /**
     * Appends everything the model actually said, refusals included.
     *
     * <p>
     * A refusal is a {@code Content} variant alongside {@code output_text}, so skipping it made a
     * model that declined to answer indistinguishable from a model that said nothing at all — the
     * caller sees an empty string either way and has no evidence to tell the two apart.
     */
    private static void appendText(StringBuilder target, ResponseOutputMessage message) {
        for (ResponseOutputMessage.Content content : message.content()) {
            content.outputText().ifPresent(part -> target.append(part.text()));
            content.refusal().ifPresent(part -> target.append(part.refusal()));
        }
    }

    @Override
    public void generateStream(LlmRequest request, Consumer<LlmResponseChunk> onChunk) {
        log.info("Sending streaming Responses request: model={}", request.model());
        ResponseCreateParams params = buildParams(request);

        // Whether a turn that produced nothing is worth re-requesting is decided by EmptyTurnRetry,
        // shared with GeminiLlmClient. It cannot be expressed as an LlmRetry classification: the
        // verdict is taken here while consuming, outside the withRetry that wraps establishment.
        EmptyTurnRetry.run("Azure OpenAI Responses", request.model(), attempt -> {
            StreamOutcome outcome = new StreamOutcome();

            // Responses numbers every output item — a reasoning item is typically 0 and the first
            // tool call 1 — while ToolCallAccumulator treats the index as a list position and pads
            // with empty builders up to it. Densifying to 0,1,2… in order of first appearance keeps
            // a leading reasoning item from minting a phantom tool call. Per ATTEMPT, or a
            // re-request would resume numbering after the abandoned turn's items.
            Map<Long, Integer> toolIndexes = new HashMap<>();

            // Retry ONLY establishment. createStreaming blocks until the response headers arrive,
            // so a transient failure here throws before any chunk reaches the caller and a retry
            // cannot replay rendered output; a mid-stream failure could, so it is left alone. Same
            // split as GeminiLlmClient and AzureOpenAiLlmClient.
            StreamResponse<ResponseStreamEvent> established =
                LlmRetry.withRetry("AzureOpenAiResponses.generateStream", 3,
                    () -> client.responses().createStreaming(params));

            try (StreamResponse<ResponseStreamEvent> stream = established) {
                stream.stream().forEach(event -> {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new CancellationException(
                            "Azure OpenAI Responses stream interrupted");
                    }
                    LlmResponseChunk chunk = toChunk(event, toolIndexes, outcome);
                    if (chunk != null) {
                        if (chunk.usage() != null) {
                            outcome.usage = chunk.usage();
                        }
                        onChunk.accept(chunk);
                    }
                });
            } catch (RuntimeException e) {
                if (Thread.currentThread().isInterrupted() || e instanceof CancellationException
                    || e.getCause() instanceof InterruptedException
                    || e.getCause() instanceof CancellationException) {
                    log.info("Azure OpenAI Responses stream cancelled/interrupted");
                    throw new CancellationException("Azure OpenAI Responses stream interrupted");
                }
                log.error("Azure OpenAI Responses streaming call failed", e);
                throw e;
            }

            // Closes this HTTP call for accounting BEFORE any verdict is taken: an abandoned
            // attempt was billed just as a winning one is, and gets its own llm_call_logs row
            // rather than being summed onto whichever attempt happened to succeed. Emitted after
            // the stream drains, so the terminal event's usage has already been read.
            onChunk.accept(LlmResponseChunk.endOfCall(outcome.usage, null));

            // Not re-requested — a refused or errored turn repeats.
            if (outcome.failure != null) {
                throw new LlmCallFailedException(outcome.failure, null, outcome.usage);
            }

            return new EmptyTurnRetry.Turn(!outcome.isEmpty(), outcome.cleanlyCompleted(),
                outcome.usage, outcome.describe());
        });
    }

    /**
     * What a stream actually produced, so an empty one can say why rather than being reported as a
     * success the caller then has to guess about.
     */
    private static final class StreamOutcome {
        private boolean sawContent;
        private boolean sawToolCall;
        private String status;
        private String failure;
        private int events;
        private LlmUsage usage;

        /**
         * Whether this turn finished cleanly and produced nothing a caller can use.
         *
         * <p>
         * The {@code completed} requirement is what separates the two ways of ending with no
         * answer, and only one of them is worth re-requesting. A stream that was <b>cut short</b>
         * leaves {@code status} null — a transport fault, where a second attempt is a guess about
         * the network rather than about the model — and an {@code incomplete} turn ran out of its
         * output budget, which a re-request will simply do again. {@code failed} and {@code error}
         * never reach here; they throw.
         *
         * <p>
         * Note the honest limit of "nothing reached the caller": the reasoning summary DID, and it
         * is re-emitted on the second attempt. That is accepted, because the alternative is handing
         * the user a blank answer; what must never be duplicated is answer content, and by
         * construction there was none.
         */
        boolean isEmpty() {
            return !sawContent && !sawToolCall;
        }

        boolean cleanlyCompleted() {
            return "completed".equals(status);
        }

        String describe() {
            StringBuilder out = new StringBuilder();
            out.append("status=").append(status == null ? "none" : status);
            out.append(", events=").append(events);
            if (usage != null) {
                out.append(", completionTokens=").append(usage.completionTokens())
                    .append(" of which reasoning=").append(usage.thoughtTokens());
            }
            if (status == null) {
                out.append(" — the stream ended without a terminal event, which means it was cut "
                    + "short rather than completed");
            }
            return out.toString();
        }
    }

    /**
     * Describes a terminal turn that should fail the call, or {@code null} when it may stand.
     *
     * <p>
     * Truncation is deliberately NOT a failure: {@link AzureOpenAiLlmClient} lists
     * {@code TOKEN_LIMIT_REACHED} among its benign finish reasons and delivers the partial answer,
     * so raising here would make one provider stricter than the rest for the same outcome.
     */
    private static String describeFailure(Response response) {
        boolean failed = response.status()
            .map(status -> status.value() == ResponseStatus.Value.FAILED).orElse(false);
        return failed || response.error().isPresent() ? failureText(response) : null;
    }

    /**
     * Renders the failure unconditionally. Separate from {@link #describeFailure} because on the
     * streaming path the event type <i>is</i> the verdict — a {@code response.failed} event is a
     * failure whether or not the payload also sets {@code status} or {@code error}, and deciding it
     * a second time from those fields would let a terse payload turn a failure into a silent
     * success.
     */
    private static String failureText(Response response) {
        return "Azure OpenAI Responses turn failed: " + response.error()
            .map(error -> error.code() + " " + error.message()).orElse("no error detail");
    }

    private static void warnIfIncomplete(Response response) {
        boolean incomplete = response.status()
            .map(status -> status.value() == ResponseStatus.Value.INCOMPLETE).orElse(false);
        if (!incomplete) {
            return;
        }
        log.warn(
            "Azure OpenAI Responses turn is incomplete (reason={}); the answer is truncated "
                + "but is delivered as-is, matching the chat-completions client.",
            response.incompleteDetails().flatMap(details -> details.reason()).map(Object::toString)
                .orElse("unknown"));
    }

    /**
     * Maps one stream event onto a chunk, or {@code null} when it carries nothing a caller wants,
     * recording along the way whether this turn produced anything at all.
     */
    private LlmResponseChunk toChunk(ResponseStreamEvent event, Map<Long, Integer> toolIndexes,
        StreamOutcome outcome) {
        outcome.events++;
        Optional<String> text = event.outputTextDelta().map(delta -> delta.delta());
        if (text.isPresent()) {
            outcome.sawContent = true;
            return new LlmResponseChunk(text.get(), null, null, null);
        }
        // A refusal is the model declining, out loud. It arrives on its own event rather than as
        // output text, so dropping it made "I won't answer that" identical to silence — and worse
        // than identical once the empty-turn guard exists, since a refusal would be re-requested as
        // though nothing had been said and then reported as a producing-nothing failure.
        Optional<String> refusal = event.refusalDelta().map(delta -> delta.delta());
        if (refusal.isPresent()) {
            outcome.sawContent = true;
            return new LlmResponseChunk(refusal.get(), null, null, null);
        }
        // The reasoning summary, which chat completions cannot emit at all. Deliberately does NOT
        // count as content: a turn that only thought is exactly the empty turn worth re-requesting.
        Optional<String> reasoning = event.reasoningSummaryTextDelta().map(delta -> delta.delta());
        if (reasoning.isPresent()) {
            return new LlmResponseChunk(null, reasoning.get(), null, null);
        }
        // A tool call is announced whole — id and name — before any argument arrives, so unlike the
        // chat-completions path there is never an id-less fragment to guess an owner for.
        if (event.outputItemAdded().isPresent()) {
            ResponseOutputItemAddedEvent added = event.outputItemAdded().get();
            return added.item().functionCall().map(call -> {
                outcome.sawToolCall = true;
                return toolChunk(denseIndex(toolIndexes, added.outputIndex()), call.callId(),
                    call.name(), null);
            }).orElse(null);
        }
        if (event.functionCallArgumentsDelta().isPresent()) {
            ResponseFunctionCallArgumentsDeltaEvent delta =
                event.functionCallArgumentsDelta().get();
            return toolChunk(denseIndex(toolIndexes, delta.outputIndex()), null, null,
                delta.delta());
        }
        // Server-side code execution. Emitted on `done` rather than from the code deltas so the
        // fenced block is never split across chunks; the run is short and the alternative is
        // opening a fence in one chunk and hoping to close it in another.
        Optional<String> code = event.codeInterpreterCallCodeDone().map(done -> done.code());
        if (code.isPresent()) {
            outcome.sawContent = true;
            return new LlmResponseChunk(codeExecutionBlock("```python\n" + code.get() + "\n```"),
                null, null, null);
        }
        if (event.outputItemDone().isPresent()) {
            return event.outputItemDone().get().item().codeInterpreterCall()
                .map(AzureOpenAiResponsesLlmClient::executionResultBlock).orElse(null);
        }
        // Usage comes off WHICHEVER terminal event carries it, not just the happy one. A stream
        // that ends incomplete or failed has still burned tokens the provider will bill for, and
        // reading only `completed` left them with no llm_call_logs row at all.
        if (event.incomplete().isPresent()) {
            Response response = event.incomplete().get().response();
            outcome.status = "incomplete";
            warnIfIncomplete(response);
            return usageChunk(response);
        }
        if (event.failed().isPresent()) {
            Response response = event.failed().get().response();
            outcome.status = "failed";
            outcome.failure = failureText(response);
            return usageChunk(response);
        }
        if (event.error().isPresent()) {
            outcome.status = "error";
            outcome.failure = "Azure OpenAI Responses stream error: " + event.error().get().code()
                + " " + event.error().get().message();
            return null;
        }
        return event.completed().map(done -> {
            outcome.status = "completed";
            return usageChunk(done.response());
        }).orElse(null);
    }

    private static LlmResponseChunk usageChunk(Response response) {
        return new LlmResponseChunk(null, null, null, usageOf(response.usage().orElse(null)));
    }

    /**
     * Renders what the sandbox produced. Matches {@link GeminiLlmClient}'s markup for its own
     * server-side execution, which the chat UI already knows how to style and sanitise.
     */
    private static LlmResponseChunk executionResultBlock(ResponseCodeInterpreterToolCall call) {
        String logs = call
            .outputs().orElse(List.of()).stream().map(output -> output.logs()
                .map(ResponseCodeInterpreterToolCall.Output.Logs::logs).orElse(""))
            .filter(text -> !text.isBlank()).collect(Collectors.joining("\n"));
        if (logs.isBlank()) {
            // An image-only run, or `include` was not requested; the code block already went out.
            return null;
        }
        return new LlmResponseChunk(
            codeExecutionBlock("**Code execution result:**\n```\n" + logs + "\n```"), null, null,
            null);
    }

    private static String codeExecutionBlock(String body) {
        return "\n\n<code-execution>\n" + body + "\n</code-execution>\n\n";
    }

    private static int denseIndex(Map<Long, Integer> toolIndexes, long outputIndex) {
        int next = toolIndexes.size();
        return toolIndexes.computeIfAbsent(outputIndex, key -> next);
    }

    private static LlmResponseChunk toolChunk(int index, String id, String name, String argsDelta) {
        return new LlmResponseChunk(null, null,
            List.of(new LlmToolCallDelta(index, id, name, argsDelta)), null);
    }

    private static LlmUsage usageOf(ResponseUsage usage) {
        if (usage == null) {
            return null;
        }
        return new LlmUsage((int) usage.inputTokens(), (int) usage.outputTokens(),
            (int) usage.totalTokens(), (int) usage.inputTokensDetails().cachedTokens(),
            (int) usage.outputTokensDetails().reasoningTokens());
    }

    ResponseCreateParams buildParams(LlmRequest request) {
        boolean reasoning = isReasoningModel(request.model());
        ResponseCreateParams.Builder builder = ResponseCreateParams.builder().model(request.model())
            .inputOfResponse(buildInput(request, reasoning))
            // Azure defaults Responses to store=true, which retains the conversation server-side.
            // Nothing here reads a stored response back, so retention would be pure exposure.
            .store(false);

        if (request.maxTokens() > 0) {
            builder.maxOutputTokens(request.maxTokens());
        }
        if (reasoning) {
            // A reasoning model rejects any temperature but its own default; an ordinary model is
            // the exact opposite. The MODEL decides, never whether an effort value resolved.
            ReasoningEffort effort = resolveReasoningEffort(request);
            // Ask for the thinking text unconditionally. Without a summary the reasoning is billed
            // and discarded, and LlmResponseChunk.reasoning() stays null as on chat completions —
            // so nesting this inside the effort guard silenced the reasoning of a model that goes
            // on reasoning at its own default whenever thinking was switched off or the configured
            // level was unusable. The effort is the only part either of those should give up.
            Reasoning.Builder thinking = Reasoning.builder().summary(Reasoning.Summary.AUTO);
            if (effort != null) {
                thinking.effort(effort);
            }
            builder.reasoning(thinking.build());
        } else {
            builder.temperature(request.temperature());
        }
        if (request.seed() != null) {
            // Chat completions and Gemini both honour a seed; Responses has no such parameter, so
            // a caller asking for reproducible output will not get it. Said out loud rather than
            // dropped, because the callers that set it set it deliberately.
            log.warn("Ignoring seed {}: the Responses API has no seed parameter, so this call is "
                + "not reproducible.", request.seed());
        }
        applyResponseFormat(request, builder);
        applyTools(request, builder);
        return builder.build();
    }

    /**
     * Applies JSON mode / a response schema. Unlike Gemini this surface allows structured output
     * <i>and</i> tools at once, so there is no either-or to arbitrate.
     */
    private static void applyResponseFormat(LlmRequest request,
        ResponseCreateParams.Builder builder) {
        boolean hasSchema = request.responseSchema() != null && !request.responseSchema().isEmpty();
        boolean wantsJson =
            request.responseMimeType() != null && !request.responseMimeType().isBlank();
        if (!hasSchema && !wantsJson) {
            return;
        }
        if (hasSchema) {
            ResponseFormatTextJsonSchemaConfig.Schema schema =
                ResponseFormatTextJsonSchemaConfig.Schema.builder().build();
            for (Map.Entry<String, Object> entry : request.responseSchema().entrySet()) {
                schema = schema.toBuilder()
                    .putAdditionalProperty(entry.getKey(), JsonValue.from(entry.getValue()))
                    .build();
            }
            builder.text(ResponseTextConfig.builder()
                .format(ResponseFormatTextConfig.ofJsonSchema(
                    ResponseFormatTextJsonSchemaConfig.builder().name("response").schema(schema)
                        // Non-strict for the same reason as the tool schemas: strict additionally
                        // demands additionalProperties:false and every property in `required`,
                        // which
                        // the schemas this app sends do not satisfy.
                        .strict(false).build()))
                .build());
            return;
        }
        builder.text(ResponseTextConfig.builder()
            .format(
                ResponseFormatTextConfig.ofJsonObject(ResponseFormatJsonObject.builder().build()))
            .build());
    }

    private List<ResponseInputItem> buildInput(LlmRequest request, boolean reasoning) {
        List<ResponseInputItem> items = new ArrayList<>();
        for (LlmMessage message : request.messages()) {
            String role = message.role() == null ? "" : message.role().toLowerCase(Locale.ROOT);
            switch (role) {
                case "system" -> items.add(easy(
                    // Reasoning models take instructions as `developer`, matching the chat client.
                    reasoning ? EasyInputMessage.Role.DEVELOPER : EasyInputMessage.Role.SYSTEM,
                    message.content()));
                case "user" -> items.add(easy(EasyInputMessage.Role.USER, message.content()));
                case "assistant" -> addAssistant(items, message);
                case "tool" -> items.add(ResponseInputItem
                    .ofFunctionCallOutput(ResponseInputItem.FunctionCallOutput.builder()
                        .callId(nullSafe(message.toolCallId())).output(nullSafe(message.content()))
                        .build()));
                default -> log.warn("Ignoring message with unsupported role '{}'", message.role());
            }
        }
        return items;
    }

    /**
     * An assistant turn can carry prose, tool calls, or both. The calls become their own input
     * items — Responses has no assistant message that contains them, which is the shape difference
     * from chat completions.
     */
    private static void addAssistant(List<ResponseInputItem> items, LlmMessage message) {
        if (message.content() != null && !message.content().isBlank()) {
            items.add(easy(EasyInputMessage.Role.ASSISTANT, message.content()));
        }
        if (message.toolCalls() == null) {
            return;
        }
        for (LlmToolCall call : message.toolCalls()) {
            items.add(ResponseInputItem
                .ofFunctionCall(ResponseFunctionToolCall.builder().callId(nullSafe(call.id()))
                    .name(nullSafe(call.name())).arguments(nullSafe(call.arguments())).build()));
        }
    }

    private static ResponseInputItem easy(EasyInputMessage.Role role, String content) {
        return ResponseInputItem.ofEasyInputMessage(
            EasyInputMessage.builder().role(role).content(nullSafe(content)).build());
    }

    private static void applyTools(LlmRequest request, ResponseCreateParams.Builder builder) {
        if (request.codeExecution()) {
            // Verified against this endpoint: a code_interpreter tool with an auto container is
            // accepted and produces a code_interpreter_call output item. Chat completions has no
            // equivalent, so this is capability the other Azure client cannot offer.
            builder.addTool(Tool.ofCodeInterpreter(Tool.CodeInterpreter.builder()
                .container(Tool.CodeInterpreter.Container.CodeInterpreterToolAuto.builder().build())
                .build()));
            // Without this the done item carries outputs=null — the code the model wrote arrives
            // but what it produced does not, which is the half worth showing.
            builder.addInclude(ResponseIncludable.CODE_INTERPRETER_CALL_OUTPUTS);
        }
        if (request.tools() == null || request.tools().isEmpty()) {
            return;
        }
        for (LlmTool tool : request.tools()) {
            FunctionTool.Parameters.Builder parameters = FunctionTool.Parameters.builder();
            if (tool.inputSchema() != null) {
                tool.inputSchema().forEach(
                    (key, value) -> parameters.putAdditionalProperty(key, JsonValue.from(value)));
            }
            builder.addTool(Tool.ofFunction(FunctionTool.builder().name(tool.name())
                .description(nullSafe(tool.description())).parameters(parameters.build())
                // Non-strict: the corpus tools carry hand-written schemas that strict mode rejects
                // (it requires additionalProperties:false and every property in `required`).
                .strict(false).build()));
        }
    }

    /**
     * Resolves the reasoning effort, preferring the per-request level over the client-wide default.
     * Returns {@code null} when thinking is off or the level is unusable, leaving the model's own
     * default in place.
     */
    ReasoningEffort resolveReasoningEffort(LlmRequest request) {
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
        if (!VALID_THINKING_LEVELS.contains(normalized)) {
            log.warn("Invalid thinkingLevel '{}'; expected one of MINIMAL/LOW/MEDIUM/HIGH. "
                + "Leaving reasoning effort unset.", requested);
            return null;
        }
        return ReasoningEffort.of(normalized);
    }

    /** See {@link AzureOpenAiLlmClient#isReasoningModel(String)} — the same rule, same reasons. */
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

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
