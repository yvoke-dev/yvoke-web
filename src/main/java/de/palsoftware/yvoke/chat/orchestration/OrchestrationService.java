package de.palsoftware.yvoke.chat.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.llm.core.model.LlmMessage;
import de.palsoftware.yvoke.llm.core.model.LlmPart;
import de.palsoftware.yvoke.rag.core.model.AgenticRequest;
import de.palsoftware.yvoke.rag.core.model.RagResult;
import de.palsoftware.yvoke.rag.core.service.RagService;
import de.palsoftware.yvoke.rag.prompt.Playbook;
import de.palsoftware.yvoke.rag.prompt.PlaybookService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import de.palsoftware.yvoke.llm.core.LlmFailureSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import de.palsoftware.yvoke.llm.core.context.LlmCallContextHolder;
import de.palsoftware.yvoke.chat.core.model.Conversation;
import de.palsoftware.yvoke.chat.core.repository.ConversationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.lang.Nullable;

/**
 * Runs a multi-agent orchestration: a pro-model orchestrator delegates sub-questions to flash-model
 * specialists (via {@code call_specialist}), synthesises an answer, and a pro-model reviewer
 * validates it against the evidence the specialists gathered (via {@code submit_review}). On
 * rejection the orchestrator revises up to {@code maxReviewRounds} times; the last answer is always
 * delivered (flagged if never approved). Every agent invocation is persisted to agent_runs/steps.
 */
