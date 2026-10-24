package de.palsoftware.yvoke.rag.core.service;

import de.palsoftware.yvoke.rag.core.ContextAwareToolCallback;
import de.palsoftware.yvoke.rag.core.CitationStreamingFilter;
import de.palsoftware.yvoke.rag.core.model.AgenticChatContext;
import de.palsoftware.yvoke.rag.core.model.AgenticRequest;
import de.palsoftware.yvoke.rag.core.model.RagResult;


import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.service.CollectionService;
import de.palsoftware.yvoke.document.core.model.ChunkRow;
import de.palsoftware.yvoke.document.core.model.DocumentDetails;
import de.palsoftware.yvoke.document.core.model.DocumentRow;
import de.palsoftware.yvoke.document.core.repository.ChunkRepository;
import de.palsoftware.yvoke.document.core.repository.DocumentRepository;
import de.palsoftware.yvoke.rag.retrieval.ChunkBlocks;
import de.palsoftware.yvoke.rag.retrieval.HybridSearchResult;
import de.palsoftware.yvoke.rag.retrieval.SearchOptions;
import de.palsoftware.yvoke.rag.retrieval.SearchWithId;
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
import de.palsoftware.yvoke.rag.core.websearch.WebSearchService;
import de.palsoftware.yvoke.rag.core.websearch.WebSearchToolCallback;
import de.palsoftware.yvoke.rag.prompt.SystemPrompt;
import de.palsoftware.yvoke.rag.prompt.SystemPromptService;
import de.palsoftware.yvoke.rag.retrieval.HybridSearch;
import de.palsoftware.yvoke.shared.text.AssistantTranscript;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final HybridSearch hybridSearch;
    private final LlmClient llmClient;
    private final CitationVerifier citationVerifier;
    private final ObjectMapper objectMapper;
    private final SystemPromptService systemPromptService;
    private final WebSearchService webSearchService;
    private final CollectionService collectionService;
    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
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
            temperature, null, true, null, List.of(), null, null, null, null);
    }

    public RagService(HybridSearch hybridSearch, LlmClient llmClient,
        CitationVerifier citationVerifier, ObjectMapper objectMapper, int maxIterations,
        int maxTokens, double temperature, String thinkingLevel, boolean chatEnabled,
        @Nullable SystemPromptService systemPromptService, List<ToolCallback> toolCallbacks) {
        this(hybridSearch, llmClient, citationVerifier, objectMapper, maxIterations, maxTokens,
            temperature, thinkingLevel, chatEnabled, systemPromptService, toolCallbacks, null, null,
            null, null);
    }

    public RagService(HybridSearch hybridSearch, LlmClient llmClient,
        CitationVerifier citationVerifier, ObjectMapper objectMapper, int maxIterations,
        int maxTokens, double temperature, String thinkingLevel, boolean chatEnabled,
        @Nullable SystemPromptService systemPromptService, List<ToolCallback> toolCallbacks,
        @Nullable WebSearchService webSearchService) {
        this(hybridSearch, llmClient, citationVerifier, objectMapper, maxIterations, maxTokens,
            temperature, thinkingLevel, chatEnabled, systemPromptService, toolCallbacks,
            webSearchService, null, null, null);
    }

    public RagService(HybridSearch hybridSearch, LlmClient llmClient,
        CitationVerifier citationVerifier, ObjectMapper objectMapper,
        @Nullable CollectionService collectionService,
        @Nullable DocumentRepository documentRepository,
        @Nullable ChunkRepository chunkRepository) {
        this(hybridSearch, llmClient, citationVerifier, objectMapper, 6, 4096, 0.7, null, true,
            null, List.of(), null, collectionService, documentRepository, chunkRepository);
    }

    @Autowired
    public RagService(HybridSearch hybridSearch, LlmClient llmClient,
        CitationVerifier citationVerifier, ObjectMapper objectMapper,
        @Value("${app.ai.rag.max-iterations}") int maxIterations,
        @Value("${app.ai.rag.max-tokens}") int maxTokens,
        @Value("${app.ai.rag.temperature}") double temperature,
        @Value("${app.ai.rag.thinking-level}") String thinkingLevel,
        @Value("${app.chat.enabled}") boolean chatEnabled,
        @Nullable SystemPromptService systemPromptService,
        @Nullable List<ToolCallback> toolCallbacks, @Nullable WebSearchService webSearchService,
        @Nullable CollectionService collectionService,
        @Nullable DocumentRepository documentRepository,
        @Nullable ChunkRepository chunkRepository) {
        this.hybridSearch = hybridSearch;
        this.llmClient = llmClient;
        this.citationVerifier = citationVerifier;
        this.objectMapper = objectMapper;
        this.systemPromptService = systemPromptService;
        this.webSearchService = webSearchService;
        this.collectionService = collectionService;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
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
        WebSearchToolCallback webSearchCallback =
            new WebSearchToolCallback(webSearchService, objectMapper);
        toolRegistry.put(webSearchCallback.getToolDefinition().name(), webSearchCallback);
        log.info("Registered agentic tool: {}", webSearchCallback.getToolDefinition().name());
    }

    /** Per-turn streaming state, accumulated on the (single) calling thread. */
    private static final class TurnState {
        /**
         * The answer text, and ONLY the answer text — what the model may be replayed. Chrome (the
         * {@code <think>} markers, tool banners, the loop-cap warning) reaches the sink without
         * passing through here, which is what makes replaying it unrepresentable rather than merely
         * discouraged.
         */
        final StringBuilder answerText = new StringBuilder();
        final StringBuilder reasoning = new StringBuilder();
        /** Raw content length before citation filtering, to detect that the filter removed some. */
        int rawAnswerLength;
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

        AgenticChatContext ctx = new AgenticChatContext(request.allowedAreasLower());
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

            TurnTools tools = hitCap ? TurnTools.none()
                : buildTurnTools(request.allowedTools(), runRegistry, alwaysInclude);

            TurnState turn = new TurnState(new CitationStreamingFilter(citationVerifier));
            String resolvedThinkingLevel = request.thinkingLevel();
            if (resolvedThinkingLevel == null || resolvedThinkingLevel.isBlank()) {
                resolvedThinkingLevel = this.thinkingLevel;
            }

            llmClient.generateStream(
                new LlmRequest(resolvedModel, new ArrayList<>(messages), temperature, maxTokens,
                    tools.declarations(), resolvedThinkingLevel, request.codeExecution()),
                chunk -> handleChunk(chunk, turn, sink));

            // Close a dangling <think> block and flush any buffered citation content.
            if (turn.inReasoning) {
                render(sink, "\n</think>\n\n");
                turn.inReasoning = false;
            }
            for (String token : turn.citationFilter.flush()) {
                answer(turn, sink, token);
            }

            log.info("Agentic turn {}/{} LLM streaming finished", iterations + 1, maxIterations);

            promptTokensAcc += turn.promptTokens;
            completionTokensAcc += turn.completionTokens;
            totalTokensAcc += turn.totalTokens;
            cachedTokensAcc += turn.cachedTokens;
            thoughtTokensAcc += turn.thoughtTokens;

            String assistantContent = turn.answerText.toString();

            List<LlmPart> assistantParts = new ArrayList<>();
            if (turn.reasoning.length() > 0) {
                assistantParts.add(new LlmPart("thought", turn.reasoning.toString(), null,
                    turn.thoughtPartSignature));
            }
            if (turn.answerText.length() > 0) {
                // Same buffer as assistantContent, so content() == concat(text parts) holds by
                // construction rather than by two places agreeing.
                //
                // The signature is dropped when the citation filter removed something: it was
                // produced over the text the model emitted, and these bytes are no longer that
                // text. Gemini re-sends it verbatim, and a signature that does not cover its part
                // would fail on the FOLLOW-UP turn — intermittently, only on runs that contained a
                // fabricated citation, and attributed to something else.
                boolean filtered = turn.rawAnswerLength != turn.answerText.length();
                if (filtered) {
                    log.debug(
                        "Citation filter altered the answer ({} raw chars -> {}); dropping the"
                            + " text-part signature for this turn",
                        turn.rawAnswerLength, turn.answerText.length());
                }
                assistantParts.add(new LlmPart("text", turn.answerText.toString(), null,
                    filtered ? null : turn.textPartSignature));
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

            // Keyed on the PARTS, not on the answer text. A turn that produced only reasoning has
            // produced something; before the split its <think> markers happened to make
            // assistantContent non-empty, so this guard never saw that case. Reporting it as an
            // empty response would blame a malformed function call for a working provider.
            if (assistantParts.isEmpty() && toolCalls.isEmpty()) {
                throw new IllegalStateException("The LLM generated an empty response (possible "
                    + "MALFORMED_FUNCTION_CALL or safety block).");
            }

            if (!toolCalls.isEmpty()) {
                executeToolCalls(toolCalls, messages, sink, ctx, tools, request.traceToolCalls());
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
                render(sink, "<think>\n");
                turn.reasoningStarted = true;
                turn.inReasoning = true;
            }
            render(sink, reasoningToken);
        }

        if (contentToken != null && !contentToken.isEmpty()) {
            turn.rawAnswerLength += contentToken.length();
            if (turn.inReasoning) {
                render(sink, "\n</think>\n\n");
                turn.inReasoning = false;
            }
            for (String token : turn.citationFilter.processToken(contentToken)) {
                answer(turn, sink, token);
            }
        }
    }

    /**
     * Renders a token to the user and nowhere else.
     *
     * <p>
     * Takes no {@link TurnState} on purpose: chrome physically cannot reach the replayed assistant
     * turn through this method, so the defect it replaces cannot be re-introduced by a careless
     * call site. Do not add an overload that accepts a turn.
     */
    private static void render(Consumer<String> sink, String token) {
        if (token == null || token.isEmpty()) {
            return;
        }
        sink.accept(token);
    }

    /**
     * Renders a token AND records it as answer text. Only citation-filtered content may come here;
     * the filter is the boundary between what the model produced and what we are willing to repeat
     * back to it.
     */
    private static void answer(TurnState turn, Consumer<String> sink, String token) {
        if (token == null || token.isEmpty()) {
            return;
        }
        turn.answerText.append(token);
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

    /**
     * The tools for one turn: what the model is told it may call, and what may actually be
     * dispatched.
     *
     * <p>
     * The two travel as one value, and {@code executeToolCalls} is given nothing else, because the
     * defect this replaces was precisely that they were two separate things. {@code allowedTools}
     * filtered only the declarations while dispatch resolved against the whole run registry, so the
     * allow-list governed what was offered and nothing at all governed what was executed — and the
     * one caller that depends on the restriction being real is the reviewer, which
     * {@code OrchestrationService} bars from {@code get_section} on the argument that a reviewer
     * using it judges claims against sibling text no specialist ever retrieved. Handing the
     * unfiltered registry to the dispatch site again is the only way to reintroduce that, and there
     * is no longer a parameter through which to do it.
     *
     * @param denied names present in the registry that this run's allow-list refused. Carried for
     *        diagnosis only — a String cannot be invoked — so a stale playbook advertising a tool a
     *        role no longer has is distinguishable in the log from a model inventing a name.
     * @param unavailable names permitted by the run's allow-list that were withheld by a
     *        deployment-level gate (e.g. {@code WebSearch} when {@code app.web-search.*} is off).
     */
    private record TurnTools(List<LlmTool> declarations, Map<String, ToolCallback> dispatch,
        Set<String> denied, Set<String> unavailable) {

        /** The forced post-cap turn: offered nothing, so it may execute nothing. */
        static TurnTools none() {
            return new TurnTools(List.of(), Map.of(), Set.of(), Set.of());
        }
    }

    @SuppressWarnings("unchecked")
    private TurnTools buildTurnTools(List<String> allowedTools,
        Map<String, ToolCallback> runRegistry, Set<String> alwaysInclude) {
        List<LlmTool> declarations = new ArrayList<>();
        Map<String, ToolCallback> dispatch = new HashMap<>();
        Set<String> denied = new HashSet<>();
        Set<String> unavailable = new HashSet<>();
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
                denied.add(toolName);
                continue;
            }
            if (WebSearchToolCallback.TOOL_NAME.equals(toolName)
                && (webSearchService == null || !webSearchService.isAvailable())) {
                unavailable.add(toolName);
                continue;
            }
            try {
                Map<String, Object> inputSchema =
                    objectMapper.readValue(callback.getToolDefinition().inputSchema(), Map.class);
                declarations.add(
                    new LlmTool(toolName, callback.getToolDefinition().description(), inputSchema));
                // Populated in the same branch as the declaration, so "offered" and "dispatchable"
                // are the same set by construction rather than by two places agreeing. A tool whose
                // schema will not parse is therefore not dispatchable either — it was never
                // offered, so the model has no legitimate way to name it.
                dispatch.put(toolName, callback);
            } catch (Exception e) {
                log.error("Failed to parse schema for tool {}", toolName, e);
            }
        }
        return new TurnTools(declarations, dispatch, denied, unavailable);
    }

    private void executeToolCalls(List<LlmToolCall> toolCalls, List<LlmMessage> messages,
        Consumer<String> sink, AgenticChatContext ctx, TurnTools tools, boolean traceToolCalls) {
        for (LlmToolCall tc : toolCalls) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Chat generation cancelled");
            }
            log.info("Executing agentic tool: {} with args: {}", tc.name(), tc.arguments());
            // Only what this turn actually offered. There is no unfiltered registry in scope.
            ToolCallback callback = tools.dispatch().get(tc.name());

            if ("ask_clarifying_question".equals(tc.name())) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> args = objectMapper.readValue(tc.arguments(), Map.class);
                    String question = (String) args.get("question");
                    @SuppressWarnings("unchecked")
                    List<String> options = (List<String>) args.get("options");

                    StringBuilder xml = new StringBuilder();
                    xml.append("\n<clarifying-question>\n");
                    xml.append("  <question>").append(xmlText(question)).append("</question>\n");
                    if (options != null) {
                        for (String opt : options) {
                            xml.append("  <option>").append(xmlText(opt)).append("</option>\n");
                        }
                    }
                    xml.append("</clarifying-question>\n\n");
                    sink.accept(xml.toString());
                } catch (Exception e) {
                    log.error("Failed to format clarifying question XML", e);
                    emitToolBanner(sink, tc, traceToolCalls);
                }
            } else {
                emitToolBanner(sink, tc, traceToolCalls);
            }

            String responseData;
            boolean isError = false;
            if (callback != null) {
                try {
                    String guarded = guardToolExecution(tc, ctx);
                    if (guarded != null) {
                        responseData = guarded;
                        isError =
                            responseData.startsWith("Error:") || responseData.startsWith("Error ");
                        log.info("Tool {} intercepted by area guard: {}", tc.name(), responseData);
                    } else {
                        if (callback instanceof ContextAwareToolCallback contextAware) {
                            responseData = contextAware.callWithContext(tc.arguments(), ctx);
                        } else {
                            responseData = callback.call(tc.arguments());
                        }
                        if (responseData != null && (responseData.startsWith("Error:")
                            || responseData.startsWith("Error "))) {
                            isError = true;
                        }
                        log.info("Tool {} executed successfully, response length: {}", tc.name(),
                            responseData != null ? responseData.length() : 0);
                    }
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
                    isError = true;
                }
            } else if (tools.unavailable().contains(tc.name())) {
                log.warn(
                    "Tool {} was withheld by the deployment gate; it is permitted by the playbook "
                        + "but disabled or unconfigured in this deployment (check app.web-search.* "
                        + "and app.ai.gemini.api-key)",
                    tc.name());
                responseData = "Error: Tool " + tc.name() + " not found.";
                isError = true;
            } else if (tools.denied().contains(tc.name())) {
                // Registered, but not permitted this run — a stale playbook advertising a tool the
                // role no longer has, which costs a turn every time it happens. Worth telling apart
                // from an invented name in the LOG; the model-facing string stays identical,
                // because the run's configuration is something it can neither see nor fix.
                log.warn(
                    "Tool {} was denied by this run's allow-list; it is registered but was not "
                        + "offered this turn (check the playbook's tool list)",
                    tc.name());
                responseData = "Error: Tool " + tc.name() + " not found.";
                isError = true;
            } else {
                log.warn("Tool {} not found", tc.name());
                responseData = "Error: Tool " + tc.name() + " not found.";
                isError = true;
            }
            List<LlmPart> toolParts =
                List.of(new LlmPart("function_response", responseData, null, null));
            messages.add(
                new LlmMessage("tool", responseData, toolParts, null, tc.id(), tc.name(), isError));
        }
    }

    private static void emitToolBanner(Consumer<String> sink, LlmToolCall tc,
        boolean traceToolCalls) {
        if (traceToolCalls && tc.id() != null && !tc.id().isBlank()) {
            String argsSingleLine =
                tc.arguments() != null ? tc.arguments().replaceAll("\\R", " ") : "{}";
            sink.accept(String.format("🔧 *Calling tool:* %s(%s) #%s\n\n", tc.name(),
                argsSingleLine, tc.id()));
        } else {
            sink.accept(String.format("🔧 *Calling tool:* %s(%s)\n\n", tc.name(), tc.arguments()));
        }
    }

    /**
     * Escapes model-authored text for an XML text node.
     *
     * <p>
     * The clarifying-question block is the one place this service builds markup by concatenation
     * from text the model wrote, and nothing downstream escapes it: the citation filter touches
     * only bracketed tokens, and {@code markdown-render.js} lifts the whole block out before
     * {@code marked} runs, so the component that would have escaped raw HTML never sees it. On a
     * corpus where {@code <Person>} and {@code 
     * 
    <table>
     * } are ordinary identifiers this is routine.
     *
     * <p>
     * The cost is not cosmetic. An unescaped identifier is parsed as an element, DOMPurify drops it
     * and hoists its children, and the browser reads back {@code textContent} — so the user sees a
     * question with a word missing while the orchestrator is shown the original, and an option
     * mangled the same way is written into the composer and submitted as the user's next message.
     * The corruption is fed back to the model as the answer it asked for.
     *
     * <p>
     * Ampersand first, or the escaping would re-escape its own output. Quotes are deliberately left
     * alone: these are text nodes, and the frontend escapes quotes itself when it lifts the text
     * into an attribute.
     */
    private static String xmlText(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
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

    @Nullable
    private String guardToolExecution(LlmToolCall tc, @Nullable AgenticChatContext ctx) {
        if (ctx == null || collectionService == null) {
            return null;
        }

        String name = tc.name();
        if (name == null) {
            return null;
        }

        Map<String, Object> args = parseJsonArguments(tc.arguments());

        switch (name) {
            case "search_corpus":
                return guardSearchCorpus(args, ctx);
            case "list_documents":
                return guardListDocuments(args, ctx);
            case "get_toc":
                return guardGetToc(args, ctx);
            case "get_section":
                return guardGetSection(args, ctx);
            case "search_graph_entities":
            case "get_graph_neighbors":
            case "get_json_schema":
            case "query_json_objects":
                return guardCollectionParamTool(name, args, ctx);
            case "verify_citations":
                return guardVerifyCitations(args, ctx);
            default:
                return null;
        }
    }

    @Nullable
    private String guardCollectionParamTool(String toolName, Map<String, Object> args,
        AgenticChatContext ctx) {
        String colName = (String) args.get("collection");
        if (colName != null && !colName.isBlank()) {
            Collection col = resolveCollection(colName, ctx);
            if (col == null) {
                return "Error: Collection '" + colName.trim() + "' does not exist.";
            }
        } else if ("search_graph_entities".equals(toolName) && ctx.getAllowedAreasLower() != null) {
            List<Collection> allowed = collectionService.listCollections().stream()
                .filter(c -> ctx.isAreaAllowed(c.area())).toList();
            if (allowed.isEmpty()) {
                return "[]";
            }
        }
        return null;
    }

    @Nullable
    private String guardSearchCorpus(Map<String, Object> args, AgenticChatContext ctx) {
        String colName = (String) args.get("collection");
        if (colName != null && !colName.isBlank()) {
            Collection col = resolveCollection(colName, ctx);
            if (col == null) {
                return "Error: Collection '" + colName.trim() + "' does not exist.";
            }
            return null;
        }

        // Omit-collection search
        if (ctx.getAllowedAreasLower() != null) {
            List<Collection> allowed = collectionService.listCollections().stream()
                .filter(c -> ctx.isAreaAllowed(c.area())).toList();
            if (allowed.isEmpty()) {
                return "No relevant documents found.";
            }
            if (hybridSearch != null) {
                String q = (String) args.get("query");
                if (q == null || q.isBlank()) {
                    return "Error: 'query' parameter is required for search_corpus. If you wanted to list documents or modules in a collection (e.g. by kind), please use the 'list_documents' tool instead.";
                }
                List<String> allowedNames = allowed.stream().map(Collection::name).toList();
                Integer limit = (args.get("limit") instanceof Number n) ? n.intValue() : null;
                String tag = (String) args.get("tag");
                String tagError = requireAreaTag(allowed, tag);
                if (tagError != null) {
                    return tagError;
                }
                List<String> tagList =
                    (tag != null && !tag.isBlank()) ? List.of(tag.trim()) : List.of();
                SearchWithId swi = hybridSearch.searchWithId(q.trim(),
                    new SearchOptions(allowedNames, limit, null, null, 0, true, tagList));
                if (swi != null) {
                    if (swi.searchId() != null) {
                        ctx.getSearchIds().add(swi.searchId());
                    }
                    if (swi.results() != null && !swi.results().isEmpty()) {
                        for (HybridSearchResult res : swi.results()) {
                            ctx.getRetrievedChunkIds().add(res.id());
                        }
                        return ChunkBlocks.format(swi.results(), ctx);
                    }
                }
                return "No relevant documents found.";
            }
        }
        return null;
    }

    @Nullable
    private String guardListDocuments(Map<String, Object> args, AgenticChatContext ctx) {
        String colName = (String) args.get("collection");
        if (colName != null && !colName.isBlank()) {
            Collection col = resolveCollection(colName, ctx);
            if (col == null) {
                return "Error: Collection '" + colName.trim() + "' does not exist.";
            }
            return null;
        }

        // Omit-collection listing
        if (ctx.getAllowedAreasLower() != null) {
            List<Collection> allowed = collectionService.listCollections().stream()
                .filter(c -> ctx.isAreaAllowed(c.area())).toList();
            if (allowed.isEmpty()) {
                return "| document_id | kind | title | chunks |\n|---|---|---|---|";
            }
            String rawTag = (String) args.get("tag");
            String parsedTag = (rawTag != null && !rawTag.isBlank()) ? rawTag.trim() : null;
            String tagError = requireAreaTag(allowed, parsedTag);
            if (tagError != null) {
                return tagError;
            }
            String rawKind = (String) args.get("kind");
            String parsedKind = (rawKind != null && !rawKind.isBlank()) ? rawKind.trim() : null;
            String rawQuery = (String) args.get("query");
            String fuzzyQuery = (rawQuery != null && !rawQuery.isBlank()) ? rawQuery.trim() : null;
            int lim = (args.get("limit") instanceof Number n) ? n.intValue() : 100;
            int off = (args.get("offset") instanceof Number n) ? n.intValue() : 0;
            if (documentRepository != null) {
                StringBuilder sb = new StringBuilder();
                sb.append("| document_id | kind | title | chunks |\n|---|---|---|---|\n");
                for (Collection c : allowed) {
                    try {
                        String effectiveKind = parsedKind;
                        if (parsedKind != null) {
                            List<String> validKinds =
                                documentRepository.findDistinctKindsInCollection(c.name());
                            if (validKinds != null && !validKinds.isEmpty()) {
                                effectiveKind = validKinds.stream()
                                    .filter(parsedKind::equalsIgnoreCase).findFirst().orElse(null);
                                if (effectiveKind == null) {
                                    continue;
                                }
                            }
                        }
                        List<DocumentDetails> docs = documentRepository.listDocuments(c.name(), lim,
                            off, effectiveKind, parsedTag, null, null, fuzzyQuery);
                        if (docs != null) {
                            for (DocumentDetails d : docs) {
                                sb.append("| ").append(d.id() != null ? d.id() : "").append(" | ")
                                    .append(escapeTableCell(d.kind())).append(" | ")
                                    .append(escapeTableCell(d.title())).append(" | ")
                                    .append(d.chunkCount()).append(" |\n");
                            }
                        }
                    } catch (Exception e) {
                        log.warn("Failed to list documents for collection {}", c.name(), e);
                    }
                }
                return sb.toString();
            }
            return "| document_id | kind | title | chunks |\n|---|---|---|---|";
        }
        return null;
    }

    @Nullable
    private static String requireAreaTag(List<Collection> allowed, @Nullable String rawTag) {
        List<Collection> taggedCols =
            allowed.stream().filter(c -> c.tags() != null && !c.tags().isEmpty()).toList();
        if (taggedCols.isEmpty()) {
            return null;
        }
        String parsedTag = (rawTag != null && !rawTag.isBlank()) ? rawTag.trim() : null;
        if (parsedTag == null) {
            if (allowed.size() == 1 && taggedCols.size() == 1) {
                Collection single = taggedCols.get(0);
                return "Error: Collection '" + single.name()
                    + "' is version/tag-scoped. Please specify 'tag' (valid values: "
                    + String.join(", ", single.tags())
                    + ") so results come from a single version/dataset.";
            }
            String taggedSummary = taggedCols.stream()
                .map(c -> c.name() + " (valid tags: " + String.join(", ", c.tags()) + ")")
                .collect(Collectors.joining("; "));
            return "Error: Area contains version/tag-scoped collections (" + taggedSummary
                + "). Please specify 'collection' or 'tag' so results come from a single version/dataset.";
        }
        for (Collection tc : taggedCols) {
            if (!tc.tags().contains(parsedTag)) {
                return "Error: Tag '" + parsedTag + "' does not exist in collection '" + tc.name()
                    + "' (valid tags: " + String.join(", ", tc.tags())
                    + "). Please specify 'collection' or a 'tag' present in all tagged collections.";
            }
        }
        return null;
    }

    @Nullable
    private String guardGetToc(Map<String, Object> args, AgenticChatContext ctx) {
        String docIdStr = (String) args.get("document_id");
        if (docIdStr == null || docIdStr.isBlank()) {
            docIdStr = (String) args.get("documentId");
        }
        if (docIdStr == null || docIdStr.isBlank()) {
            return null;
        }
        UUID docId = parseUuid(docIdStr);
        if (docId != null && documentRepository != null) {
            Optional<DocumentRow> docOpt = documentRepository.findById(docId);
            if (docOpt.isPresent()) {
                Optional<Collection> colOpt =
                    collectionService.findById(docOpt.get().collectionId());
                if (colOpt.isEmpty() || !ctx.isAreaAllowed(colOpt.get().area())) {
                    return "Error: Document with ID '" + docId + "' not found.";
                }
            }
        }
        return null;
    }

    @Nullable
    private String guardGetSection(Map<String, Object> args, AgenticChatContext ctx) {
        String docIdStr = (String) args.get("document_id");
        if (docIdStr == null || docIdStr.isBlank()) {
            docIdStr = (String) args.get("documentId");
        }
        String chunkIdStr = (String) args.get("chunk_id");
        if (chunkIdStr == null || chunkIdStr.isBlank()) {
            chunkIdStr = (String) args.get("chunkId");
        }

        if (docIdStr != null && !docIdStr.isBlank()) {
            UUID docId = parseUuid(docIdStr);
            if (docId != null && documentRepository != null) {
                Optional<DocumentRow> docOpt = documentRepository.findById(docId);
                if (docOpt.isPresent()) {
                    Optional<Collection> colOpt =
                        collectionService.findById(docOpt.get().collectionId());
                    if (colOpt.isEmpty() || !ctx.isAreaAllowed(colOpt.get().area())) {
                        return "Error: Document not found: " + docIdStr.trim();
                    }
                }
            }
        }

        if (chunkIdStr != null && !chunkIdStr.isBlank()) {
            if (chunkRepository != null && documentRepository != null) {
                Optional<ChunkRow> chunkOpt = Optional.empty();
                try {
                    chunkOpt = chunkRepository.findByIdPrefix(chunkIdStr.trim());
                } catch (IllegalArgumentException ignored) {
                    // Short or non-hex prefix; let the tool or UUID fallback handle it
                }
                if (chunkOpt.isEmpty()) {
                    UUID chunkId = parseUuid(chunkIdStr);
                    if (chunkId != null) {
                        chunkOpt = chunkRepository.findById(chunkId);
                    }
                }
                if (chunkOpt.isPresent()) {
                    Optional<DocumentRow> docOpt =
                        documentRepository.findById(chunkOpt.get().documentId());
                    if (docOpt.isPresent()) {
                        Optional<Collection> colOpt =
                            collectionService.findById(docOpt.get().collectionId());
                        if (colOpt.isEmpty() || !ctx.isAreaAllowed(colOpt.get().area())) {
                            return "Error: Chunk not found: " + chunkIdStr.trim();
                        }
                    }
                }
            }
        }

        return null;
    }

    @Nullable
    private String guardVerifyCitations(Map<String, Object> args, AgenticChatContext ctx) {
        if (citationVerifier == null) {
            return null;
        }
        List<String> citations = extractCitations(args.get("citations"));
        if (citations == null || citations.isEmpty()) {
            return "Error: 'citations' parameter is required. At least one citation token must be specified.";
        }
        List<CitationVerifier.CitationCheckResult> results =
            citationVerifier.checkCitations(citations, ctx.getAllowedAreasLower());
        return CitationVerifier.formatReport(results);
    }

    private static String escapeTableCell(@Nullable String val) {
        if (val == null) {
            return "";
        }
        return val.replace("|", "\\|").replace("\n", " ");
    }

    public @Nullable Collection resolveCollection(@Nullable String collectionName,
        @Nullable AgenticChatContext context) {
        if (collectionName == null || collectionName.isBlank() || collectionService == null) {
            return null;
        }
        String trimmed = collectionName.trim();
        List<Collection> allCols = collectionService.listCollections();
        if (allCols == null) {
            return null;
        }
        Collection matched = allCols.stream().filter(c -> c.name().equalsIgnoreCase(trimmed))
            .findFirst().orElse(null);
        if (matched == null) {
            return null;
        }
        if (context != null && !context.isAreaAllowed(matched.area())) {
            return null;
        }
        return matched;
    }

    public @Nullable UUID resolveCollectionId(@Nullable String collectionName,
        @Nullable AgenticChatContext context) {
        Collection col = resolveCollection(collectionName, context);
        return col != null ? col.id() : null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonArguments(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    private List<String> extractCitations(Object citationsObj) {
        if (citationsObj instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item != null) {
                    result.add(item.toString());
                }
            }
            return result;
        }
        return Collections.emptyList();
    }

    private static UUID parseUuid(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw.toString().trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Turns a stored assistant transcript into text the model may be replayed.
     *
     * <p>
     * This used to strip tool banners and leave {@code <think>} blocks untouched, so every
     * follow-up turn replayed the model's own reasoning back to it as prose. The rule now lives in
     * {@link AssistantTranscript}, shared with the orchestrator's stripper, which had the opposite
     * half of the same job.
     */
    private String cleanAssistantContent(String content) {
        return AssistantTranscript.toModelText(content);
    }
}
