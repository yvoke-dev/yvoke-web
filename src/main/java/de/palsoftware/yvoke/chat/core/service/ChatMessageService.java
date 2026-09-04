package de.palsoftware.yvoke.chat.core.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import de.palsoftware.yvoke.chat.core.model.Conversation;
import de.palsoftware.yvoke.chat.core.model.ConversationSetting;
import de.palsoftware.yvoke.chat.core.model.Message;
import de.palsoftware.yvoke.chat.core.repository.ConversationRepository;
import de.palsoftware.yvoke.chat.core.repository.MessageRepository;
import de.palsoftware.yvoke.chat.orchestration.OrchestrationService;
import de.palsoftware.yvoke.chat.orchestration.OrchestrationService.OrchestrationResult;
import de.palsoftware.yvoke.llm.core.model.LlmMessage;
import de.palsoftware.yvoke.rag.core.model.AgenticRequest;
import de.palsoftware.yvoke.rag.core.model.RagResult;
import de.palsoftware.yvoke.rag.core.service.RagService;
import de.palsoftware.yvoke.rag.prompt.Playbook;
import de.palsoftware.yvoke.rag.prompt.PlaybookService;
import de.palsoftware.yvoke.rag.prompt.SystemPrompt;
import de.palsoftware.yvoke.rag.prompt.SystemPromptService;
import de.palsoftware.yvoke.rag.retrieval.RetrievalLogRepository;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import de.palsoftware.yvoke.llm.core.context.LlmCallContextHolder;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class ChatMessageService {
    private static final Logger log = LoggerFactory.getLogger(ChatMessageService.class);

    /**
     * User-facing text shown when a generation fails. The real cause is always logged server-side;
     * it must never be embedded into the persisted/streamed message, which would leak SQL / LLM
     * provider / stack-trace detail to the end user (SEC-17).
     */
    private static final String GENERIC_ERROR_TEXT =
        "⚠️ *[System Error: the assistant could not complete this response.]*";

    /**
     * Terminal status for a generation the user stopped, as distinct from one that failed.
     *
     * <p>
     * Both used to persist "error", so nothing downstream could tell a Stop from a fault — which is
     * how a run killed by an HTTP 429 came to be captioned "[Generation stopped by user]" in the
     * chat. {@code messages.status} is an unconstrained TEXT column and no query filters on
     * {@code 'error'}, so the new value needs no migration.
     */
    static final String STATUS_CANCELLED = "cancelled";

    private final MessageRepository messageRepository;
    private final ConversationRepository conversationRepository;
    private final ChatConversationService chatConversationService;
    private final RagService ragService;
    private final RetrievalLogRepository retrievalLogRepository;
    private final PlaybookService playbookService;
    private final SystemPromptService systemPromptService;
    private final TransactionTemplate transactionTemplate;
    private final AsyncTaskExecutor taskExecutor;
    private final ChatCancellationService chatCancellationService;
    private final OrchestrationService orchestrationService;

    public ChatMessageService(MessageRepository messageRepository,
        ConversationRepository conversationRepository,
        ChatConversationService chatConversationService, RagService ragService,
        RetrievalLogRepository retrievalLogRepository, PlaybookService playbookService,
        SystemPromptService systemPromptService, PlatformTransactionManager transactionManager,
        @Qualifier("mvcTaskExecutor") AsyncTaskExecutor taskExecutor,
        ChatCancellationService chatCancellationService,
        OrchestrationService orchestrationService) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.messageRepository = messageRepository;
        this.conversationRepository = conversationRepository;
        this.chatConversationService = chatConversationService;
        this.ragService = ragService;
        this.retrievalLogRepository = retrievalLogRepository;
        this.playbookService = playbookService;
        this.systemPromptService = systemPromptService;
        this.taskExecutor = taskExecutor;
        this.chatCancellationService = chatCancellationService;
        this.orchestrationService = orchestrationService;
    }

    public List<Message> getMessages(UUID conversationId) {
        chatConversationService.checkChatEnabled();
        chatConversationService.verifyOwnership(conversationId, true);
        return messageRepository.findByConversationId(conversationId, 100, 0);
    }

    /**
     * Validates access, persists the user message and resolves model/history/tools synchronously.
     * Intended to run on the request thread so authorization/validation failures surface as normal
     * HTTP responses <em>before</em> any SSE stream is committed.
     */
    public PreparedChat prepare(UUID conversationId, String userContent, String promptName) {
        chatConversationService.checkChatEnabled();
        Conversation conversation = chatConversationService.verifyOwnership(conversationId, false);

        if (promptName == null || promptName.isBlank()) {
            throw new IllegalArgumentException(
                "A playbook must be selected before asking a question.");
        }
        Playbook playbook = playbookService.getPlaybook(promptName)
            .orElseThrow(() -> new IllegalArgumentException("The selected playbook is invalid."));

        UUID userMessageId = UUID.randomUUID();
        String modelContent = transactionTemplate.execute(
            status -> saveUserMessage(conversationId, userMessageId, userContent, promptName));

        List<String> allowedTools = playbook.tools();
        boolean codeExecution = playbook.codeExecution();

        Map<String, Object> settings = conversation.settings();
        String modelToUse = resolveModelToUse(settings);
        String systemPrompt = resolveSystemPrompt(settings);
        String thinkingLevel = resolveThinkingLevel(settings);
        List<LlmMessage> historyMessages = getPriorHistory(conversationId, userMessageId);

        return new PreparedChat(conversationId, userMessageId, modelContent, modelToUse,
            systemPrompt, historyMessages, allowedTools, thinkingLevel, codeExecution);
    }

    public UUID prepareAndSubmitAsync(UUID conversationId, String content, String promptName) {
        chatConversationService.checkChatEnabled();
        Conversation conversation = chatConversationService.verifyOwnership(conversationId, false);
        String orchestratorProfile = (String) conversation.settings()
            .get(ConversationSetting.ORCHESTRATOR_PROFILE.getValue());
        if (orchestratorProfile != null && !orchestratorProfile.isBlank()) {
            return submitOrchestratedAsync(conversationId, content, orchestratorProfile);
        }

        PreparedChat prepared = prepare(conversationId, content, promptName);
        UUID assistantMessageId = UUID.randomUUID();

        String pbName = (promptName != null && !promptName.isBlank()) ? promptName : null;
        transactionTemplate.executeWithoutResult(status -> {
            Message assistantMessage = new Message(assistantMessageId, conversationId, "assistant",
                "", pbName, Collections.emptyList(), Collections.emptyList(), Instant.now(), null,
                null, null, null, null, "generating", prepared.modelToUse());
            messageRepository.save(assistantMessage);
        });

        UUID userId = conversation.userId();
        taskExecutor.submit(() -> {
            chatCancellationService.register(conversationId, Thread.currentThread());
            StringBuilder assistantContent = new StringBuilder();
            Consumer<String> sink = token -> {
                if (token != null) {
                    assistantContent.append(token);
                }
            };

            try {
                // Same attribution rule as the SSE and sync paths: spend points at the
                // persisted user message. This path does write a "generating" assistant row up
                // front, so assistantMessageId would satisfy the FK — but using it here would make
                // llm_call_logs.message_id mean different things depending on which entry point
                // served the turn, and any GROUP BY message_id would silently mix the two.
                LlmCallContextHolder.set(conversationId, prepared.userMessageId(), null, userId,
                    "chat", "assistant");
                RagResult ragResult =
                    ragService.generateAgenticAnswer(buildAgenticRequest(prepared), sink);

                String generatedContent = assistantContent.toString();
                transactionTemplate.executeWithoutResult(status -> {
                    messageRepository.updateContentAndStatus(assistantMessageId, generatedContent,
                        ragResult.retrievedChunkIds(), Collections.emptyList(),
                        ragResult.promptTokens(), ragResult.completionTokens(),
                        ragResult.totalTokens(), ragResult.cachedTokens(),
                        ragResult.thoughtTokens(), "done", prepared.modelToUse());
                    linkRetrievalLogs(ragResult, assistantMessageId);
                    // LLM usage is accounted for by AccountingLlmClient, one row per actual call.
                });
            } catch (CancellationException e) {
                Thread.interrupted(); // Clear interrupted status so DB updates can execute
                log.info("Async generation cancelled by user for conversation: {}", conversationId);
                String stopMsg = assistantContent.toString() + "\n\n*[Generation stopped by user]*";
                transactionTemplate.executeWithoutResult(status -> {
                    messageRepository.updateContentAndStatus(assistantMessageId, stopMsg,
                        Collections.emptyList(), Collections.emptyList(), 0, 0, 0, 0, 0,
                        STATUS_CANCELLED, prepared.modelToUse());
                });
            } catch (Exception e) {
                Thread.interrupted(); // Clear interrupted status so DB updates can execute
                log.error("Error during async generation for conversation: {}", conversationId, e);
                String errorMsg = assistantContent.toString() + "\n\n" + GENERIC_ERROR_TEXT;
                transactionTemplate.executeWithoutResult(status -> {
                    messageRepository.updateContentAndStatus(assistantMessageId, errorMsg,
                        Collections.emptyList(), Collections.emptyList(), 0, 0, 0, 0, 0, "error",
                        prepared.modelToUse());
                });
            } finally {
                LlmCallContextHolder.clear();
                chatCancellationService.deregister(conversationId, Thread.currentThread());
            }
        });

        return assistantMessageId;
    }

    /**
     * Orchestrator-mode async send: no playbook prepend, runs the multi-agent orchestration on the
     * background executor, and lands the final answer in the placeholder assistant message. The
     * per-agent trace is persisted to agent_runs/agent_steps by {@link OrchestrationService}.
     */
    private UUID submitOrchestratedAsync(UUID conversationId, String content, String profileName) {
        UUID userMessageId = UUID.randomUUID();
        transactionTemplate
            .execute(status -> saveUserMessage(conversationId, userMessageId, content, null));
        List<LlmMessage> history = getPriorHistory(conversationId, userMessageId);

        UUID assistantMessageId = UUID.randomUUID();
        UUID agentRunId = UUID.randomUUID();
        transactionTemplate.executeWithoutResult(status -> {
            Message assistantMessage = new Message(assistantMessageId, conversationId, "assistant",
                "", null, Collections.emptyList(), Collections.emptyList(), Instant.now(), null,
                null, null, null, null, "generating", null);
            messageRepository.save(assistantMessage);
        });

        taskExecutor.submit(() -> {
            chatCancellationService.register(conversationId, Thread.currentThread());
            try {
                // userMessageId, not assistantMessageId: the other three chat entry points
                // attribute LLM spend to the persisted user message, and a GROUP BY message_id
                // that mixed the two would be comparing questions with answers.
                OrchestrationResult result = orchestrationService.runOrchestration(conversationId,
                    userMessageId, content, history, agentRunId, profileName);
                transactionTemplate.executeWithoutResult(status -> {
                    messageRepository.updateContentAndStatus(assistantMessageId, result.content(),
                        result.retrievedChunkIds(), Collections.emptyList(), result.promptTokens(),
                        result.completionTokens(), result.totalTokens(), result.cachedTokens(),
                        result.thoughtTokens(), "done", null);
                    for (UUID searchId : result.searchIds()) {
                        retrievalLogRepository.updateMessageId(searchId, assistantMessageId);
                    }
                });
            } catch (CancellationException e) {
                Thread.interrupted();
                log.info("Orchestrated generation cancelled for conversation: {}", conversationId);
                transactionTemplate.executeWithoutResult(
                    status -> messageRepository.updateContentAndStatus(assistantMessageId,
                        "*[Generation stopped by user]*", Collections.emptyList(),
                        Collections.emptyList(), 0, 0, 0, 0, 0, STATUS_CANCELLED, null));
            } catch (Exception e) {
                Thread.interrupted();
                log.error("Error during orchestrated generation for conversation: {}",
                    conversationId, e);
                transactionTemplate.executeWithoutResult(
                    status -> messageRepository.updateContentAndStatus(assistantMessageId,
                        GENERIC_ERROR_TEXT, Collections.emptyList(), Collections.emptyList(), 0, 0,
                        0, 0, 0, "error", null));
            } finally {
                chatCancellationService.deregister(conversationId, Thread.currentThread());
            }
        });

        return assistantMessageId;
    }

    public Optional<Message> getMessageStatus(UUID messageId) {
        return messageRepository.findById(messageId);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("Checking for stale 'generating' messages to clean up on startup...");
        try {
            messageRepository.resetGeneratingMessages();
        } catch (Exception e) {
            log.error("Failed to clean up stale generating messages", e);
        }
    }

    /**
     * Streams the agentic answer to {@code sink}, then persists the assistant message and emits the
     * final {@code [DONE]} token. Blocking; intended to run on a background (virtual) thread.
     */
    public void stream(PreparedChat prepared, UUID assistantMessageId, Consumer<String> sink) {
        log.info(
            "ChatService: Processing user message for conversation {} using model '{}' and thinkingLevel '{}'",
            prepared.conversationId(), prepared.modelToUse(), prepared.thinkingLevel());

        StringBuilder assistantContent = new StringBuilder();
        Consumer<String> wrappedSink = token -> {
            if (token != null) {
                assistantContent.append(token);
            }
            sink.accept(token);
        };

        UUID userId = resolveUserId(prepared.conversationId());

        try {
            // The assistant message is not persisted until generation finishes, so it cannot be
            // referenced here: AccountingLlmClient publishes one row per call, mid-generation, and
            // fk_llm_call_logs_messages would reject it. The user message is already persisted by
            // prepare(), so spend attributes to the question it answers.
            LlmCallContextHolder.set(prepared.conversationId(), prepared.userMessageId(), null,
                userId, "chat", "assistant");
            RagResult ragResult =
                ragService.generateAgenticAnswer(buildAgenticRequest(prepared), wrappedSink);

            transactionTemplate.executeWithoutResult(
                status -> saveAssistantMessage(ragResult, prepared.conversationId(),
                    assistantMessageId, assistantContent.toString(), prepared.modelToUse()));

            sink.accept(formatDoneToken(ragResult, assistantMessageId));
        } catch (CancellationException e) {
            log.info("Streaming generation cancelled by user for conversation: {}",
                prepared.conversationId());
            throw e;
        } catch (Exception e) {
            log.error("Error during streaming generation for conversation: {}",
                prepared.conversationId(), e);
            String errorMsg = "\n\n" + GENERIC_ERROR_TEXT;
            assistantContent.append(errorMsg);

            transactionTemplate.executeWithoutResult(status -> {
                Message assistantMessage = new Message(assistantMessageId,
                    prepared.conversationId(), "assistant", assistantContent.toString(), null,
                    Collections.emptyList(), Collections.emptyList(), Instant.now(), 0, 0, 0, 0, 0,
                    "error", prepared.modelToUse());
                messageRepository.save(assistantMessage);
            });

            sink.accept(errorMsg);
            sink.accept(doneEvent(assistantMessageId, 0, 0, 0, 0, 0));
        } finally {
            LlmCallContextHolder.clear();
        }
    }

    public Message generateSync(PreparedChat prepared, UUID assistantMessageId) {
        log.info(
            "ChatService: Processing user message (sync) for conversation {} using model '{}' and thinkingLevel '{}'",
            prepared.conversationId(), prepared.modelToUse(), prepared.thinkingLevel());

        StringBuilder assistantContent = new StringBuilder();
        Consumer<String> sink = token -> {
            if (token != null) {
                assistantContent.append(token);
            }
        };

        UUID userId = resolveUserId(prepared.conversationId());
        try {
            // The assistant message is not persisted until generation finishes, so it cannot be
            // referenced here: AccountingLlmClient publishes one row per call, mid-generation, and
            // fk_llm_call_logs_messages would reject it. The user message is already persisted by
            // prepare(), so spend attributes to the question it answers.
            LlmCallContextHolder.set(prepared.conversationId(), prepared.userMessageId(), null,
                userId, "chat", "assistant");
            RagResult ragResult =
                ragService.generateAgenticAnswer(buildAgenticRequest(prepared), sink);

            String content = assistantContent.toString();
            transactionTemplate.executeWithoutResult(status -> saveAssistantMessage(ragResult,
                prepared.conversationId(), assistantMessageId, content, prepared.modelToUse()));

            return new Message(assistantMessageId, prepared.conversationId(), "assistant", content,
                null, ragResult.retrievedChunkIds(), Collections.emptyList(), Instant.now(),
                ragResult.promptTokens(), ragResult.completionTokens(), ragResult.totalTokens(),
                ragResult.cachedTokens(), ragResult.thoughtTokens(), "done", prepared.modelToUse());
        } catch (CancellationException e) {
            log.info("Sync generation cancelled by user for conversation: {}",
                prepared.conversationId());
            throw e;
        } catch (Exception e) {
            log.error("Error during sync generation for conversation: {}",
                prepared.conversationId(), e);
            String errorMsg = assistantContent.toString() + "\n\n" + GENERIC_ERROR_TEXT;

            transactionTemplate.executeWithoutResult(status -> {
                Message assistantMessage =
                    new Message(assistantMessageId, prepared.conversationId(), "assistant",
                        errorMsg, null, Collections.emptyList(), Collections.emptyList(),
                        Instant.now(), 0, 0, 0, 0, 0, "error", prepared.modelToUse());
                messageRepository.save(assistantMessage);
            });

            return new Message(assistantMessageId, prepared.conversationId(), "assistant", errorMsg,
                null, Collections.emptyList(), Collections.emptyList(), Instant.now(), 0, 0, 0, 0,
                0, "error", prepared.modelToUse());
        } finally {
            LlmCallContextHolder.clear();
        }
    }


    /** Immutable setup produced on the request thread and consumed by the streaming task. */
    public record PreparedChat(UUID conversationId, UUID userMessageId, String modelContent, String modelToUse,
        String systemPrompt, List<LlmMessage> history, List<String> allowedTools, String thinkingLevel,
        boolean codeExecution) {
        public PreparedChat(UUID conversationId, String modelContent, String modelToUse,
            String systemPrompt, List<LlmMessage> history, List<String> allowedTools, String thinkingLevel,
            boolean codeExecution) {
            this(conversationId, null, modelContent, modelToUse, systemPrompt, history, allowedTools, thinkingLevel, codeExecution);
        }
    }

    private String saveUserMessage(UUID conversationId, UUID userMessageId, String userContent,
        String promptName) {
        boolean isFirstUse = true;
        if (promptName != null && !promptName.isBlank()) {
            List<Message> priorMessages =
                messageRepository.findByConversationId(conversationId, 100, 0);
            isFirstUse = priorMessages.stream().filter(msg -> "user".equalsIgnoreCase(msg.role()))
                .noneMatch(msg -> promptName.equals(msg.playbook()));
        }

        String promptText = (promptName != null && !promptName.isBlank() && isFirstUse)
            ? playbookService.getPlaybook(promptName).map(Playbook::templateText).orElse(null)
            : null;
        boolean hasPrompt = promptText != null && !promptText.isBlank();
        String modelContent = hasPrompt ? promptText + "\n\n---\n\n" + userContent : userContent;

        boolean playbookHasTemplate = false;
        if (promptName != null && !promptName.isBlank()) {
            playbookHasTemplate = playbookService.getPlaybook(promptName)
                .map(Playbook::templateText).map(t -> !t.isBlank()).orElse(false);
        }

        Message userMessage = new Message(userMessageId, conversationId, "user", userContent,
            (promptName != null && !promptName.isBlank()) ? promptName : null,
            Collections.emptyList(), Collections.emptyList(), Instant.now());
        messageRepository.save(userMessage);

        long messageCount = messageRepository.countByConversationId(conversationId);
        if (messageCount == 1) {
            chatConversationService.autoTitle(conversationId, userContent);
        }

        return modelContent;
    }

    private String resolveModelToUse(Map<String, Object> settings) {
        // Resolved against the whitelist, not read raw: a conversation pinned to a model that has
        // since been retired must answer on the current default rather than keep calling a model
        // the picker can no longer even display. The rule is static so this path runs the REAL one
        // and takes only the list from the collaborator.
        String modelToUse = ChatConversationService.effectiveModel(
            (String) settings.get(ConversationSetting.MODEL.getValue()),
            chatConversationService.getAllowedModels());
        if (modelToUse == null || modelToUse.isBlank()) {
            throw new IllegalStateException("No model selected in conversation settings");
        }
        return modelToUse;
    }

    private String resolveSystemPrompt(Map<String, Object> settings) {
        String chatPromptName = (String) settings.get(ConversationSetting.CHAT_PROMPT.getValue());
        if (chatPromptName != null && !chatPromptName.isBlank()) {
            return systemPromptService.getPrompt(chatPromptName.trim())
                .map(SystemPrompt::systemPrompt).orElse(null);
        }
        return null;
    }

    private String resolveThinkingLevel(Map<String, Object> settings) {
        String level = (String) settings.get(ConversationSetting.THINKING_LEVEL.getValue());
        return (level != null && !level.isBlank()) ? level : "medium";
    }

    private List<LlmMessage> getPriorHistory(UUID conversationId, UUID excludeMessageId) {
        List<Message> priorMessages =
            messageRepository.findByConversationId(conversationId, 100, 0);
        List<LlmMessage> history = new ArrayList<>();
        Set<String> seenPlaybooks = new HashSet<>();

        for (Message msg : priorMessages) {
            if (msg.id().equals(excludeMessageId)) {
                continue;
            }
            if ("user".equalsIgnoreCase(msg.role())) {
                String content = msg.content();
                String playbookName = msg.playbook();
                if (playbookName != null && !playbookName.isBlank()) {
                    if (seenPlaybooks.add(playbookName)) {
                        String templateText = playbookService.getPlaybook(playbookName)
                            .map(Playbook::templateText).orElse("");
                        if (!templateText.isBlank()) {
                            content = templateText + "\n\n---\n\n" + content;
                        }
                    }
                }
                history.add(new LlmMessage("user", content));
            } else if ("assistant".equalsIgnoreCase(msg.role())) {
                if ("generating".equalsIgnoreCase(msg.status()) || msg.content() == null
                    || msg.content().isBlank()) {
                    continue;
                }
                history.add(new LlmMessage("assistant", msg.content()));
            }
        }
        return history;
    }


    /**
     * Builds the agentic RAG request from a prepared chat (shared by the stream/sync/async paths).
     */
    private AgenticRequest buildAgenticRequest(PreparedChat prepared) {
        return AgenticRequest.builder().query(prepared.modelContent())
            .modelOverride(prepared.modelToUse()).history(prepared.history())
            .systemPromptOverride(prepared.systemPrompt()).allowedTools(prepared.allowedTools())
            .thinkingLevel(prepared.thinkingLevel()).codeExecution(prepared.codeExecution())
            .build();
    }

    /**
     * Resolves the owning user id for a conversation (null when unknown or the repository is
     * absent). Used on the request/executor thread where the conversation row is authoritative; the
     * async path instead captures {@code conversation.userId()} before submitting to a background
     * thread with no {@code SecurityContext}.
     */
    private UUID resolveUserId(UUID conversationId) {
        return conversationRepository != null
            ? conversationRepository.findById(conversationId).map(Conversation::userId).orElse(null)
            : null;
    }

    private void saveAssistantMessage(RagResult ragResult, UUID conversationId,
        UUID assistantMessageId, String content, String modelToUse) {
        log.info("ChatService sendMessage stream completed for conversation: {}", conversationId);

        Message assistantMessage = new Message(assistantMessageId, conversationId, "assistant",
            content, null, ragResult.retrievedChunkIds(), Collections.emptyList(), Instant.now(),
            ragResult.promptTokens(), ragResult.completionTokens(), ragResult.totalTokens(),
            ragResult.cachedTokens(), ragResult.thoughtTokens(), "done", modelToUse);
        messageRepository.save(assistantMessage);

        // LLM usage is accounted for by AccountingLlmClient, one row per actual call.

        linkRetrievalLogs(ragResult, assistantMessageId);
    }

    private void linkRetrievalLogs(RagResult ragResult, UUID assistantMessageId) {
        if (ragResult.searchIds() != null && !ragResult.searchIds().isEmpty()) {
            for (UUID sid : ragResult.searchIds()) {
                retrievalLogRepository.updateMessageId(sid, assistantMessageId);
            }
        } else if (ragResult.searchId() != null) {
            retrievalLogRepository.updateMessageId(ragResult.searchId(), assistantMessageId);
        }
    }

    private String formatDoneToken(RagResult ragResult, UUID assistantMessageId) {
        return doneEvent(assistantMessageId, ragResult.promptTokens(), ragResult.completionTokens(),
            ragResult.totalTokens(), ragResult.cachedTokens(), ragResult.thoughtTokens());
    }

    /**
     * The terminal SSE {@code [DONE]} line, carrying the assistant message id + token usage as a
     * <b>named JSON</b> payload (MNT-17) — replacing the former space-separated positional tokens
     * the client parsed by array index (fragile: adding a field shifted every downstream index).
     * Every field is a server-owned primitive (a UUID and non-negative ints), none of it
     * user/LLM-derived, so the small fixed JSON is assembled directly.
     */
    private static String doneEvent(UUID messageId, int promptTokens, int completionTokens,
        int totalTokens, int cachedTokens, int thoughtTokens) {
        return "[DONE] {\"messageId\":\"" + messageId + "\",\"promptTokens\":" + promptTokens
            + ",\"completionTokens\":" + completionTokens + ",\"totalTokens\":" + totalTokens
            + ",\"cachedTokens\":" + cachedTokens + ",\"thoughtTokens\":" + thoughtTokens + "}";
    }
}