@Service
public class OrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationService.class);

    private static final String ROLE_ORCHESTRATOR = "orchestrator";
    private static final String ROLE_SPECIALIST = "specialist";
    private static final String ROLE_REVIEWER = "reviewer";

    private final RagService ragService;
    private final PlaybookService playbookService;
    private final OrchestratorProperties properties;
    private final OrchestratorProfileService profileService;
    private final AgentRunRepository runRepository;
    private final AgentStepRepository stepRepository;
    private final ObjectMapper objectMapper;
    private final ConversationRepository conversationRepository;

    public OrchestrationService(RagService ragService, PlaybookService playbookService,
        OrchestratorProperties properties, @Nullable OrchestratorProfileService profileService,
        AgentRunRepository runRepository, AgentStepRepository stepRepository,
        ObjectMapper objectMapper, @Nullable ConversationRepository conversationRepository) {
        this.ragService = ragService;
        this.playbookService = playbookService;
        this.properties = properties;
        this.profileService = profileService;
        this.runRepository = runRepository;
        this.stepRepository = stepRepository;
        this.objectMapper = objectMapper;
        this.conversationRepository = conversationRepository;
    }

    public record OrchestrationResult(String content, List<UUID> retrievedChunkIds,
        List<UUID> searchIds, int promptTokens, int completionTokens, int totalTokens,
        int cachedTokens, int thoughtTokens, String status) {}

    /** Blocking; intended to run on a background (virtual) thread. */
    public OrchestrationResult runOrchestration(UUID conversationId, UUID messageId,
        String question, List<LlmMessage> history, UUID agentRunId, String profileName) {

        ResolvedProfile profile;
        Playbook orchestratorPlaybook;
        Playbook reviewerPlaybook;
        List<Playbook> specialists;
        try {
            profile = profileService != null ? profileService.resolve(profileName)
                : properties.resolve(profileName);
            orchestratorPlaybook = requirePlaybook(profile.orchestratorPlaybook(), "orchestrator");
            reviewerPlaybook = requirePlaybook(profile.reviewerPlaybook(), "reviewer");
            specialists = profile.specialistPlaybooks().stream()
                .map(n -> requirePlaybook(n, "specialist")).toList();
        } catch (Exception e) {
            // Resolution runs before the run row exists, so a missing profile or playbook used to
            // leave no agent_runs row at all — the run simply vanished and the only account of it
            // was in the container log. Record it, then rethrow unchanged.
            log.error("Orchestration setup failed for conversation {} (run {})", conversationId,
                agentRunId, e);
            recordSetupFailure(agentRunId, conversationId, profileName, e);
            throw e;
        }
        List<String> specialistNames = specialists.stream().map(Playbook::name).toList();

        runRepository.create(agentRunId, conversationId, profileName, configSnapshot(profile));

        RunState st =
            new RunState(agentRunId, conversationId, messageId, profile,
                conversationRepository != null
                    ? conversationRepository.findById(conversationId).map(Conversation::userId)
                        .orElse(null)
                    : null);
        st.specialistCfg = profile.specialist();
        int round = 0;
        String answer = "";
        Verdict verdict = null;
        String status;
        String content;

        try {
            String orchestratorSystemPrompt = orchestratorPlaybook.templateText()
                + "\n\n## Available specialists\n" + renderRoster(specialists);

            Verdict lastRejection = null;
            while (true) {
                st.round = round;
                // The orchestrator keeps ONE conversation for the whole run. Round 2 continues the
                // transcript that produced the draft — its own tool calls, the specialists' answers
                // and the draft itself are all still there — instead of starting a fresh call that
                // remembers none of it and can only research the question again. What it never saw
                // is the specialists' own tool output, which is harvested into st.evidence for the
                // reviewer; that travels as text, and only the entries not already sent, since
                // anything sent in an earlier round is still in the transcript.
                boolean revising = lastRejection != null;
                String orchestratorQuery =
                    revising
                        ? renderRevisionTask(
                            st.evidence.subList(st.evidenceSent, st.evidence.size()), lastRejection)
                        : question;
                st.evidenceSent = st.evidence.size();

                // Orchestrator turn — may call specialists via call_specialist.
                ToolCallback callSpecialist = new CallSpecialistTool(objectMapper, specialistNames,
                    (pbName, subQuestion) -> runSpecialist(st, specialists, pbName, subQuestion));
                AgentOutcome orch = runAgent(st, ROLE_ORCHESTRATOR, orchestratorPlaybook.name(),
                    orchestratorSystemPrompt, orchestratorQuery, orchestratorQuery,
                    profile.orchestrator(), List.of("ask_clarifying_question"), false,
                    List.<ToolCallback>of(callSpecialist), revising ? null : history,
                    st.orchestratorMessages, null);
                // Everything this turn said and was told, carried into the next round. The prior
                // conversation is already inside it, so the chat history is passed only on the
                // first turn — after that it is in the transcript and re-sending would duplicate.
                st.orchestratorMessages = orch.result().messages();

                if (orch.result().clarifyingQuestion() != null) {
                    // Orchestrator escalated a clarification to the user — deliver it, skip review.
                    content = orch.emitted();
                    status = "clarify";
                    finish(agentRunId, messageId, status, round, null, st, null);
                    return result(st, content, status);
                }

                answer = orch.answerText();

                // Reviewer turn — validate-only, ends by calling submit_review.
                Verdict[] holder = new Verdict[1];
                ToolCallback submitReview = new SubmitReviewTool(objectMapper, v -> holder[0] = v);
                String reviewTask = renderReviewTask(question, answer, st.evidence);
                // No history and no prior messages: the reviewer judges the answer against the
                // supplied evidence alone, and inheriting the orchestrator's transcript — including
                // its own earlier drafts and the reviewer's previous notes — would stop it being a
                // validate-only, independent check.
                //
                // No get_section either, and that is the point rather than an economy. It resolves
                // a chunk id to the whole enclosing SECTION, so a reviewer using it judged claims
                // against sibling text no specialist ever retrieved — widening "the evidence" past
                // what the answer was built from. With the cited sources supplied in full there is
                // nothing left for it to fetch that the reviewer is entitled to see: a claim the
                // cited sources do not support is a citation defect, and the review loop is how
                // that gets corrected.
                runAgent(st, ROLE_REVIEWER, reviewerPlaybook.name(),
                    reviewerPlaybook.templateText(), reviewTask, reviewTask, profile.reviewer(),
                    List.of("verify_citations"), false, List.<ToolCallback>of(submitReview), null,
                    null, holder);
                verdict = holder[0] != null ? holder[0]
                    : Verdict.reject("Reviewer did not submit a verdict.");

                if (verdict.approved() || round >= profile.maxReviewRounds()) {
                    break;
                }
                round++;
                lastRejection = verdict;
            }

            boolean approved = verdict != null && verdict.approved();
            status = approved ? "done" : "delivered_flagged";
            content = approved ? answer : answer + flagNote(round, verdict);
            finish(agentRunId, messageId, status, round, verdict, st, null);
            return result(st, content, status);
        } catch (CancellationException e) {
            // A user pressing Stop is not a failure. CancellationException is a RuntimeException,
            // so it shared the catch below and every cancelled run was filed as 'error' with a
            // meaningless message — indistinguishable, afterwards, from a genuine fault.
            log.info("Orchestration cancelled for conversation {} (run {})", conversationId,
                agentRunId);
            finishOffInterrupt(agentRunId, messageId, "cancelled", round, verdict, st, null);
            throw e;
        } catch (Exception e) {
            log.error("Orchestration failed for conversation {} (run {})", conversationId,
                agentRunId, e);
            finishOffInterrupt(agentRunId, messageId, "error", round, verdict, st,
                composeRunError(st, e));
            throw e;
        }
    }

    /**
     * Records a failure that happened before the run row existed, so it is still visible in the
     * admin trace. Best-effort: a failure to write must not replace the real exception.
     */
    private void recordSetupFailure(UUID agentRunId, UUID conversationId, String profileName,
        Exception cause) {
        try {
            runRepository.create(agentRunId, conversationId, profileName, null);
            runRepository.finish(agentRunId, null, "error", 0, null, 0, 0, 0, 0, 0,
                "agent: (run did not start — profile/playbook resolution failed)\n"
                    + LlmFailureSummary.detail(cause));
        } catch (Exception recordingFailure) {
            log.warn("Could not record orchestration setup failure for run {}: {}", agentRunId,
                recordingFailure.toString());
        }
    }

    /**
     * Composes what the admin page shows for a failed run: which agent was executing, then the
     * decoded provider diagnosis.
     *
     * <p>
     * The agent line is only emitted when the recorded context belongs to <em>this</em> exception.
     * A specialist failure that the orchestrator recovered from leaves {@code lastFailure} behind,
     * and stamping it onto a later, unrelated error would name the wrong agent with full
     * confidence.
     */
    private static String composeRunError(RunState st, Throwable e) {
        String agentLine = "agent: (context unavailable)";
        if (st != null && st.lastFailure != null && st.lastFailure.matches(e)) {
            agentLine = "agent: " + st.lastFailure.describe(st);
        }
        return agentLine + "\n" + LlmFailureSummary.detail(e);
    }

    /**
     * Runs {@code finish} with the interrupt flag cleared, restoring it afterwards.
     *
     * <p>
     * Cancellation arrives as a thread interrupt, and a JDBC write on an interrupted thread can be
     * refused — which would silently lose the very record that makes a cancelled or aborted run
     * visible. {@code ChatMessageService} clears the flag for the same reason before its own
     * writes.
     */
    private void finishOffInterrupt(UUID agentRunId, UUID messageId, String status, int round,
        Verdict verdict, RunState st, String error) {
        boolean wasInterrupted = Thread.interrupted();
        try {
            finish(agentRunId, messageId, status, round, verdict, st, error);
        } catch (Exception writeFailure) {
            log.warn("Could not record orchestration outcome for run {}: {}", agentRunId,
                writeFailure.toString());
        } finally {
            if (wasInterrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Invoked by call_specialist: runs one specialist, records the step, harvests its evidence. */
    private String runSpecialist(RunState st, List<Playbook> specialists, String playbookName,
        String subQuestion) {
        int maxCalls = st.profile != null ? st.profile.maxSpecialistCalls()
            : properties.resolvedMaxSpecialistCalls();
        if (st.specialistCalls >= maxCalls) {
            return "Specialist-call budget exhausted (" + maxCalls
                + "). Synthesise your answer from the specialist results you already have.";
        }
        st.specialistCalls++;
        Playbook pb =
            specialists.stream().filter(p -> p.name().equals(playbookName)).findFirst().orElseThrow(
                () -> new IllegalArgumentException("Specialist not in profile: " + playbookName));

        String query = (pb.templateText() != null && !pb.templateText().isBlank())
            ? pb.templateText() + "\n\n---\n\n" + subQuestion
            : subQuestion;
        List<String> allowed = new ArrayList<>(pb.tools() != null ? pb.tools() : List.of());
        if (!allowed.contains("ask_clarifying_question")) {
            allowed.add("ask_clarifying_question");
        }

        AgentOutcome outcome = runAgent(st, ROLE_SPECIALIST, pb.name(), null, query, subQuestion,
            profileSpecialistCfg(st), allowed, pb.codeExecution(), List.<ToolCallback>of(), null,
            null, null);

        collectEvidence(outcome.result(), st.evidence, pb.name());

        if (outcome.result().clarifyingQuestion() != null) {
            StringBuilder sb = new StringBuilder();
            sb.append("SPECIALIST NEEDS CLARIFICATION (").append(pb.name()).append("): ")
                .append(outcome.result().clarifyingQuestion());
            List<String> opts = outcome.result().clarifyingOptions();
            if (opts != null && !opts.isEmpty()) {
                sb.append(" Options: ").append(String.join(" | ", opts));
            }
            sb.append("\nResolve it from the conversation context and re-call this specialist with "
                + "the clarified question, or if you cannot, ask the user via ask_clarifying_question.");
            return sb.toString();
        }
        String answer = outcome.answerText();
        return answer.isBlank() ? "(the specialist returned no answer)" : answer;
    }

    // ------------------------------------------------------------------------------------------
    // Agent invocation + trace recording
    // ------------------------------------------------------------------------------------------

    private record AgentOutcome(String emitted, String answerText, RagResult result) {}

    /**
     * Runs one agent through the shared agentic loop, records its trace step, and accumulates its
     * tokens / retrieved chunks into the run state. {@code verdictHolder} (reviewer only) is read
     * after the run to store the verdict on the step.
     */
    private AgentOutcome runAgent(RunState st, String role, String playbookName,
        String systemPromptOverride, String query, String traceInput,
        OrchestratorProperties.RoleConfig cfg, List<String> allowedTools, boolean codeExecution,
        List<ToolCallback> extraTools, List<LlmMessage> history, List<LlmMessage> priorMessages,
        Verdict[] verdictHolder) {

        // Allocated when the step STARTS, not when it finishes. A specialist runs nested inside the
        // orchestrator's turn and therefore completes first, so numbering on completion recorded
        // every specialist ahead of the orchestrator that delegated to it — the trace read
        // backwards, showing work before the decision that caused it. The failure path below reuses
        // this number rather than minting its own, or a failed step would consume two.
        int seq = st.seq.incrementAndGet();
        StringBuilder emitted = new StringBuilder();
        // A specialist runs NESTED inside the orchestrator's own agentic loop, on the same thread:
        // call_specialist is wired as an inline tool handler, so this method re-enters itself.
        // Clearing the ThreadLocal on the way out of the inner frame would wipe the outer
        // orchestrator's attribution, and every orchestrator call after the first delegation —
        // including the final synthesis — would be logged with no conversation, run or user.
        LlmCallContextHolder.Context previousContext = LlmCallContextHolder.get();
        try {
            LlmCallContextHolder.set(st.conversationId, st.messageId, st.runId, st.userId,
                "orchestrator", role);
            RagResult result = ragService.generateAgenticAnswer(
                AgenticRequest.builder().query(query).modelOverride(cfg.model()).history(history)
                    .priorMessages(priorMessages).systemPromptOverride(systemPromptOverride)
                    .allowedTools(allowedTools).thinkingLevel(cfg.thinkingLevel())
                    .codeExecution(codeExecution).extraTools(extraTools).build(),
                token -> {
                    if (token != null) {
                        emitted.append(token);
                    }
                });

            String answerText = extractAnswerText(result, emitted.toString());
            Verdict verdict = verdictHolder != null ? verdictHolder[0] : null;

            st.accumulate(result);
            stepRepository.insert(UUID.randomUUID(), st.runId, seq, role, st.round, playbookName,
                cfg.model(), cfg.thinkingLevel(), traceInput, emitted.toString(),
                messagesAddedBy(result, priorMessages), verdict, result.promptTokens(),
                result.completionTokens(), result.totalTokens(), result.cachedTokens(),
                result.thoughtTokens());

            // LLM usage is accounted for by AccountingLlmClient, one row per actual call, using
            // the source/role set on LlmCallContextHolder above.

            return new AgentOutcome(emitted.toString(), answerText, result);
        } catch (Exception e) {
            // The step row is written only on success, so a throwing agent left no trace at all —
            // its role, playbook, model, prompt and partially-streamed output died with the frame.
            st.lastFailure = new FailureContext(e, role, playbookName, cfg.model(), st.round, seq);
            if (!(e instanceof CancellationException)) {
                recordFailedStep(st, seq, role, playbookName, cfg, traceInput, emitted.toString(),
                    e);
            }
            throw e;
        } finally {
            if (previousContext != null) {
                LlmCallContextHolder.set(previousContext.conversationId(),
                    previousContext.messageId(), previousContext.agentRunId(),
                    previousContext.userId(), previousContext.source(), previousContext.role());
            } else {
                LlmCallContextHolder.clear();
            }
        }
    }

    /**
     * Writes the trace row for an agent that threw. Best-effort and deliberately swallowing: a
     * failure to record must never replace the exception the caller is about to see.
     */
    private void recordFailedStep(RunState st, int seq, String role, String playbookName,
        OrchestratorProperties.RoleConfig cfg, String traceInput, String partialOutput,
        Throwable cause) {
        try {
            stepRepository.insertFailed(UUID.randomUUID(), st.runId, seq, role, st.round,
                playbookName, cfg.model(), cfg.thinkingLevel(), traceInput, partialOutput,
                LlmFailureSummary.detail(cause));
        } catch (Exception recordingFailure) {
            log.warn("Could not record failed {} step for run {}: {}", role, st.runId,
                recordingFailure.toString());
        }
    }

    private OrchestratorProperties.RoleConfig profileSpecialistCfg(RunState st) {
        return st.specialistCfg;
    }

    private void finish(UUID agentRunId, UUID messageId, String status, int round, Verdict verdict,
        RunState st, String error) {
        runRepository.finish(agentRunId, messageId, status, round, verdict, st.promptTokens,
            st.completionTokens, st.totalTokens, st.cachedTokens, st.thoughtTokens, error);
    }

    private OrchestrationResult result(RunState st, String content, String status) {
        return new OrchestrationResult(content, new ArrayList<>(st.chunkIds),
            new ArrayList<>(st.searchIds), st.promptTokens, st.completionTokens, st.totalTokens,
            st.cachedTokens, st.thoughtTokens, status);
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    private Playbook requirePlaybook(String name, String role) {
        return playbookService.getPlaybook(name)
            .orElseThrow(() -> new IllegalStateException("Orchestrator " + role + " playbook '"
                + name + "' is not defined in the playbooks table."));
    }

    private static String renderRoster(List<Playbook> specialists) {
        StringBuilder sb = new StringBuilder();
        for (Playbook p : specialists) {
            sb.append("- **").append(p.name()).append("** — ").append(p.title());
            if (p.description() != null && !p.description().isBlank()) {
                sb.append(": ").append(p.description());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * The next turn of the orchestrator's own conversation after a rejection. The question, the
     * draft and every specialist answer are already in that conversation, so this says only what is
     * new: the specialists' own tool output, which the orchestrator never saw because it lives
     * inside the specialists' nested runs, and what the reviewer wants changed.
     *
     * <p>
     * De-duplicated but deliberately NOT cite-scoped. The draft is the thing being revised, and the
     * reviewer's objection is usually that some claim is unsupported — so the material this turn
     * needs is exactly what the draft does not yet cite. Scoping it would hand the orchestrator
     * only what it already used and send it back to delegate for evidence it was holding.
     */
    private static String renderRevisionTask(List<String> newEvidence, Verdict verdict) {
        StringBuilder sb = new StringBuilder();
        if (!newEvidence.isEmpty()) {
            sb.append("## Source evidence behind the specialist answers above\n")
                .append(EvidenceDigest.deduped(newEvidence)).append("\n\n");
        }
        sb.append("## Reviewer feedback to address\n").append(verdict.feedback()).append("\n\n");

        // Separated because the remedies differ, and conflating them is expensive. "Delegate only
        // for material that is genuinely missing" was the previous wording and it did not hold: a
        // reviewer that named the exact swap to make still drew a 347,969-token specialist call,
        // because a rejection reads as a research failure unless something says otherwise.
        if (!verdict.citationFixes().isEmpty()) {
            sb.append("## Citation fixes — no research needed\n");
            sb.append("Each of these is repairable from evidence **already supplied** above: the "
                + "source was retrieved, the citation on it is wrong, missing or duplicated. Apply "
                + "them by editing the answer's citations and `## References` — do NOT call "
                + "call_specialist for any of them.\n");
            for (String fix : verdict.citationFixes()) {
                sb.append("- ").append(fix).append("\n");
            }
            sb.append("\n");
        }
        if (!verdict.unsupportedClaims().isEmpty()) {
            sb.append("## Unsupported claims\n");
            sb.append("Nothing supplied supports these. Either remove the claim, or delegate to a "
                + "specialist for the material — this is the only part of the feedback that may "
                + "warrant a new specialist call.\n");
            for (String claim : verdict.unsupportedClaims()) {
                sb.append("- ").append(claim).append("\n");
            }
            sb.append("\n");
        }

        sb.append("Revise the answer you just gave. Keep everything the reviewer did not object "
            + "to.");
        if (verdict.isCitationOnly()) {
            sb.append(" Every objection above is a citation fix, so this revision needs no new "
                + "evidence at all — do NOT call call_specialist.");
        } else {
            sb.append(" Delegate again only for material that is genuinely missing above.");
        }
        return sb.toString();
    }

    /**
     * The messages this turn actually added. The orchestrator's list is cumulative across rounds,
     * so persisting all of it per step would store round 1 again inside round 2's row, and again
     * inside round 3's — the trace would grow quadratically while showing each step work it did not
     * do.
     */
    private static List<LlmMessage> messagesAddedBy(RagResult result,
        List<LlmMessage> priorMessages) {
        List<LlmMessage> all = result.messages();
        if (priorMessages == null || all == null || all.size() <= priorMessages.size()) {
            return all;
        }
        return List.copyOf(all.subList(priorMessages.size(), all.size()));
    }

    /**
     * The reviewer is given the sources the answer cites and nothing else, and holds no tool that
     * can reach anything else. That is what makes each citation testable as the claim it is — "this
     * source supports this statement" — rather than a label the reviewer can excuse by finding the
     * fact somewhere else in the pile.
     *
     * <p>
     * It therefore has to be told that uncited sources exist but are not shown. Without that it
     * cannot tell "the corpus has nothing on this" from "you did not cite it", phrases its feedback
     * as the former, and the orchestrator answers by delegating a fresh search when re-citing
     * material it already holds would have done.
     */
    private static String renderReviewTask(String question, String answer, List<String> evidence) {
        StringBuilder sb = new StringBuilder();
        sb.append("Validate the candidate answer below. Do NOT search for new information — check "
            + "it ONLY against the supplied evidence, then call submit_review.\n\n");
        sb.append("## Original question\n").append(question).append("\n\n");
        sb.append("## Candidate answer\n").append(answer).append("\n\n");
        sb.append("## Evidence gathered by the specialists (the ONLY basis for validation)\n");
        if (evidence.isEmpty()) {
            sb.append("(no evidence was captured)\n");
        } else {
            sb.append("You are shown the sources this answer cites, and only those. The "
                + "specialists retrieved others that the answer does not cite; those are not "
                + "shown here. So when a claim is not supported by the evidence below, the fault "
                + "may be a missing or wrong citation rather than a missing source — say the "
                + "answer must cite what supports the claim, rather than concluding no source "
                + "exists.\n\n");
            sb.append(EvidenceDigest.citeScoped(evidence, answer)).append("\n");
        }
        return sb.toString();
    }

    private static String flagNote(int round, Verdict verdict) {
        String reason = verdict != null && verdict.feedback() != null ? verdict.feedback()
            : "unspecified concerns";
        return "\n\n---\n⚠️ *This answer did not pass automated review after " + (round + 1)
            + " attempt(s). Reviewer notes: " + reason + "*";
    }

    private static String extractAnswerText(RagResult result, String emittedFallback) {
        LlmMessage lastAssistant = null;
        if (result.messages() != null) {
            for (LlmMessage m : result.messages()) {
                if ("assistant".equalsIgnoreCase(m.role())) {
                    lastAssistant = m;
                }
            }
        }
        if (lastAssistant != null && lastAssistant.parts() != null) {
            StringBuilder sb = new StringBuilder();
            for (LlmPart p : lastAssistant.parts()) {
                if ("text".equals(p.type()) && p.text() != null) {
                    sb.append(p.text());
                }
            }
            if (sb.length() > 0) {
                return sb.toString().trim();
            }
        }
        return stripThinkAndTools(emittedFallback).trim();
    }

    private static void collectEvidence(RagResult result, List<String> evidence,
        String specialist) {
        if (result.messages() == null) {
            return;
        }
        for (LlmMessage m : result.messages()) {
            if ("tool".equalsIgnoreCase(m.role()) && m.content() != null
                && !m.content().isBlank()) {
                String tool = m.toolName() != null ? m.toolName() : "tool";
                evidence.add("[" + specialist + " · " + tool + "]\n" + m.content());
            }
        }
    }

    private static String stripThinkAndTools(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        String withoutThink = content.replaceAll("(?s)<think>.*?</think>", "");
        StringBuilder sb = new StringBuilder();
        for (String line : withoutThink.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("🔧") || trimmed.startsWith("<clarifying-question")
                || trimmed.startsWith("<question>") || trimmed.startsWith("<option>")
                || trimmed.startsWith("</clarifying-question")) {
                continue;
            }
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

    private Map<String, Object> configSnapshot(ResolvedProfile p) {
        return Map.of("profile", p.name(), "orchestratorPlaybook", p.orchestratorPlaybook(),
            "reviewerPlaybook", p.reviewerPlaybook(), "specialistPlaybooks",
            p.specialistPlaybooks(), "orchestrator", roleMap(p.orchestrator()), "reviewer",
            roleMap(p.reviewer()), "specialist", roleMap(p.specialist()), "maxReviewRounds",
            p.maxReviewRounds(), "maxSpecialistCalls", p.maxSpecialistCalls());
    }

    private static Map<String, Object> roleMap(OrchestratorProperties.RoleConfig c) {
        return Map.of("model", String.valueOf(c.model()), "thinkingLevel",
            String.valueOf(c.thinkingLevel()));
    }

    /** Mutable per-run accumulator. Orchestration runs single-threaded, so no synchronisation. */
    /**
     * Which agent was executing when a call threw, captured at the throw site because the role,
     * playbook and model are frame-locals of {@code runAgent} and are gone by the time the run-level
     * catch sees the exception.
     *
     * <p>
     * {@code throwable} is held so the context can be matched to the exception that actually ends
     * the run: a specialist failure the orchestrator recovers from must not label a later error.
     */
    private record FailureContext(Throwable throwable, String role, String playbookName,
        String model, int round, int seq) {

        boolean matches(Throwable candidate) {
            for (Throwable c = candidate; c != null; c = c.getCause()) {
                if (c == throwable) {
                    return true;
                }
                if (c.getCause() == c) {
                    break;
                }
            }
            return false;
        }

        String describe(RunState st) {
            // The failing step's own number, not a count of finished ones: seq is now allocated
            // when a step starts, so "afterSteps=N" would have named the step that failed while
            // claiming N had completed before it.
            return "role=" + role + " playbook=" + playbookName + " model=" + model + " round="
                + round + " atStep=" + seq + " specialistCalls=" + st.specialistCalls;
        }
    }

    private static final class RunState {
        final UUID runId;
        final UUID conversationId;
        final UUID messageId;
        final UUID userId;
        final ResolvedProfile profile;
        final AtomicInteger seq = new AtomicInteger(0);
        final Set<UUID> chunkIds = new LinkedHashSet<>();
        final Set<UUID> searchIds = new LinkedHashSet<>();
        final List<String> evidence = new ArrayList<>();
        int promptTokens;
        int completionTokens;
        int totalTokens;
        int cachedTokens;
        int thoughtTokens;
        int specialistCalls;
        int round;
        OrchestratorProperties.RoleConfig specialistCfg;
        FailureContext lastFailure;

        /**
         * The orchestrator's conversation, carried across review rounds so a revision continues it
         * rather than starting a new one that remembers nothing. Null until its first turn returns.
         */
        List<LlmMessage> orchestratorMessages;

        /**
         * How much of {@link #evidence} the orchestrator has already been sent. Entries beyond this
         * are new since its last turn; everything below it is already in
         * {@link #orchestratorMessages} and re-sending it would duplicate.
         */
        int evidenceSent;

        RunState(UUID runId, UUID conversationId, UUID messageId, ResolvedProfile profile,
            UUID userId) {
            this.runId = runId;
            this.conversationId = conversationId;
            this.messageId = messageId;
            this.profile = profile;
            this.userId = userId;
        }

        RunState(UUID runId, UUID conversationId, UUID messageId, ResolvedProfile profile) {
            this(runId, conversationId, messageId, profile, null);
        }

        RunState(UUID runId, ResolvedProfile profile) {
            this(runId, null, null, profile, null);
        }

        void accumulate(RagResult r) {
            promptTokens += r.promptTokens();
            completionTokens += r.completionTokens();
            totalTokens += r.totalTokens();
            cachedTokens += r.cachedTokens();
            thoughtTokens += r.thoughtTokens();
            if (r.retrievedChunkIds() != null) {
                chunkIds.addAll(r.retrievedChunkIds());
            }
            if (r.searchIds() != null) {
                searchIds.addAll(r.searchIds());
            }
        }
    }
}
