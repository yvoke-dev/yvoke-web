package de.palsoftware.yvoke.chat.orchestration;

import java.time.Instant;
import java.util.List;

/**
 * Domain model record representing an orchestrator profile with per-profile settings.
 *
 * <p>
 * {@code prototype} mirrors the playbook flag of the same name: an experimental profile is hidden
 * from the chat clients' profile pickers unless the user has turned prototype visibility on. It is
 * a discovery flag only — it never affects how a run resolves or executes, so
 * {@link ResolvedProfile} deliberately does not carry it.
 */
public record OrchestratorProfile(String name,int maxReviewRounds,int maxSpecialistCalls,String orchestratorPlaybook,String reviewerPlaybook,List<String>specialistPlaybooks,String orchestratorModel,String orchestratorThinkingLevel,String reviewerModel,String reviewerThinkingLevel,String specialistModel,String specialistThinkingLevel,boolean prototype,Instant createdAt,Instant updatedAt){}
