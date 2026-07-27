package de.palsoftware.yvoke.chat.orchestration;

import de.palsoftware.yvoke.chat.api.model.OrchestratorRunRequest;
import de.palsoftware.yvoke.chat.core.model.Conversation;
import de.palsoftware.yvoke.chat.core.repository.ConversationRepository;
import de.palsoftware.yvoke.shared.user.model.User;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Persists a completed multi-agent run reported by the desktop client into {@code agent_runs} +
 * {@code agent_steps}, reusing the same repositories the server-side {@link OrchestrationService}
 * writes to. This gives desktop runs parity with web runs in the admin trace viewer.
 */
@Service
public class DesktopOrchestratorRunService {
    private static final Logger log = LoggerFactory.getLogger(DesktopOrchestratorRunService.class);

    private final ConversationRepository conversationRepository;
    private final AgentRunRepository agentRunRepository;
    private final AgentStepRepository agentStepRepository;

    public DesktopOrchestratorRunService(ConversationRepository conversationRepository,
        AgentRunRepository agentRunRepository, AgentStepRepository agentStepRepository) {
        this.conversationRepository = conversationRepository;
        this.agentRunRepository = agentRunRepository;
        this.agentStepRepository = agentStepRepository;
    }

    /** Records a finished run owned by {@code user}. Returns the new {@code agent_runs} id. */
    public UUID record(User user, OrchestratorRunRequest req) {
        if (req == null || req.conversationId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "conversationId is required");
        }
        if (req.profileName() == null || req.profileName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "profileName is required");
        }
        verifyOwnership(req.conversationId(), user);

        UUID runId = UUID.randomUUID();
        agentRunRepository.create(runId, req.conversationId(), req.profileName(), req.config());
        agentRunRepository.finish(runId, req.messageId(),
            req.status() != null ? req.status() : "done", zero(req.reviewRounds()),
            req.finalVerdict(), zero(req.promptTokens()), zero(req.completionTokens()),
            zero(req.totalTokens()), zero(req.cachedTokens()), zero(req.thoughtTokens()),
            req.error());

        List<OrchestratorRunRequest.Step> steps = req.steps() != null ? req.steps() : List.of();
        int autoSeq = 0;
        for (OrchestratorRunRequest.Step s : steps) {
            agentStepRepository.insert(UUID.randomUUID(), runId,
                s.seq() != null ? s.seq() : autoSeq, s.role() != null ? s.role() : "specialist",
                zero(s.round()), s.playbookName(), s.model(), s.thinkingLevel(), s.input(),
                s.output(), s.messages(), s.verdict(), zero(s.promptTokens()),
                zero(s.completionTokens()), zero(s.totalTokens()), zero(s.cachedTokens()),
                zero(s.thoughtTokens()));
            autoSeq++;
        }
        log.info("Recorded desktop orchestrator run {} (conversation={}, profile={}, steps={})",
            runId, req.conversationId(), req.profileName(), steps.size());
        return runId;
    }

    private void verifyOwnership(UUID conversationId, User user) {
        Conversation conversation = conversationRepository.findById(conversationId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Conversation not found: " + conversationId));
        if (!Objects.equals(conversation.userId(), user.id())) {
            throw new AccessDeniedException("Access denied to conversation: " + conversationId);
        }
    }

    private static int zero(Integer v) {
        return v != null ? v : 0;
    }
}
