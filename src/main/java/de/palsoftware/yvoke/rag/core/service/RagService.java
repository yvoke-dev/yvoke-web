package de.palsoftware.yvoke.rag.core.service;

import de.palsoftware.yvoke.rag.core.ContextAwareToolCallback;
import de.palsoftware.yvoke.rag.core.CitationStreamingFilter;
import de.palsoftware.yvoke.rag.core.model.AgenticChatContext;
import de.palsoftware.yvoke.rag.core.model.AgenticRequest;
import de.palsoftware.yvoke.rag.core.model.RagResult;


import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.llm.core.LlmFailureSummary;
import de.palsoftware.yvoke.llm.core.model.LlmMessage;
import de.palsoftware.yvoke.llm.core.model.LlmRequest;
import de.palsoftware.yvoke.llm.core.model.LlmToolCall;
import de.palsoftware.yvoke.llm.core.model.LlmTool;
import de.palsoftware.yvoke.llm.core.model.LlmResponseChunk;
import de.palsoftware.yvoke.llm.core.model.LlmPart;
import de.palsoftware.yvoke.llm.core.model.LlmToolCallDelta;
import de.palsoftware.yvoke.llm.core.service.LlmClient;
import de.palsoftware.yvoke.rag.prompt.SystemPrompt;
import de.palsoftware.yvoke.rag.prompt.SystemPromptService;
import de.palsoftware.yvoke.rag.retrieval.HybridSearch;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final LlmClient llmClient;
    private final CitationVerifier citationVerifier;
    private final ObjectMapper objectMapper;
    private final SystemPromptService systemPromptService;
    private final int maxIterations;
    private final int maxTokens;
    private final double temperature;
    private final String thinkingLevel;

    private final boolean chatEnabled;

    private final Map<String, ToolCallback> toolRegistry = new HashMap<>();

    public Map<String, ToolCallback> getToolRegistry() {
        return toolRegistry;
    }

    public RagService(HybridSearch hybridSearch, LlmClient llmClient,
        CitationVerifier citationVerifier, ObjectMapper objectMapper, int maxIterations,
        int maxTokens, double temperature) {
        this(hybridSearch, llmClient, citationVerifier, objectMapper, maxIterations, maxTokens,
            temperature, null, true, null, List.of());
    }

    @Autowired
    public RagService(HybridSearch hybridSearch, LlmClient llmClient,
        CitationVerifier citationVerifier, ObjectMapper objectMapper,
        @Value("${app.ai.rag.max-iterations}") int maxIterations,
        @Value("${app.ai.rag.max-tokens}") int maxTokens,
        @Value("${app.ai.rag.temperature}") double temperature,
        @Value("${app.ai.rag.thinking-level}") String thinkingLevel,
        @Value("${app.chat.enabled}") boolean chatEnabled,
        @Nullable SystemPromptService systemPromptService, List<ToolCallback> toolCallbacks) {
        this.llmClient = llmClient;
        this.citationVerifier = citationVerifier;
        this.objectMapper = objectMapper;
        this.systemPromptService = systemPromptService;
        this.maxIterations = maxIterations;
        this.maxTokens = maxTokens;
        this.temperature = temperature;
        this.thinkingLevel = thinkingLevel;
        this.chatEnabled = chatEnabled;
        if (toolCallbacks != null) {
            for (ToolCallback callback : toolCallbacks) {
                toolRegistry.put(callback.getToolDefinition().name(), callback);
                log.info("Registered agentic tool: {}", callback.getToolDefinition().name());
            }
        }
    }

    /** Per-turn streaming state, accumulated on the (single) calling thread. */
    private static final class TurnState {
        final StringBuilder emitted = new StringBuilder();
        final StringBuilder reasoning = new StringBuilder();
        final StringBuilder textContent = new StringBuilder();
        final ToolCallAccumulator toolCallAccumulator = new ToolCallAccumulator();
        final CitationStreamingFilter citationFilter;
        String thoughtPartSignature;
        String textPartSignature;
        boolean inReasoning;
        boolean reasoningStarted;
        int promptTokens;
        int completionTokens;
        int totalTokens;
        int cachedTokens;
        int thoughtTokens;

        TurnState(CitationStreamingFilter citationFilter) {
            this.citationFilter = citationFilter;
        }
    }

    /**
     * Runs the agentic loop, pushing rendered tokens to {@code sink} as they are produced. Blocks
     * on the calling (virtual) thread until the answer is complete, then returns the run metadata.
     */
    public RagResult generateAgenticAnswer(AgenticRequest request, Consumer<String> sink) {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Chat generation cancelled");
        }
        checkChatEnabled();
        String query = request.query();
        List<LlmMessage> history = request.history();
        String systemPromptOverride = request.systemPromptOverride();

        String resolvedModel = request.modelOverride();
        if (resolvedModel == null || resolvedModel.isBlank()) {
            throw new IllegalArgumentException("modelOverride must not be null or blank");
        }

        log.info("Generating agentic answer for query: '{}', modelOverride: '{}'", query,
            resolvedModel);

        AgenticChatContext ctx = new AgenticChatContext();
        List<LlmMessage> messages = seedMessages(request, systemPromptOverride, history, query);

        // Per-run tool dispatch: the shared registry plus any run-scoped extra tools (e.g. the
        // orchestrator's call_specialist / the reviewer's submit_review). Extra tools are always
        // available for this run and bypass the playbook allow-list.
        Map<String, ToolCallback> runRegistry = new HashMap<>(toolRegistry);
        Set<String> alwaysInclude = new HashSet<>();
        if (request.extraTools() != null) {
            for (ToolCallback extra : request.extraTools()) {
                String name = extra.getToolDefinition().name();
                runRegistry.put(name, extra);
                alwaysInclude.add(name);
            }
        }

        int promptTokensAcc = 0;
        int completionTokensAcc = 0;
        int totalTokensAcc = 0;
        int cachedTokensAcc = 0;
        int thoughtTokensAcc = 0;

        int iterations = 0;
        boolean shouldContinue = true;
        boolean hitCap = false;

        while (shouldContinue && iterations <= maxIterations) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Chat generation cancelled");
            }
            log.info("Agentic turn {}/{}", iterations + 1, maxIterations);

            List<LlmTool> llmTools = hitCap ? new ArrayList<>()
                : buildLlmTools(request.allowedTools(), runRegistry, alwaysInclude);

            TurnState turn = new TurnState(new CitationStreamingFilter(citationVerifier));
            String resolvedThinkingLevel = request.thinkingLevel();
            if (resolvedThinkingLevel == null || resolvedThinkingLevel.isBlank()) {
                resolvedThinkingLevel = this.thinkingLevel;
            }

            llmClient.generateStream(
                new LlmRequest(resolvedModel, new ArrayList<>(messages), temperature, maxTokens,
                    llmTools, resolvedThinkingLevel, request.codeExecution()),
                chunk -> handleChunk(chunk, turn, sink));

            // Close a dangling <think> block and flush any buffered citation content.
            if (turn.inReasoning) {
                emit(turn, sink, "\n</think>\n\n");
                turn.inReasoning = false;
            }
            for (String token : turn.citationFilter.flush()) {
                emit(turn, sink, token);
            }

            log.info("Agentic turn {}/{} LLM streaming finished", iterations + 1, maxIterations);

            promptTokensAcc += turn.promptTokens;
            completionTokensAcc += turn.completionTokens;
            totalTokensAcc += turn.totalTokens;
            cachedTokensAcc += turn.cachedTokens;
            thoughtTokensAcc += turn.thoughtTokens;

            String assistantContent = turn.emitted.toString();

            List<LlmPart> assistantParts = new ArrayList<>();
            if (turn.reasoning.length() > 0) {
                assistantParts.add(new LlmPart("thought", turn.reasoning.toString(), null,
                    turn.thoughtPartSignature));
            }
            if (turn.textContent.length() > 0) {
                assistantParts.add(
                    new LlmPart("text", turn.textContent.toString(), null, turn.textPartSignature));
            }

            List<LlmToolCall> toolCalls = Collections.emptyList();
            if (!turn.toolCallAccumulator.isEmpty()) {
                toolCalls = turn.toolCallAccumulator.assemble();
                for (LlmToolCall tc : toolCalls) {
                    String tcSig = tc.extraContent() != null
                        ? (String) tc.extraContent().get("thoughtSignature")
                        : null;
                    assistantParts.add(new LlmPart("function_call", null, tc, tcSig));
                }
            }

            messages.add(new LlmMessage("assistant", assistantContent, assistantParts, toolCalls,
                null, null));

            if (assistantContent.isEmpty() && toolCalls.isEmpty()) {
                throw new IllegalStateException("The LLM generated an empty response (possible "
                    + "MALFORMED_FUNCTION_CALL or safety block).");
            }

            if (!toolCalls.isEmpty()) {
                executeToolCalls(toolCalls, messages, sink, ctx, runRegistry);
                iterations++;
                if (ctx.isHaltRequested()) {
                    log.info("Halt requested by a tool (clarifying question / review verdict). "
                        + "Halting agentic loop.");
                    shouldContinue = false;
                } else if (iterations >= maxIterations) {
                    log.warn(
                        "Agentic loop hit cap of {} iterations. Forcing final text generation.",
                        maxIterations);
                    hitCap = true;
                }
            } else {
                shouldContinue = false;
            }
        }

        if (hitCap) {
            sink.accept(String.format(
                "\n\n⚠️ *[System: The maximum loop count of %d iterations was hit. The response was "
                    + "cut off to prevent an infinite loop.]*",
                maxIterations));
        }

        List<UUID> retrievedChunkIds =
            ctx.getRetrievedChunkIds() != null ? new ArrayList<>(ctx.getRetrievedChunkIds())
                : new ArrayList<>();
        List<UUID> searchIds =
            ctx.getSearchIds() != null ? new ArrayList<>(ctx.getSearchIds()) : new ArrayList<>();

        log.info("Agentic loop finished. Total turns executed: {}", iterations + 1);

        return new RagResult(retrievedChunkIds, new ArrayList<>(messages), null, searchIds,
            promptTokensAcc, completionTokensAcc, totalTokensAcc, cachedTokensAcc, thoughtTokensAcc,
            ctx.getClarifyingQuestion(), ctx.getClarifyingOptions());
    }

    /**
     * Processes one streamed chunk: tallies usage, captures signatures, accumulates tool-call
     * deltas, and emits reasoning/content tokens (wrapped in {@code <think>} tags and citation
     * filtered) to the sink.
     */
    private void handleChunk(LlmResponseChunk chunk, TurnState turn, Consumer<String> sink) {
        if (chunk == null) {
            return;
        }

        if (chunk.usage() != null) {
            turn.promptTokens = chunk.usage().promptTokens();
            turn.completionTokens = chunk.usage().completionTokens();
            turn.totalTokens = chunk.usage().totalTokens();
            turn.cachedTokens = chunk.usage().cachedTokens();
            turn.thoughtTokens = chunk.usage().thoughtTokens();
        }

        if (chunk.parts() != null) {
            for (LlmPart lp : chunk.parts()) {
                if ("thought".equals(lp.type()) && lp.thoughtSignature() != null) {
                    turn.thoughtPartSignature = lp.thoughtSignature();
                } else if ("text".equals(lp.type()) && lp.thoughtSignature() != null) {
                    turn.textPartSignature = lp.thoughtSignature();
                }
            }
        }

        if (chunk.toolCallDeltas() != null) {
            for (LlmToolCallDelta delta : chunk.toolCallDeltas()) {
                turn.toolCallAccumulator.accept(delta);
            }
        }

        String reasoningToken = chunk.reasoning();
        String contentToken = chunk.content();

        if (reasoningToken != null && !reasoningToken.isEmpty()) {
            turn.reasoning.append(reasoningToken);
            if (!turn.reasoningStarted) {
                emit(turn, sink, "<think>\n");
                turn.reasoningStarted = true;
                turn.inReasoning = true;
            }
            emit(turn, sink, reasoningToken);
        }

        if (contentToken != null && !contentToken.isEmpty()) {
            turn.textContent.append(contentToken);
            if (turn.inReasoning) {
                emit(turn, sink, "\n</think>\n\n");
                turn.inReasoning = false;
            }
            for (String token : turn.citationFilter.processToken(contentToken)) {
                emit(turn, sink, token);
            }
        }
    }

    /** Emits a non-empty token to the sink and records it as part of the assistant turn content. */
    private static void emit(TurnState turn, Consumer<String> sink, String token) {
        if (token == null || token.isEmpty()) {
            return;
        }
        turn.emitted.append(token);
        sink.accept(token);
    }

    /**
     * The message list this call starts from: either a continuation of a conversation the caller
     * already holds, or a fresh one built from {@code history}.
     *
     * <p>
     * The two channels are not interchangeable and the difference is the whole point of the
     * distinction. {@code history} is flattened by {@link #buildInitialMessages} to user/assistant
     * content strings, which is right for prior chat turns but silently drops tool calls and every
     * {@code tool} message — so an agent handed its own past conversation that way loses the
     * evidence in it. {@code priorMessages} is taken verbatim, system prompt included, and only the
     * new user turn is appended. It is therefore the caller's job to supply a list that already
     * begins with a system message; {@code systemPromptOverride} is deliberately not re-applied,
     * because overwriting index 0 would silently rewrite the instructions the earlier turns were
     * answering.
     */
    private List<LlmMessage> seedMessages(AgenticRequest request, String systemPromptOverride,
        List<LlmMessage> history, String query) {
        List<LlmMessage> prior = request.priorMessages();
        if (prior == null || prior.isEmpty()) {
            return buildInitialMessages(systemPromptOverride, history, query);
        }
        List<LlmMessage> messages = new ArrayList<>(prior);
        messages.add(new LlmMessage("user", query));
        log.info("Continuing an existing agent conversation: {} prior messages", prior.size());
        return messages;
    }

    private List<LlmMessage> buildInitialMessages(String systemPromptOverride,
        List<LlmMessage> history, String query) {
        List<LlmMessage> messages = new ArrayList<>();
        String systemPromptText =
            (systemPromptOverride != null && !systemPromptOverride.isBlank()) ? systemPromptOverride
                : loadAgenticSystemPrompt();
        messages.add(new LlmMessage("system", systemPromptText));

        if (history != null && !history.isEmpty()) {
            for (LlmMessage msg : history) {
                if ("user".equalsIgnoreCase(msg.role())) {
                    messages.add(new LlmMessage("user", msg.content()));
                } else if ("assistant".equalsIgnoreCase(msg.role())) {
                    messages.add(new LlmMessage("assistant", cleanAssistantContent(msg.content())));
                }
            }
        }

        messages.add(new LlmMessage("user", query));
        return messages;
    }

    @SuppressWarnings("unchecked")
    private List<LlmTool> buildLlmTools(List<String> allowedTools,
        Map<String, ToolCallback> runRegistry, Set<String> alwaysInclude) {
        List<LlmTool> llmTools = new ArrayList<>();
        for (ToolCallback callback : runRegistry.values()) {
            String toolName = callback.getToolDefinition().name();
            // Deny by default: null is treated exactly like an empty list. Skipping the filter when
            // allowedTools is null would be fail-OPEN — the one input a caller can produce by
            // accident (a field left unset) would grant the entire catalogue, which is backwards
            // for
            // an allow-list. Nothing relies on the old behaviour: every producer passes a real
            // list.
            if (!alwaysInclude.contains(toolName)
                && (allowedTools == null || !allowedTools.contains(toolName))) {
                continue;
            }
            try {
                Map<String, Object> inputSchema =
                    objectMapper.readValue(callback.getToolDefinition().inputSchema(), Map.class);
                llmTools.add(new LlmTool(callback.getToolDefinition().name(),
                    callback.getToolDefinition().description(), inputSchema));
            } catch (Exception e) {
                log.error("Failed to parse schema for tool {}", callback.getToolDefinition().name(),
                    e);
            }
        }
        return llmTools;
    }

    private void executeToolCalls(List<LlmToolCall> toolCalls, List<LlmMessage> messages,
        Consumer<String> sink, AgenticChatContext ctx, Map<String, ToolCallback> runRegistry) {
        for (LlmToolCall tc : toolCalls) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Chat generation cancelled");
            }
            log.info("Executing agentic tool: {} with args: {}", tc.name(), tc.arguments());
            ToolCallback callback = runRegistry.get(tc.name());

            if ("ask_clarifying_question".equals(tc.name())) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> args = objectMapper.readValue(tc.arguments(), Map.class);
                    String question = (String) args.get("question");
                    @SuppressWarnings("unchecked")
                    List<String> options = (List<String>) args.get("options");

                    StringBuilder xml = new StringBuilder();
                    xml.append("\n<clarifying-question>\n");
                    xml.append("  <question>").append(question).append("</question>\n");
                    if (options != null) {
                        for (String opt : options) {
                            xml.append("  <option>").append(opt).append("</option>\n");
                        }
                    }
                    xml.append("</clarifying-question>\n\n");
                    sink.accept(xml.toString());
                } catch (Exception e) {
                    log.error("Failed to format clarifying question XML", e);
                    sink.accept(
                        String.format("🔧 *Calling tool:* %s(%s)\n\n", tc.name(), tc.arguments()));
                }
            } else {
                sink.accept(
                    String.format("🔧 *Calling tool:* %s(%s)\n\n", tc.name(), tc.arguments()));
            }

            String responseData;
            if (callback != null) {
                try {
                    if (callback instanceof ContextAwareToolCallback contextAware) {
                        responseData = contextAware.callWithContext(tc.arguments(), ctx);
                    } else {
                        responseData = callback.call(tc.arguments());
                    }
                    log.info("Tool {} executed successfully, response length: {}", tc.name(),
                        responseData != null ? responseData.length() : 0);
                } catch (Exception e) {
                    if (Thread.currentThread().isInterrupted() || e instanceof InterruptedException
                        || e instanceof CancellationException
                        || e.getCause() instanceof InterruptedException
                        || e.getCause() instanceof CancellationException) {
                        Thread.currentThread().interrupt(); // Restore interrupted status
                        throw new CancellationException(
                            "Chat generation cancelled during tool execution");
                    }
                    // The rich diagnosis goes to the log and the agent-run trace; what the model
                    // gets back must stay provider-neutral. A tool result is appended to the
                    // conversation, and in orchestrated mode the orchestrator's synthesized text
                    // becomes the user-visible answer — so an exception class or HTTP status handed
                    // back here can be paraphrased straight past the generic error wording that
                    // exists to keep provider and stack detail away from end users (SEC-17).
                    log.error("Failed to execute tool {}: {}", tc.name(),
                        LlmFailureSummary.shortLine(e), e);
                    responseData = "Error: the " + tc.name()
                        + " tool could not be completed. Continue with the evidence you already"
                        + " have and state the gap in your answer.";
                }
            } else {
                log.warn("Tool {} not found", tc.name());
                responseData = "Error: Tool " + tc.name() + " not found.";
            }
            List<LlmPart> toolParts =
                List.of(new LlmPart("function_response", responseData, null, null));
            messages.add(new LlmMessage("tool", responseData, toolParts, null, tc.id(), tc.name()));
        }
    }

    private String loadAgenticSystemPrompt() {
        if (systemPromptService != null) {
            String activeName = systemPromptService.getDefaultChatPromptName();
            return systemPromptService.getPrompt(activeName).map(SystemPrompt::systemPrompt)
                .orElse("");
        }
        return "";
    }

    private void checkChatEnabled() {
        if (!chatEnabled) {
            throw new IllegalStateException("Webchat is disabled.");
        }
    }

    private String cleanAssistantContent(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        String[] lines = content.split("\\r?\\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("🔧 *Calling tool:*") || trimmed.startsWith("🔧 Calling tool:")
                || trimmed.startsWith("?? *Calling tool:*")
                || trimmed.startsWith("?? Calling tool:")) {
                continue;
            }
            sb.append(line).append("\n");
        }
        return sb.toString().trim();
    }
}
