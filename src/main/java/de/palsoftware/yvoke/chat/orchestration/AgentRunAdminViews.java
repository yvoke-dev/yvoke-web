package de.palsoftware.yvoke.chat.orchestration;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Per-view DTOs for the agent-run admin pages (ARC-01 / Wave 3.3). The templates render these slim
 * projections instead of raw {@link AgentRun}/{@link AgentStep} records, so persistence fields that
 * the pages never show (e.g. the run {@code config} JSON) do not leak into Thymeleaf. Accessor
 * names mirror the source records; mapping is in {@code AgentRunAdminViewService}.
 */
public final class AgentRunAdminViews {

    private AgentRunAdminViews() {}

    /** Row in the agent-runs listing (admin/agent-runs). */
    public record RunSummary(UUID id, UUID conversationId, String profileName, String status,
        int reviewRounds, Integer totalTokens, Instant startedAt) {}

    /**
     * Header/summary of a single run (admin/agent-run-detail).
     *
     * <p>
     * {@code errorSummary} is the first line of {@code error} — the summary table keeps one
     * scannable line while the full multi-line diagnosis goes in its own card, since a table cell
     * collapses newlines and would run the whole block together.
     */
    public record RunDetail(UUID id, UUID conversationId, UUID messageId, String profileName,
        String status, int reviewRounds, Integer promptTokens, Integer completionTokens,
        Integer totalTokens, String finalVerdict, String errorSummary, String error,
        Instant startedAt, Instant finishedAt) {}

    /** One step within a run (admin/agent-run-detail). */
    public record StepView(String role, int seq, int round, String playbookName, String model,
        String thinkingLevel, String verdict, String input, String output, String messages,
        Integer totalTokens, String status, String error) {}

    /** Everything the run-detail page renders. */
    public record RunDetailPage(RunDetail run, List<StepView> steps) {}
}
