package de.palsoftware.yvoke.chat.orchestration;

import de.palsoftware.yvoke.chat.orchestration.AgentRunAdminViews.RunDetail;
import de.palsoftware.yvoke.chat.orchestration.AgentRunAdminViews.RunDetailPage;
import de.palsoftware.yvoke.chat.orchestration.AgentRunAdminViews.RunSummary;
import de.palsoftware.yvoke.chat.orchestration.AgentRunAdminViews.StepView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-side view service for the agent-run admin pages (ARC-01 / Wave 3.3). Maps raw
 * {@link AgentRun}/{@link AgentStep} rows to the per-view DTOs in {@code AgentRunAdminViews}.
 */
@Service
public class AgentRunAdminViewService {

    private final AgentRunRepository agentRunRepository;
    private final AgentStepRepository agentStepRepository;

    public AgentRunAdminViewService(AgentRunRepository agentRunRepository,
        AgentStepRepository agentStepRepository) {
        this.agentRunRepository = agentRunRepository;
        this.agentStepRepository = agentStepRepository;
    }

    @Transactional(readOnly = true)
    public List<RunSummary> recentRuns(int limit) {
        return agentRunRepository.findRecent(limit).stream()
            .map(AgentRunAdminViewService::toSummary).toList();
    }

    @Transactional(readOnly = true)
    public Optional<RunDetailPage> runDetail(UUID id) {
        return agentRunRepository.findById(id).map(run -> {
            List<StepView> steps = agentStepRepository.findByRunId(id).stream()
                .map(AgentRunAdminViewService::toStepView).toList();
            return new RunDetailPage(toDetail(run), steps);
        });
    }

    private static RunSummary toSummary(AgentRun r) {
        return new RunSummary(r.id(), r.conversationId(), r.profileName(), r.status(),
            r.reviewRounds(), r.totalTokens(), r.startedAt());
    }

    private static RunDetail toDetail(AgentRun r) {
        return new RunDetail(r.id(), r.conversationId(), r.messageId(), r.profileName(), r.status(),
            r.reviewRounds(), r.promptTokens(), r.completionTokens(), r.totalTokens(),
            r.finalVerdict(), firstLine(r.error()), r.error(), r.startedAt(), r.finishedAt());
    }

    private static StepView toStepView(AgentStep s) {
        return new StepView(s.role(), s.seq(), s.round(), s.playbookName(), s.model(),
            s.thinkingLevel(), s.verdict(), s.input(), s.output(), s.messages(), s.totalTokens(),
            s.status(), s.error());
    }

    /** Derived here rather than in Thymeleaf, which has no clean way to take a first line. */
    private static String firstLine(String error) {
        return error == null ? null : error.lines().findFirst().orElse(null);
    }
}
