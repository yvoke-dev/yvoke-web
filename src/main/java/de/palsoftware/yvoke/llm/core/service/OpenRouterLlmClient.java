package de.palsoftware.yvoke.llm.core.service;

import de.palsoftware.yvoke.llm.core.LlmRetry;
import de.palsoftware.yvoke.llm.core.model.LlmMessage;
import de.palsoftware.yvoke.llm.core.model.LlmRequest;
import de.palsoftware.yvoke.llm.core.model.LlmResponse;
import de.palsoftware.yvoke.llm.core.model.LlmResponseChunk;
import de.palsoftware.yvoke.llm.core.model.LlmTool;
import de.palsoftware.yvoke.llm.core.model.LlmToolCall;
import de.palsoftware.yvoke.llm.core.model.LlmToolCallDelta;
import de.palsoftware.yvoke.llm.core.model.LlmUsage;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.openai.core.http.StreamResponse;

public class OpenRouterLlmClient implements LlmClient {
    private static final Logger log = LoggerFactory.getLogger(OpenRouterLlmClient.class);
    private final OpenAIClient client;
    private final ObjectMapper objectMapper;

    public OpenRouterLlmClient(String baseUrl, String apiKey, ObjectMapper objectMapper) {
        log.info("Initializing OpenRouterLlmClient with baseUrl: {}", baseUrl);
        this.client = OpenAIOkHttpClient.builder().baseUrl(baseUrl).apiKey(apiKey)
            // Explicit timeout so a hung upstream can't pin a worker thread indefinitely
            // (mirrors the Gemini client's 300s budget for long generations).
            .timeout(Duration.ofSeconds(300)).build();
        this.objectMapper = objectMapper;
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        log.info("Sending non-streaming request to OpenRouter: model={}", request.model());
        ChatCompletionCreateParams params = buildCreateParams(request, false);
        return LlmRetry.withRetry("OpenRouter.generate", 3, () -> {
            try {
                ChatCompletion completion = client.chat().completions().create(params);
                String content = "";
                if (!completion.choices().isEmpty()) {
                    content = completion.choices().get(0).message().content().orElse("");
                }
                int promptTokens = 0;
                int completionTokens = 0;
                int totalTokens = 0;
                int cachedTokens = 0;
                int thoughtTokens = 0;
                if (completion.usage().isPresent()) {
                    var usage = completion.usage().get();
                    promptTokens = (int) usage.promptTokens();
                    completionTokens = (int) usage.completionTokens();
                    totalTokens = (int) usage.totalTokens();

                    try {
                        String json = objectMapper.writeValueAsString(usage);
                        Map<?, ?> map = objectMapper.readValue(json, Map.class);
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
                        log.debug("Failed to parse cached or reasoning tokens", e);
                    }
                }
                return new LlmResponse(content, new LlmUsage(promptTokens, completionTokens,
                    totalTokens, cachedTokens, thoughtTokens));
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("OpenRouter call failed", e);
            }
        });
    }

    @Override
    public void generateStream(LlmRequest request, Consumer<LlmResponseChunk> onChunk) {
        log.info("Sending streaming request to OpenRouter: model={}", request.model());
        ChatCompletionCreateParams params = buildCreateParams(request, true);

        try (StreamResponse<ChatCompletionChunk> stream =
            client.chat().completions().createStreaming(params)) {
            stream.stream().forEach(chunk -> {
                if (Thread.currentThread().isInterrupted()) {
                    throw new CancellationException("OpenRouter stream interrupted");
                }
                String content = null;
                String reasoning = null;
                List<LlmToolCallDelta> toolDeltas = null;
                LlmUsage usage = null;

                if (!chunk.choices().isEmpty()) {
                    ChatCompletionChunk.Choice choice = chunk.choices().get(0);
                    ChatCompletionChunk.Choice.Delta delta = choice.delta();
                    content = delta.content().orElse(null);

                    // Try to extract deepseek reasoning tokens from unmapped properties
                    Map<String, JsonValue> additional = delta._additionalProperties();
                    if (additional.containsKey("reasoning_content")) {
                        JsonValue val = additional.get("reasoning_content");
                        if (val != null) {
                            reasoning = (String) val.convert(String.class);
                        }
                    }

                    // Also extract reasoning from choice level if present (sometimes OpenRouter
                    // places it there)
                    if (reasoning == null
                        && choice._additionalProperties().containsKey("reasoning")) {
                        JsonValue val = choice._additionalProperties().get("reasoning");
                        if (val != null) {
                            reasoning = (String) val.convert(String.class);
                        }
                    }

                    if (delta.toolCalls().isPresent()) {
                        List<ChatCompletionChunk.Choice.Delta.ToolCall> sdkCalls =
                            delta.toolCalls().get();
                        toolDeltas = new ArrayList<>();
                        for (ChatCompletionChunk.Choice.Delta.ToolCall sc : sdkCalls) {
                            String id = sc.id().orElse(null);
                            String name = null;
                            String argDelta = null;
                            if (sc.function().isPresent()) {
                                name = sc.function().get().name().orElse(null);
                                argDelta = sc.function().get().arguments().orElse(null);
                            }
                            toolDeltas
                                .add(new LlmToolCallDelta((int) sc.index(), id, name, argDelta));
                        }
                    }
                }

                if (chunk.usage().isPresent()) {
                    var cu = chunk.usage().get();
                    int pTokens = (int) cu.promptTokens();
                    int cTokens = (int) cu.completionTokens();
                    int tTokens = (int) cu.totalTokens();
                    int cchTokens = 0;
                    int thTokens = 0;
                    try {
                        String json = objectMapper.writeValueAsString(cu);
                        Map<?, ?> map = objectMapper.readValue(json, Map.class);
                        if (map.containsKey("prompt_tokens_details")) {
                            Map<?, ?> ptd = (Map<?, ?>) map.get("prompt_tokens_details");
                            if (ptd != null && ptd.containsKey("cached_tokens")) {
                                cchTokens = ((Number) ptd.get("cached_tokens")).intValue();
                            }
                        }
                        if (map.containsKey("completion_tokens_details")) {
                            Map<?, ?> ctd = (Map<?, ?>) map.get("completion_tokens_details");
                            if (ctd != null && ctd.containsKey("reasoning_tokens")) {
                                thTokens = ((Number) ctd.get("reasoning_tokens")).intValue();
                            }
                        }
                    } catch (Exception e) {
                        log.debug("Failed to parse cached or reasoning tokens from chunk usage", e);
                    }
                    usage = new LlmUsage(pTokens, cTokens, tTokens, cchTokens, thTokens);
                }

                onChunk.accept(new LlmResponseChunk(content, reasoning, toolDeltas, usage));
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
    }

    private ChatCompletionCreateParams buildCreateParams(LlmRequest request, boolean stream) {
        ChatCompletionCreateParams.Builder builder =
            ChatCompletionCreateParams.builder().model(request.model())
                .temperature(request.temperature()).maxCompletionTokens(request.maxTokens());

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

        // Request OpenRouter reasoning & stream options
        builder.putAdditionalBodyProperty("include_reasoning", JsonValue.from(true));
        if (stream) {
            builder.streamOptions(ChatCompletionStreamOptions.builder().includeUsage(true).build());
        }

        return builder.build();
    }
}
